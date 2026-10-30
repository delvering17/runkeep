package com.runkeep.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.math.roundToInt

enum class PositionPhase { IDLE, PERMISSION, DISABLED, WAITING, RECEIVING, ERROR }
data class PositionState(
    val phase: PositionPhase = PositionPhase.IDLE,
    val fix: Location? = null,
    val updates: Int = 0,
    val detail: String? = null
) {
    fun ageMs(now: Long = SystemClock.elapsedRealtime()): Long? = fix?.let { (now - it.elapsedRealtimeNanos / 1_000_000).coerceAtLeast(0) }
    fun recentFix(now: Long = SystemClock.elapsedRealtime()): Location? = fix?.takeIf { (ageMs(now) ?: Long.MAX_VALUE) <= 30_000 }
    fun message(now: Long = SystemClock.elapsedRealtime()): String = when (phase) {
        PositionPhase.IDLE -> "위치 확인 대기"
        PositionPhase.PERMISSION -> "정확한 위치 권한이 필요합니다"
        PositionPhase.DISABLED -> "휴대폰 위치 기능이 꺼져 있습니다"
        PositionPhase.WAITING -> "첫 위치를 찾는 중"
        PositionPhase.ERROR -> detail ?: "위치 서비스에 연결하지 못했습니다"
        PositionPhase.RECEIVING -> when {
            (ageMs(now) ?: Long.MAX_VALUE) > 10_000 -> "위치 갱신 대기 · 마지막 수신 ${(ageMs(now) ?: 0) / 1000}초 전"
            fix != null && fix.accuracy > 25 -> "위치 수신 · 오차 ±${fix.accuracy.roundToInt()}m · 정확도가 좋아지면 기록합니다"
            else -> "위치 수신 · 오차 ±${fix?.accuracy?.roundToInt() ?: 0}m"
        }
    }
}

/** A shared subscription: closing the map/test screen never stops an active workout. Main thread only. */
class LocationController(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private val client = LocationServices.getFusedLocationProviderClient(this.context)
    private val mutable = MutableStateFlow(PositionState())
    val state = mutable.asStateFlow()
    private val owners = mutableSetOf<Any>()
    private var subscribed = false
    private var generation = 0
    val simulation = LocationSimulation(
        onLocation = { accept(it, simulated = true) },
        onSourceChanged = { mutable.value = PositionState(); restart() }
    )
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach { accept(it) } }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { restart() }
    }
    val permitted get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val enabled get() = LocationManagerCompat.isLocationEnabled(manager)

    fun acquire(): Closeable {
        val token = Any(); owners += token
        if (owners.size == 1) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            restart()
        } else if (!subscribed) restart()
        return Closeable {
            if (owners.remove(token) && owners.isEmpty()) {
                generation++; client.removeLocationUpdates(callback); subscribed = false
                simulation.onRelease()
                context.unregisterReceiver(receiver)
                mutable.value = mutable.value.copy(phase = PositionPhase.IDLE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun restart() {
        if (owners.isEmpty()) return
        val ticket = ++generation
        client.removeLocationUpdates(callback); subscribed = false
        if (simulation.enabled) {
            subscribed = true
            mutable.value = mutable.value.copy(phase = PositionPhase.WAITING, detail = null)
            simulation.onAcquire()
            return
        }
        if (!permitted) { mutable.value = PositionState(PositionPhase.PERMISSION); return }
        if (!enabled) { mutable.value = PositionState(PositionPhase.DISABLED); return }
        mutable.value = mutable.value.copy(phase = PositionPhase.WAITING, detail = null)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            subscribed = true
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    if (ticket == generation && owners.isNotEmpty()) {
                        subscribed = false
                        mutable.value = mutable.value.copy(phase = PositionPhase.ERROR, detail = "위치 연결 실패 · ${it.localizedMessage ?: "Google Play 서비스를 확인해주세요"}")
                    }
                }
            client.lastLocation.addOnSuccessListener { location ->
                if (ticket == generation && location != null && SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= 30_000_000_000L) accept(location)
            }
        } catch (_: SecurityException) { subscribed = false; mutable.value = PositionState(PositionPhase.PERMISSION) }
    }

    private fun accept(location: Location, simulated: Boolean = false) {
        if (simulated != simulation.enabled) return
        if (owners.isEmpty() || !subscribed || !location.hasAccuracy()) return
        if (location.elapsedRealtimeNanos <= (mutable.value.fix?.elapsedRealtimeNanos ?: -1)) return
        mutable.value = PositionState(PositionPhase.RECEIVING, Location(location), mutable.value.updates + 1)
    }
}
