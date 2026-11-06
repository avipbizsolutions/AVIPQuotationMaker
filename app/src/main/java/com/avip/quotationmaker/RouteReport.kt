package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Day route report, for the founders only (Atul and Rahul). Shows each executive's path on a map
 * for a chosen day, with distance, on-duty time and the stops where they spent time.
 * Not shown to anyone else; the backend also refuses the data to non-founders.
 */
class RouteReportActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var date = Lead.startOfToday()
    private var execMobile = ""
    private var data: JSONObject? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        if (!Officer.founder(this)) { finish(); return }
        body = leadScreen(tr("Day Route Report", "Day route report"), tr("सिर्फ Founders", "Founders only"),
            right = listOf(barButton(R.drawable.ic_refresh) { load() }))
        load()
    }

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.ENGLISH)

    private fun load() {
        if (loading) return
        loading = true
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Remote.call(this@RouteReportActivity, "routeReport", JSONObject().put("date", dayFmt.format(Date(date))).put("execMobile", execMobile)) }
            }
            loading = false
            if (isFinishing) return@launch
            r.onSuccess { data = it }
            render()
            r.exceptionOrNull()?.let {
                android.widget.Toast.makeText(this@RouteReportActivity, (it as? RemoteException)?.message ?: tr("नहीं खुला", "Could not load"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render() {
        body.removeAllViews()
        // day picker
        body.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(horizontal {
                for (i in 0 until 14) {
                    val d = Lead.startOfToday() - i * 86_400_000L
                    val on = d == date
                    addView(vertical {
                        gravity = Gravity.CENTER
                        background = if (on) rounded(Ui.BRAND, dpf(12f)) else rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        addView(text(if (i == 0) tr("आज", "Today") else SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(d)), 11f, if (on) Color.WHITE else Ui.MUTED, bold = true))
                        addView(text(SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(d)), 13f, if (on) Color.WHITE else Ui.TEXT, bold = true))
                        isClickable = true
                        setOnClickListener { date = d; load() }
                    }, lp(WRAP, WRAP).margins(context, r = 6))
                }
            })
        }, lp().margins(this, b = 10))
        // executive picker
        val execs = data?.optJSONArray("execs")
        if (execs != null && execs.length() > 0) {
            val names = mutableListOf(tr("सब", "Everyone")); val mobiles = mutableListOf("")
            for (i in 0 until execs.length()) {
                val e = execs.getJSONObject(i)
                names.add(e.optString("name").substringBefore(' ') + if (e.optBoolean("hasData")) " ●" else "")
                mobiles.add(e.optString("mobile"))
            }
            val sel = mobiles.indexOf(execMobile).coerceAtLeast(0)
            body.addView(chips(names, names[sel]) { p -> execMobile = mobiles[names.indexOf(p)]; load() }, lp().margins(this, b = 10))
        }
        if (loading) { body.addView(text(tr("आ रहा है…", "Loading…"), 14f, Ui.MUTED)); return }
        val routes = data?.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            body.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(tr("इस दिन कोई route record नहीं।", "No route recorded on this day."), 14f, Ui.MUTED) { gravity = Gravity.CENTER })
                addView(text(tr("Executive का phone उस दिन on होना और app में login होना ज़रूरी है।", "The executive's phone must be on and logged in that day."), 12f, Ui.MUTED) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            })
            return
        }
        for (i in 0 until routes.length()) renderRoute(routes.getJSONObject(i))
    }

    private fun renderRoute(route: JSONObject) {
        val ptsArr = route.optJSONArray("points") ?: JSONArray()
        val pts = (0 until ptsArr.length()).map { ptsArr.getJSONObject(it) }
            .sortedBy { it.optLong("t") }
        val card = card(14).apply { }
        card.addView(horizontal {
            addView(vertical {
                addView(text(route.optString("exec"), 16f, Ui.TEXT, bold = true))
                if (pts.isNotEmpty()) addView(text(timeFmt.format(Date(pts.first().optLong("t"))) + " → " + timeFmt.format(Date(pts.last().optLong("t"))), 12.5f, Ui.MUTED))
            }, lp(0, WRAP, 1f))
            addView(text("${pts.size} " + tr("points", "points"), 11.5f, Ui.MUTED))
        })
        if (pts.size < 2) {
            card.addView(text(tr("बहुत कम points — route नहीं बना।", "Too few points to draw a route."), 13f, Ui.MUTED), lp().margins(this, t = 8))
            body.addView(card, lp().margins(this, b = 12)); return
        }
        // distance + duration
        var dist = 0.0
        for (k in 1 until pts.size) {
            val d = FloatArray(1)
            Location.distanceBetween(pts[k - 1].optDouble("lat"), pts[k - 1].optDouble("lng"), pts[k].optDouble("lat"), pts[k].optDouble("lng"), d)
            dist += d[0]
        }
        val mins = (pts.last().optLong("t") - pts.first().optLong("t")) / 60000
        card.addView(horizontal {
            addView(stat(String.format(Locale.US, "%.1f", dist / 1000) + " km", tr("दूरी", "Distance")), lp(0, WRAP, 1f).margins(context, r = 6))
            addView(stat("${mins / 60}h ${mins % 60}m", tr("on-duty समय", "On duty")), lp(0, WRAP, 1f).margins(context, r = 6))
            addView(stat("${stops(pts).size}", tr("रुके", "Stops")), lp(0, WRAP, 1f))
        }, lp().margins(this, t = 10))
        // map
        val mapBox = android.widget.FrameLayout(this).apply {
            background = rounded(0xFFE9EBED.toInt(), dpf(12f))
            addView(text(tr("Map बन रहा है…", "Drawing the map…"), 12.5f, Ui.MUTED) { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        }
        card.addView(mapBox, lp(MATCH, dp(240)).margins(this, t = 10))
        val coords = pts.map { doubleArrayOf(it.optDouble("lat"), it.optDouble("lng")) }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { MapTiles.renderRoute(coords, dp(330), dp(240)) }.getOrNull()
            }
            if (isFinishing) return@launch
            mapBox.removeAllViews()
            if (bmp != null) mapBox.addView(ImageView(this@RouteReportActivity).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            else mapBox.addView(text(tr("Map के लिए internet चाहिए", "Map needs internet"), 12.5f, Ui.MUTED) { gravity = Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        }
        // stops
        val st = stops(pts)
        if (st.isNotEmpty()) card.addView(vertical {
            addView(text(tr("रुकने की जगह", "Where time was spent"), 14f, Ui.TEXT, bold = true), lp().margins(context, t = 12, b = 4))
            st.forEach { s ->
                addView(horizontal {
                    setPadding(0, dp(6), 0, dp(6))
                    addView(vertical {
                        addView(text(timeFmt.format(Date(s.from)) + " – " + timeFmt.format(Date(s.to)) + "  ·  " + "${(s.to - s.from) / 60000} min", 13.5f, Ui.TEXT, bold = true))
                        addView(text(String.format(Locale.US, "%.5f, %.5f", s.lat, s.lng), 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(tr("Map", "Map"), 13f, Ui.BRAND, bold = true) {
                        setPadding(dp(10), dp(8), 0, dp(8))
                        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}"))) }
                    })
                })
            }
        }, lp().margins(this, t = 4))
        card.addView(pillButton(tr("Google Maps में route खोलें", "Open route in Google Maps"), Ui.BRAND_LIGHT, Ui.BRAND, heightDp = 44) {
            openInMaps(coords)
        }, lp().margins(this, t = 10))
        body.addView(card, lp().margins(this, b = 12))
    }

    private fun stat(value: String, label: String): View = vertical {
        background = rounded(Ui.BG, dpf(12f))
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(10))
        addView(text(value, 17f, Ui.BRAND, bold = true) { gravity = Gravity.CENTER; maxLines = 1 })
        addView(text(label, 11f, Ui.MUTED) { gravity = Gravity.CENTER })
    }

    private data class Stop(val lat: Double, val lng: Double, val from: Long, val to: Long)

    /** Places where the person stayed within ~80 m for 12 minutes or more. */
    private fun stops(pts: List<JSONObject>): List<Stop> {
        val out = mutableListOf<Stop>()
        var i = 0
        while (i < pts.size) {
            var j = i + 1
            while (j < pts.size) {
                val d = FloatArray(1)
                Location.distanceBetween(pts[i].optDouble("lat"), pts[i].optDouble("lng"), pts[j].optDouble("lat"), pts[j].optDouble("lng"), d)
                if (d[0] > 80f) break
                j++
            }
            val from = pts[i].optLong("t"); val to = pts[j - 1].optLong("t")
            if (to - from >= 12 * 60000L) {
                val lat = pts.subList(i, j).sumOf { it.optDouble("lat") } / (j - i)
                val lng = pts.subList(i, j).sumOf { it.optDouble("lng") } / (j - i)
                out.add(Stop(lat, lng, from, to))
                i = j
            } else i++
        }
        return out
    }

    /** Google Maps directions with a few sampled waypoints (Maps allows a limited number). */
    private fun openInMaps(coords: List<DoubleArray>) {
        val step = (coords.size / 9).coerceAtLeast(1)
        val sample = coords.filterIndexed { idx, _ -> idx % step == 0 }.toMutableList()
        if (sample.last() !== coords.last()) sample.add(coords.last())
        val origin = coords.first(); val dest = coords.last()
        val mids = sample.drop(1).dropLast(1).joinToString("|") { "${it[0]},${it[1]}" }
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${origin[0]},${origin[1]}&destination=${dest[0]},${dest[1]}" +
            (if (mids.isNotBlank()) "&waypoints=" + Uri.encode(mids) else "") + "&travelmode=driving")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }
}
