package com.example.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.R
import com.example.data.api.DeepSeekApiClient
import com.example.data.api.GoogleTranslateApiClient
import com.example.data.api.LocalAsrClient
import com.example.data.api.RealtimeAsrClient
import com.example.ui.InterpreterWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class OverlayCaptionService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayRoot: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private var tvOverlayStatus: TextView? = null
    private var tvMicStatus: TextView? = null
    private var tvMicLevel: TextView? = null
    private var tvTranslationHint: TextView? = null
    private var panelListening: View? = null
    private var panelTranslated: View? = null
    private var actionRow: View? = null
    private var replayGroup: View? = null
    private var collapseGroup: View? = null
    private var voiceWaveView: VoiceWaveView? = null
    private var btnMic: android.widget.ImageView? = null
    private var btnSpeakAgain: android.widget.ImageView? = null
    private var btnReplayMic: android.widget.ImageView? = null
    private var btnCollapseMic: android.widget.ImageView? = null
    private var btnCloseMic: android.widget.ImageView? = null
    private var isOptionsShowing: Boolean = false
    private var lastEnglishTranslation: String = ""
    private var lastTranslatedWordCount: Int = 0
    private var lastTranslatedOriginalText: String = ""
    private var overlayDisplayMode: Int = DISPLAY_MODE_BOTH

    private var overlayAlpha: Float = 0.80f
    private var overlayFontScale: Float = 1.0f

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ViToEn state
    enum class MicState { IDLE, RECORDING, PROCESSING, TRANSLATED, PLAYING }
    val micState = _micState
    private var batchMediaRecorder: android.media.MediaRecorder? = null
    private var batchRecordFile: File? = null
    private var batchRecordJob: Job? = null
    private var recordingStartTime = 0L
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var textToSpeechSystem: android.speech.tts.TextToSpeech? = null

    // EnToVi state
    private var realtimeAsrClient: RealtimeAsrClient? = null
    private val audioRecorder = com.example.ui.AudioRecorder()
    private var partialTranslationJob: Job? = null

    enum class OverlayMode { NONE, EN_TO_VI, VI_TO_EN }

    companion object {
        private const val CHANNEL_ID = "overlay_caption_channel"
        private const val NOTIFICATION_ID = 2202
        private const val TAG = "OverlayCaptionService"

        const val ACTION_START = "com.example.overlay.START" // Deprecated fallback
        const val ACTION_START_EN_TO_VI = "com.example.overlay.START_EN_TO_VI"
        const val ACTION_START_VI_TO_EN = "com.example.overlay.START_VI_TO_EN"
        const val ACTION_UPDATE = "com.example.overlay.UPDATE"
        const val ACTION_STYLE = "com.example.overlay.STYLE"
        const val ACTION_STOP = "com.example.overlay.STOP"
        
        const val EXTRA_ORIGINAL = "extra_original"
        const val EXTRA_TRANSLATED = "extra_translated"
        const val EXTRA_ALPHA = "extra_alpha"
        const val EXTRA_FONT_SCALE = "extra_font_scale"
        const val EXTRA_DISPLAY_MODE = "extra_display_mode"

        const val DISPLAY_MODE_BOTH = 0
        const val DISPLAY_MODE_EN = 1
        const val DISPLAY_MODE_VI = 2

        private val _activeMode = MutableStateFlow(OverlayMode.NONE)
        val activeMode = _activeMode.asStateFlow()

        val isPausedFlow = MutableStateFlow(false)

        val _currentOriginalFlow = MutableStateFlow("")
        val currentOriginalFlow = _currentOriginalFlow.asStateFlow()

        val _currentTranslatedFlow = MutableStateFlow("")
        val currentTranslatedFlow = _currentTranslatedFlow.asStateFlow()

        val _micState = MutableStateFlow(MicState.IDLE)
        val micStateFlow = _micState.asStateFlow()

        const val ACTION_VI_TO_EN_MIC = "com.example.overlay.VI_TO_EN_MIC"
        const val ACTION_VI_TO_EN_REPLAY = "com.example.overlay.VI_TO_EN_REPLAY"
        const val ACTION_VI_TO_EN_CLONE_REPLAY = "com.example.overlay.ACTION_VI_TO_EN_CLONE_REPLAY"

        val _micLevelFlow = MutableStateFlow(0f)
        val micLevelFlow = _micLevelFlow.asStateFlow()

        val _micStatusFlow = MutableStateFlow("")
        val micStatusFlow = _micStatusFlow.asStateFlow()

        val _isSpeakingFlow = MutableStateFlow(false)
        val isSpeakingFlow = _isSpeakingFlow.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OverlayCaptionService::class.java).apply {
                action = ACTION_START_EN_TO_VI
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayCaptionService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun update(context: Context, original: String, translated: String) {
            val intent = Intent(context, OverlayCaptionService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_ORIGINAL, original)
                putExtra(EXTRA_TRANSLATED, translated)
            }
            context.startService(intent)
        }

        fun updateStyle(context: Context, alpha: Float, fontScale: Float, displayMode: Int = DISPLAY_MODE_BOTH) {
            val intent = Intent(context, OverlayCaptionService::class.java).apply {
                action = ACTION_STYLE
                putExtra(EXTRA_ALPHA, alpha)
                putExtra(EXTRA_FONT_SCALE, fontScale)
                putExtra(EXTRA_DISPLAY_MODE, displayMode)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun vibrate(durationMs: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            val vib = vibratorManager?.defaultVibrator ?: vibrator
            vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Phụ đề nổi đang chạy"))
        initializeTTS()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_START_EN_TO_VI -> {
                startMode(OverlayMode.EN_TO_VI)
            }
            ACTION_START_VI_TO_EN -> {
                val sourceBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("SOURCE_BOUNDS", android.graphics.Rect::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("SOURCE_BOUNDS")
                }
                startMode(OverlayMode.VI_TO_EN, sourceBounds)
            }
            ACTION_UPDATE -> {
                val original = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
                val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty()
                updateOverlayText(original, translated)
            }
            ACTION_STYLE -> {
                val alpha = intent.getFloatExtra(EXTRA_ALPHA, overlayAlpha)
                val fontScale = intent.getFloatExtra(EXTRA_FONT_SCALE, overlayFontScale)
                val displayMode = intent.getIntExtra(EXTRA_DISPLAY_MODE, overlayDisplayMode)
                applyStyle(alpha, fontScale, displayMode)
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_VI_TO_EN_MIC -> {
                toggleViToEnMic()
            }
            ACTION_VI_TO_EN_REPLAY -> {
                replayLastTranslation()
            }
            ACTION_VI_TO_EN_CLONE_REPLAY -> {
                replayLastTranslationWithClone()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupMode()
        serviceScope.cancel()
        _activeMode.value = OverlayMode.NONE
        InterpreterWidgetProvider.updateWidgetState(this)
        
        runCatching { textToSpeechSystem?.shutdown() }
    }

    private fun startMode(mode: OverlayMode, sourceBounds: android.graphics.Rect? = null) {
        if (_activeMode.value == mode) return
        
        cleanupMode()
        _activeMode.value = mode
        
        createOverlay(mode, sourceBounds)
        
        if (mode == OverlayMode.EN_TO_VI) {
            startAsrPipeline()
        } else if (mode == OverlayMode.VI_TO_EN) {
            renderViToEnIdleUi()
        }
        
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun createOverlay(mode: OverlayMode, sourceBounds: android.graphics.Rect? = null) {
        if (mode == OverlayMode.VI_TO_EN) {
            // No floating WindowManager overlay for ViToEn mode, handled inside the widget
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        val view = if (mode == OverlayMode.EN_TO_VI) {
            inflater.inflate(R.layout.overlay_subtitle, null)
        } else {
            inflater.inflate(R.layout.overlay_mic, null)
        }
        overlayRoot = view

        setupOverlayInteractions(view, mode)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (mode == OverlayMode.VI_TO_EN) {
                if (sourceBounds != null) {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    val density = resources.displayMetrics.density
                    val offset = (40 * density).toInt()
                    y = (sourceBounds.top - offset).coerceAtLeast(0)
                } else {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                }
            } else {
                gravity = Gravity.TOP
                val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
                y = prefs.getInt("overlay_pos_y", 120)
            }
        }
        layoutParams = params
        
        val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
        overlayDisplayMode = prefs.getInt("overlay_display_mode", DISPLAY_MODE_BOTH)
        view.alpha = if (isPausedFlow.value) 0.5f else 1.0f

        windowManager?.addView(view, params)
        applyStyle(overlayAlpha, overlayFontScale, overlayDisplayMode)
    }

    private fun setupOverlayInteractions(view: View, mode: OverlayMode) {
        if (mode == OverlayMode.EN_TO_VI) {
            tvOriginal = view.findViewById(R.id.tv_original)
            tvTranslated = view.findViewById(R.id.tv_translated)
            tvOverlayStatus = null

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var runnable1s: Runnable? = null
            var runnable2s: Runnable? = null

            view.setOnTouchListener(object : View.OnTouchListener {
                private var initialY = 0
                private var initialTouchY = 0f
                private var initialTouchX = 0f
                private var isLongPress1sTriggered = false
                private var isLongPress2sTriggered = false
                private var canDrag = false
                private val touchSlop = 10f * resources.displayMetrics.density

                private var trashView: View? = null
                private var isHoveredOnTrash = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val lp = layoutParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = lp.y
                            initialTouchY = event.rawY
                            initialTouchX = event.rawX
                            isLongPress1sTriggered = false
                            isLongPress2sTriggered = false
                            canDrag = false
                            isHoveredOnTrash = false

                            runnable1s = Runnable {
                                if (!isLongPress1sTriggered && !canDrag) {
                                    isLongPress1sTriggered = true
                                    vibrate(50)
                                    if (isPausedFlow.value) {
                                        resumeAsrPipeline()
                                        android.widget.Toast.makeText(applicationContext, "Phụ đề: Đang nghe", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        pauseAsrPipeline()
                                        android.widget.Toast.makeText(applicationContext, "Phụ đề: Đã tạm dừng", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            handler.postDelayed(runnable1s!!, 1000)

                            runnable2s = Runnable {
                                if (!isLongPress2sTriggered) {
                                    isLongPress2sTriggered = true
                                    canDrag = true
                                    vibrate(40)
                                    handler.postDelayed({ vibrate(40) }, 100)
                                    showTrashView()
                                }
                            }
                            handler.postDelayed(runnable2s!!, 2000)

                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaY = event.rawY - initialTouchY
                            val deltaX = event.rawX - initialTouchX

                            if (!canDrag) {
                                if (Math.abs(deltaY) > touchSlop || Math.abs(deltaX) > touchSlop) {
                                    runnable1s?.let { handler.removeCallbacks(it) }
                                    runnable2s?.let { handler.removeCallbacks(it) }
                                }
                            } else {
                                lp.y = (initialY + deltaY).toInt()
                                windowManager?.updateViewLayout(overlayRoot, lp)
                                checkTrashHover(event.rawX, event.rawY)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            runnable1s?.let { handler.removeCallbacks(it) }
                            runnable2s?.let { handler.removeCallbacks(it) }

                            if (canDrag) {
                                removeTrashView()
                                if (isHoveredOnTrash) {
                                    stopSelf()
                                } else {
                                    val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
                                    prefs.edit().putInt("overlay_pos_y", lp.y).apply()
                                }
                            } else {
                                if (!isLongPress1sTriggered && !isLongPress2sTriggered) {
                                    val nextMode = when (overlayDisplayMode) {
                                        DISPLAY_MODE_BOTH -> DISPLAY_MODE_EN
                                        DISPLAY_MODE_EN -> DISPLAY_MODE_VI
                                        else -> DISPLAY_MODE_BOTH
                                    }
                                    val msg = when (nextMode) {
                                        DISPLAY_MODE_BOTH -> "Hiển thị: Song ngữ"
                                        DISPLAY_MODE_EN -> "Hiển thị: Tiếng Anh"
                                        DISPLAY_MODE_VI -> "Hiển thị: Tiếng Việt"
                                        else -> ""
                                    }
                                    android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    
                                    val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
                                    prefs.edit().putInt("overlay_display_mode", nextMode).apply()
                                    
                                    applyStyle(overlayAlpha, overlayFontScale, nextMode)
                                }
                            }
                            return true
                        }
                    }
                    return false
                }

                private fun showTrashView() {
                    if (trashView != null) return
                    val inflater = LayoutInflater.from(this@OverlayCaptionService)
                    trashView = inflater.inflate(R.layout.overlay_trash, null)
                    
                    val density = resources.displayMetrics.density
                    val trashParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        y = (80 * density).toInt()
                    }
                    windowManager?.addView(trashView, trashParams)
                }

                private fun removeTrashView() {
                    trashView?.let {
                        runCatching { windowManager?.removeView(it) }
                    }
                    trashView = null
                    isHoveredOnTrash = false
                }

                private fun checkTrashHover(rawX: Float, rawY: Float) {
                    val view = trashView ?: return
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val density = resources.displayMetrics.density

                    val trashCenterX = screenWidth / 2f
                    val trashCenterY = screenHeight - (130f * density)

                    val distance = Math.hypot((rawX - trashCenterX).toDouble(), (rawY - trashCenterY).toDouble())
                    val threshold = 90f * density

                    val trashCircle = view.findViewById<View>(R.id.trash_circle)
                    val tvLabel = view.findViewById<TextView>(R.id.tv_trash_label)

                    if (distance < threshold) {
                        if (!isHoveredOnTrash) {
                            isHoveredOnTrash = true
                            vibrate(20)
                            trashCircle?.scaleX = 1.25f
                            trashCircle?.scaleY = 1.25f
                            trashCircle?.background?.mutate()?.setTint(android.graphics.Color.parseColor("#E53935"))
                            tvLabel?.text = "Thả ra để tắt phụ đề"
                            tvLabel?.setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                        }
                    } else {
                        if (isHoveredOnTrash) {
                            isHoveredOnTrash = false
                            trashCircle?.scaleX = 1.0f
                            trashCircle?.scaleY = 1.0f
                            trashCircle?.background?.mutate()?.setTint(android.graphics.Color.parseColor("#B32C2C2C"))
                            tvLabel?.text = "Kéo vào đây để tắt"
                            tvLabel?.setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
                        }
                    }
                }
            })
        } else if (mode == OverlayMode.VI_TO_EN) {
            tvMicStatus = view.findViewById(R.id.tv_mic_status)
            tvMicLevel = view.findViewById(R.id.tv_mic_level)
            panelListening = view.findViewById(R.id.panel_listening)
            panelTranslated = view.findViewById(R.id.panel_translated)
            actionRow = view.findViewById(R.id.action_row)
            replayGroup = view.findViewById(R.id.replay_group)
            collapseGroup = view.findViewById(R.id.collapse_group)
            voiceWaveView = view.findViewById(R.id.voice_wave)
            btnMic = view.findViewById(R.id.btn_mic)
            tvOriginal = view.findViewById(R.id.tv_original)
            tvTranslated = view.findViewById(R.id.tv_translated)
            btnSpeakAgain = view.findViewById(R.id.btn_speak_again)
            btnReplayMic = view.findViewById(R.id.btn_replay_mic)
            btnCollapseMic = view.findViewById(R.id.btn_collapse_mic)
            btnCloseMic = view.findViewById(R.id.btn_close_mic)

            btnMic?.setOnClickListener {
                toggleViToEnMic()
            }
            btnSpeakAgain?.setOnClickListener {
                replayLastTranslation()
            }
            btnReplayMic?.setOnClickListener {
                replayLastTranslation()
            }
            btnCollapseMic?.setOnClickListener {
                renderViToEnIdleUi(statusText = "Trả lời Việt", hintText = "Đã thu nhỏ")
                InterpreterWidgetProvider.updateWidgetState(this)
            }
            btnCloseMic?.setOnClickListener {
                stopSelf()
            }
            panelTranslated?.isClickable = true
            panelTranslated?.setOnClickListener {
                if (micState.value == MicState.TRANSLATED || micState.value == MicState.PLAYING) {
                    isOptionsShowing = !isOptionsShowing
                    renderViToEnTranslatedUi(
                        status = if (micState.value == MicState.PLAYING) "Đang phát..." else "Đã dịch xong",
                        speaking = micState.value == MicState.PLAYING
                    )
                }
            }
        }
    }

    private fun cleanupMode() {
        _micState.value = MicState.IDLE
        stopPlayback()
        audioRecorder.stopRecording()
        realtimeAsrClient?.finish()
        realtimeAsrClient = null
        batchRecordJob?.cancel()
        batchRecordJob = null
        partialTranslationJob?.cancel()
        partialTranslationJob = null
        
        runCatching {
            overlayRoot?.let { windowManager?.removeView(it) }
        }
        overlayRoot = null
        tvOriginal = null
        tvTranslated = null
        tvOverlayStatus = null
        tvMicStatus = null
        tvMicLevel = null
        tvTranslationHint = null
        panelListening = null
        panelTranslated = null
        actionRow = null
        replayGroup = null
        collapseGroup = null
        voiceWaveView = null
        btnMic = null
        btnSpeakAgain = null
        btnReplayMic = null
        btnCollapseMic = null
        btnCloseMic = null
        isOptionsShowing = false
        lastEnglishTranslation = ""
        
        _currentOriginalFlow.value = ""
        _currentTranslatedFlow.value = ""
        isPausedFlow.value = false
    }

    private fun updateOverlayText(original: String, translated: String) {
        tvOriginal?.text = "English: ${original.ifBlank { "..." }}"
        tvTranslated?.text = "Việt: ${translated.ifBlank { "..." }}"
    }

    private fun renderViToEnIdleUi(
        statusText: String = "Trả lời Việt",
        hintText: String = "Chạm để nói"
    ) {
        micState.value = MicState.IDLE
        isOptionsShowing = false
        _micStatusFlow.value = if (statusText != "Trả lời Việt") statusText else hintText
        tvMicStatus?.text = statusText
        
        panelListening?.visibility = View.VISIBLE
        panelTranslated?.visibility = View.GONE
        
        voiceWaveView?.setActive(false)
        voiceWaveView?.setLevel(0.15f)
        
        btnMic?.setImageResource(R.drawable.ic_mic)
        btnMic?.visibility = View.VISIBLE
        
        replayGroup?.visibility = View.INVISIBLE
        collapseGroup?.visibility = View.INVISIBLE
        
        btnSpeakAgain?.visibility = View.GONE
        btnCloseMic?.visibility = View.VISIBLE
        
        tvOriginal?.text = "..."
        tvTranslated?.text = "..."
        
        tvMicLevel?.visibility = View.VISIBLE
        tvMicLevel?.text = hintText
        _isSpeakingFlow.value = false
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun renderViToEnListeningUi() {
        micState.value = MicState.RECORDING
        isOptionsShowing = false
        _micStatusFlow.value = "Đang nghe"
        tvMicStatus?.text = "Đang nghe..."
        
        panelListening?.visibility = View.VISIBLE
        panelTranslated?.visibility = View.GONE
        
        voiceWaveView?.setActive(true)
        
        btnMic?.setImageResource(R.drawable.ic_stop)
        btnMic?.visibility = View.VISIBLE
        
        replayGroup?.visibility = View.INVISIBLE
        collapseGroup?.visibility = View.INVISIBLE
        
        btnSpeakAgain?.visibility = View.GONE
        btnCloseMic?.visibility = View.GONE
        
        tvMicLevel?.visibility = View.VISIBLE
        tvMicLevel?.text = "Chạm để dừng và dịch"
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun renderViToEnProcessingUi(message: String = "Đang dịch...") {
        micState.value = MicState.PROCESSING
        isOptionsShowing = false
        _micStatusFlow.value = "Đang xử lý"
        tvMicStatus?.text = message
        
        panelListening?.visibility = View.VISIBLE
        panelTranslated?.visibility = View.GONE
        
        voiceWaveView?.setActive(false)
        voiceWaveView?.setLevel(0.15f)
        
        btnMic?.setImageResource(R.drawable.ic_mic)
        btnMic?.visibility = View.VISIBLE
        
        replayGroup?.visibility = View.INVISIBLE
        collapseGroup?.visibility = View.INVISIBLE
        
        btnSpeakAgain?.visibility = View.GONE
        btnCloseMic?.visibility = View.GONE
        
        tvMicLevel?.visibility = View.VISIBLE
        tvMicLevel?.text = "Vui lòng chờ"
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun renderViToEnTranslatedUi(status: String = "Đã dịch xong", speaking: Boolean = false) {
        micState.value = if (speaking) MicState.PLAYING else MicState.TRANSLATED
        _micStatusFlow.value = if (speaking) "Đang phát tiếng Anh" else "Đã dịch xong"
        
        panelListening?.visibility = View.GONE
        panelTranslated?.visibility = View.VISIBLE
        
        btnMic?.setImageResource(R.drawable.ic_mic)
        btnMic?.visibility = View.VISIBLE

        if (isOptionsShowing) {
            tvMicStatus?.text = "Tùy chọn"
            btnSpeakAgain?.visibility = View.GONE
            btnCloseMic?.visibility = View.VISIBLE
            
            replayGroup?.visibility = View.VISIBLE
            collapseGroup?.visibility = View.VISIBLE
            
            tvMicLevel?.visibility = View.INVISIBLE
        } else {
            tvMicStatus?.text = status
            btnSpeakAgain?.visibility = View.VISIBLE
            btnCloseMic?.visibility = View.GONE
            
            replayGroup?.visibility = View.INVISIBLE
            collapseGroup?.visibility = View.INVISIBLE
            
            tvMicLevel?.visibility = View.VISIBLE
            tvMicLevel?.text = if (speaking) "Đang phát tiếng Anh" else "Nhấn để nói câu khác"
        }
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun applyStyle(alpha: Float, fontScale: Float, displayMode: Int = overlayDisplayMode) {
        overlayAlpha = alpha.coerceIn(0.2f, 1.0f)
        overlayFontScale = fontScale.coerceIn(0.8f, 2.2f)
        overlayDisplayMode = displayMode
        overlayRoot?.alpha = 1.0f
        val container = overlayRoot?.findViewById<View>(R.id.subtitle_container)
        container?.background?.mutate()?.alpha = (overlayAlpha * 255).toInt()
        tvOriginal?.textSize = 14f * overlayFontScale
        tvTranslated?.textSize = 18f * overlayFontScale

        tvOriginal?.visibility = if (overlayDisplayMode == DISPLAY_MODE_BOTH || overlayDisplayMode == DISPLAY_MODE_EN) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        tvTranslated?.visibility = if (overlayDisplayMode == DISPLAY_MODE_BOTH || overlayDisplayMode == DISPLAY_MODE_VI) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun buildNotification(content: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI Interpreter Overlay", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AI Interpreter Overlay")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    // ─── Subtitles Mode ASR Pipeline (EN→VI) ───────────────────────────────────

    private fun startAsrPipeline() {
        val apiKey = BuildConfig.ASR_API_KEY.trim()
        val baseUrl = BuildConfig.ASR_BASE_URL.trim()
        if (baseUrl.isEmpty()) {
            tvOriginal?.text = "Thiếu cấu hình ASR"
            return
        }

        val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
        val sourceLang = prefs.getString("source_lang", "EN") ?: "EN"
        val targetLang = prefs.getString("target_lang", "VI") ?: "VI"

        realtimeAsrClient?.finish()

        val client = RealtimeAsrClient(apiKey, baseUrl)
        realtimeAsrClient = client
        tvOverlayStatus?.text = "Đang kết nối ASR..."

        client.connect(
            model = "medium",
            language = sourceLang.lowercase(),
            onReady = {
                Log.d(TAG, "Background ASR ready, starting AudioRecord")
                serviceScope.launch(Dispatchers.Main) {
                    isPausedFlow.value = false
                    tvOverlayStatus?.text = "Phụ đề: Đang nghe"
                    InterpreterWidgetProvider.updateWidgetState(this@OverlayCaptionService)
                }
                audioRecorder.startStreaming(
                    scope = serviceScope,
                    onFrame = { pcm, length ->
                        if (!isPausedFlow.value) client.sendAudioFrame(pcm, length)
                    },
                    onRmsChanged = { level ->
                        _micLevelFlow.value = level
                    }
                )
            },
            onPartial = { text ->
                serviceScope.launch(Dispatchers.Main) {
                    if (text.isBlank()) {
                        lastTranslatedWordCount = 0
                        lastTranslatedOriginalText = ""
                        _currentOriginalFlow.value = ""
                        tvOriginal?.text = "$sourceLang: ..."
                        _currentTranslatedFlow.value = ""
                        tvTranslated?.text = "$targetLang: ..."
                        return@launch
                    }
                    if (lastTranslatedWordCount == 0) {
                        _currentTranslatedFlow.value = ""
                        tvTranslated?.text = "$targetLang: ..."
                    }
                    _currentOriginalFlow.value = text
                    tvOriginal?.text = "$sourceLang: $text"
                    schedulePartialTranslation(text, sourceLang, targetLang)
                }
            },
            onFinalWithViText = { text, viText ->
                Log.d(TAG, "Background Final ASR: $text | VI: $viText")
                serviceScope.launch(Dispatchers.Main) {
                    partialTranslationJob?.cancel()
                    partialTranslationJob = null
                    _currentOriginalFlow.value = text
                    tvOriginal?.text = "$sourceLang: $text"

                    if (viText.isNotBlank() && targetLang == "VI") {
                        _currentTranslatedFlow.value = viText
                        tvTranslated?.text = "Việt: $viText"
                        saveRecordToDatabase(text, viText, sourceLang, targetLang)
                        executeBackgroundTranslation(text, sourceLang, targetLang, text, viText)
                    } else {
                        val lastOriginal = lastTranslatedOriginalText
                        val lastLive = _currentTranslatedFlow.value
                        executeBackgroundTranslation(text, sourceLang, targetLang, lastOriginal, lastLive)
                    }
                    lastTranslatedOriginalText = ""
                    lastTranslatedWordCount = 0
                }
            },
            onFinal = { text ->
                Log.d(TAG, "Background Final ASR sentence: $text")
                serviceScope.launch(Dispatchers.Main) {
                    partialTranslationJob?.cancel()
                    partialTranslationJob = null
                    _currentOriginalFlow.value = text
                    tvOriginal?.text = "$sourceLang: $text"
                    val lastOriginal = lastTranslatedOriginalText
                    val lastLive = _currentTranslatedFlow.value
                    executeBackgroundTranslation(text, sourceLang, targetLang, lastOriginal, lastLive)
                    lastTranslatedOriginalText = ""
                    lastTranslatedWordCount = 0
                }
            },
            onError = { msg ->
                Log.e(TAG, "Background ASR error: $msg")
                serviceScope.launch(Dispatchers.Main) {
                    tvOverlayStatus?.text = "Lỗi ASR"
                    audioRecorder.stopRecording()
                }
            }
        )
    }

    private fun schedulePartialTranslation(text: String, sourceLang: String, targetLang: String) {
        if (text.isBlank()) {
            lastTranslatedWordCount = 0
            return
        }

        val words = text.trim().split("\\s+".toRegex())
        val wordCount = words.size

        val shouldTranslate = lastTranslatedWordCount == 0 || 
                              (wordCount - lastTranslatedWordCount >= 3) || 
                              text.endsWith(".") || text.endsWith("?")

        if (!shouldTranslate) return

        val debounceMs = 200L

        partialTranslationJob?.cancel()
        partialTranslationJob = serviceScope.launch {
            delay(debounceMs)
            if (!isActive) return@launch
            try {
                val translated = withContext(Dispatchers.IO) {
                    GoogleTranslateApiClient.translate(text, sourceLang, targetLang)
                }
                if (isActive) {
                    val isSame = translated.trim().equals(text.trim(), ignoreCase = true)
                    val finalTranslated = if (isSame) "" else translated
                    _currentTranslatedFlow.value = finalTranslated
                    tvTranslated?.text = "$targetLang: ${finalTranslated.ifBlank { "..." }}"
                    lastTranslatedOriginalText = text
                    lastTranslatedWordCount = wordCount
                }
            } catch (e: Exception) {
                // Ignore partial translations exceptions
            }
        }
    }

    private fun executeBackgroundTranslation(
        text: String,
        sourceLang: String,
        targetLang: String,
        lastOriginal: String,
        lastLive: String
    ) {
        val isTranslationComplete = (text == lastOriginal) && lastLive.trim().isNotEmpty()

        serviceScope.launch {
            try {
                val translated = if (isTranslationComplete) {
                    Log.d(TAG, "executeBackgroundTranslation: live translation is complete, reusing: $lastLive")
                    lastLive
                } else {
                    Log.d(TAG, "executeBackgroundTranslation: live translation is incomplete, translating: $text")
                    withContext(Dispatchers.IO) {
                        GoogleTranslateApiClient.translate(text, sourceLang, targetLang)
                    }
                }

                _currentTranslatedFlow.value = translated
                tvTranslated?.text = "$targetLang: $translated"

                saveRecordToDatabase(text, translated, sourceLang, targetLang)

            } catch (e: Exception) {
                Log.e(TAG, "Background translate failed", e)
            }
        }
    }

    private fun pauseAsrPipeline() {
        isPausedFlow.value = true
        tvOverlayStatus?.text = "Phụ đề: Đã tạm dừng"
        InterpreterWidgetProvider.updateWidgetState(this)
        overlayRoot?.alpha = 0.5f
    }

    private fun resumeAsrPipeline() {
        isPausedFlow.value = false
        tvOverlayStatus?.text = "Phụ đề: Đang nghe"
        InterpreterWidgetProvider.updateWidgetState(this)
        overlayRoot?.alpha = 1.0f
    }

    // ─── Mic Mode ViToEn Pipeline ─────────────────────────────────────────────

    private fun toggleViToEnMic() {
        when (micState.value) {
            MicState.IDLE, MicState.TRANSLATED -> startViToEnRecording()
            MicState.RECORDING -> stopViToEnRecordingAndProcess()
            else -> Unit
        }
    }

    private fun startViToEnRecording() {
        val file = File(cacheDir, "ptt_vi_to_en_overlay.m4a")
        batchRecordFile = file
        if (file.exists()) {
            file.delete()
        }

        try {
            batchMediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            renderViToEnListeningUi()

            batchRecordJob?.cancel()
            batchRecordJob = serviceScope.launch(Dispatchers.Main) {
                while (micState.value == MicState.RECORDING) {
                    delay(100)
                    batchMediaRecorder?.let { mr ->
                        runCatching {
                            val maxAmp = mr.maxAmplitude
                            val normalized = minOf(1f, (maxAmp.toFloat() / 32768f) * 10f)
                            _micLevelFlow.value = normalized
                            voiceWaveView?.setLevel(normalized)
                            tvMicLevel?.text = "Lvl: ${(normalized * 100).toInt()}%"
                        }
                    }
                    InterpreterWidgetProvider.updateWidgetState(this@OverlayCaptionService)
                }
            }
            InterpreterWidgetProvider.updateWidgetState(this)
        } catch (e: Exception) {
            Log.e(TAG, "Overlay batch recording start failed", e)
            renderViToEnIdleUi(statusText = "Lỗi ghi âm", hintText = "Chạm để nói")
            batchMediaRecorder = null
        }
    }

    private fun stopViToEnRecordingAndProcess() {
        val recorder = batchMediaRecorder
        val file = batchRecordFile
        batchMediaRecorder = null
        batchRecordFile = null

        if (recorder != null) {
            runCatching {
                recorder.stop()
                recorder.release()
            }.onFailure { Log.e(TAG, "Failed to stop media recorder", it) }
        }

        val elapsedMs = if (recordingStartTime > 0L) System.currentTimeMillis() - recordingStartTime else 0L
        recordingStartTime = 0L

        if (file == null || !file.exists() || file.length() == 0L) {
            renderViToEnIdleUi(statusText = "Không có âm thanh", hintText = "Chạm để nói")
            InterpreterWidgetProvider.updateWidgetState(this)
            return
        }

        val durationSec = (elapsedMs / 1000.0).coerceAtLeast(1.0)
        renderViToEnProcessingUi("Đang nhận dạng...")
        InterpreterWidgetProvider.updateWidgetState(this)

        val prefs = getSharedPreferences("usage_costs", Context.MODE_PRIVATE)
        val sourceLang = prefs.getString("source_lang", "VI") ?: "VI"
        val targetLang = prefs.getString("target_lang", "EN") ?: "EN"

        serviceScope.launch {
            try {
                val asrBaseUrl = BuildConfig.ASR_BASE_URL.trim()
                if (asrBaseUrl.isEmpty()) {
                    throw IllegalStateException("Thiếu cấu hình ASR_BASE_URL")
                }

                // Transcribe via local Whisper server
                val rawTranscript = withContext(Dispatchers.IO) {
                    val bytes = file.readBytes()
                    LocalAsrClient.transcribeAudioFile(
                        baseUrl = asrBaseUrl,
                        audioBytes = bytes,
                        filename = file.name,
                        mimeType = "audio/m4a",
                        model = "medium",
                        language = sourceLang.lowercase()
                    )
                }

                if (rawTranscript.isBlank()) {
                    renderViToEnIdleUi(statusText = "Không nhận diện được", hintText = "Chạm để nói")
                    InterpreterWidgetProvider.updateWidgetState(this@OverlayCaptionService)
                    return@launch
                }

                _currentOriginalFlow.value = rawTranscript
                tvOriginal?.text = rawTranscript
                tvTranslated?.text = "Đang dịch..."
                renderViToEnProcessingUi("Đang dịch...")

                val translated = withContext(Dispatchers.IO) {
                    GoogleTranslateApiClient.translate(rawTranscript, sourceLang, targetLang)
                }

                _currentTranslatedFlow.value = translated
                lastEnglishTranslation = translated
                tvOriginal?.text = rawTranscript
                tvTranslated?.text = translated
                renderViToEnTranslatedUi(status = "Đã dịch xong", speaking = false)

                // Save to database
                saveRecordToDatabase(rawTranscript, translated, sourceLang, targetLang)

                // Play translation using local Android TTS matching output language
                renderViToEnTranslatedUi(status = "Đang phát...", speaking = true)
                speakWithAndroidTts(translated, if (targetLang == "VI") Locale("vi", "VN") else Locale.US)

            } catch (e: Exception) {
                Log.e(TAG, "PTT process failed", e)
                renderViToEnIdleUi(
                    statusText = "Lỗi: ${e.localizedMessage ?: "Không rõ"}",
                    hintText = "Chạm để nói"
                )
                InterpreterWidgetProvider.updateWidgetState(this@OverlayCaptionService)
            }
        }
    }

    private suspend fun playAudioMp3(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(cacheDir, "overlay_ai_speech.mp3")
            FileOutputStream(cacheFile).use { it.write(bytes) }
            
            withContext(Dispatchers.Main) {
                stopPlayback()
                _isSpeakingFlow.value = true
                mediaPlayer = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(cacheFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        _isSpeakingFlow.value = false
                        it.release()
                        mediaPlayer = null
                        onPlaybackFinished()
                    }
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playAudioMp3 failed", e)
            _isSpeakingFlow.value = false
            withContext(Dispatchers.Main) {
                onPlaybackFinished()
            }
        }
    }

    private suspend fun playAudioFromBase64(base64: String) = withContext(Dispatchers.IO) {
        try {
            val rawDecoded = Base64.decode(base64, Base64.DEFAULT)
            var decoded = rawDecoded
            val isRiff = decoded.size >= 12 &&
                decoded[0] == 'R'.toByte() && decoded[1] == 'I'.toByte() &&
                decoded[2] == 'F'.toByte() && decoded[3] == 'F'.toByte() &&
                decoded[8] == 'W'.toByte() && decoded[9] == 'A'.toByte() &&
                decoded[10] == 'V'.toByte() && decoded[11] == 'E'.toByte()

            if (!isRiff) {
                decoded = addWavHeaderToPcm(decoded, 24000)
            } else {
                decoded = fixWavHeaderSize(decoded)
            }

            val cacheFile = File(cacheDir, "overlay_ai_speech.wav")
            FileOutputStream(cacheFile).use { it.write(decoded) }

            withContext(Dispatchers.Main) {
                stopPlayback()
                _isSpeakingFlow.value = true
                mediaPlayer = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(cacheFile.absolutePath)
                    prepare()
                    setOnCompletionListener {
                        _isSpeakingFlow.value = false
                        it.release()
                        mediaPlayer = null
                        onPlaybackFinished()
                    }
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playAudioFromBase64 failed", e)
            _isSpeakingFlow.value = false
            withContext(Dispatchers.Main) {
                onPlaybackFinished()
            }
        }
    }

    private fun replayLastTranslation() {
        val text = lastEnglishTranslation.ifBlank { _currentTranslatedFlow.value }
        if (text.isBlank() || micState.value == MicState.RECORDING || micState.value == MicState.PROCESSING) return
        renderViToEnTranslatedUi(status = "Phát lại...", speaking = true)
        speakWithAndroidTts(text)
    }

    private fun replayLastTranslationWithClone() {
        val text = lastEnglishTranslation.ifBlank { _currentTranslatedFlow.value }
        if (text.isBlank() || micState.value == MicState.RECORDING || micState.value == MicState.PROCESSING) return
        renderViToEnTranslatedUi(status = "Đang tạo giọng...", speaking = true)
        
        serviceScope.launch {
            try {
                val asrBaseUrl = com.example.BuildConfig.ASR_BASE_URL.trim()
                val base64Audio = withContext(Dispatchers.IO) {
                    LocalAsrClient.synthesizeCloneVoice(asrBaseUrl, text)
                }
                if (base64Audio.isNotEmpty()) {
                    renderViToEnTranslatedUi(status = "Phát giọng clone...", speaking = true)
                    playAudioFromBase64(base64Audio)
                } else {
                    throw IllegalStateException("Empty audio returned from server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clone TTS synthesis failed, falling back to local TTS", e)
                renderViToEnTranslatedUi(status = "Lỗi clone, dùng TTS...", speaking = true)
                speakWithAndroidTts(text)
            }
        }
    }

    private fun stopPlayback() {
        runCatching {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
            textToSpeechSystem?.stop()
        }
        _isSpeakingFlow.value = false
    }

    private fun onPlaybackFinished() {
        renderViToEnTranslatedUi(status = "Đã dịch xong", speaking = false)
        InterpreterWidgetProvider.updateWidgetState(this)
    }

    private fun initializeTTS() {
        textToSpeechSystem = android.speech.tts.TextToSpeech(applicationContext, { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                textToSpeechSystem?.setLanguage(Locale.US)
                setLocalMaleVoice()
            }
        }, "com.google.android.tts")
    }

    private fun setLocalMaleVoice() {
        val tts = textToSpeechSystem ?: return
        try {
            val voices = tts.voices
            if (voices != null) {
                // Search for a voice containing "male" but NOT "female"
                val maleVoice = voices.firstOrNull { voice ->
                    voice.locale.language == "en" &&
                    voice.locale.country == "US" &&
                    voice.name.contains("male", ignoreCase = true) &&
                    !voice.name.contains("female", ignoreCase = true)
                } ?: voices.firstOrNull { voice ->
                    voice.locale.language == "en" &&
                    voice.name.contains("male", ignoreCase = true) &&
                    !voice.name.contains("female", ignoreCase = true)
                }
                if (maleVoice != null) {
                    tts.voice = maleVoice
                    Log.d(TAG, "Selected local male voice by keyword: ${maleVoice.name}")
                } else {
                    // Check for common Google TTS male voice suffixes, explicitly excluding "female" and "sfg"
                    val commonMaleSuffixes = listOf("iom", "iol", "rob", "rgf")
                    val suffixMaleVoice = voices.firstOrNull { voice ->
                        voice.locale.language == "en" &&
                        !voice.name.contains("female", ignoreCase = true) &&
                        !voice.name.contains("sfg", ignoreCase = true) &&
                        commonMaleSuffixes.any { suffix -> voice.name.contains(suffix, ignoreCase = true) }
                    }
                    if (suffixMaleVoice != null) {
                        tts.voice = suffixMaleVoice
                        Log.d(TAG, "Selected local male voice by suffix: ${suffixMaleVoice.name}")
                    } else {
                        // Fallback: choose any English voice that does not contain "female"
                        val fallbackVoice = voices.firstOrNull { voice ->
                            voice.locale.language == "en" &&
                            !voice.name.contains("female", ignoreCase = true)
                        }
                        if (fallbackVoice != null) {
                            tts.voice = fallbackVoice
                            Log.d(TAG, "Selected fallback male voice: ${fallbackVoice.name}")
                        } else {
                            Log.w(TAG, "No local English male voice found")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting male voice", e)
        }
    }

    private fun speakWithAndroidTts(text: String, locale: Locale = Locale.US) {
        stopPlayback()
        if (locale == Locale.US) {
            setLocalMaleVoice() // Force update to male voice right before speaking
        } else {
            textToSpeechSystem?.language = locale
        }
        _isSpeakingFlow.value = true
        textToSpeechSystem?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_overlay")
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                while (textToSpeechSystem?.isSpeaking == true) Thread.sleep(120)
            }
            _isSpeakingFlow.value = false
            onPlaybackFinished()
        }
    }

    private fun speakWithMaleFallback(text: String) {
        serviceScope.launch {
            speakWithAndroidTts(text)
        }
    }

    private fun addWavHeaderToPcm(pcmBytes: ByteArray, sampleRate: Int = 24000): ByteArray {
        val headerSize = 44
        val totalDataLen = pcmBytes.size
        val totalAudioLen = totalDataLen + headerSize - 8
        val byteRate = sampleRate * 2

        val header = ByteArray(headerSize)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalAudioLen and 0xff).toByte()
        header[5] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[6] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[7] = ((totalAudioLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = 1
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2
        header[34] = 16
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalDataLen and 0xff).toByte()
        header[41] = ((totalDataLen shr 8) and 0xff).toByte()
        header[42] = ((totalDataLen shr 16) and 0xff).toByte()
        header[43] = ((totalDataLen shr 24) and 0xff).toByte()

        val wavBytes = ByteArray(headerSize + pcmBytes.size)
        System.arraycopy(header, 0, wavBytes, 0, headerSize)
        System.arraycopy(pcmBytes, 0, wavBytes, headerSize, pcmBytes.size)
        return wavBytes
    }

    private fun fixWavHeaderSize(wavBytes: ByteArray): ByteArray {
        if (wavBytes.size < 44) return wavBytes
        val fixed = wavBytes.clone()
        val fileSizeMinus8 = fixed.size - 8
        val dataSize = fixed.size - 44
        fixed[4] = (fileSizeMinus8 and 0xFF).toByte()
        fixed[5] = ((fileSizeMinus8 shr 8) and 0xFF).toByte()
        fixed[6] = ((fileSizeMinus8 shr 16) and 0xFF).toByte()
        fixed[7] = ((fileSizeMinus8 shr 24) and 0xFF).toByte()
        fixed[40] = (dataSize and 0xFF).toByte()
        fixed[41] = ((dataSize shr 8) and 0xFF).toByte()
        fixed[42] = ((dataSize shr 16) and 0xFF).toByte()
        fixed[43] = ((dataSize shr 24) and 0xFF).toByte()
        return fixed
    }

    private fun getDeepSeekApiKey(): String {
        var key = runCatching { BuildConfig.DEEPSEEK_API_KEY }.getOrNull()?.trim() ?: ""
        if (key.isNotEmpty() && key != "MY_DEEPSEEK_API_KEY" && key != "null") return key

        key = System.getenv("DEEPSEEK_API_KEY")?.trim() ?: ""
        if (key.isNotEmpty()) return key

        try {
            val hermesPaths = listOf(
                File("D:/hermes/.env"),
                File(System.getProperty("user.home"), ".hermes/.env")
            )
            for (hermesEnv in hermesPaths) {
                if (!hermesEnv.exists()) continue
                val lines = hermesEnv.readLines()
                for (line in lines) {
                    if (line.trim().startsWith("DEEPSEEK_API_KEY=")) {
                        val parsed = line.substringAfter("DEEPSEEK_API_KEY=").trim()
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read DeepSeek API key", e)
        }
        return ""
    }

    private fun saveRecordToDatabase(original: String, translated: String, source: String, target: String) {
        val database = com.example.data.database.AppDatabase.getDatabase(applicationContext)
        val repository = com.example.data.repository.TranslationRepository(database.translationDao())
        val mode = _activeMode.value
        
        serviceScope.launch(Dispatchers.IO) {
            repository.insertRecord(
                originalText = original,
                translatedText = translated,
                sourceLang = source,
                targetLang = target,
                sessionId = if (mode == OverlayMode.EN_TO_VI) "EN_TO_VI_WIDGET" else "VI_TO_EN_WIDGET"
            )
        }
    }
}
