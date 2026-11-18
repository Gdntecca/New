// Super Downloader — Created by Tez
package com.superdownloader.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.superdownloader.app.engine.SegInfo

/** Segmented progress bar: one coloured piece per connection. Indeterminate when size is unknown. */
@Composable
fun SegmentedBar(
    progress: Float,
    total: Long,
    segments: List<SegInfo>?,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
    height: Int = 6,
) {
    val reduce = LocalReduceMotion.current
    if (indeterminate) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        return
    }
    val animated by animateFloatAsState(progress, motionSpec(reduce, 250), label = "p")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50))) {
        drawRect(track)
        if (segments.isNullOrEmpty() || total <= 0) {
            drawRect(primary, size = Size(size.width * animated, size.height))
        } else {
            val w = size.width
            for (s in segments) {
                val x = s.start.toFloat() / total * w
                val dw = s.downloaded.toFloat() / total * w
                val c = if (s.active) ConnColors[(s.conn - 1).mod(ConnColors.size)] else primary
                drawRect(c, topLeft = Offset(x, 0f), size = Size(dw.coerceAtLeast(0f), size.height))
            }
        }
    }
}

/**
 * Piece map: a grid of small squares, each one a chunk of the file, filled as it downloads.
 * Drawn on a single Canvas so it stays cheap on low-end phones.
 */
@Composable
fun PieceMap(total: Long, downloaded: Long, segments: List<SegInfo>?, completed: Boolean, modifier: Modifier = Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val cell = 9.dp.toPx()
        val gap = 2.dp.toPx()
        val cols = maxOf(1, ((size.width + gap) / (cell + gap)).toInt())
        val rows = maxOf(1, ((size.height + gap) / (cell + gap)).toInt())
        val count = cols * rows
        val chunk = if (total > 0) total.toDouble() / count else 0.0
        // Coverage per cell from the piece list (or the overall fraction when no pieces are known).
        val fill = FloatArray(count)
        val color = IntArray(count) { -1 }
        when {
            completed -> fill.fill(1f)
            total <= 0 -> {}
            segments.isNullOrEmpty() -> {
                val f = downloaded.toDouble() / total * count
                for (i in 0 until count) fill[i] = (f - i).coerceIn(0.0, 1.0).toFloat()
            }
            else -> for (s in segments) {
                if (s.downloaded <= 0) continue
                val a = s.start.toDouble()
                val b = (s.start + s.downloaded).toDouble()
                val first = (a / chunk).toInt().coerceIn(0, count - 1)
                val last = ((b - 1) / chunk).toInt().coerceIn(0, count - 1)
                for (i in first..last) {
                    val cs = i * chunk
                    val ce = cs + chunk
                    val ov = (minOf(ce, b) - maxOf(cs, a)).coerceAtLeast(0.0)
                    fill[i] = (fill[i] + (ov / chunk).toFloat()).coerceAtMost(1f)
                    if (s.active) color[i] = s.conn
                }
            }
        }
        for (i in 0 until count) {
            val x = (i % cols) * (cell + gap)
            val y = (i / cols) * (cell + gap)
            drawRoundRect(empty, Offset(x, y), Size(cell, cell), CornerRadius(2f, 2f))
            val f = fill[i]
            if (f > 0f) {
                val c = if (color[i] > 0) ConnColors[(color[i] - 1).mod(ConnColors.size)] else primary
                val a = if (f >= 0.999f) 1f else 0.35f + 0.65f * f
                drawRoundRect(c.copy(alpha = a), Offset(x, y), Size(cell, cell), CornerRadius(2f, 2f))
            }
        }
    }
}

/** Live speed graph (last ~90 seconds). */
@Composable
fun SpeedGraph(history: List<Float>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        for (i in 1..3) {
            val y = size.height * i / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (history.size < 2) return@Canvas
        val max = (history.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.15f
        val step = size.width / (history.size - 1)
        val line = Path()
        history.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - v / max * size.height
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(primary.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
