package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.database.MeetingAudioSegment
import com.example.data.database.TranslationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

@Composable
fun MeetingRecordLayout(
    isRecording: Boolean,
    isPaused: Boolean,
    durationSec: Int,
    isProcessing: Boolean,
    transcript: String,
    rawTranscript: String,
    micStatus: String,
    records: List<TranslationRecord>,
    segments: List<MeetingAudioSegment>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onRetrySegments: () -> Unit,
    modifier: Modifier = Modifier,
    showControlButtons: Boolean = true,
    isDarkMode: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val meetingRecords = remember(records) {
        records.filter { it.sessionId.startsWith("MEETING_") }
    }
    var selectedMeetingRecord by remember { mutableStateOf<TranslationRecord?>(null) }

    // Dynamic Theme Colors
    val themeTextColor = if (isDarkMode) Color(0xFFF5F5F5) else MinimalCharcoalText
    val themeMutedTextColor = if (isDarkMode) Color(0xFFAAAAAA) else MinimalMutedGray
    val themeWhiteColor = if (isDarkMode) Color(0xFF1E1E1E) else MinimalWhite
    val themeBorderColor = if (isDarkMode) Color(0xFF333333) else MinimalBorder
    val themeSurfaceColor = if (isDarkMode) Color(0xFF252525) else Color(0xFFF9F9F9)

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
            // Header Title
            Text(
                text = "Ghi âm cuộc họp (Phân tách người nói | Offline)",
                color = themeTextColor.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            if (selectedMeetingRecord != null) {
                // CHI TIẾT BẢN GHI LỊCH SỬ
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

                // Parser để hiển thị các dòng đối thoại từ bản ghi lịch sử thô dạng bong bóng thoại
                val parsedSegments = remember(selectedMeetingRecord) {
                    parseTranscriptToSegments(selectedMeetingRecord?.translatedText.orEmpty())
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (parsedSegments.isNotEmpty()) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(parsedSegments) { seg ->
                                DialogBubbleItem(
                                    speakerLabel = seg.speakerLabel,
                                    timeText = seg.timeText,
                                    text = seg.text,
                                    isFailed = false,
                                    isPending = false,
                                    isDarkMode = isDarkMode,
                                    themeTextColor = themeTextColor
                                )
                            }
                        }
                    } else {
                        // Trình bày text phẳng nếu parse lỗi
                        val scrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = selectedMeetingRecord!!.translatedText,
                                    color = themeTextColor,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
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
                            val clip = android.content.ClipData.newPlainText("Meeting Transcript", selectedMeetingRecord!!.translatedText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Đã sao chép nội dung cuộc họp!", Toast.LENGTH_SHORT).show()
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
                // GIAO DIỆN GHI ÂM HOẶC LỊCH SỬ
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isRecording) {
                        // ĐANG TRONG CUỘC HỌP
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Bảng điều khiển thời gian ở trên cùng
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(themeSurfaceColor, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    val minutes = durationSec / 60
                                    val seconds = durationSec % 60
                                    val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                                    val labelText = if (isPaused) "Tạm dừng họp..." else "Đang họp..."
                                    val labelColor = if (isPaused) Color(0xFFFFA000) else Color.Red

                                    Text(text = labelText, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(text = timeString, color = themeTextColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Nút tạm dừng / tiếp tục
                                    IconButton(
                                        onClick = { if (isPaused) onResumeRecording() else onPauseRecording() },
                                        modifier = Modifier
                                            .background(
                                                if (isPaused) Color(0xFF4CAF50) else Color(0xFFFFA000),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // Nút hoàn thành cuộc họp
                                    IconButton(
                                        onClick = onStopRecording,
                                        modifier = Modifier
                                            .background(Color(0xFFD32F2F), RoundedCornerShape(8.dp))
                                            .size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            // Trạng thái mic hiện tại
                            Text(
                                text = micStatus,
                                color = themeTextColor.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )

                            // Kiểm tra xem có đoạn nào bị lỗi không để hiển thị nút dịch lại
                            val hasFailedSegments = segments.any { it.status == "FAILED" }
                            if (hasFailedSegments) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF8B2635).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                        Text("Có phân đoạn bị lỗi kết nối mạng.", color = themeTextColor, fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = onRetrySegments,
                                        modifier = Modifier.height(28.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Icon(Icons.Default.Refresh, null, tint = PrimaryAccent, modifier = Modifier.size(12.dp))
                                            Text("Dịch lại", color = PrimaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Hiển thị danh sách các phân đoạn hội thoại thời gian thực
                            val listState = rememberLazyListState()
                            LaunchedEffect(segments.size) {
                                if (segments.isNotEmpty()) {
                                    listState.animateScrollToItem(segments.size - 1)
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (segments.isEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        SoundWaveVisualizer(level = 0.5f, animate = !isPaused)
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            text = "Đang ghi âm cuộc họp...",
                                            color = themeTextColor.copy(alpha = 0.8f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Văn bản sẽ tự động hiển thị từng câu sau mỗi 30s.",
                                            color = themeMutedTextColor,
                                            fontSize = 10.sp
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(segments) { seg ->
                                            val timeText = formatStartTimeText(seg.startTimeMs)
                                            DialogBubbleItem(
                                                speakerLabel = seg.speakerLabel ?: "Đang nhận diện...",
                                                timeText = timeText,
                                                text = seg.transcribedText.orEmpty(),
                                                isFailed = seg.status == "FAILED",
                                                isPending = seg.status == "PENDING",
                                                isDarkMode = isDarkMode,
                                                themeTextColor = themeTextColor
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    } else if (isProcessing) {
                        // ĐANG XỬ LÝ PHÂN ĐOẠN SAU KHI DỪNG
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = SecondaryAccent)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = micStatus,
                                color = themeTextColor.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Đang đồng bộ và dịch phân đoạn cuối. Vui lòng không đóng app.",
                                color = themeMutedTextColor,
                                fontSize = 11.sp
                            )
                        }

                    } else {
                        // MÀN HÌNH CHỜ / KẾT QUẢ VỪA HOÀN THÀNH
                        if (transcript.isNotEmpty()) {
                            // Hiển thị kết quả cuộc họp vừa kết thúc
                            val finishedSegments = remember(transcript) {
                                parseTranscriptToSegments(transcript)
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "Nội dung cuộc họp vừa hoàn thành:",
                                    color = SecondaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(finishedSegments) { seg ->
                                            DialogBubbleItem(
                                                speakerLabel = seg.speakerLabel,
                                                timeText = seg.timeText,
                                                text = seg.text,
                                                isFailed = false,
                                                isPending = false,
                                                isDarkMode = isDarkMode,
                                                themeTextColor = themeTextColor
                                            )
                                        }
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
                                        Text("Họp cuộc mới", color = themeTextColor, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            // Màn hình chờ bắt đầu ghi âm
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = PrimaryAccent,
                                    modifier = Modifier.size(54.dp)
                                )
                                Text(
                                    text = "Bắt đầu cuộc họp mới",
                                    color = themeTextColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tự động ghi âm WAV không mất tiếng và phân chia đoạn 30s cục bộ.\nNhận diện giọng nói & gán nhãn người nói hoàn toàn offline.",
                                    color = themeMutedTextColor,
                                    fontSize = 11.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )

                                Spacer(Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        if (!hasPermission) onRequestPermission() else onStartRecording()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Mic, null, tint = Color.Black)
                                        Text("Ghi âm cuộc họp", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PHẦN LỊCH SỬ CUỘC HỌP
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
                                        
                                        // Hiển thị câu đầu tiên thay vì raw text dài
                                        val firstLine = record.translatedText.lines().firstOrNull { it.isNotBlank() } ?: "Bản ghi cuộc họp..."
                                        Text(
                                            text = firstLine,
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

// Bong bóng thoại đối thoại đẹp mắt
@Composable
fun DialogBubbleItem(
    speakerLabel: String,
    timeText: String,
    text: String,
    isFailed: Boolean,
    isPending: Boolean,
    isDarkMode: Boolean,
    themeTextColor: Color
) {
    val speakerColor = remember(speakerLabel) {
        getSpeakerColor(speakerLabel)
    }

    val bubbleBgColor = if (isDarkMode) {
        if (isFailed) Color(0xFF4A1521) else Color(0xFF2D2D2D)
    } else {
        if (isFailed) Color(0xFFFDE8EB) else Color(0xFFF1F1F1)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Cột nhãn người nói & Mốc thời gian
        Column(
            modifier = Modifier.width(90.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = speakerLabel,
                color = if (isPending) Color.Gray else speakerColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(
                text = timeText,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }

        // Bong bóng chứa text
        Box(
            modifier = Modifier
                .weight(1f)
                .background(bubbleBgColor, RoundedCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                .padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (isPending) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularProgressIndicator(strokeWidth = 1.5.dp, color = Color.Gray, modifier = Modifier.size(10.dp))
                        Text(text = "Đang nhận diện...", color = Color.Gray, fontSize = 11.sp)
                    }
                } else if (isFailed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color.Red, modifier = Modifier.size(12.dp))
                        Text(text = "Lỗi dịch ngầm (File được lưu an toàn)", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = text,
                            color = themeTextColor,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

// Bảng màu người nói ngẫu nhiên nhưng thống nhất
fun getSpeakerColor(label: String): Color {
    val clean = label.lowercase().trim()
    if (clean.contains("speaker 1") || clean.contains("người 1")) return Color(0xFF1E88E5) // Blue
    if (clean.contains("speaker 2") || clean.contains("người 2")) return Color(0xFF43A047) // Green
    if (clean.contains("speaker 3") || clean.contains("người 3")) return Color(0xFFE53935) // Red
    if (clean.contains("speaker 4") || clean.contains("người 4")) return Color(0xFF8E24AA) // Purple
    if (clean.contains("speaker 5") || clean.contains("người 5")) return Color(0xFFF57C00) // Orange
    
    // Fallback hashing
    val hash = label.hashCode()
    val colors = listOf(
        Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFE53935), Color(0xFF8E24AA),
        Color(0xFFF57C00), Color(0xFF00ACC1), Color(0xFF3949AB), Color(0xFFD81B60)
    )
    return colors[Math.abs(hash) % colors.size]
}

fun formatStartTimeText(ms: Long): String {
    val totalSecs = ms / 1000
    val minutes = totalSecs / 60
    val seconds = totalSecs % 60
    return String.format(Locale.getDefault(), "[%02d:%02d]", minutes, seconds)
}

// Parser cấu trúc dữ liệu thô phục vụ hiển thị
data class TempParsedSegment(val timeText: String, val speakerLabel: String, val text: String)

fun parseTranscriptToSegments(rawText: String): List<TempParsedSegment> {
    if (rawText.isBlank()) return emptyList()
    
    val list = mutableListOf<TempParsedSegment>()
    // Matcher cho định dạng: [00:30] Speaker 1: Nội dung văn bản
    val pattern = Pattern.compile("^\\[(\\d{2}:\\d{2})\\]\\s*([^:]+):\\s*(.*)$")
    
    // Mỗi đoạn phân tách bằng \n\n
    val blocks = rawText.split("\n\n")
    for (block in blocks) {
        val line = block.trim()
        if (line.isEmpty()) continue
        
        val matcher = pattern.matcher(line)
        if (matcher.find()) {
            val timeText = "[${matcher.group(1)}]"
            val speakerLabel = matcher.group(2).orEmpty().trim()
            val text = matcher.group(3).orEmpty().trim()
            list.add(TempParsedSegment(timeText, speakerLabel, text))
        } else {
            // Thử parse dòng đơn giản hơn nếu không khớp format hoàn toàn
            val colonIdx = line.indexOf(":")
            if (colonIdx > 0 && line.startsWith("[")) {
                val timeEnd = line.indexOf("]")
                if (timeEnd > 0 && timeEnd < colonIdx) {
                    val timeText = line.substring(0, timeEnd + 1)
                    val speakerLabel = line.substring(timeEnd + 1, colonIdx).trim()
                    val text = line.substring(colonIdx + 1).trim()
                    list.add(TempParsedSegment(timeText, speakerLabel, text))
                    continue
                }
            }
            // Fallback
            list.add(TempParsedSegment("[00:00]", "Speaker", line))
        }
    }
    return list
}
