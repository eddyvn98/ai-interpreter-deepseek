package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModeButton(
    title: String,
    subtitle: String,
    active: Boolean,
    brush: Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) brush
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .padding(vertical = 10.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = if (active) Color.White else Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                text = subtitle,
                color = if (active) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }
    }
}
