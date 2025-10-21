package com.brycewg.asrkb.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.LinearInterpolator

class ProcessingSpinnerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    color = Color.BLACK
    strokeWidth = 6f
  }

  private var sweepAngle: Float = 90f
  private var rotationDeg: Float = 0f
  private var animator: ValueAnimator? = null

  fun setSpinnerColor(color: Int) {
    paint.color = color
    invalidate()
  }

  fun setStrokeWidth(widthPx: Float) {
    paint.strokeWidth = widthPx
    invalidate()
  }

  fun setSweepAngle(deg: Float) {
    sweepAngle = deg
    invalidate()
  }

  fun start() {
    if (animator?.isRunning == true) return
    animator = ValueAnimator.ofFloat(0f, 360f).apply {
      duration = 1000
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener { a ->
        rotationDeg = a.animatedValue as Float
        invalidate()
      }
      start()
    }
  }

  fun stop() {
    animator?.cancel()
    animator = null
    rotationDeg = 0f
    invalidate()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stop()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    val cx = w / 2f
    val cy = h / 2f
    // 更贴近边缘：减少内缩量
    val radius = (minOf(w, h) / 2f) - paint.strokeWidth * 0.5f
    val left = cx - radius
    val top = cy - radius
    val right = cx + radius
    val bottom = cy + radius
    canvas.save()
    canvas.rotate(rotationDeg, cx, cy)
    // 以 -90 度为起点（顶部），绘制一段弧形
    canvas.drawArc(left, top, right, bottom, -90f, sweepAngle, false, paint)
    canvas.restore()
  }
}
