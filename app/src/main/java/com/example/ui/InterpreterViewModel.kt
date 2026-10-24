package com.example.ui

import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.ConversationSummary
import com.example.data.database.TranslationRecord
import com.example.data.repository.TranslationRepository
import com.example.data.api.RealtimeAsrClient
import com.example.overlay.OverlayCaptionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "InterpreterVM"

internal data class QueuedSpeech(
    val text: String,
    val locale: Locale,
    val utteranceId: String
)

class InterpreterViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository: TranslationRepository
    val translationRecords: StateFlow<List<TranslationRecord>>
    val conversationSummaries: StateFlow<List<ConversationSummary>>

    // Temporary in-memory records to prevent UI flickering during active translation streaming
    internal val pendingRecords = MutableStateFlow<List<TranslationRecord>>(emptyList())
    private var nextPendingId = -1L
    internal var lastTranslatedOriginalText: String = ""
    internal var lastTranslatedWordCount: Int = 0

    internal fun getNextPendingId(): Long {
        return nextPendingId--
    }

    internal fun addPendingRecord(id: Long, originalText: String, translatedText: String, sourceLang: String, targetLang: String, sessionId: String) {
        val record = TranslationRecord(
            id = id,
            originalText = originalText,
            translatedText = translatedText,
            sourceLang = sourceLang,
            targetLang = targetLang,
            sessionId = sessionId,
            timestamp = System.currentTimeMillis()
        )
        pendingRecords.value = pendingRecords.value + record
    }

    internal fun updatePendingRecordTranslation(id: Long, translatedText: String) {
        pendingRecords.value = pendingRecords.value.map {
            if (it.id == id) {
                it.copy(translatedText = translatedText)
            } else {
                it
            }
        }
    }

    internal fun removePendingRecord(id: Long) {
        pendingRecords.value = pendingRecords.value.filter { it.id != id }
    }

    internal val audioRecorder = AudioRecorder()
    internal var realtimeAsrClient: RealtimeAsrClient? = null

    internal var batchMediaRecorder: android.media.MediaRecorder? = null
    internal var batchRecordFile: java.io.File? = null
    internal var batchRecordJob: kotlinx.coroutines.Job? = null

    // Job for debounced partial-ASR translation (EN→VI realtime mode)
    internal var partialTranslationJob: kotlinx.coroutines.Job? = null

    // Dedicated scope for all audio processing coroutines.
    // Cancelling this scope kills ALL in-flight processing (translation, TTS, playback)
    // without touching the parent viewModelScope. A new scope is created after each cancel.
    internal var processingScope = createProcessingScope()

    private fun createProcessingScope() =
        kotlinx.coroutines.CoroutineScope(
            viewModelScope.coroutineContext +
            kotlinx.coroutines.SupervisorJob() +
            Dispatchers.Main
        )

    internal fun cancelProcessing() {
        partialTranslationJob?.cancel()
        partialTranslationJob = null
        processingScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        processingScope = createProcessingScope()
        _isGeneratingVoice.value = false
        stopPlayback()
        clearSpeechQueue()
    }

    // ─── New Flow States ──────────────────────────────────────────────────────
    internal val _selectedViToEnFlow = MutableStateFlow(ViToEnFlow.QWEN_DEFAULT)
    val selectedViToEnFlow: StateFlow<ViToEnFlow> = _selectedViToEnFlow.asStateFlow()

    internal val _selectedPhaGiaTts = MutableStateFlow(PhaGiaTtsType.EDGE_TTS)
    val selectedPhaGiaTts: StateFlow<PhaGiaTtsType> = _selectedPhaGiaTts.asStateFlow()

    internal val _executionLatencyInfo = MutableStateFlow<String?>(null)
    val executionLatencyInfo: StateFlow<String?> = _executionLatencyInfo.asStateFlow()

    fun setViToEnFlow(flow: ViToEnFlow) {
        // Cancel ALL in-progress processing from the previous flow to prevent bleed-over
        cancelProcessing()
        _selectedViToEnFlow.value = flow
        _errorMessage.value = null
        // Create a new distinct session ID for the target flow to ensure complete visual history isolation
        _currentSessionId.value = "${flow.name}_${UUID.randomUUID()}"
        _currentOriginalText.value = ""
        _currentTranslatedText.value = ""
    }

    fun setPhaGiaTts(tts: PhaGiaTtsType) {
        _selectedPhaGiaTts.value = tts
        _errorMessage.value = null
    }

    fun setListeningModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_listening_mode_enabled", enabled).apply()
        _isListeningModeEnabled.value = enabled
    }

    fun clearLatencyInfo() {
        _executionLatencyInfo.value = null
    }


    // ─── UI State ────────────────────────────────────────────────────────────

    internal val prefs = application.getSharedPreferences("usage_costs", android.content.Context.MODE_PRIVATE)

    internal val _currentMode = MutableStateFlow(TranslateMode.EN_TO_VI)
    val currentMode: StateFlow<TranslateMode> = _currentMode.asStateFlow()

    internal val _sourceLang = MutableStateFlow(prefs.getString("source_lang", "EN") ?: "EN")
    val sourceLang: StateFlow<String> = _sourceLang.asStateFlow()

    internal val _targetLang = MutableStateFlow(prefs.getString("target_lang", "VI") ?: "VI")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()


    internal val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    internal val _isPaused = MutableStateFlow(true)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    internal val _isGeneratingVoice = MutableStateFlow(false)
    val isGeneratingVoice: StateFlow<Boolean> = _isGeneratingVoice.asStateFlow()

    internal val _isGeneratingCloneVoice = MutableStateFlow(false)
    val isGeneratingCloneVoice: StateFlow<Boolean> = _isGeneratingCloneVoice.asStateFlow()

    internal val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    internal val _currentOriginalText = MutableStateFlow("")
    val currentOriginalText: StateFlow<String> = _currentOriginalText.asStateFlow()

    internal val _currentTranslatedText = MutableStateFlow("")
    val currentTranslatedText: StateFlow<String> = _currentTranslatedText.asStateFlow()

    internal val _currentAiSummaryText = MutableStateFlow("")
    val currentAiSummaryText: StateFlow<String> = _currentAiSummaryText.asStateFlow()

    internal val _isSummaryPaused = MutableStateFlow(true)
    val isSummaryPaused: StateFlow<Boolean> = _isSummaryPaused.asStateFlow()

    internal val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    internal val _activeSummary = MutableStateFlow<String?>(null)
    val activeSummary: StateFlow<String?> = _activeSummary.asStateFlow()

    internal val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    internal val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    internal val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    internal val _micStatus = MutableStateFlow("Sẵn sàng")
    val micStatus: StateFlow<String> = _micStatus.asStateFlow()

    internal val _isOverlayEnabled = MutableStateFlow(false)
    val isOverlayEnabled: StateFlow<Boolean> = _isOverlayEnabled.asStateFlow()

    internal val _overlayAlpha = MutableStateFlow(prefs.getFloat("overlay_alpha", 0.8f))
    val overlayAlpha: StateFlow<Float> = _overlayAlpha.asStateFlow()

    internal val _overlayFontScale = MutableStateFlow(prefs.getFloat("overlay_font_scale", 1.0f))
    val overlayFontScale: StateFlow<Float> = _overlayFontScale.asStateFlow()

    internal val _isTtsEnabled = MutableStateFlow(prefs.getBoolean("is_tts_enabled", false))
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    internal val _isDarkMode = MutableStateFlow(prefs.getBoolean("is_dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    internal var textToSpeechSystem: TextToSpeech? = null
    internal var mediaPlayer: MediaPlayer? = null
    internal val speechQueue = ArrayDeque<QueuedSpeech>()
    internal val speechCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    internal var speechQueueWorker: kotlinx.coroutines.Job? = null
    internal var speechSequence = 0

    internal var recordingStartTime = 0L

    internal val _overlayDisplayMode = MutableStateFlow(prefs.getInt("overlay_display_mode", OverlayCaptionService.DISPLAY_MODE_BOTH))
    val overlayDisplayMode: StateFlow<Int> = _overlayDisplayMode.asStateFlow()

    private val _totalCostUsd = MutableStateFlow(prefs.getFloat("total_cost_usd", 0f))
    val totalCostUsd: StateFlow<Float> = _totalCostUsd.asStateFlow()

    internal val _isListeningModeEnabled = MutableStateFlow(prefs.getBoolean("is_listening_mode_enabled", false))
    val isListeningModeEnabled: StateFlow<Boolean> = _isListeningModeEnabled.asStateFlow()

    internal val _speechRate = MutableStateFlow(prefs.getFloat("speech_rate", 1.25f))
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    fun setSpeechRate(rate: Float) {
        prefs.edit().putFloat("speech_rate", rate).apply()
        _speechRate.value = rate
        textToSpeechSystem?.setSpeechRate(rate)
    }


    // ─── Meeting Record State ────────────────────────────────────────────────
    val isMeetingRecording: StateFlow<Boolean> = MeetingRecordService.isRecording
    val isMeetingPaused: StateFlow<Boolean> = MeetingRecordService.isPaused
    val meetingRecordDuration: StateFlow<Int> = MeetingRecordService.durationSec

    internal val _isProcessingMeeting = MutableStateFlow(false)
    val isProcessingMeeting: StateFlow<Boolean> = _isProcessingMeeting.asStateFlow()

    internal val _meetingTranscript = MutableStateFlow("")
    val meetingTranscript: StateFlow<String> = _meetingTranscript.asStateFlow()

    internal val _meetingRawTranscript = MutableStateFlow("")
    val meetingRawTranscript: StateFlow<String> = _meetingRawTranscript.asStateFlow()

    internal val _meetingCompletionMessage = MutableStateFlow<String?>(null)
    val meetingCompletionMessage: StateFlow<String?> = _meetingCompletionMessage.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentMeetingSegments: StateFlow<List<com.example.data.database.MeetingAudioSegment>> = 
        MeetingRecordService.meetingSessionId.flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getSegmentsBySessionId(sessionId)
            } else {
                kotlinx.coroutines.flow.flowOf(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    internal fun addCost(amountUsd: Double) {
        val current = prefs.getFloat("total_cost_usd", 0f)
        val newCost = (current + amountUsd).toFloat()
        prefs.edit().putFloat("total_cost_usd", newCost).apply()
        _totalCostUsd.value = newCost
    }

    fun resetCost() {
        prefs.edit().putFloat("total_cost_usd", 0f).apply()
        _totalCostUsd.value = 0f
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    init {
        val database = AppDatabase.getDatabase(application)
        repository = TranslationRepository(database.translationDao())

        translationRecords = kotlinx.coroutines.flow.combine(
            repository.allRecords,
            pendingRecords
        ) { dbRecords, pending ->
            (dbRecords + pending).sortedByDescending { it.timestamp }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        conversationSummaries = repository.allSummaries.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        initializeTTS()

        // Collect segments recorded in background during pause/stop
        viewModelScope.launch {
            MeetingRecordService.latestSegment.collect { segmentInfo ->
                if (segmentInfo != null) {
                    processMeetingSegment(segmentInfo.sessionId, segmentInfo.filePath, segmentInfo.durationSec)
                    MeetingRecordService.clearLatestSegment()
                }
            }
        }

        // Collect overlay text flows and feed them to VM text flows when overlay is active
        viewModelScope.launch {
            OverlayCaptionService.currentOriginalFlow.collect { text ->
                if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE && text.isNotEmpty()) {
                    _currentOriginalText.value = text
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.currentTranslatedFlow.collect { text ->
                if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE && text.isNotEmpty()) {
                    _currentTranslatedText.value = text
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.activeMode.collect { mode ->
                if (mode == OverlayCaptionService.OverlayMode.EN_TO_VI) {
                    _currentMode.value = TranslateMode.EN_TO_VI
                    _isOverlayEnabled.value = true
                } else if (mode == OverlayCaptionService.OverlayMode.VI_TO_EN) {
                    _currentMode.value = TranslateMode.VI_TO_EN
                    _isOverlayEnabled.value = true
                } else {
                    _isOverlayEnabled.value = false
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.isPausedFlow.collect { paused ->
                if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE) {
                    _isPaused.value = paused
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.micLevelFlow.collect { level ->
                if (OverlayCaptionService.activeMode.value == OverlayCaptionService.OverlayMode.VI_TO_EN) {
                    _micLevel.value = level
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.micStatusFlow.collect { status ->
                if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE && status.isNotEmpty()) {
                    _micStatus.value = status
                }
            }
        }
        viewModelScope.launch {
            OverlayCaptionService.isSpeakingFlow.collect { speaking ->
                if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE) {
                    _isSpeaking.value = speaking
                }
            }
        }

        syncModeWithLanguages()

        Log.d(TAG, "ViewModel initialized – realtime Google STT mode")
    }

    private fun initializeTTS() {
        Log.d(TAG, "initializeTTS: Creating TextToSpeech instance with Google engine...")
        textToSpeechSystem = TextToSpeech(getApplication(), { status ->
            Log.d(TAG, "initializeTTS: OnInitListener status = $status")
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeechSystem?.setLanguage(Locale.US)
                Log.d(TAG, "initializeTTS: setLanguage(Locale.US) result = $result")
                textToSpeechSystem?.setSpeechRate(_speechRate.value)
                textToSpeechSystem?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart: utteranceId=$utteranceId at ${System.currentTimeMillis()}")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS onDone: utteranceId=$utteranceId at ${System.currentTimeMillis()}")
                        if (utteranceId != null) {
                            speechCompletions.remove(utteranceId)?.complete(Unit)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS onError: utteranceId=$utteranceId at ${System.currentTimeMillis()}")
                        if (utteranceId != null) {
                            speechCompletions.remove(utteranceId)?.completeExceptionally(
                                IllegalStateException("TTS error for $utteranceId")
                            )
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS onError: utteranceId=$utteranceId errorCode=$errorCode at ${System.currentTimeMillis()}")
                        if (utteranceId != null) {
                            speechCompletions.remove(utteranceId)?.completeExceptionally(
                                IllegalStateException("TTS error $errorCode for $utteranceId")
                            )
                        }
                    }
                })
            } else {
                Log.e(TAG, "initializeTTS failed with status: $status")
            }
        }, "com.google.android.tts")
    }

    internal fun enqueueSpeech(text: String, locale: Locale, utterancePrefix: String) {
        if (text.isBlank()) return
        val utteranceId = "${utterancePrefix}_${System.currentTimeMillis()}_${speechSequence++}"
        val request = QueuedSpeech(text = text, locale = locale, utteranceId = utteranceId)
        speechQueue.addLast(request)
        if (speechQueueWorker?.isActive != true) {
            speechQueueWorker = viewModelScope.launch(Dispatchers.Main) {
                runSpeechQueue()
            }
        }
        _isSpeaking.value = true
    }

    internal fun clearSpeechQueue() {
        speechQueue.clear()
        speechCompletions.values.forEach { deferred ->
            if (!deferred.isCompleted) {
                // Use cancel() not completeExceptionally(CancellationException) —
                // completeExceptionally can propagate synchronously to a main-thread
                // caller (e.g. Compose click handler) and poison the gesture pipeline,
                // making subsequent button clicks unresponsive.
                deferred.cancel()
            }
        }
        speechCompletions.clear()
        _isSpeaking.value = false
    }

    private suspend fun runSpeechQueue() {
        while (speechQueue.isNotEmpty()) {
            val request = speechQueue.removeFirst()
            try {
                playQueuedSpeech(request)
            } catch (e: Exception) {
                Log.e(TAG, "runSpeechQueue failed for ${request.utteranceId}", e)
            }
        }
        _isSpeaking.value = false
        speechQueueWorker = null
    }

    private suspend fun playQueuedSpeech(request: QueuedSpeech) {
        val deferred = CompletableDeferred<Unit>()
        speechCompletions[request.utteranceId] = deferred
        val originalLanguage = textToSpeechSystem?.language
        textToSpeechSystem?.language = request.locale
        textToSpeechSystem?.setSpeechRate(_speechRate.value)
        Log.d(TAG, "playQueuedSpeech: speak() utteranceId=${request.utteranceId} at ${System.currentTimeMillis()} locale=${request.locale} rate=${_speechRate.value}")
        textToSpeechSystem?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, request.utteranceId)
        try {
            deferred.await()
        } finally {
            speechCompletions.remove(request.utteranceId)
            if (originalLanguage != null) {
                textToSpeechSystem?.language = originalLanguage
            }
        }
    }

    // ─── Session control ─────────────────────────────────────────────────────

    fun setTranslateMode(mode: TranslateMode) {
        // Cleanup real-time ASR BEFORE changing mode so stopListening() routes correctly.
        // If we change _currentMode first, stopListening() will think it is a VI_TO_EN session
        // and skip cleaning up the realtimeAsrClient, leaving stale callbacks that fire later.
        realtimeAsrClient?.finish()
        realtimeAsrClient = null
        audioRecorder.stopRecording()

        _currentMode.value = mode
        _isPaused.value = true // Reset pause state to keep EN_TO_VI and VI_TO_EN independent

        if (mode == TranslateMode.EN_TO_VI) {
            setSourceLang("EN")
            setTargetLang("VI")
        } else if (mode == TranslateMode.VI_TO_EN) {
            setSourceLang("VI")
            setTargetLang("EN")
        }

        stopListening()
    }

    fun setSourceLang(lang: String) {
        prefs.edit().putString("source_lang", lang).apply()
        _sourceLang.value = lang
        syncModeWithLanguages()
    }

    fun setTargetLang(lang: String) {
        prefs.edit().putString("target_lang", lang).apply()
        _targetLang.value = lang
        syncModeWithLanguages()
    }

    private fun syncModeWithLanguages() {
        val src = _sourceLang.value.uppercase()
        val tgt = _targetLang.value.uppercase()
        if (_currentMode.value != TranslateMode.MEETING_RECORD) {
            if (src == "VI" && tgt == "EN") {
                _currentMode.value = TranslateMode.VI_TO_EN
            } else {
                _currentMode.value = TranslateMode.EN_TO_VI
            }
        }
    }


    fun toggleSession() {
        Log.d(TAG, "toggleSession: isPaused=${_isPaused.value}")
        if (_isPaused.value) startSession() else stopSession()
    }

    fun startSession() {
        Log.d(TAG, "startSession: mode=${_currentMode.value}")
        _isPaused.value = false
        _errorMessage.value = null
        if (_currentMode.value == TranslateMode.EN_TO_VI) {
            val currentId = _currentSessionId.value
            if (currentId.isNullOrBlank() || !currentId.startsWith("EN_TO_VI_")) {
                _currentSessionId.value = "EN_TO_VI_${UUID.randomUUID()}"
            }
            startListening()
        } else if (_currentMode.value == TranslateMode.VI_TO_EN) {
            val currentId = _currentSessionId.value
            if (currentId.isNullOrBlank() || !currentId.startsWith("VI_TO_EN_")) {
                _currentSessionId.value = "VI_TO_EN_${UUID.randomUUID()}"
            }
            startListening()
        }
    }

    fun stopSession() {
        _isPaused.value = true
        stopListening()
    }

    // ─── Overlay ─────────────────────────────────────────────────────────────

    fun setOverlayEnabled(enabled: Boolean) {
        _isOverlayEnabled.value = enabled
        val app = getApplication<Application>()
        if (enabled) {
            OverlayCaptionService.start(app)
            OverlayCaptionService.update(app, _currentOriginalText.value, _currentTranslatedText.value)
            OverlayCaptionService.updateStyle(app, _overlayAlpha.value, _overlayFontScale.value, _overlayDisplayMode.value)
        } else {
            OverlayCaptionService.stop(app)
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_tts_enabled", enabled).apply()
        _isTtsEnabled.value = enabled
    }

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("is_dark_mode", enabled).apply()
        _isDarkMode.value = enabled
    }

    fun setOverlayAlpha(value: Float) {
        prefs.edit().putFloat("overlay_alpha", value).apply()
        _overlayAlpha.value = value
        if (_isOverlayEnabled.value) {
            OverlayCaptionService.updateStyle(getApplication(), _overlayAlpha.value, _overlayFontScale.value, _overlayDisplayMode.value)
        }
    }

    fun setOverlayFontScale(value: Float) {
        prefs.edit().putFloat("overlay_font_scale", value).apply()
        _overlayFontScale.value = value
        if (_isOverlayEnabled.value) {
            OverlayCaptionService.updateStyle(getApplication(), _overlayAlpha.value, _overlayFontScale.value, _overlayDisplayMode.value)
        }
    }

    fun setOverlayDisplayMode(value: Int) {
        prefs.edit().putInt("overlay_display_mode", value).apply()
        _overlayDisplayMode.value = value
        if (_isOverlayEnabled.value) {
            OverlayCaptionService.updateStyle(getApplication(), _overlayAlpha.value, _overlayFontScale.value, _overlayDisplayMode.value)
        }
    }

    fun toggleSummaryPause() {
        _isSummaryPaused.value = !_isSummaryPaused.value
    }

    fun consumeMeetingCompletionMessage() {
        _meetingCompletionMessage.value = null
    }

    // ─── Mic / Recording ─────────────────────────────────────────────────────

    fun startListening() {
        Log.d(TAG, "startListening: isPaused=${_isPaused.value}")
        if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE) {
            if (OverlayCaptionService.activeMode.value == OverlayCaptionService.OverlayMode.EN_TO_VI) {
                OverlayCaptionService.isPausedFlow.value = false
            }
            return
        }
        if (_currentMode.value == TranslateMode.EN_TO_VI && _isPaused.value) return

        val context = getApplication<Application>()
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            _errorMessage.value = "Chưa cấp quyền ghi âm."
            _micStatus.value = "Thiếu quyền ghi âm"
            return
        }

        stopPlayback()
        _errorMessage.value = null
        startRealtimeStreaming()
    }

    fun stopListening() {
        Log.d(TAG, "stopListening called")
        if (OverlayCaptionService.activeMode.value != OverlayCaptionService.OverlayMode.NONE) {
            if (OverlayCaptionService.activeMode.value == OverlayCaptionService.OverlayMode.EN_TO_VI) {
                OverlayCaptionService.isPausedFlow.value = true
            }
            return
        }
        if (_isListening.value) {
            _isListening.value = false
        }
        _micLevel.value = 0f

        batchRecordJob?.cancel()
        batchRecordJob = null

        // Stop microphone
        audioRecorder.stopRecording()

        if (recordingStartTime > 0L) {
            val elapsedMs = System.currentTimeMillis() - recordingStartTime
            recordingStartTime = 0L
            val elapsedSec = elapsedMs / 1000.0
            val cost = elapsedSec * 0.00009
            addCost(cost)
            Log.d(TAG, "ASR used $elapsedSec seconds, cost: $cost USD")
        }

        // Close WebSocket gracefully – server will flush remaining transcription
        realtimeAsrClient?.finish()
        realtimeAsrClient = null
    }

    override fun onCleared() {
        super.onCleared()
        processingScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        stopListening()
        OverlayCaptionService.stop(getApplication())
        textToSpeechSystem?.shutdown()
        mediaPlayer?.release()
    }
}
