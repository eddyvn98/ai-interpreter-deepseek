package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.example.overlay.OverlayCaptionService

private enum class PendingMicAction {
    TOGGLE_SESSION,
    PUSH_TO_TALK_START
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpreterApp(viewModel: InterpreterViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val currentMode by viewModel.currentMode.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isGeneratingVoice by viewModel.isGeneratingVoice.collectAsState()
    val isSummarizing by viewModel.isSummarizing.collectAsState()
    val isSummaryPaused by viewModel.isSummaryPaused.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val micStatus by viewModel.micStatus.collectAsState()
    val currentOriginal by viewModel.currentOriginalText.collectAsState()
    val currentTranslated by viewModel.currentTranslatedText.collectAsState()
    val currentAiSummary by viewModel.currentAiSummaryText.collectAsState()
    val records by viewModel.translationRecords.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val isOverlayEnabled by viewModel.isOverlayEnabled.collectAsState()
    val overlayAlpha by viewModel.overlayAlpha.collectAsState()
    val overlayFontScale by viewModel.overlayFontScale.collectAsState()
    val overlayDisplayMode by viewModel.overlayDisplayMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val totalCostUsd by viewModel.totalCostUsd.collectAsState()
    val isListeningModeEnabled by viewModel.isListeningModeEnabled.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()

    // Dynamic Theme Colors
    val themeBgColor = if (isDarkMode) Color(0xFF121212) else MinimalCreamBg
    val themeTextColor = if (isDarkMode) Color(0xFFF5F5F5) else MinimalCharcoalText
    val themeMutedTextColor = if (isDarkMode) Color(0xFFAAAAAA) else MinimalMutedGray
    val themePillColor = if (isDarkMode) Color(0xFF2C2C2C) else MinimalPillBeige
    val themeWhiteColor = if (isDarkMode) Color(0xFF1E1E1E) else MinimalWhite
    val themeBorderColor = if (isDarkMode) Color(0xFF333333) else MinimalBorder

    val isMeetingRecording by viewModel.isMeetingRecording.collectAsState()
    val isMeetingPaused by viewModel.isMeetingPaused.collectAsState()
    val meetingRecordDuration by viewModel.meetingRecordDuration.collectAsState()
    val isProcessingMeeting by viewModel.isProcessingMeeting.collectAsState()
    val meetingTranscript by viewModel.meetingTranscript.collectAsState()
    val meetingRawTranscript by viewModel.meetingRawTranscript.collectAsState()
    val meetingCompletionMessage by viewModel.meetingCompletionMessage.collectAsState()

    fun getLanguageLabel(code: String): String {
        return when (code.uppercase()) {
            "EN" -> "English"
            "VI" -> "Tiếng Việt"
            "ZH" -> "Tiếng Trung"
            "JA" -> "Tiếng Nhật"
            "YUE" -> "Hồng Kông"
            "AUTO" -> "Tự động"
            else -> code
        }
    }

    // Dynamic column label helper
    fun getColumnLabel(code: String, isSource: Boolean): String {
        val name = when (code.uppercase()) {
            "EN" -> "Tiếng Anh (English)"
            "VI" -> "Tiếng Việt (Vietnamese)"
            "ZH" -> "Tiếng Trung (Chinese)"
            "JA" -> "Tiếng Nhật (Japanese)"
            "YUE" -> "Tiếng Quảng Đông (Cantonese)"
            "AUTO" -> "Tự động (Auto)"
            else -> code
        }
        return if (isSource) name else "$name (Bản dịch)"
    }

    // Keep screen on for translation modes
    androidx.compose.runtime.DisposableEffect(currentMode) {
        val activity = context as? android.app.Activity
        if (currentMode == TranslateMode.EN_TO_VI || currentMode == TranslateMode.VI_TO_EN) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingMicAction by remember { mutableStateOf<PendingMicAction?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasRecordPermission = granted
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) {
            when (action) {
                PendingMicAction.TOGGLE_SESSION -> viewModel.toggleSession()
                PendingMicAction.PUSH_TO_TALK_START -> viewModel.onPushToTalkStart()
                null -> Unit
            }
        } else {
            Toast.makeText(context, "Cần quyền ghi âm để dùng chức năng nói.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestMicPermission(action: PendingMicAction) {
        pendingMicAction = action
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val groupedSessions = remember(records) {
        records.groupBy { it.sessionId }.toList().sortedByDescending { (_, list) -> list.maxOfOrNull { it.timestamp } ?: 0L }
    }

    val currentSessionRecords = remember(records, currentSessionId, currentMode) {
        if (currentSessionId.isNullOrBlank()) {
            emptyList()
        } else {
            records.filter { record ->
                record.sessionId == currentSessionId
            }.sortedBy { it.timestamp }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.86f), drawerContainerColor = themeBgColor) {
                Text("Quản lý chi phí & Nhật ký", color = themeTextColor, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = themeWhiteColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Tổng chi phí ước tính", color = themeMutedTextColor, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$${"%.5f".format(totalCostUsd)} USD",
                            color = PrimaryAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${"%,.0f".format(totalCostUsd * 25400)} VND",
                            color = SecondaryAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.Button(
                            onClick = { viewModel.resetCost() },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF8B2635)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Đặt lại chi phí tích lũy", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = themeWhiteColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Tốc độ đọc giọng nói (TTS)", color = themeMutedTextColor, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${"%.2f".format(speechRate)}x",
                                color = SecondaryAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = if (speechRate <= 1.0f) "Chậm" else if (speechRate <= 1.3f) "Bình thường" else "Nhanh",
                                color = themeTextColor.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = speechRate,
                            onValueChange = { viewModel.setSpeechRate(it) },
                            valueRange = 0.8f..2.0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Nhật ký theo session", color = themeTextColor, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(groupedSessions) { (sessionId, items) ->
                        Card(colors = CardDefaults.cardColors(containerColor = themeWhiteColor), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val displayName = if (sessionId.startsWith("MEETING_")) "Cuộc họp: " + sessionId.takeLast(6) else "Session: " + sessionId.take(8)
                                    Text(displayName, color = SecondaryAccent, fontSize = 12.sp)
                                    Text("Số câu: ${items.size}", color = themeTextColor.copy(alpha = 0.85f), fontSize = 12.sp)
                                    Text(text = items.firstOrNull()?.translatedText.orEmpty(), color = themeTextColor.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 2)
                                }
                                IconButton(onClick = { viewModel.deleteSession(sessionId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Xóa session", tint = Color.Red.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(containerColor = themeBgColor) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Header (Menu, Language Capsule, Settings Toggle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = {
                            viewModel.clearCurrentSessionHistory()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = themeTextColor
                        )
                    }

                    var showSourceMenu by remember { mutableStateOf(false) }
                    var showTargetMenu by remember { mutableStateOf(false) }

                    // Language Selector Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(themePillColor)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            Text(
                                text = getLanguageLabel(sourceLang),
                                color = themeTextColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { showSourceMenu = true }
                            )
                            DropdownMenu(
                                expanded = showSourceMenu,
                                onDismissRequest = { showSourceMenu = false },
                                modifier = Modifier.background(themeWhiteColor)
                            ) {
                                listOf("EN", "VI", "ZH", "JA", "YUE", "AUTO").forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text(getLanguageLabel(code), color = themeTextColor) },
                                        onClick = {
                                            viewModel.setSourceLang(code)
                                            showSourceMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Text("➜", color = themeTextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                        Box {
                            Text(
                                text = getLanguageLabel(targetLang),
                                color = themeTextColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable { showTargetMenu = true }
                            )
                            DropdownMenu(
                                expanded = showTargetMenu,
                                onDismissRequest = { showTargetMenu = false },
                                modifier = Modifier.background(themeWhiteColor)
                            ) {
                                listOf("VI", "EN").forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text(getLanguageLabel(code), color = themeTextColor) },
                                        onClick = {
                                            viewModel.setTargetLang(code)
                                            showTargetMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Light / Dark mode toggle button
                        IconButton(
                            onClick = { viewModel.setDarkMode(!isDarkMode) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Đổi giao diện",
                                tint = themeTextColor
                            )
                        }

                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Thêm tùy chọn",
                                    tint = themeTextColor
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(themeWhiteColor)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isOverlayEnabled) "Tắt phụ đề nổi" else "Bật phụ đề nổi", color = themeTextColor, fontSize = 14.sp) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Subtitles,
                                            contentDescription = null,
                                            tint = if (isOverlayEnabled) MinimalAccentBrown else themeMutedTextColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                            Toast.makeText(context, "Hãy cấp quyền 'Hiển thị trên ứng dụng khác' rồi bật lại phụ đề nổi.", Toast.LENGTH_LONG).show()
                                        } else {
                                            viewModel.setOverlayEnabled(!isOverlayEnabled)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Lịch sử & Nhật ký", color = themeTextColor, fontSize = 14.sp) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = null,
                                            tint = themeMutedTextColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        scope.launch { drawerState.open() }
                                    }
                                )
                            }
                        }
                    }
                }

                // Cost summary (minimal line)
                Text(
                    text = "Chi phí tích lũy: $${"%.5f".format(totalCostUsd)} (~${"%,.0f".format(totalCostUsd * 25400)} đ)",
                    color = themeMutedTextColor,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                if (!errorMessage.isNullOrBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFCDD2))
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFC62828),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                // 2. Content view
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (currentMode == TranslateMode.EN_TO_VI) {
                        ListeningMaxLayout(
                            sessionRecords = currentSessionRecords,
                            currentOriginal = currentOriginal,
                            currentTranslated = currentTranslated,
                            isGeneratingVoice = isGeneratingVoice,
                            summaryText = if (isSummarizing) "Đang tóm tắt..." else currentAiSummary.ifBlank { "Chưa có tóm tắt." },
                            summaryPaused = isSummaryPaused,
                            onPauseSummary = { viewModel.toggleSummaryPause() },
                            isPaused = isPaused,
                            isListening = isListening,
                            micLevel = micLevel,
                            micStatus = micStatus,
                            isListeningModeEnabled = isListeningModeEnabled,
                            onListeningModeToggle = { viewModel.setListeningModeEnabled(it) },
                            modifier = Modifier.fillMaxSize(),
                            onClearScreen = null,
                            onToggleSession = {
                                if (!hasRecordPermission) requestMicPermission(PendingMicAction.TOGGLE_SESSION) else viewModel.toggleSession()
                            },
                            sourceLanguageLabel = getColumnLabel(sourceLang, true),
                            targetLanguageLabel = getColumnLabel(targetLang, false),
                            showPlayPauseHeaderButton = false,
                            isDarkMode = isDarkMode
                        )
                    } else if (currentMode == TranslateMode.VI_TO_EN) {
                        ListeningMaxLayout(
                            sessionRecords = currentSessionRecords,
                            currentOriginal = currentOriginal,
                            currentTranslated = currentTranslated,
                            isGeneratingVoice = isGeneratingVoice,
                            summaryText = if (isSummarizing) "Đang tóm tắt..." else currentAiSummary.ifBlank { "Chưa có tóm tắt." },
                            summaryPaused = isSummaryPaused,
                            onPauseSummary = { viewModel.toggleSummaryPause() },
                            isPaused = isPaused,
                            isListening = isListening,
                            micLevel = micLevel,
                            micStatus = micStatus,
                            isListeningModeEnabled = isListeningModeEnabled,
                            onListeningModeToggle = { viewModel.setListeningModeEnabled(it) },
                            modifier = Modifier.fillMaxSize(),
                            onClearScreen = null,
                            onToggleSession = {
                                if (!hasRecordPermission) requestMicPermission(PendingMicAction.TOGGLE_SESSION) else viewModel.toggleSession()
                            },
                            sourceLanguageLabel = getColumnLabel(sourceLang, true),
                            targetLanguageLabel = getColumnLabel(targetLang, false),
                            emptyStateText = "Chưa có nội dung dịch. Hãy bắt đầu nói tiếng Việt.",
                            showSummary = true,
                            isVerticalSplit = true,
                            showPlayPauseHeaderButton = false,
                            isDarkMode = isDarkMode
                        )
                    } else if (currentMode == TranslateMode.MEETING_RECORD) {
                        MeetingRecordLayout(
                            isRecording = isMeetingRecording,
                            isPaused = isMeetingPaused,
                            durationSec = meetingRecordDuration,
                            isProcessing = isProcessingMeeting,
                            transcript = meetingTranscript,
                            rawTranscript = meetingRawTranscript,
                            micStatus = micStatus,
                            records = records,
                            hasPermission = hasRecordPermission,
                            onRequestPermission = { requestMicPermission(PendingMicAction.TOGGLE_SESSION) },
                            onStartRecording = { viewModel.startMeetingRecording() },
                            onPauseRecording = { viewModel.pauseMeetingRecording() },
                            onResumeRecording = { viewModel.resumeMeetingRecording() },
                            onStopRecording = { viewModel.stopMeetingRecording() },
                            onDeleteRecord = { id -> viewModel.deleteHistoryRecord(id) },
                            modifier = Modifier.fillMaxSize(),
                            showControlButtons = false,
                            isDarkMode = isDarkMode
                        )
                    }
                }

