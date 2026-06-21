package com.example.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Path
import android.os.Build
import android.widget.RemoteViews
import com.example.R
import com.example.overlay.OverlayCaptionService
import java.util.Locale
import kotlin.math.sin
import kotlin.math.PI

class InterpreterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_BUTTON_CLICK -> {
                val buttonType = intent.getStringExtra(EXTRA_BUTTON_TYPE) ?: return
                when (buttonType) {
                    "EN_TO_VI" -> {
                        val currentMode = OverlayCaptionService.activeMode.value
                        if (currentMode == OverlayCaptionService.OverlayMode.EN_TO_VI) {
                            OverlayCaptionService.stop(context)
                        } else {
                            if (currentMode != OverlayCaptionService.OverlayMode.NONE) {
                                OverlayCaptionService.stop(context)
                            }
                            val serviceIntent = Intent(context, OverlayCaptionService::class.java).apply {
                                action = OverlayCaptionService.ACTION_START_EN_TO_VI
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        }
                    }
                    "VI_TO_EN" -> {
                        val currentMode = OverlayCaptionService.activeMode.value
                        if (currentMode == OverlayCaptionService.OverlayMode.VI_TO_EN) {
                            OverlayCaptionService.stop(context)
                        } else {
                            if (currentMode != OverlayCaptionService.OverlayMode.NONE) {
                                OverlayCaptionService.stop(context)
                            }
                            val serviceIntent = Intent(context, OverlayCaptionService::class.java).apply {
                                action = OverlayCaptionService.ACTION_START_VI_TO_EN
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        }
                    }
                    "MEETING" -> {
                        if (MeetingRecordService.isRecording.value) {
                            MeetingRecordService.stopService(context)
                        } else {
                            MeetingRecordService.startService(context)
                        }
                    }
                }
                updateWidgetState(context)
            }
            ACTION_WIDGET_ACTIVE_MIC -> {
                val serviceIntent = Intent(context, OverlayCaptionService::class.java).apply {
                    action = OverlayCaptionService.ACTION_VI_TO_EN_MIC
                }
                context.startService(serviceIntent)
            }
            ACTION_WIDGET_ACTIVE_REPLAY -> {
                val serviceIntent = Intent(context, OverlayCaptionService::class.java).apply {
                    action = OverlayCaptionService.ACTION_VI_TO_EN_REPLAY
                }
                context.startService(serviceIntent)
            }
            ACTION_WIDGET_ACTIVE_CLOSE -> {
                OverlayCaptionService.stop(context)
                updateWidgetState(context)
            }
        }
    }

    companion object {
        const val ACTION_BUTTON_CLICK = "com.example.ui.WIDGET_BUTTON_CLICK"
        const val EXTRA_BUTTON_TYPE = "BUTTON_TYPE"

        const val ACTION_WIDGET_ACTIVE_MIC = "com.example.ui.WIDGET_ACTIVE_MIC"
        const val ACTION_WIDGET_ACTIVE_REPLAY = "com.example.ui.WIDGET_ACTIVE_REPLAY"
        const val ACTION_WIDGET_ACTIVE_CLOSE = "com.example.ui.WIDGET_ACTIVE_CLOSE"

        private var wavePhase = 0f

        fun updateWidgetState(context: Context) {
            val intent = Intent(context, InterpreterWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, InterpreterWidgetProvider::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }

        private fun drawVoiceWave(widthPx: Int, heightPx: Int, level: Float, phase: Float, active: Boolean): Bitmap {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val w = widthPx.toFloat()
            val h = heightPx.toFloat()
            val centerY = h / 2f

            val accentStart = 0xFF66E3FF.toInt()
            val accentMid = 0xFFB78CFF.toInt()
            val accentEnd = 0xFFFFA06A.toInt()

            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val endBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            val shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    0xFF66E3FF.toInt(),
                    accentStart,
                    accentMid,
                    0xFFFF77CC.toInt(),
                    accentEnd
                ),
                floatArrayOf(0f, 0.2f, 0.5f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
            wavePaint.shader = shader
            softPaint.shader = shader

            val baseAmplitude = (h * (0.12f + level * 0.22f)).coerceAtLeast(h * 0.09f)
            val points = 80
            val path = Path()

            // Wave 1: Background Glow Wave (Thick, translucent)
            val amp1 = baseAmplitude * 0.85f
            val freq1 = 2.85f * PI.toFloat()
            val phase1 = phase - 0.7f
            path.reset()
            for (i in 0..points) {
                val p = i / points.toFloat()
                val x = p * w
                val wave = sin((p * freq1 + phase1).toDouble()).toFloat()
                val ripple = sin((p * freq1 * 2f + phase1 * 1.3f).toDouble()).toFloat()
                val y = centerY + (wave * amp1) + (ripple * amp1 * 0.05f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            softPaint.strokeWidth = h * 0.15f
            softPaint.alpha = 55
            canvas.drawPath(path, softPaint)

            // Wave 2: Secondary Wave (Medium thickness, semi-transparent)
            val amp2 = baseAmplitude * 0.72f
            val freq2 = 3.8f * PI.toFloat()
            val phase2 = phase + 1.1f
            path.reset()
            for (i in 0..points) {
                val p = i / points.toFloat()
                val x = p * w
                val wave = sin((p * freq2 + phase2).toDouble()).toFloat()
                val ripple = sin((p * freq2 * 1.8f + phase2 * 0.9f).toDouble()).toFloat()
                val y = centerY + (wave * amp2) + (ripple * amp2 * 0.06f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            softPaint.strokeWidth = h * 0.07f
            softPaint.alpha = 135
            canvas.drawPath(path, softPaint)

            // Wave 3: Main Wave (Thin, sharp, fully opaque)
            val amp3 = baseAmplitude
            val freq3 = 3.3f * PI.toFloat()
            val phase3 = phase
            path.reset()
            for (i in 0..points) {
                val p = i / points.toFloat()
                val x = p * w
                val wave = sin((p * freq3 + phase3).toDouble()).toFloat()
                val ripple = sin((p * freq3 * 2.2f + phase3 * 1.1f).toDouble()).toFloat()
                val y = centerY + (wave * amp3) + (ripple * amp3 * 0.07f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            wavePaint.strokeWidth = h * 0.048f
            wavePaint.alpha = 255
            canvas.drawPath(path, wavePaint)

            // Draw end bars
            val barWidth = w * 0.013f
            val gap = w * 0.012f
            val base = h * 0.12f
            val heights = listOf(base * 0.5f, base * 0.72f, base * 1.05f, base * 0.9f, base * 0.62f)
            val colors = listOf(accentStart, accentMid, accentEnd, accentMid, accentStart)
            val leftStart = w * 0.07f
            val rightEnd = w * 0.93f

            for (i in heights.indices) {
                endBarPaint.color = colors[i]
                endBarPaint.alpha = if (active) 210 else 130
                val xLeft = leftStart + i * (barWidth + gap)
                val xRight = rightEnd - i * (barWidth + gap)
                canvas.drawRoundRect(
                    xLeft, centerY - heights[i] / 2f, xLeft + barWidth, centerY + heights[i] / 2f,
                    barWidth / 2f, barWidth / 2f, endBarPaint
                )
                canvas.drawRoundRect(
                    xRight, centerY - heights[i] / 2f, xRight + barWidth, centerY + heights[i] / 2f,
                    barWidth / 2f, barWidth / 2f, endBarPaint
                )
            }

            return bitmap
        }

        private fun createActiveMicPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, InterpreterWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_ACTIVE_MIC
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, 10, intent, flags)
        }

        private fun createActiveReplayPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, InterpreterWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_ACTIVE_REPLAY
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, 11, intent, flags)
        }

        private fun createActiveClosePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, InterpreterWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_ACTIVE_CLOSE
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, 12, intent, flags)
        }

        private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.interpreter_widget)

            // 1. Get States
            val overlayMode = OverlayCaptionService.activeMode.value
            val isOverlayPaused = OverlayCaptionService.isPausedFlow.value
            val isMeetingRecording = MeetingRecordService.isRecording.value
            val isMeetingPaused = MeetingRecordService.isPaused.value
            val meetingDuration = MeetingRecordService.durationSec.value

            val micState = OverlayCaptionService.micStateFlow.value
            val originalText = OverlayCaptionService.currentOriginalFlow.value
            val translatedText = OverlayCaptionService.currentTranslatedFlow.value

            if (overlayMode == OverlayCaptionService.OverlayMode.VI_TO_EN) {
                // Show active layout, hide default menu
                views.setViewVisibility(R.id.layout_default_menu, android.view.View.GONE)
                views.setViewVisibility(R.id.layout_vi_to_en_active, android.view.View.VISIBLE)

                // Update text and icons based on micState
                when (micState) {
                    OverlayCaptionService.MicState.RECORDING -> {
                        views.setViewVisibility(R.id.widget_active_wave, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.widget_active_text_container, android.view.View.GONE)

                        views.setTextViewText(R.id.widget_active_title, "Trả lời Việt")
                        views.setTextViewText(R.id.widget_active_status, "Đang nghe...")

                        // Increment phase and draw voice wave
                        wavePhase = (wavePhase + 0.35f) % (2f * Math.PI.toFloat())
                        val density = context.resources.displayMetrics.density
                        val widthPx = (280 * density).toInt().coerceAtLeast(100)
                        val heightPx = (40 * density).toInt().coerceAtLeast(20)
                        val waveBitmap = drawVoiceWave(widthPx, heightPx, OverlayCaptionService.micLevelFlow.value, wavePhase, true)
                        views.setImageViewBitmap(R.id.widget_active_wave, waveBitmap)

                        views.setImageViewResource(R.id.btn_widget_active_mic, R.drawable.ic_stop)
                        views.setViewVisibility(R.id.btn_widget_active_replay, android.view.View.GONE)
                    }
                    OverlayCaptionService.MicState.PROCESSING -> {
                        views.setViewVisibility(R.id.widget_active_wave, android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_active_text_container, android.view.View.VISIBLE)

                        views.setTextViewText(R.id.widget_active_title, "Trả lời Việt")
                        views.setTextViewText(R.id.widget_active_status, "Đang xử lý")

                        views.setTextViewText(R.id.widget_active_text_vi, originalText.ifBlank { "Đang dịch..." })
                        views.setTextViewText(R.id.widget_active_text_en, "Đang dịch...")

                        views.setImageViewResource(R.id.btn_widget_active_mic, R.drawable.ic_mic)
                        views.setViewVisibility(R.id.btn_widget_active_replay, android.view.View.GONE)
                    }
                    OverlayCaptionService.MicState.TRANSLATED, OverlayCaptionService.MicState.PLAYING -> {
                        views.setViewVisibility(R.id.widget_active_wave, android.view.View.GONE)
                        views.setViewVisibility(R.id.widget_active_text_container, android.view.View.VISIBLE)

                        views.setTextViewText(R.id.widget_active_title, "Trả lời Việt")
                        views.setTextViewText(
                            R.id.widget_active_status,
                            if (micState == OverlayCaptionService.MicState.PLAYING) "Đang phát..." else "Đã dịch xong"
                        )

                        views.setTextViewText(R.id.widget_active_text_vi, originalText.ifBlank { "..." })
                        views.setTextViewText(R.id.widget_active_text_en, translatedText.ifBlank { "..." })

                        views.setImageViewResource(R.id.btn_widget_active_mic, R.drawable.ic_mic)
                        views.setViewVisibility(R.id.btn_widget_active_replay, android.view.View.VISIBLE)
                    }
                    else -> { // IDLE
                        views.setViewVisibility(R.id.widget_active_wave, android.view.View.VISIBLE)
                        views.setViewVisibility(R.id.widget_active_text_container, android.view.View.GONE)

                        views.setTextViewText(R.id.widget_active_title, "Trả lời Việt")
                        views.setTextViewText(R.id.widget_active_status, "Chạm nút để nói")

                        // Draw flat idle voice wave
                        val density = context.resources.displayMetrics.density
                        val widthPx = (280 * density).toInt().coerceAtLeast(100)
                        val heightPx = (40 * density).toInt().coerceAtLeast(20)
                        val waveBitmap = drawVoiceWave(widthPx, heightPx, 0.05f, 0f, false)
                        views.setImageViewBitmap(R.id.widget_active_wave, waveBitmap)

                        views.setImageViewResource(R.id.btn_widget_active_mic, R.drawable.ic_mic)
                        views.setViewVisibility(R.id.btn_widget_active_replay, android.view.View.GONE)
                    }
                }

                // Bind Active PendingIntents
                views.setOnClickPendingIntent(R.id.btn_widget_active_mic, createActiveMicPendingIntent(context))
                views.setOnClickPendingIntent(R.id.btn_widget_active_replay, createActiveReplayPendingIntent(context))
                views.setOnClickPendingIntent(R.id.btn_widget_active_close, createActiveClosePendingIntent(context))
            } else {
                // Show default menu, hide active layout
                views.setViewVisibility(R.id.layout_default_menu, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.layout_vi_to_en_active, android.view.View.GONE)

                // 2. Set Status Text & Active Styles
                var statusText = "Sẵn sàng"
                var isEnActive = false
                var isViActive = false
                var isMeetingActive = false

                if (overlayMode == OverlayCaptionService.OverlayMode.EN_TO_VI) {
                    isEnActive = true
                    statusText = if (isOverlayPaused) "Phụ đề: Đang tạm dừng" else "Phụ đề: Đang dịch"
                }

                if (isMeetingRecording) {
                    isMeetingActive = true
                    val minutes = meetingDuration / 60
                    val seconds = meetingDuration % 60
                    val durationStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                    statusText = if (isMeetingPaused) "Họp: Tạm dừng" else "Họp: Đang ghi ($durationStr)"
                }

                views.setTextViewText(R.id.widget_status, statusText)

                // Button styling
                views.setInt(
                    R.id.btn_en_to_vi,
                    "setBackgroundResource",
                    if (isEnActive) R.drawable.widget_btn_active_en else R.drawable.widget_btn_normal
                )
                views.setTextColor(
                    R.id.btn_en_to_vi,
                    if (isEnActive) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
                )
                views.setTextViewCompoundDrawablesRelative(
                    R.id.btn_en_to_vi,
                    0,
                    if (isEnActive) R.drawable.ic_widget_headphones_active else R.drawable.ic_widget_headphones,
                    0,
                    0
                )

                views.setInt(
                    R.id.btn_vi_to_en,
                    "setBackgroundResource",
                    if (isViActive) R.drawable.widget_btn_active_vi else R.drawable.widget_btn_normal
                )
                views.setTextColor(
                    R.id.btn_vi_to_en,
                    if (isViActive) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
                )
                views.setTextViewCompoundDrawablesRelative(
                    R.id.btn_vi_to_en,
                    0,
                    if (isViActive) R.drawable.ic_widget_reply_active else R.drawable.ic_widget_reply,
                    0,
                    0
                )

                views.setInt(
                    R.id.btn_meeting,
                    "setBackgroundResource",
                    if (isMeetingActive) R.drawable.widget_btn_active_meeting else R.drawable.widget_btn_normal
                )
                views.setTextColor(
                    R.id.btn_meeting,
                    if (isMeetingActive) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
                )
                views.setTextViewCompoundDrawablesRelative(
                    R.id.btn_meeting,
                    0,
                    if (isMeetingActive) R.drawable.ic_widget_meeting_active else R.drawable.ic_widget_meeting,
                    0,
                    0
                )

                // 3. Bind PendingIntents for Clicks
                views.setOnClickPendingIntent(R.id.btn_en_to_vi, createPendingIntent(context, "EN_TO_VI", 1))
                views.setOnClickPendingIntent(R.id.btn_vi_to_en, createPendingIntent(context, "VI_TO_EN", 2))
                views.setOnClickPendingIntent(R.id.btn_meeting, createPendingIntent(context, "MEETING", 3))
            }

            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createPendingIntent(context: Context, buttonType: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, InterpreterWidgetProvider::class.java).apply {
                action = ACTION_BUTTON_CLICK
                putExtra(EXTRA_BUTTON_TYPE, buttonType)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}
