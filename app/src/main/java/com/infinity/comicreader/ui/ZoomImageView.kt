package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pinch-to-zoom, double-tap-to-zoom, pan-when-zoomed image view.
 *
 * When the image is at its resting fit scale, horizontal (or vertical) drags are handed
 * over to the parent [androidx.viewpager2.widget.ViewPager2] or [androidx.recyclerview.widget.RecyclerView]
 * so normal page turns work seamlessly.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    enum class FitMode { WIDTH, HEIGHT, SCREEN, STRETCH }

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var fitMode: FitMode = FitMode.SCREEN
    private var minScale = 1f
    private var maxScale = 5f
    private var currentScale = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    /** Single-tap listener: receives the tap's horizontal position as a 0..1 fraction. */
    var onTap: ((Float) -> Unit)? = null

    /** Vertical drag on the left third of the view (used for the brightness gesture). */
    var onLeftVerticalDrag: ((Float) -> Unit)? = null

    var doubleTapEnabled = true

    /** True when the image is currently zoomed in beyond its base fit scale. */
    val isZoomed: Boolean
        get() = currentScale > minScale * 1.05f

    private var tapDownX = 0f
    private var tapDownY = 0f
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val target = (currentScale * factor).coerceIn(minScale, maxScale)
                val real = target / currentScale
                matrix.postScale(real, real, detector.focusX, detector.focusY)
                currentScale = target
                constrainMatrix()
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // With double-tap zoom off there is no second tap to wait for: act at once.
                if (!doubleTapEnabled) {
                    dispatchTap(e)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (doubleTapEnabled) dispatchTap(e)
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                // When double-tap zoom is off, each tap in a rapid pair is just a tap.
                if (!doubleTapEnabled) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            tapDownX = e.x
                            tapDownY = e.y
                        }
                        MotionEvent.ACTION_UP ->
                            if (abs(e.x - tapDownX) <= tapSlop && abs(e.y - tapDownY) <= tapSlop) dispatchTap(e)
                    }
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!doubleTapEnabled) return false
                if (currentScale > minScale * 1.1f) {
                    fit(fitMode)
                } else {
                    zoomTo(minScale * 2.75f, e.x, e.y)
                }
                return true
            }
        }
    )

    private fun dispatchTap(e: MotionEvent) {
        if (width > 0) onTap?.invoke((e.x / width).coerceIn(0f, 1f))
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setFitMode(mode: FitMode) {
        fitMode = mode
        fit(mode)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable == null) return
        if (width > 0 && height > 0) {
            fit(fitMode)
        } else {
            post { fit(fitMode) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) fit(fitMode)
    }

    fun fit(mode: FitMode = fitMode) {
        val d: Drawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val drawableW = d.intrinsicWidth.toFloat()
        val drawableH = d.intrinsicHeight.toFloat()
        if (viewW <= 0 || viewH <= 0 || drawableW <= 0 || drawableH <= 0) return

        matrix.reset()
        if (mode == FitMode.STRETCH) {
            matrix.postScale(viewW / drawableW, viewH / drawableH)
            currentScale = 1f
            minScale = 1f
            maxScale = 8f
            imageMatrix = matrix
            return
        }

        val scaleX = viewW / drawableW
        val scaleY = viewH / drawableH
        val scale = when (mode) {
            FitMode.WIDTH -> scaleX
            FitMode.HEIGHT -> scaleY
            FitMode.SCREEN -> min(scaleX, scaleY)
            FitMode.STRETCH -> scaleX
        }
        minScale = scale
        maxScale = max(scale * 5f, 5f)
        currentScale = scale

        val scaledW = drawableW * scale
        val scaledH = drawableH * scale
        val startX = if (scaledW > viewW) 0f else (viewW - scaledW) / 2f
        val startY = if (scaledH > viewH) 0f else (viewH - scaledH) / 2f

        matrix.postScale(scale, scale)
        matrix.postTranslate(startX, startY)
        imageMatrix = matrix
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val clamped = target.coerceIn(minScale, maxScale)
        val factor = clamped / currentScale
        matrix.postScale(factor, factor, focusX, focusY)
        currentScale = clamped
        constrainMatrix()
        imageMatrix = matrix
    }

    private fun constrainMatrix() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val trans = currentTranslation()
        val contentW = d.intrinsicWidth * currentScaleX()
        val contentH = d.intrinsicHeight * currentScaleY()

        val dx = clampAxis(trans.x, viewW, contentW)
        val dy = clampAxis(trans.y, viewH, contentH)
        if (dx != 0f || dy != 0f) matrix.postTranslate(dx, dy)
    }

    private fun clampAxis(trans: Float, viewSize: Float, contentSize: Float): Float {
        if (contentSize <= viewSize) {
            return (viewSize - contentSize) / 2f - trans
        }
        if (trans > 0f) return -trans
        if (trans + contentSize < viewSize) return viewSize - (trans + contentSize)
        return 0f
    }

    private fun currentTranslation(): PointF {
        matrix.getValues(matrixValues)
        return PointF(matrixValues[Matrix.MTRANS_X], matrixValues[Matrix.MTRANS_Y])
    }

    private fun currentScaleX(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun currentScaleY(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_Y]
    }

    private fun roomX(dx: Float): Boolean {
        val d = drawable ?: return false
        val contentW = d.intrinsicWidth * currentScaleX()
        if (contentW <= width + 1f) return false
        val tx = currentTranslation().x
        return (dx > 0 && tx < -1f) || (dx < 0 && tx + contentW > width + 1f)
    }

    private fun roomY(dy: Float): Boolean {
        val d = drawable ?: return false
        val contentH = d.intrinsicHeight * currentScaleY()
        if (contentH <= height + 1f) return false
        val ty = currentTranslation().y
        return (dy > 0 && ty < -1f) || (dy < 0 && ty + contentH > height + 1f)
    }

    private fun canPan(): Boolean {
        val d = drawable ?: return false
        return d.intrinsicWidth * currentScaleX() > width + 2f ||
            d.intrinsicHeight * currentScaleY() > height + 2f
    }

    private var leftDragActive = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = false
                leftDragActive = onLeftVerticalDrag != null && !canPan() && event.x < width * 0.2f
                parent?.requestDisallowInterceptTouchEvent(canPan())
            }
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val index = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == 0 && event.pointerCount > 1) 1 else 0
                if (index < event.pointerCount) {
                    lastX = event.getX(index)
                    lastY = event.getY(index)
                }
                dragging = false
                leftDragActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                leftDragActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        if (scaleDetector.isInProgress) return
        if (event.pointerCount == 1) {
            val dx = event.x - lastX
            val dy = event.y - lastY
            if (canPan()) {
                val horizontal = abs(dx) >= abs(dy)
                val room = if (horizontal) roomX(dx) else roomY(dy)
                parent?.requestDisallowInterceptTouchEvent(room)
                matrix.postTranslate(dx, dy)
                constrainMatrix()
                imageMatrix = matrix
            } else if (leftDragActive && abs(dy) > abs(dx) * 1.5f && abs(dy) > 6f) {
                parent?.requestDisallowInterceptTouchEvent(true)
                onLeftVerticalDrag?.invoke(-dy / height.coerceAtLeast(1))
            } else if (abs(dx) > 8f || abs(dy) > 8f) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            lastX = event.x
            lastY = event.y
        }
    }
}
