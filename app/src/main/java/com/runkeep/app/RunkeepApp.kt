package com.runkeep.app

import android.app.Application
import androidx.core.content.edit
import com.runkeep.app.data.RunDatabase
import com.runkeep.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

class RunkeepApp : Application() {
    private val diagnosticScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    val diagnostics by lazy { com.runkeep.app.diagnostics.DiagnosticRecorder(java.io.File(noBackupFilesDir, "diagnostics"), diagnosticScope) }
    override fun onCreate() {
        super.onCreate()
        diagnostics.waiting(settings.value.diagnosticRecording)
        diagnosticScope.launch {
            runCatching { diagnostics.maintain(db.dao().runs().first().map { it.id }.toSet()) }
            while (isActive) {
                runCatching { withContext(Dispatchers.IO) { com.runkeep.app.diagnostics.DiagnosticShareFiles(java.io.File(cacheDir, "diagnostic-shares")).clean() } }
                delay(60_000)
            }
        }
    }
    val db by lazy { RunDatabase.open(this) }
    val location by lazy { com.runkeep.app.tracking.LocationController(this) }
    val settings by lazy {
        val raw = getSharedPreferences("preferences", MODE_PRIVATE).getString("settings", null)
        MutableStateFlow((raw?.let { runCatching { codec.decodeFromString<Preferences>(it) }.getOrNull() } ?: Preferences()).normalized())
    }
    val live = MutableStateFlow(LiveRun())
    val error = MutableStateFlow<String?>(null)
    fun updateSettings(value: Preferences) {
        val normalized = value.normalized()
        settings.value = normalized
        diagnostics.waiting(normalized.diagnosticRecording)
        getSharedPreferences("preferences", MODE_PRIVATE).edit { putString("settings", codec.encodeToString(normalized)) }
    }
}
