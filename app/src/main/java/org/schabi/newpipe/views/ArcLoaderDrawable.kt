package org.schabi.newpipe.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Material 3 style indeterminate circular loader: a round-capped arc that spins while its length
 * grows and shrinks, over a faint full ring. Colour comes from the view's tint
 * (`android:indeterminateTint`); stroke width scales with the size of the view.
 *
 * Inflated from `<drawable class="org.schabi.newpipe.views.ArcLoaderDrawable"/>`.
 */
class ArcLoaderDrawable : Drawable(), Animatable {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    private var tint: ColorStateList? = null
    private var color = DEFAULT_COLOR
    private var cycle = 0
    private var progress = 0f

    private val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidateSelf()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: Animator) {
                cycle = (cycle + 1) % 36
            }
        })
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val size = minOf(b.width(), b.height()).toFloat()
        val stroke = size * STROKE_FRACTION
        arcPaint.strokeWidth = stroke
        trackPaint.strokeWidth = stroke
        arcPaint.color = color
        trackPaint.color = color
        trackPaint.alpha = ((color ushr 24) * TRACK_ALPHA).toInt()

        val inset = stroke / 2f
        oval.set(
            b.exactCenterX() - size / 2f + inset, b.exactCenterY() - size / 2f + inset,
            b.exactCenterX() + size / 2f - inset, b.exactCenterY() + size / 2f - inset
        )
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)

        // grow for the first half of a cycle (head runs ahead), shrink in the second (tail catches up)
        val t = progress
        val tri = if (t < 0.5f) t * 2f else (1f - t) * 2f
        val p = easing.getInterpolation(tri)
        val sweep = MIN_SWEEP + (MAX_SWEEP - MIN_SWEEP) * p
        val tailAdvance = if (t < 0.5f) 0f else (MAX_SWEEP - sweep)
        val start = -90f + t * 720f + cycle * (MAX_SWEEP - MIN_SWEEP) + tailAdvance
        canvas.drawArc(oval, start, sweep, false, arcPaint)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateColor()
        invalidateSelf()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(stateSet: IntArray): Boolean {
        val changed = updateColor()
        if (changed) invalidateSelf()
        return changed
    }

    private fun updateColor(): Boolean {
        val new = tint?.getColorForState(state, tint!!.defaultColor) ?: DEFAULT_COLOR
        val changed = new != color
        color = new
        return changed
    }

    override fun setAlpha(alpha: Int) {
        arcPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        arcPaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        animator.cancel()
    }

    override fun isRunning() = animator.isRunning

    private companion object {
        const val CYCLE_MS = 1800L
        const val MIN_SWEEP = 20f
        const val MAX_SWEEP = 270f
        const val STROKE_FRACTION = 0.10f
        const val TRACK_ALPHA = 0.14f
        const val DEFAULT_COLOR = 0xFF888888.toInt()
    }
}
