package com.example.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun SoundWaveVisualizer(level: Float, animate: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "mic")
    val phase by if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "phase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Canvas(modifier = Modifier.width(70.dp).height(20.dp).padding(top = 4.dp)) {
        val w = size.width
        val h = size.height
        val points = 24
        val amp = if (animate) (h * (0.15f + level * 0.45f)) else 0f
        for (i in 0 until points) {
            val p = i.toFloat() / points
            val x = p * w
            val y = h / 2 + sin((p * 3f * PI + phase)).toFloat() * amp
            drawCircle(color = SecondaryAccent, radius = 2.2f, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}
