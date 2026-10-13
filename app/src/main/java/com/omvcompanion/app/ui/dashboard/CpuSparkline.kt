package com.omvcompanion.app.ui.dashboard

import androidx.compose.ui.geometry.Offset

internal fun cpuSparklineOffset(xFraction: Float, cpu: Double, width: Float, height: Float, inset: Float): Offset {
    val padding = inset.coerceAtLeast(0f).coerceAtMost(minOf(width, height).coerceAtLeast(0f) / 2)
    return Offset(padding + (width - 2 * padding).coerceAtLeast(0f) * xFraction.coerceIn(0f, 1f),
        padding + (height - 2 * padding).coerceAtLeast(0f) * (1 - cpu.coerceIn(0.0, 100.0) / 100).toFloat())
}
