package com.runkeep.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.runkeep.core.healthimport.*
import kotlinx.coroutines.CancellationException

internal class HealthConnectReadAdapter(private val context: Context) : HealthImportSource {
    private val client get() = HealthConnectClient.getOrCreate(context)

    override fun availability() = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.UNSUPPORTED
    }

    override suspend fun access(): HealthReadAccess = safely {
        val granted = client.permissionController.getGrantedPermissions()
        HealthReadAccess(granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class)),
            granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class)))
    }

    override suspend fun sessions(window: HealthWindow, token: String?): HealthPage<HealthSession> = safely {
        val page = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,
            TimeRangeFilter.between(window.start.minusSeconds(3600), window.end.plusSeconds(3600)),
            dataOriginFilter = setOf(DataOrigin(SAMSUNG_HEALTH_ORIGIN)), pageToken = token))
        HealthPage(page.records.filter { it.startTime < it.endTime }.map {
            HealthSession(it.metadata.id, it.metadata.dataOrigin.packageName, HealthWindow(it.startTime, it.endTime), it.title)
        }, page.pageToken)
    }

    override suspend fun hearts(window: HealthWindow, samsungOnly: Boolean, token: String?): HealthPage<HealthSeries> = safely {
        val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class,
            TimeRangeFilter.between(window.start, window.end),
            dataOriginFilter = if (samsungOnly) setOf(DataOrigin(SAMSUNG_HEALTH_ORIGIN)) else emptySet(), pageToken = token))
        HealthPage(page.records.map {
            HealthSeries(it.metadata.id, it.metadata.dataOrigin.packageName, it.startTime, it.endTime,
                it.samples.map { sample -> HealthReading(sample.time, sample.beatsPerMinute) })
        }, page.pageToken)
    }

    private suspend fun <T> safely(read: suspend () -> T): T = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (denied: SecurityException) {
        throw HealthReadException(HealthFailure.ACCESS_RESTRICTED)
    } catch (failure: Exception) {
        throw HealthReadException(HealthFailure.READ_FAILED)
    }
}
