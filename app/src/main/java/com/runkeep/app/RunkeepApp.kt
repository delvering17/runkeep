package com.runkeep.app

import android.app.Application
import androidx.core.content.edit
import com.runkeep.app.data.RunDatabase
import com.runkeep.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

class RunkeepApp : Application() {
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
        getSharedPreferences("preferences", MODE_PRIVATE).edit { putString("settings", codec.encodeToString(normalized)) }
    }
}