                // 3. Floating Overlay Options
                if (isOverlayEnabled) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = themePillColor.copy(alpha = 0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, themeBorderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Độ trong suốt phụ đề nổi: ${(overlayAlpha * 100).toInt()}%", color = themeTextColor, fontSize = 11.sp)
                            Slider(
                                value = overlayAlpha,
                                onValueChange = { viewModel.setOverlayAlpha(it) },
                                valueRange = 0.2f..1f,
                                modifier = Modifier.height(24.dp)
                            )
                            Text("Cỡ chữ phụ đề nổi: ${"%.1f".format(overlayFontScale)}x", color = themeTextColor, fontSize = 11.sp)
                            Slider(
                                value = overlayFontScale,
                                onValueChange = { viewModel.setOverlayFontScale(it) },
                                valueRange = 0.8f..2.2f,
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }

                // 4. Tap mic to start
                val statusText = when {
                    currentMode == TranslateMode.MEETING_RECORD -> {
                        if (isMeetingRecording) {
                            if (isMeetingPaused) "Đang tạm dừng ghi cuộc họp..." else "Đang ghi cuộc họp chạy ẩn..."
                        } else {
                            "Tap mic to start meeting recording"
                        }
                    }
                    isListening -> "Listening... Tap mic to stop"
                    else -> "Tap mic to start"
                }
                Text(
                    text = statusText,
                    color = themeMutedTextColor,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp)
                )

