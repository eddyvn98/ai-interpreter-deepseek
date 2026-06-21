package com.example.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 90
    }

    private val endBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private var level = 0.4f
    private var active = false
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (active) {
            startAnimator()
        } else {
            stopAnimator()
        }
        invalidate()
    }

    private fun startAnimator() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, (2f * PI).toFloat()).apply {
            duration = 950L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f
        val accentStart = 0xFF66E3FF.toInt()
        val accentMid = 0xFFB78CFF.toInt()
        val accentEnd = 0xFFFFA06A.toInt()

        val shader = LinearGradient(
            0f,
            0f,
            w,
            0f,
            intArrayOf(
                0xFF66E3FF.toInt(),
                accentStart,
                accentMid,
                0xFFFF77CC.toInt(),
                accentEnd,
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        wavePaint.shader = shader
        softPaint.shader = shader

        val baseAmplitude = (h * (0.12f + level * 0.22f)).coerceAtLeast(h * 0.09f)
        val points = 160

        // Wave 1: Background Glow Wave (Thick, translucent)
        val amp1 = baseAmplitude * 0.85f
        val freq1 = 2.85f * PI
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
        val freq2 = 3.8f * PI
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
        val freq3 = 3.3f * PI
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
        canvas.drawPath(path, wavePaint)

        drawEndBars(canvas, w, h, accentStart, accentMid, accentEnd)
    }

    private fun drawEndBars(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int, c3: Int) {
        val barWidth = w * 0.013f
        val gap = w * 0.012f
        val base = h * 0.12f
        val heights = listOf(base * 0.5f, base * 0.72f, base * 1.05f, base * 0.9f, base * 0.62f)
        val colors = listOf(c1, c2, c3, c2, c1)
        val leftStart = w * 0.07f
        val rightEnd = w * 0.93f
        val center = h / 2f

        for (i in heights.indices) {
            endBarPaint.color = colors[i]
            endBarPaint.alpha = if (active) 210 else 130
            val xLeft = leftStart + i * (barWidth + gap)
            val xRight = rightEnd - i * (barWidth + gap)
            canvas.drawRoundRect(
                xLeft,
                center - heights[i] / 2f,
                xLeft + barWidth,
                center + heights[i] / 2f,
                barWidth / 2f,
                barWidth / 2f,
                endBarPaint
            )
            canvas.drawRoundRect(
                xRight,
                center - heights[i] / 2f,
                xRight + barWidth,
                center + heights[i] / 2f,
                barWidth / 2f,
                barWidth / 2f,
                endBarPaint
            )
        }
    }
}
