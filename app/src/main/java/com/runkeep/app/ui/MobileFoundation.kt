package com.runkeep.app.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Current app window, not the physical device: resizing does not own screen / draft state. */
data class MobileWindow(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp, val fontScale: Float) {
    val useRail get() = width >= 600.dp && height >= 480.dp && fontScale < 1.8f
    val useSupportingPane get() = width >= 840.dp && height >= 480.dp
}
@Composable fun mobileWindow(): MobileWindow {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { MobileWindow(size.width.toDp(), size.height.toDp(), fontScale) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AdaptiveActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** Temporary task. Back / scrim / swipe / explicit cancel all call the same cancellation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskSheet(title: String, onCancel: () -> Unit, actions: @Composable () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState? = null, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val window = mobileWindow()
    val activity = LocalActivity.current
    // Material 3 hide callbacks can finish when the old Activity is being disposed.
    // A configuration change is not a user cancellation of the saved draft.
    ModalBottomSheet(onDismissRequest = { if (activity?.isChangingConfigurations != true && activity?.isDestroyed != true) onCancel() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.safeDrawing }) {
        Column(Modifier.fillMaxWidth().heightIn(max = window.height * .9f).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val bodyScroll = scrollState ?: rememberScrollState()
            Column(Modifier.weight(1f, fill = false).then(if (scrollable) Modifier.verticalScroll(bodyScroll) else Modifier), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            actions()
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Persistent map information: bottom sheet on phones, side panel in a wide window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MapSupportingPanel(state: BottomSheetScaffoldState, panel: @Composable () -> Unit, peekHeight: androidx.compose.ui.unit.Dp? = null, content: @Composable (PaddingValues) -> Unit) {
    if (mobileWindow().useSupportingPane) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content(PaddingValues()) }
            Surface(Modifier.width(360.dp).fillMaxHeight(), tonalElevation = 2.dp) { panel() }
        }
    } else {
        val scope = rememberCoroutineScope()
        BackHandler(enabled = state.bottomSheetState.currentValue == SheetValue.Expanded) { scope.launch { state.bottomSheetState.partialExpand() } }
        val peek = peekHeight ?: if (mobileWindow().fontScale >= 1.5f) 160.dp else 108.dp
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val heightPx = with(density) { maxHeight.toPx() }
            val coveredPx by remember(state, heightPx, peek, density) {
                derivedStateOf {
                    val offset = runCatching { state.bottomSheetState.requireOffset() }.getOrNull()
                    if (offset == null) with(density) { peek.toPx() } else (heightPx - offset).coerceIn(0f, heightPx)
                }
            }
            BottomSheetScaffold(scaffoldState = state, sheetPeekHeight = peek,
                sheetContainerColor = MaterialTheme.colorScheme.surface, sheetContent = { panel() }) {
                content(PaddingValues(bottom = with(density) { coveredPx.toDp() }))
            }
        }
    }
}

class DraftExitState {
    var interceptor: ((() -> Unit) -> Unit)? = null
    fun request(action: () -> Unit) { interceptor?.invoke(action) ?: action() }
}
val LocalDraftExit = staticCompositionLocalOf<DraftExitState> { error("Missing draft exit host") }

/** Long edits ask once; dismissing the confirmation keeps all draft values. */
@Composable fun GuardDraftExit(dirty: Boolean, onDiscard: () -> Unit = {}, enabled: Boolean = true) {
    val host = LocalDraftExit.current
    val currentDirty by rememberUpdatedState(dirty)
    val discard by rememberUpdatedState(onDiscard)
    val currentEnabled by rememberUpdatedState(enabled)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(host) {
        val interceptor: (() -> Unit) -> Unit = { action -> if (currentEnabled) { if (currentDirty) pending = action else action() } }
        host.interceptor = interceptor
        onDispose { if (host.interceptor === interceptor) host.interceptor = null }
    }
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text("편집을 마칠까요?") },
        text = { Text("아직 저장하지 않은 변경이 있습니다.") },
        confirmButton = { TextButton(onClick = { val action = pending; pending = null; discard(); action?.invoke() }) { Text("변경 버리고 닫기") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("계속 편집") } })
}
