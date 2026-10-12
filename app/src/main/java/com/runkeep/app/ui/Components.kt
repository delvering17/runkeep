package com.runkeep.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runkeep.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun dateText(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 · HH:mm"))
@Composable fun Metric(label: String, value: String, modifier: Modifier = Modifier, suffix: String = "") {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value + suffix, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    }
}
@Composable fun Section(title: String, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        trailing?.invoke()
    }
}
@Composable fun EmptyState(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun TinyChart(values: List<Pair<Long, Double>>, label: String, unit: String, maxGapMs: Long = 30_000) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        if (values.isEmpty()) { Text("데이터 없음", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
        val min = values.minOf { it.second }; val max = values.maxOf { it.second }
        Text("%.0f–%.0f %s".format(min, max, unit), style = MaterialTheme.typography.labelMedium)
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val start = values.first().first; val span = (values.last().first - start).coerceAtLeast(1)
            val range = (max - min).coerceAtLeast(1.0)
            val path = Path()
            var previous: Long? = null
            values.forEach { (time, value) ->
                val x = (time - start).toFloat() / span * size.width
                val y = size.height - 6 - ((value - min) / range * (size.height - 12)).toFloat()
                if (previous == null || time - previous!! > maxGapMs) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(color, 2f, Offset(x, y)); previous = time
            }
            drawPath(path, color, style = Stroke(3f))
        }
    }
}
@Composable fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}
