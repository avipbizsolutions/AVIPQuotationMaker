package com.avip.quotationmaker

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/** Read-only browse of every system and price, for answering quick phone questions. */
class PriceListActivity : AppCompatActivity() {

    private var type = SystemType.ONGRID
    private var kw = ""
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar("Price List", "Prices include GST"))
        body = vertical { setPadding(dp(16), dp(14), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setContentView(root)
        fill()
    }

    private fun fill() {
        body.removeAllViews()
        body.addView(typeSwitch(type) { type = it; kw = ""; fill() })
        val list = Catalog.of(type)
        val sizes = list.map { it.kw }.distinct().sorted().map { Fmt.num(it) + " kW" }
        body.addView(chips(sizes, kw) { kw = it; fill() }, lp().margins(this, t = 12, b = 4))
        list.filter { kw.isBlank() || Fmt.num(it.kw) + " kW" == kw }.forEach { p ->
            body.addView(packageCard(p, selected = false, recommended = false) {}, lp().margins(this, t = 12))
        }
    }
}
