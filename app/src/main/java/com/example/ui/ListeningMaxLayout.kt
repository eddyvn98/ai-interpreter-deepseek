package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.unit.sp


@Composable
fun ListeningMaxLayout(
    sessionRecords: List<com.example.data.database.TranslationRecord>,
    currentOriginal: String,
    currentTranslated: String,
    isGeneratingVoice: Boolean,
    summaryText: String,
    summaryPaused: Boolean,
    onPauseSummary: () -> Unit,
    isPaused: Boolean,
    isListening: Boolean,
    micLevel: Float,
    micStatus: String,
    isListeningModeEnabled: Boolean,
    onListeningModeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClearScreen: (() -> Unit)? = null,
    onToggleSession: () -> Unit,
    sourceLanguageLabel: String = "Tiếng Anh (English)",
    targetLanguageLabel: String = "Tiếng Việt (Bản dịch)",
    emptyStateText: String = "Chưa có nội dung dịch. Hãy bắt đầu nói hoặc phát âm thanh tiếng Anh.",
    showSummary: Boolean = true,
    isVerticalSplit: Boolean = true,
    showPlayPauseHeaderButton: Boolean = true,
    isDarkMode: Boolean = false
) {
    val scrollState = rememberScrollState()
    val summaryScrollState = rememberScrollState()
    var isSummaryExpanded by remember { mutableStateOf(false) }

    var lastSummaryMax by remember { mutableStateOf(0) }
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Dynamic Theme Colors
    val themeTextColor = if (isDarkMode) Color(0xFFF5F5F5) else MinimalCharcoalText
    val themeMutedTextColor = if (isDarkMode) Color(0xFFAAAAAA) else MinimalMutedGray
    val themePillColor = if (isDarkMode) Color(0xFF2C2C2C) else MinimalPillBeige
    val themeWhiteColor = if (isDarkMode) Color(0xFF1E1E1E) else MinimalWhite
    val themeBorderColor = if (isDarkMode) Color(0xFF333333) else MinimalBorder

    val originalSessionText = remember(sessionRecords, currentOriginal) {
        val list = sessionRecords.map { it.originalText }
        val showCurrent = currentOriginal.isNotBlank() && (list.isEmpty() || list.last() != currentOriginal)
        val combined = (list + if (showCurrent) listOf(currentOriginal) else emptyList()).joinToString("\n\n")
        combined.replace(Regex("([.?!。？！])\\s*"), "$1\n\n").trim()
    }
    val translatedSessionText = remember(sessionRecords, currentTranslated, isGeneratingVoice) {
        val list = sessionRecords.map { it.translatedText }
        val showCurrent = currentTranslated.isNotBlank() && (list.isEmpty() || list.last() != currentTranslated)
        val suffix = if (isGeneratingVoice && currentTranslated.isBlank()) {
            listOf("Đang dịch...")
        } else if (showCurrent) {
            listOf(currentTranslated)
        } else {
            emptyList()
        }
        val combined = (list + suffix).joinToString("\n\n")
        combined.replace(Regex("([.?!。？！])\\s*"), "$1\n\n").trim()
    }

    // Detect manual scrolling away from bottom
    if (scrollState.isScrollInProgress) {
        val isAtBottom = scrollState.value >= scrollState.maxValue - 80 // 80 pixels buffer
        autoScrollEnabled = isAtBottom
    }

    LaunchedEffect(scrollState.maxValue) {
        if (autoScrollEnabled) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(summaryScrollState.maxValue) {
        val newMax = summaryScrollState.maxValue
        if (newMax > lastSummaryMax) {
            val wasAtBottom = (lastSummaryMax - summaryScrollState.value) <= 120
            if (wasAtBottom || lastSummaryMax == 0) {
                summaryScrollState.scrollTo(newMax)
            }
        }
        lastSummaryMax = newMax
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isListening) Color(0xFF4CAF50) else Color.Gray)
                    )
                    Text(
                        text = if (isListening) "Đang nghe: $micStatus" else if (isPaused) "Tạm dừng" else micStatus,
                        color = themeTextColor.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (isListening) SoundWaveVisualizer(level = micLevel)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Nút Bắt đầu / Dừng nghe (Play / Pause)
                    if (showPlayPauseHeaderButton) {
                        IconButton(
                            onClick = onToggleSession,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Bắt đầu / Dừng nghe",
                                tint = if (isPaused) themeTextColor.copy(alpha = 0.8f) else SecondaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { onListeningModeToggle(!isListeningModeEnabled) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = "Chế độ tai nghe",
                            tint = if (isListeningModeEnabled) PrimaryAccent else themeMutedTextColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    onClearScreen?.let {
                        IconButton(
                            onClick = it,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Xóa màn hình",
                                tint = Color.Red.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Fixed side-by-side column headers
            if (isVerticalSplit) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeTextColor.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = sourceLanguageLabel,
                        color = themeMutedTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = targetLanguageLabel,
                        color = themeMutedTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (isVerticalSplit) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Render each translation record side-by-side or stacked
                            sessionRecords.forEach { record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Original Text
                                    Text(
                                        text = record.originalText,
                                        color = themeMutedTextColor,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // Subtle vertical divider matching the taller column height
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(themeBorderColor)
                                    )
                                    
                                    // Translated Text
                                    Text(
                                        text = record.translatedText,
                                        color = themeTextColor,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            val showCurrentOriginal = currentOriginal.isNotBlank() && run {
                                val lastOriginal = sessionRecords.lastOrNull()?.originalText
                                if (lastOriginal == null) {
                                    true
                                } else {
                                    val cleanLast = lastOriginal.lowercase().replace(Regex("[.?!。？！\\s]"), "")
                                    val cleanCurrent = currentOriginal.lowercase().replace(Regex("[.?!。？！\\s]"), "")
                                    cleanLast != cleanCurrent
                                }
                            }
                            
                            if (showCurrentOriginal) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Live ASR text
                                    Text(
                                        text = currentOriginal,
                                        color = themeMutedTextColor,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // Subtle vertical divider matching the taller column height
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.dp)
                                            .background(themeBorderColor)
                                    )
                                    
                                    // Live Translated text
                                    val displayTranslated = if (isGeneratingVoice && currentTranslated.isBlank()) {
                                        "Đang dịch..."
                                    } else {
                                        currentTranslated
                                    }
                                    Text(
                                        text = displayTranslated,
                                        color = themeTextColor.copy(alpha = 0.8f),
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Empty State if there's no content
                            if (sessionRecords.isEmpty() && !showCurrentOriginal) {
                                Text(
                                    text = emptyStateText,
                                    color = themeMutedTextColor.copy(alpha = 0.6f),
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            } else {
                // CHẾ ĐỘ CHIA NGANG (Cho Trả lời Việt - VI_TO_EN)
                // Chia làm 2 vùng chính cân đối: Vùng gốc Tiếng Việt (ở trên) và Vùng dịch Tiếng Anh (ở dưới)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val originalScrollState = rememberScrollState()
                    val translatedScrollState = rememberScrollState()
                    
                    // Tự động cuộn xuống cuối của 2 vùng khi có text mới
                    LaunchedEffect(originalSessionText) {
                        originalScrollState.animateScrollTo(originalScrollState.maxValue)
                    }
                    LaunchedEffect(translatedSessionText) {
                        translatedScrollState.animateScrollTo(translatedScrollState.maxValue)
                    }

                    // Vùng trên (Source text - Tiếng Việt)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, themeBorderColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = sourceLanguageLabel.uppercase(),
                                color = themeMutedTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(originalScrollState)
                            ) {
                                SelectionContainer {
                                    val displayText = originalSessionText.ifBlank { emptyStateText }
                                    val displayColor = if (originalSessionText.isBlank()) themeMutedTextColor.copy(alpha = 0.6f) else themeMutedTextColor
                                    Text(
                                        text = displayText,
                                        color = displayColor,
                                        fontSize = 20.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Vùng dưới (Target text - Tiếng Anh dịch)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MinimalAccentBrown.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = targetLanguageLabel.uppercase(),
                                color = MinimalAccentBrown.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(translatedScrollState)
                            ) {
                                SelectionContainer {
                                    val displayText = translatedSessionText.ifBlank { if (originalSessionText.isNotBlank()) "Translating..." else "" }
                                    val displayColor = if (translatedSessionText.isBlank()) themeTextColor.copy(alpha = 0.5f) else themeTextColor
                                    Text(
                                        text = displayText,
                                        color = displayColor,
                                        fontSize = 24.sp,
                                        lineHeight = 34.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showSummary) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(themePillColor.copy(alpha = 0.3f))
                        .border(0.5.dp, themeBorderColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSummaryExpanded = !isSummaryExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isSummaryExpanded) "▼ Tóm tắt" else "▲ Tóm tắt",
                                color = themeTextColor.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (!isSummaryExpanded && summaryText.isNotBlank() && summaryText != "Chưa có tóm tắt.") {
                                Text(
                                    text = if (summaryText.length > 30) "(${summaryText.take(30).trim()}...)" else "($summaryText)",
                                    color = themeMutedTextColor,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(
                            onClick = onPauseSummary,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (summaryPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Tạm dừng tóm tắt",
                                tint = SecondaryAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    if (isSummaryExpanded) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .verticalScroll(summaryScrollState)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = summaryText,
                                    color = themeTextColor.copy(alpha = 0.9f),
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
