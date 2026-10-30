package com.runkeep.app.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

class RowReorderState(val list: LazyListState) {
    var draggingId by mutableStateOf<String?>(null)
    var dragTop by mutableFloatStateOf(0f)
    var pointerY by mutableFloatStateOf(0f)
    var ids: List<String> = emptyList()
    var enabled = true
    var onFeedback: () -> Unit = {}
    var onMove: (String, Int) -> Unit = { _, _ -> }
    var onStart: () -> Unit = {}
    var onEnd: () -> Unit = {}
    var onCancel: () -> Unit = {}

    fun reorder() {
        val id = draggingId ?: return
        val from = ids.indexOf(id)
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val index = ids.indexOfFirst { it == item.key }
            index >= 0 && item.key != id && pointerY >= item.offset && pointerY <= item.offset + item.size &&
                ((index > from && pointerY > item.offset + item.size / 2f) ||
                    (index < from && pointerY < item.offset + item.size / 2f))
        } ?: return
        onMove(id, ids.indexOfFirst { it == target.key }); onFeedback()
    }
    fun translation(id: String): Float = if (draggingId == id)
        dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset?.toFloat() ?: dragTop) else 0f
}

@Composable fun rememberRowReorder(list: LazyListState, ids: List<String>, enabled: Boolean,
    onMove: (String, Int) -> Unit, onStart: () -> Unit, onEnd: () -> Unit, onCancel: () -> Unit): RowReorderState {
    val haptic = LocalHapticFeedback.current
    val state = remember(list) { RowReorderState(list) }
    state.onFeedback = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    state.ids = ids; state.enabled = enabled; state.onMove = onMove
    state.onStart = onStart; state.onEnd = onEnd; state.onCancel = onCancel
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(state.draggingId) {
        while (state.draggingId != null) {
            withFrameNanos { }
            val info = list.layoutInfo
            val delta = when {
                state.pointerY < info.viewportStartOffset + edge -> -edge / 5f
                state.pointerY > info.viewportEndOffset - edge -> edge / 5f
                else -> 0f
            }
            if (delta != 0f) { list.scrollBy(delta); state.reorder() }
        }
    }
    return state
}

@Composable fun RowReorderHandle(state: RowReorderState, id: String, label: String) {
    Box(Modifier.size(48.dp).pointerInput(id, state.enabled) {
        if (state.enabled) detectDragGestures(
            onDragStart = { pointer ->
                state.list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let { item ->
                    state.onStart(); state.onFeedback(); state.dragTop = item.offset.toFloat()
                    state.pointerY = item.offset + pointer.y; state.draggingId = id
                }
            }, onDrag = { change, amount ->
                change.consume(); state.dragTop += amount.y; state.pointerY += amount.y; state.reorder()
            }, onDragEnd = { state.onEnd(); state.draggingId = null },
            onDragCancel = { state.onCancel(); state.draggingId = null })
    }, contentAlignment = Alignment.Center) { Icon(Icons.Default.DragHandle, label) }
}
