package com.catan.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A die, drawn rather than loaded.
 *
 * There is no dice artwork in the asset set, and drawing them keeps the pips crisp at any size.
 */
@Composable
fun Die(value: Int, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val side = kotlin.math.min(this.size.width, this.size.height)
        val corner = side * 0.18f

        drawRoundRect(
            color = Color(0xFFFAF6EC),
            size = Size(side, side),
            cornerRadius = CornerRadius(corner, corner),
        )
        drawRoundRect(
            color = Color(0xFF6B4423),
            size = Size(side, side),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = side * 0.06f),
        )

        // Pip positions on a 3x3 grid, in units of the die's side.
        val low = 0.27f
        val mid = 0.5f
        val high = 0.73f
        val pips: List<Pair<Float, Float>> = when (value.coerceIn(1, 6)) {
            1 -> listOf(mid to mid)
            2 -> listOf(low to low, high to high)
            3 -> listOf(low to low, mid to mid, high to high)
            4 -> listOf(low to low, low to high, high to low, high to high)
            5 -> listOf(low to low, low to high, mid to mid, high to low, high to high)
            else -> listOf(
                low to low, low to mid, low to high,
                high to low, high to mid, high to high,
            )
        }

        val pipRadius = side * 0.085f
        for ((x, y) in pips) {
            drawCircle(
                color = Color(0xFF23180E),
                radius = pipRadius,
                center = Offset(x * side, y * side),
            )
        }
    }
}

/** The pair of dice from the last roll. */
@Composable
fun DicePair(roll: Pair<Int, Int>?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Die(roll?.first ?: 1, size)
        Die(roll?.second ?: 1, size)
    }
}
