package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable fun MapCameraControls(fitEnabled: Boolean, locationEnabled: Boolean, following: Boolean,
    onFit: () -> Unit, onLocation: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onFit, enabled = fitEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.CenterFocusStrong, "코스 전체 보기")
        }
        FilledTonalIconButton(onClick = onLocation, enabled = locationEnabled, modifier = Modifier.size(48.dp).semantics {
            selected = following
            if (following) stateDescription = "위치 따라가는 중"
        }) {
            Icon(Icons.Outlined.MyLocation, "현재 위치로")
        }
    }
}
