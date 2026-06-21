package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TripleTextPanel(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color.White,
    onSpeak: (() -> Unit)? = null,
    speaking: Boolean = false,
    onPauseSummary: (() -> Unit)? = null,
    summaryPaused: Boolean = false
) {
    val scrollState = rememberScrollState()
    var lastMax by remember { mutableStateOf(0) }

    LaunchedEffect(scrollState.maxValue) {
        val newMax = scrollState.maxValue
        if (newMax > lastMax) {
            val wasAtBottom = (lastMax - scrollState.value) <= 120
            if (wasAtBottom || lastMax == 0) {
                scrollState.scrollTo(newMax)
            }
        }
        lastMax = newMax
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalGray),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(title, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onPauseSummary?.let { 
                        IconButton(onClick = it, modifier = Modifier.size(40.dp)) { 
                            Icon(
                                imageVector = if (summaryPaused) Icons.Default.PlayArrow else Icons.Default.Pause, 
                                contentDescription = null, 
                                tint = SecondaryAccent,
                                modifier = Modifier.size(22.dp)
                            ) 
                        } 
                    }
                    onSpeak?.let { 
                        IconButton(onClick = it, modifier = Modifier.size(48.dp)) { 
                            Icon(
                                imageVector = Icons.Default.VolumeUp, 
                                contentDescription = null, 
                                tint = SecondaryAccent,
                                modifier = Modifier.size(26.dp)
                            ) 
                        } 
                    }
                    if (speaking) SoundWaveVisualizer(level = 0.6f)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = accent,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
