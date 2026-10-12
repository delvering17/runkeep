package com.runkeep.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.runkeep.app.data.PrivatePayloadStore
import java.io.File

@Composable private fun courseDraftStore(): PrivatePayloadStore {
    val context = LocalContext.current
    return remember(context) { PrivatePayloadStore(File(context.cacheDir, "course-payloads")) }
}

@Composable fun rememberSavedCourseDraft(vararg inputs: Any?, initial: () -> String): MutableState<String> {
    val store = courseDraftStore()
    val saver = remember(store) { Saver<String, String>(
        save = { runCatching { store.save(it) }.getOrNull() }, restore = { store.read(it) }) }
    return rememberSaveable(*inputs, stateSaver = saver) { mutableStateOf(initial()) }
}

@Composable fun rememberSavedOptionalCourseDraft(vararg inputs: Any?, initial: () -> String?): MutableState<String?> {
    val store = courseDraftStore()
    val saver = remember(store) { Saver<String?, String>(
        save = { value -> value?.let { runCatching { store.save(it) }.getOrNull() } }, restore = { store.read(it) }) }
    return rememberSaveable(*inputs, stateSaver = saver) { mutableStateOf(initial()) }
}
