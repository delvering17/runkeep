package com.runkeep.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

/** A domain value is a compact row, independent from the platform's permission/file dialogs. */
@Composable fun SelectionRow(label: String, value: String, onClick: () -> Unit,
                             modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { stateDescription = value }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class SelectionChoice(val key: String, val label: String, val supporting: String? = null)

class DomainSelectionState(val list: LazyListState, val query: MutableState<String>)
@Composable fun rememberDomainSelectionState(): DomainSelectionState {
    val list = rememberLazyListState()
    val query = rememberSaveable { mutableStateOf("") }
    return remember(list, query) { DomainSelectionState(list, query) }
}

@Composable fun SelectionOptionRow(choice: SelectionChoice, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            choice.supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Stable keys and an isolated candidate: dismiss/back/cancel never apply a value. */
@Composable fun DomainSelectionSheet(title: String, choices: List<SelectionChoice>, selectedKey: String?,
                                    onSelect: (String) -> Unit, onCancel: () -> Unit, state: DomainSelectionState? = null) {
    var candidate by rememberSaveable { mutableStateOf(selectedKey) }
    val selection = state ?: rememberDomainSelectionState()
    var query by selection.query
    val scope = rememberCoroutineScope()
    TaskSheet(title, onCancel, actions = {
        AdaptiveActions {
            TextButton(onClick = onCancel) { Text("취소") }
            Button(enabled = choices.any { it.key == candidate }, onClick = { candidate?.let(onSelect) }) { Text("적용") }
        }
    }, scrollable = false) {
        if (choices.size >= 8) OutlinedTextField(query, { query = it; scope.launch { selection.list.scrollToItem(0) } }, label = { Text("검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val shown = remember(choices, query) { choices.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.supporting?.contains(query, ignoreCase = true) == true } }
        LazyColumn(state = selection.list, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
            if (shown.isEmpty()) item { Text("검색 결과 없음") }
            items(shown, key = { it.key }) { choice -> SelectionOptionRow(choice, candidate == choice.key, { candidate = choice.key }) }
        }
    }
}

@Composable fun IntegerSettingRow(label: String, value: Int, range: IntRange, suffix: String,
                                  onConfirm: (Int) -> Unit, enabled: Boolean = true, presets: List<Int> = emptyList()) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectionRow(label, "$value$suffix", { open = true }, enabled = enabled)
    if (open) {
        var text by rememberSaveable { mutableStateOf(value.toString()) }
        val candidate = text.toIntOrNull()?.takeIf { it in range }
        TaskSheet(label, { open = false }, actions = {
            AdaptiveActions {
                TextButton(onClick = { open = false }) { Text("취소") }
                Button(enabled = candidate != null, onClick = { candidate?.let(onConfirm); open = false }) { Text("적용") }
            }
        }) {
            OutlinedTextField(text, { next -> if (next.length <= 8 && next.all { it in '0'..'9' }) text = next },
                label = { Text(suffix) }, isError = candidate == null, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (candidate == null) Text("${range.first}~${range.last}$suffix", color = MaterialTheme.colorScheme.error)
            AdaptiveActions { presets.filter { it in range }.forEach { preset -> TextButton(onClick = { text = preset.toString() }) { Text("$preset$suffix") } } }
        }
    }
}

@Composable fun DomainSelectionField(label: String, value: String, choices: List<SelectionChoice>, selectedKey: String?,
                                    onSelect: (String) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = rememberDomainSelectionState()
    SelectionRow(label, value, { open = true }, enabled = enabled)
    if (open) DomainSelectionSheet(label, choices, selectedKey, { onSelect(it); open = false }, { open = false }, state = state)
}
