package com.runkeep.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.runkeep.core.*

/** Unit selection is beside the draft, while confirm/cancel remain owned by MeasurementSheet. */
@Composable internal fun DistanceEditor(text: String, unit: DistanceUnit, isError: Boolean,
                                        onTextChange: (String) -> Unit, onUnitChange: (DistanceUnit) -> Unit) {
    var unitsOpen by remember { mutableStateOf(false) }
    Column {
    OutlinedTextField(value = text, onValueChange = { onTextChange(acceptDistanceEdit(text, it)) },
        label = { Text("거리") }, singleLine = true, isError = isError, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        trailingIcon = {
            Box {
                TextButton(onClick = { unitsOpen = true }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "거리 단위"
                    stateDescription = unit.label
                }) {
                    Row {
                        Text(unit.label)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }

            }
        })
    if (unitsOpen) DistanceUnit.entries.forEach { next ->
        SelectionOptionRow(SelectionChoice(next.name, next.label), unit == next, {
            onUnitChange(next); unitsOpen = false
        })
    }
    }
}

internal fun distancePresetLabel(preset: DistancePreset): String = when (preset) {
    DistancePreset.HALF_MARATHON -> "하프 (21.0975 km)"
    DistancePreset.MARATHON -> "풀 (42.195 km)"
    else -> "${distanceInputText(preset.meters, DistanceUnit.KILOMETERS)} km"
}
