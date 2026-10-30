package com.runkeep.app.tracking

import android.location.Location

/** Normal debug/release builds cannot inject positions or start a replay. */
class LocationSimulation(onLocation: (Location) -> Unit, onSourceChanged: () -> Unit) {
    val enabled = false
    fun onAcquire() = Unit
    fun onRelease() = Unit
}
