package com.infinity.comicreader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Displays a bitmap fitted to the view and lets the user drag a crop rectangle
 * over it. Corners resize; the inside of the rectangle moves it.
 *
 * Normalized crop coordinates `[0..1]` are preserved across layout/size changes
 * so toggling system bars or resizing the window does not wipe the selection.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val displayRect = RectF()
    private val cropRect = RectF()
    private val normCrop = RectF(0.1f, 0.1f, 0.9f, 0.9f)
    private var hasSelection = false

    private var activeHandle = HANDLE_NONE
    private var lastX = 0f
    private var lastY = 0f

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        hasSelection = false
        normCrop.set(0.1f, 0.1f, 0.9f, 0.9f)
        if (width > 0 && height > 0) {
            computeMatrix()
            applyNormalizedCrop()
        } else {
            requestLayout()
        }
    }

    fun resetSelection() {
        normCrop.set(0.1f, 0.1f, 0.9f, 0.9f)
        hasSelection = true
        applyNormalizedCrop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeMatrix()
        applyNormalizedCrop()
    }

    private fun computeMatrix() {
        val b = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val scale = minOf(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f
        displayRect.set(dx, dy, dx + dw, dy + dh)
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
    }

    private fun applyNormalizedCrop() {
        if (displayRect.isEmpty) return
        if (!hasSelection) {
            normCrop.set(0.1f, 0.1f, 0.9f, 0.9f)
            hasSelection = true
        }
        val dw = displayRect.width()
        val dh = displayRect.height()
        cropRect.set(
            displayRect.left + normCrop.left * dw,
            displayRect.top + normCrop.top * dh,
            displayRect.left + normCrop.right * dw,
            displayRect.top + normCrop.bottom * dh
        )
        invalidate()
    }

    private fun saveNormalizedCrop() {
        val dw = displayRect.width()
        val dh = displayRect.height()
        if (dw <= 0f || dh <= 0f) return
        normCrop.set(
            ((cropRect.left - displayRect.left) / dw).coerceIn(0f, 1f),
            ((cropRect.top - displayRect.top) / dh).coerceIn(0f, 1f),
            ((cropRect.right - displayRect.left) / dw).coerceIn(0f, 1f),
            ((cropRect.bottom - displayRect.top) / dh).coerceIn(0f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        canvas.drawBitmap(b, imageMatrix, bitmapPaint)

        // Dim the area outside the crop rectangle.
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        canvas.drawRect(cropRect, borderPaint)

        val r = 10f * density
        canvas.drawCircle(cropRect.left, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, r, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, r, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, r, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || displayRect.isEmpty) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitTest(x, y)
                lastX = x
                lastY = y
                return activeHandle != HANDLE_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                when (activeHandle) {
                    HANDLE_MOVE -> moveCrop(dx, dy)
                    HANDLE_TL -> {
                        cropRect.left += dx; cropRect.top += dy
                    }
                    HANDLE_TR -> {
                        cropRect.right += dx; cropRect.top += dy
                    }
                    HANDLE_BL -> {
                        cropRect.left += dx; cropRect.bottom += dy
                    }
                    HANDLE_BR -> {
                        cropRect.right += dx; cropRect.bottom += dy
                    }
                }
                normalize()
                saveNormalizedCrop()
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = HANDLE_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Int {
        val r = 32f * density
        if (near(x, y, cropRect.left, cropRect.top, r)) return HANDLE_TL
        if (near(x, y, cropRect.right, cropRect.top, r)) return HANDLE_TR
        if (near(x, y, cropRect.left, cropRect.bottom, r)) return HANDLE_BL
        if (near(x, y, cropRect.right, cropRect.bottom, r)) return HANDLE_BR
        if (cropRect.contains(x, y)) return HANDLE_MOVE
        return HANDLE_NONE
    }

    private fun near(x: Float, y: Float, px: Float, py: Float, r: Float): Boolean {
        val dx = x - px
        val dy = y - py
        return dx * dx + dy * dy <= r * r
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(displayRect.left - cropRect.left, displayRect.right - cropRect.right)
        val clampedDy = dy.coerceIn(displayRect.top - cropRect.top, displayRect.bottom - cropRect.bottom)
        cropRect.offset(clampedDx, clampedDy)
    }

    private fun normalize() {
        val minW = minOf(48f * density, displayRect.width().coerceAtLeast(1f))
        val minH = minOf(48f * density, displayRect.height().coerceAtLeast(1f))

        cropRect.left = cropRect.left.coerceIn(displayRect.left, displayRect.right)
        cropRect.right = cropRect.right.coerceIn(displayRect.left, displayRect.right)
        cropRect.top = cropRect.top.coerceIn(displayRect.top, displayRect.bottom)
        cropRect.bottom = cropRect.bottom.coerceIn(displayRect.top, displayRect.bottom)

        when (activeHandle) {
            HANDLE_TL, HANDLE_BL -> {
                if (cropRect.left > cropRect.right - minW) {
                    cropRect.left = (cropRect.right - minW).coerceAtLeast(displayRect.left)
                }
            }
            HANDLE_TR, HANDLE_BR -> {
                if (cropRect.right < cropRect.left + minW) {
                    cropRect.right = (cropRect.left + minW).coerceAtMost(displayRect.right)
                }
            }
        }
        when (activeHandle) {
            HANDLE_TL, HANDLE_TR -> {
                if (cropRect.top > cropRect.bottom - minH) {
                    cropRect.top = (cropRect.bottom - minH).coerceAtLeast(displayRect.top)
                }
            }
            HANDLE_BL, HANDLE_BR -> {
                if (cropRect.bottom < cropRect.top + minH) {
                    cropRect.bottom = (cropRect.top + minH).coerceAtMost(displayRect.bottom)
                }
            }
        }
    }

    /** The current crop rectangle expressed in source-bitmap pixel coordinates. */
    fun bitmapCropRect(): Rect {
        val b = bitmap ?: return Rect()
        val inverse = Matrix()
        imageMatrix.invert(inverse)
        val points = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        inverse.mapPoints(points)
        val left = points[0].toInt().coerceIn(0, b.width - 1)
        val top = points[1].toInt().coerceIn(0, b.height - 1)
        var right = points[2].toInt().coerceIn(left + 1, b.width)
        var bottom = points[3].toInt().coerceIn(top + 1, b.height)
        if (right <= left) right = (left + 1).coerceAtMost(b.width)
        if (bottom <= top) bottom = (top + 1).coerceAtMost(b.height)
        return Rect(left, top, right, bottom)
    }

    companion object {
        private const val HANDLE_NONE = 0
        private const val HANDLE_MOVE = 1
        private const val HANDLE_TL = 2
        private const val HANDLE_TR = 3
        private const val HANDLE_BL = 4
        private const val HANDLE_BR = 5
    }
}
