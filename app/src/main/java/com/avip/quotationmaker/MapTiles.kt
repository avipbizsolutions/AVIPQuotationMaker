package com.avip.quotationmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Builds a small map thumbnail from free OpenStreetMap tiles (no API key).
 * Follows the OSM tile policy: identifies the app via User-Agent, caches tiles,
 * and shows "© OpenStreetMap" attribution on every map.
 */
object MapTiles {
    private const val TILE = 256
    private const val USER_AGENT = "AVIPSuryaSales/1.0 (AVIP Solar site survey app)"
    private val cache = LruCache<String, Bitmap>(32)

    fun render(lat: Double, lon: Double, zoom: Int, sizePx: Int): Bitmap? {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val px = (lon + 180.0) / 360.0 * n * TILE
        val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE
        val left = px - sizePx / 2.0
        val top = py - sizePx / 2.0

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(230, 230, 230))

        val tx0 = floor(left / TILE).toInt()
        val tx1 = floor((left + sizePx - 1) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt()
        val ty1 = floor((top + sizePx - 1) / TILE).toInt()

        var anyTile = false
        for (ty in ty0..ty1) {
            if (ty !in 0 until n) continue
            for (tx in tx0..tx1) {
                val wrappedX = ((tx % n) + n) % n
                val tile = fetch(zoom, wrappedX, ty) ?: continue
                anyTile = true
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            }
        }
        if (!anyTile) {
            out.recycle()
            return null // offline: stamp is drawn without a map
        }

        drawPin(canvas, sizePx / 2f, sizePx / 2f, sizePx.toFloat())
        drawAttribution(canvas, sizePx.toFloat())
        return out
    }

    /**
     * A day route drawn over OSM tiles: fits all points, draws the path and the start/end markers.
     * Returns null when offline (no tiles).
     */
    fun renderRoute(points: List<DoubleArray>, widthPx: Int, heightPx: Int): Bitmap? {
        if (points.isEmpty()) return null
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        points.forEach { p ->
            minLat = minOf(minLat, p[0]); maxLat = maxOf(maxLat, p[0])
            minLng = minOf(minLng, p[1]); maxLng = maxOf(maxLng, p[1])
        }
        val pad = 0.15
        val dLat = (maxLat - minLat).coerceAtLeast(0.002); val dLng = (maxLng - minLng).coerceAtLeast(0.002)
        minLat -= dLat * pad; maxLat += dLat * pad; minLng -= dLng * pad; maxLng += dLng * pad
        var zoom = 18
        while (zoom > 2) {
            val n = 1 shl zoom
            val spanX = (worldX(maxLng, n) - worldX(minLng, n))
            val spanY = (worldY(minLat, n) - worldY(maxLat, n))
            if (spanX <= widthPx && spanY <= heightPx) break
            zoom--
        }
        val n = 1 shl zoom
        val cx = worldX((minLng + maxLng) / 2, n)
        val cy = (worldY(minLat, n) + worldY(maxLat, n)) / 2
        val left = cx - widthPx / 2.0
        val top = cy - heightPx / 2.0
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(233, 235, 237))
        val tx0 = floor(left / TILE).toInt(); val tx1 = floor((left + widthPx - 1) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt(); val ty1 = floor((top + heightPx - 1) / TILE).toInt()
        var anyTile = false
        for (ty in ty0..ty1) {
            if (ty !in 0 until n) continue
            for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                val tile = fetch(zoom, wx, ty) ?: continue
                anyTile = true
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            }
        }
        if (!anyTile) { out.recycle(); return null }
        val path = android.graphics.Path()
        points.forEachIndexed { i, p ->
            val x = (worldX(p[1], n) - left).toFloat(); val y = (worldY(p[0], n) - top).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.argb(140, 255, 255, 255); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.rgb(14, 107, 58); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(path, halo); canvas.drawPath(path, line)
        fun dot(p: DoubleArray, fill: Int) {
            val x = (worldX(p[1], n) - left).toFloat(); val y = (worldY(p[0], n) - top).toFloat()
            canvas.drawCircle(x, y, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            canvas.drawCircle(x, y, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        }
        dot(points.first(), Color.rgb(21, 101, 192)) // start: blue
        dot(points.last(), Color.rgb(179, 38, 30))   // end: red
        drawAttribution(canvas, widthPx.toFloat())
        return out
    }

    private fun worldX(lon: Double, n: Int) = (lon + 180.0) / 360.0 * n * TILE
    private fun worldY(lat: Double, n: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n * TILE
    }

    private fun fetch(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        return try {
            val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(key, it) }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawPin(c: Canvas, cx: Float, tipY: Float, size: Float) {
        val r = size * 0.05f
        val headY = tipY - r * 1.8f
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 38, 38) }
        val path = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - r * 0.85f, headY + r * 0.5f)
            lineTo(cx + r * 0.85f, headY + r * 0.5f)
            close()
        }
        c.drawPath(path, red)
        c.drawCircle(cx, headY, r, red)
        c.drawCircle(cx, headY, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun drawAttribution(c: Canvas, size: Float) {
        val text = "© OpenStreetMap"
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = size * 0.05f
        }
        val w = p.measureText(text)
        val pad = size * 0.012f
        val bg = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        c.drawRect(size - w - pad * 2, size - p.textSize - pad * 2, size, size, bg)
        c.drawText(text, size - w - pad, size - pad - p.descent(), p)
    }
}
