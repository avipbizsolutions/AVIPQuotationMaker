package com.avip.quotationmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Draws the map + address + coordinates + time panel onto the bottom of the photo. */
object Stamp {

    fun formatLatLon(loc: Location): String =
        String.format(Locale.US, "Lat %.6f°  Long %.6f°", loc.latitude, loc.longitude)

    fun formatTime(d: Date): String =
        SimpleDateFormat("EEE, dd/MM/yyyy hh:mm a 'GMT'XXX", Locale.getDefault()).format(d)

    /** [heading] = direction the camera faced (degrees from north); adds a north arrow when set. */
    fun draw(
        photo: Bitmap,
        map: Bitmap?,
        loc: Location?,
        place: PlaceInfo,
        time: Date,
        heading: Float? = null,
        extra: List<String> = emptyList(),
        brand: String = "",
    ) {
        val c = Canvas(photo)
        val unit = min(photo.width, photo.height).toFloat()
        val margin = unit * 0.025f
        val pad = unit * 0.02f
        val mapSize = unit * 0.24f
        val corner = unit * 0.02f

        val panelLeft = margin
        val panelRight = photo.width - margin
        val textInset = if (map != null) mapSize + pad * 2 else pad
        val textWidth = (panelRight - panelLeft - textInset - pad).toInt().coerceAtLeast(50)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.038f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.027f
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }

        val title = place.title.ifBlank { if (loc == null) "Location unavailable" else brand.ifBlank { "AVIP Solar Sales" } }
        val body = buildString {
            extra.filter { it.isNotBlank() }.forEach { appendLine(it) }
            if (place.address.isNotBlank()) appendLine(place.address)
            if (loc != null) appendLine(formatLatLon(loc))
            if (heading != null) appendLine("Facing ${Compass.label(heading)}")
            append(formatTime(time))
        }

        val titleLayout = layout(title, titlePaint, textWidth, 2)
        val bodyLayout = layout(body, bodyPaint, textWidth, 8)
        val gap = unit * 0.01f
        val textHeight = titleLayout.height + gap + bodyLayout.height

        val panelHeight = max(if (map != null) mapSize else 0f, textHeight) + pad * 2
        val panelBottom = photo.height - margin
        val panelTop = panelBottom - panelHeight

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        c.drawRoundRect(RectF(panelLeft, panelTop, panelRight, panelBottom), corner, corner, bg)

        if (map != null) {
            val mapTop = panelTop + (panelHeight - mapSize) / 2f
            val dst = RectF(panelLeft + pad, mapTop, panelLeft + pad + mapSize, mapTop + mapSize)
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, corner, corner, Path.Direction.CW) })
            c.drawBitmap(map, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            map.recycle()
        }

        c.save()
        c.translate(panelLeft + textInset, panelTop + (panelHeight - textHeight) / 2f)
        titleLayout.draw(c)
        c.translate(0f, titleLayout.height + gap)
        bodyLayout.draw(c)
        c.restore()

        // Brand tag in the top-left corner.
        if (brand.isNotBlank()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = unit * 0.032f; typeface = Typeface.DEFAULT_BOLD
            }
            val w = tp.measureText(brand)
            val r = RectF(margin, margin, margin + w + pad * 2, margin + tp.textSize + pad * 1.4f)
            c.drawRoundRect(r, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 14, 107, 58) })
            c.drawText(brand, r.left + pad, r.bottom - pad * 0.7f - tp.descent() / 2, tp)
        }

        // North symbol in the top-right corner of the photo.
        if (heading != null) {
            val radius = unit * 0.07f
            Compass.drawNorthArrow(
                c,
                photo.width - margin - radius,
                margin + radius,
                radius,
                heading,
            )
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}
