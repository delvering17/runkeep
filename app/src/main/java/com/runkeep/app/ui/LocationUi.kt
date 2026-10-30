package com.runkeep.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runkeep.app.RunkeepApp
import com.runkeep.app.tracking.PositionState
import java.io.Closeable

/** Subscription follows visibility; a diagnostic screen cannot keep recording after leaving it. */
@Composable fun rememberPosition(app: RunkeepApp, enabled: Boolean = true, askPermission: Boolean = true): PositionState {
    val owner = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++ }
    LaunchedEffect(enabled, askPermission) {
        if (enabled && askPermission && !app.location.permitted) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    DisposableEffect(owner, enabled, permissionRevision) {
        var lease: Closeable? = null
        fun start() { if (enabled && lease == null) lease = app.location.acquire() }
        fun stop() { lease?.close(); lease = null }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> start(); Lifecycle.Event.ON_PAUSE -> stop(); else -> Unit }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    val state by app.location.state.collectAsStateWithLifecycle()
    return state
}
