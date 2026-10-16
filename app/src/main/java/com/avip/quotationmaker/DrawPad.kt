package com.avip.quotationmaker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.io.File

/**
 * Finger drawing area. Used for the customer's signature and for the rough site
 * sketch (with N / E / S / W marks, like the paper form).
 */
@SuppressLint("ViewConstructor")
class DrawPad(context: Context, private val compass: Boolean) : View(context) {

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (compass) Ui.BLUE else 0xFF0B2A6B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(if (compass) 3f else 2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB9C4BD.toInt(); textSize = context.dpf(15f); isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDCE3DE.toInt(); strokeWidth = context.dpf(1f); style = Paint.Style.STROKE
    }
    private val paths = mutableListOf<Path>()
    private var current: Path? = null
    private var base: Bitmap? = null
    var onDrawn: (() -> Unit)? = null

    val isEmpty get() = paths.isEmpty() && base == null

    fun clear() { paths.clear(); base = null; invalidate(); onDrawn?.invoke() }

    fun undo() { if (paths.isNotEmpty()) { paths.removeAt(paths.size - 1); invalidate(); onDrawn?.invoke() } }

    /** Shows an earlier saved drawing so it can be added to. */
    fun loadFrom(file: File) {
        if (file.exists()) base = BitmapFactory.decodeFile(file.absolutePath)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        base?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        if (base == null) drawGuides(canvas, guide, dash)
        paths.forEach { canvas.drawPath(it, ink) }
    }

    private fun drawGuides(c: Canvas, text: Paint, line: Paint) {
        val w = width.toFloat(); val h = height.toFloat()
        if (compass) {
            val pad = context.dpf(16f)
            c.drawText("N", w / 2, pad + text.textSize * 0.4f, text)
            c.drawText("S", w / 2, h - pad / 2, text)
            c.drawText("W", pad, h / 2 + text.textSize / 3, text)
            c.drawText("E", w - pad, h / 2 + text.textSize / 3, text)
        } else {
            val y = h * 0.75f
            c.drawLine(context.dpf(20f), y, w - context.dpf(20f), y, line)
            text.textSize = context.dpf(12f)
            c.drawText("Sign above the line", w / 2, y + context.dpf(20f), text)
            text.textSize = context.dpf(15f)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true) // don't scroll the page while drawing
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y) }
                paths += current!!
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) current?.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                current?.lineTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onDrawn?.invoke()
            }
        }
        invalidate()
        return true
    }

    /** Saves the drawing as PNG (white background, with the compass marks). */
    fun save(file: File): Boolean {
        if (isEmpty || width == 0) return false
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        base?.let { c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        if (base == null && compass) drawGuides(c, Paint(guide).apply { color = 0xFF66746B.toInt() }, dash)
        paths.forEach { c.drawPath(it, ink) }
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }
}
