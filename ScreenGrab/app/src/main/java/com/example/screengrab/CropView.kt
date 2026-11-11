package com.example.screengrab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Image with a draggable crop rectangle: drag edges/corners to resize, drag inside to move. */
class CropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var bitmap: Bitmap? = null
    private val imgRect = RectF()   // where the bitmap is drawn (view coordinates)
    private val crop = RectF()      // crop rectangle (view coordinates)
    private val density = resources.displayMetrics.density
    private val touchPx = 32 * density
    private val minPx = 56 * density

    private val dim = Paint().apply { color = 0xAA000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var flags = 0; private var lastX = 0f; private var lastY = 0f
    private companion object { const val L = 1; const val R = 2; const val T = 4; const val B = 8; const val MOVE = 16 }

    fun setBitmap(b: Bitmap) { bitmap = b; layoutImage(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutImage() }

    private fun layoutImage() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * s; val h = b.height * s
        imgRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        crop.set(imgRect); crop.inset(w * 0.1f, h * 0.1f)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        c.drawBitmap(b, null, imgRect, null)
        c.save(); c.clipOutRect(crop); c.drawRect(imgRect, dim); c.restore()
        c.drawRect(crop, border)
        val r = 7 * density
        for (x in listOf(crop.left, crop.right)) for (y in listOf(crop.top, crop.bottom)) c.drawCircle(x, y, r, handle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                flags = 0
                val inside = e.x in crop.left - touchPx..crop.right + touchPx &&
                        e.y in crop.top - touchPx..crop.bottom + touchPx
                if (inside) {
                    if (abs(e.x - crop.left) < touchPx) flags = flags or L
                    if (abs(e.x - crop.right) < touchPx) flags = flags or R
                    if (abs(e.y - crop.top) < touchPx) flags = flags or T
                    if (abs(e.y - crop.bottom) < touchPx) flags = flags or B
                    if (flags == 0 && crop.contains(e.x, e.y)) flags = MOVE
                }
                lastX = e.x; lastY = e.y
                return flags != 0
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = e.x - lastX; var dy = e.y - lastY
                if (flags == MOVE) {
                    dx = dx.coerceIn(imgRect.left - crop.left, imgRect.right - crop.right)
                    dy = dy.coerceIn(imgRect.top - crop.top, imgRect.bottom - crop.bottom)
                    crop.offset(dx, dy)
                } else {
                    if (flags and L != 0) crop.left = (crop.left + dx).coerceIn(imgRect.left, crop.right - minPx)
                    if (flags and R != 0) crop.right = (crop.right + dx).coerceIn(crop.left + minPx, imgRect.right)
                    if (flags and T != 0) crop.top = (crop.top + dy).coerceIn(imgRect.top, crop.bottom - minPx)
                    if (flags and B != 0) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minPx, imgRect.bottom)
                }
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> flags = 0
        }
        return true
    }

    /** Returns the cropped region at the original image resolution. */
    fun getCropped(): Bitmap? {
        val b = bitmap ?: return null
        val s = b.width / imgRect.width()
        val x = ((crop.left - imgRect.left) * s).toInt().coerceIn(0, b.width - 1)
        val y = ((crop.top - imgRect.top) * s).toInt().coerceIn(0, b.height - 1)
        val w = (crop.width() * s).toInt().coerceIn(1, b.width - x)
        val h = (crop.height() * s).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }
}