                // 5. Control Center
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isMeetingActive = currentMode == TranslateMode.MEETING_RECORD
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .weight(1.2f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isMeetingActive) themePillColor else themeWhiteColor)
                            .border(1.dp, themeBorderColor, RoundedCornerShape(24.dp))
                            .clickable {
                                if (isMeetingActive) {
                                    viewModel.setTranslateMode(TranslateMode.EN_TO_VI)
                                } else {
                                    viewModel.setTranslateMode(TranslateMode.MEETING_RECORD)
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Họp liên tục",
                            tint = themeTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMeetingActive) "Đang họp" else "Họp liên tục",
                            color = themeTextColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    val mainMicBgColor = if (isListening) Color(0xFFD32F2F) else MinimalAccentBrown
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(mainMicBgColor)
                            .clickable {
                                if (!hasRecordPermission) {
                                    requestMicPermission(PendingMicAction.TOGGLE_SESSION)
                                } else {
                                    if (currentMode == TranslateMode.MEETING_RECORD) {
                                        if (!isMeetingRecording) {
                                            viewModel.startMeetingRecording()
                                        } else {
                                            if (isMeetingPaused) viewModel.resumeMeetingRecording() else viewModel.pauseMeetingRecording()
                                        }
                                    } else {
                                        viewModel.toggleSession()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val micIcon = if (isListening || (isMeetingRecording && !isMeetingPaused)) Icons.Default.Pause else Icons.Default.Mic
                        Icon(
                            imageVector = micIcon,
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        if (currentMode == TranslateMode.MEETING_RECORD && isMeetingRecording) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Dừng họp",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    viewModel.stopMeetingRecording()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Circular Button to toggle TTS playback auto-play (default off, saves value)
                    IconButton(
                        onClick = { viewModel.setTtsEnabled(!isTtsEnabled) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isTtsEnabled) MinimalAccentBrown else themePillColor)
                            .border(1.dp, themeBorderColor, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isTtsEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Bật/Tắt loa phát âm",
                            tint = if (isTtsEnabled) Color.White else themeTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasRecordPermission) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(meetingCompletionMessage) {
        val msg = meetingCompletionMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.consumeMeetingCompletionMessage()
        }
    }
}
