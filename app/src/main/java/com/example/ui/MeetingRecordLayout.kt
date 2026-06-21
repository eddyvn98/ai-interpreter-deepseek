package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MeetingRecordLayout(
    isRecording: Boolean,
    isPaused: Boolean,
    durationSec: Int,
    isProcessing: Boolean,
    transcript: String,
    rawTranscript: String,
    micStatus: String,
    records: List<com.example.data.database.TranslationRecord>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDeleteRecord: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showControlButtons: Boolean = true,
    isDarkMode: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val meetingRecords = remember(records) {
        records.filter { it.sessionId.startsWith("MEETING_") }
    }
    var selectedMeetingRecord by remember { mutableStateOf<com.example.data.database.TranslationRecord?>(null) }

    // Dynamic Theme Colors
    val themeTextColor = if (isDarkMode) Color(0xFFF5F5F5) else MinimalCharcoalText
    val themeMutedTextColor = if (isDarkMode) Color(0xFFAAAAAA) else MinimalMutedGray
    val themePillColor = if (isDarkMode) Color(0xFF2C2C2C) else MinimalPillBeige
    val themeWhiteColor = if (isDarkMode) Color(0xFF1E1E1E) else MinimalWhite
    val themeBorderColor = if (isDarkMode) Color(0xFF333333) else MinimalBorder

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
            Text(
                text = "Ghi âm cuộc họp (STT: Whisper medium | EN+VI)",
                color = themeTextColor.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            if (selectedMeetingRecord != null) {
                // Chi tiết bản ghi quá khứ
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chi tiết bản ghi cuộc họp",
                        color = SecondaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { selectedMeetingRecord = null }) {
                        Text("Quay lại", color = PrimaryAccent, fontSize = 12.sp)
                    }
                }

                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Nội dung ghi âm cuộc họp:",
                            color = PrimaryAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        SelectionContainer {
                            Text(
                                text = selectedMeetingRecord!!.translatedText,
                                color = themeTextColor,
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Meeting Transcript", selectedMeetingRecord!!.originalText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Đã sao chép nội dung vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sao chép", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            onDeleteRecord(selectedMeetingRecord!!.id)
                            selectedMeetingRecord = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B2635)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Text("Xóa bản ghi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

            } else {
                // Giao diện ghi âm / Đang xử lý / Chờ
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isProcessing -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(color = SecondaryAccent)
                                Text(
                                    text = micStatus,
                                    color = themeTextColor.copy(alpha = 0.85f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Đang xử lý file âm thanh cuộc họp. Vui lòng không đóng app.",
                                    color = themeMutedTextColor,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        isRecording -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val minutes = durationSec / 60
                                val seconds = durationSec % 60
                                val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                                val timeColor = if (isPaused) Color.Yellow else themeTextColor
                                val labelText = if (isPaused) "Đang tạm dừng ghi âm..." else "Đang ghi âm cuộc họp..."
                                val labelColor = if (isPaused) Color.Yellow else Color.Red

                                Text(
                                    text = labelText,
                                    color = labelColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = timeString,
                                    color = timeColor,
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Bạn có thể tắt màn hình, mic vẫn đang hoạt động chạy ẩn.",
                                    color = themeMutedTextColor,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                SoundWaveVisualizer(level = 0.5f, animate = !isPaused)
                            }
                        }
                        else -> {
                            if (transcript.isNotEmpty()) {
                                val scrollState = rememberScrollState()
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = "Nội dung ghi âm vừa hoàn thành:",
                                        color = SecondaryAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)) {
                                        SelectionContainer {
                                            Text(
                                                text = transcript,
                                                color = themeTextColor,
                                                fontSize = 15.sp,
                                                lineHeight = 22.sp
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("Meeting Transcript", transcript)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Đã sao chép nội dung!", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Sao chép", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = onStartRecording,
                                            border = BorderStroke(1.dp, themeBorderColor),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Ghi cuộc họp mới", color = themeTextColor, fontSize = 12.sp)
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = PrimaryAccent,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "Nhấn để bắt đầu ghi âm cuộc họp",
                                        color = themeTextColor,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Nhận diện giọng nói Việt & Anh tự động bằng Whisper local. Hoạt động ngầm ngay cả khi tắt màn hình điện thoại.",
                                        color = themeMutedTextColor,
                                        fontSize = 11.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showControlButtons) {
                if (!isProcessing && !isRecording && transcript.isEmpty()) {
                    Button(
                        onClick = {
                            if (!hasPermission) onRequestPermission() else onStartRecording()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Mic, null, tint = Color.Black)
                            Text("Bắt đầu ghi cuộc họp", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isRecording) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (isPaused) onResumeRecording() else onPauseRecording() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPaused) Color(0xFF4CAF50) else Color(0xFFFFA000)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (isPaused) "Tiếp tục" else "Tạm dừng",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isPaused) "Tiếp tục" else "Tạm dừng",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Button(
                            onClick = onStopRecording,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.2f).fillMaxHeight()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Hoàn thành",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (!isRecording && !isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(themeBorderColor)
                )
                Text(
                    text = "Lịch sử cuộc họp (${meetingRecords.size})",
                    color = themeTextColor.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.8f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (meetingRecords.isEmpty()) {
                        item {
                            Text(
                                text = "Chưa có bản ghi cuộc họp nào.",
                                color = themeTextColor.copy(alpha = 0.4f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(meetingRecords) { record ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = themeWhiteColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .pointerInput(record) {
                                                detectTapGestures(onTap = { selectedMeetingRecord = record })
                                            }
                                    ) {
                                        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(record.timestamp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(date, color = SecondaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Text("Xem chi tiết", color = PrimaryAccent, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = record.translatedText,
                                            color = themeTextColor.copy(alpha = 0.85f),
                                            fontSize = 12.sp,
                                            maxLines = 2
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRecord(record.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Xóa cuộc họp",
                                            tint = Color.Red.copy(alpha = 0.7f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
