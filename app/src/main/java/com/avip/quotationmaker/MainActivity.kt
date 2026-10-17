package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

/** Home: one big "New Site Visit" button, quick stats, price list and recent visits. */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical { setBackgroundColor(Ui.BG) }
        content = vertical(16)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        setContentView(root)
        checkUpdateInBackground()
    }

    private fun checkUpdateInBackground() {
        lifecycleScope.launch {
            val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.fetchLatest() } ?: return@launch
            if (latest.versionCode <= Updater.currentVersionCode(this@MainActivity) || isFinishing) return@launch
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Update ready")
                .setMessage("Version ${latest.versionName} of AVIP Surya Sales is available. Update now? It takes a few seconds.")
                .setPositiveButton("Update") { _, _ ->
                    startActivity(Intent(this@MainActivity, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                .setNegativeButton("Later", null).show()
        }
    }

    override fun onResume() {
        super.onResume()
        build()
        Remote.flushAsync(this)
    }

    private fun build() {
        content.removeAllViews()
        content.setPadding(0, 0, 0, dp(24))
        content.addView(hero())

        val inner = vertical { setPadding(dp(16), 0, dp(16), 0) }
        content.addView(inner)

        // Big call to action
        inner.addView(horizontal {
            background = gradient(dpf(20f), Ui.SUN, 0xFFFFB84D.toInt())
            elevation = dpf(6f)
            setPadding(dp(18), dp(20), dp(18), dp(20))
            foreground = context.ripple()
            addView(badge(R.drawable.ic_sun, Ui.BRAND_DARK, 60))
            addView(vertical {
                addView(text("New Site Visit", 22f, Ui.TEXT, bold = true))
                addView(text("Survey  →  Pick system  →  Quotation PDF", 13f, 0xFF4A3A10.toInt()), lp().margins(context, t = 2))
            }, lp(0, WRAP, 1f).margins(context, l = 14))
            addView(text("›", 34f, Ui.TEXT, bold = true))
            setOnClickListener { startActivity(Intent(context, VisitActivity::class.java)) }
        }, lp().margins(this, t = 16, b = 14))

        // Stats
        val visits = VisitStore.all(this)
        val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        // a visit counts once the site visit is submitted (quotation created)
        val today = visits.count { it.isQuoted && it.quotedAt >= dayStart }
        val monthQuotes = visits.filter { it.isQuoted && it.quotedAt >= monthStart }
        val due = visits.filter { it.followUpDue }.sortedBy { it.followUpAt }
        inner.addView(horizontal {
            addView(stat("$today", "Visits today", Ui.BRAND), lp(0, WRAP, 1f).margins(context, r = 8))
            addView(stat("${monthQuotes.size}", "Visits this month", Ui.BLUE), lp(0, WRAP, 1f).margins(context, r = 8))
            addView(stat("${due.size}", "Follow-ups due", if (due.isNotEmpty()) Ui.DANGER else Ui.MUTED), lp(0, WRAP, 1f))
        }, lp().margins(this, b = 14))

        if (due.isNotEmpty()) {
            inner.addView(horizontal {
                addView(icon(R.drawable.ic_call, Ui.DANGER, 18))
                addView(text("Follow-up calls due", 17f, Ui.TEXT, bold = true), lp(0, WRAP, 1f).margins(context, l = 8))
            }, lp().margins(this, b = 8))
            due.take(5).forEach { inner.addView(visitRow(it) { build() }, lp().margins(this, b = 10)) }
            if (due.size > 5) inner.addView(text("+ ${due.size - 5} more in All Visits", 13f, Ui.BRAND, bold = true) {
                setOnClickListener { startActivity(Intent(context, HistoryActivity::class.java)) }
            }, lp().margins(this, b = 14))
        }

        // Shortcuts
        inner.addView(horizontal {
            addView(shortcut("Price List", "All systems & prices", R.drawable.ic_grid, Ui.BLUE) {
                startActivity(Intent(context, PriceListActivity::class.java))
            }, lp(0, WRAP, 1f).margins(context, r = 10))
            addView(shortcut("All Visits", "${visits.size} saved", R.drawable.ic_history, 0xFF6A1B9A.toInt()) {
                startActivity(Intent(context, HistoryActivity::class.java))
            }, lp(0, WRAP, 1f))
        }, lp().margins(this, b = 14))

        val note = Catalog.settings["announcement"]
        if (note.isNotBlank()) {
            inner.addView(horizontal {
                background = rounded(Ui.SUN_LIGHT, dpf(14f), Ui.SUN, dp(1))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(icon(R.drawable.ic_bolt, Ui.SUN, 22))
                addView(text(note, 13.5f, Ui.TEXT), lp(0, WRAP, 1f).margins(context, l = 10))
            }, lp().margins(this, b = 14))
        }

        // Recent visits
        inner.addView(horizontal {
            addView(text("Recent visits", 17f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            if (visits.size > 4) addView(text("See all", 14f, Ui.BRAND, bold = true) {
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setOnClickListener { startActivity(Intent(context, HistoryActivity::class.java)) }
            })
        }, lp().margins(this, t = 4, b = 8))
        if (visits.isEmpty()) {
            inner.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_home, Ui.LINE, 48))
                addView(text("No visits yet.\nTap New Site Visit at the customer's home.", 14f, Ui.MUTED) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 8))
            })
        } else {
            visits.filter { it !in due }.take(4).forEach { inner.addView(visitRow(it) { build() }, lp().margins(this, b = 10)) }
        }
    }

    private fun hero(): View = vertical {
        background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
        setPadding(dp(18), dp(14), dp(10), dp(22))
        addView(horizontal {
            addView(android.widget.FrameLayout(context).apply {
                background = rounded(Color.WHITE, dpf(12f))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.avip_solar_logo)
                    adjustViewBounds = true
                }, android.widget.FrameLayout.LayoutParams(dp(118), WRAP))
            }, lp(WRAP, WRAP))
            addView(text("Surya Sales", 15f, Color.WHITE, bold = true) { maxLines = 1 }, lp(0, WRAP, 1f).margins(context, l = 10))
            addView(barButton(R.drawable.ic_refresh) { refresh() })
            addView(barButton(R.drawable.ic_person) { startActivity(Intent(context, ProfileActivity::class.java)) })
        })
        val first = Officer.name(context).substringBefore(' ')
        addView(text("Namaste, $first", 24f, Color.WHITE, bold = true), lp().margins(context, t = 18))
        val ago = if (Catalog.updatedAt > 0) DateUtils.getRelativeTimeSpanString(Catalog.updatedAt).toString() else "—"
        addView(horizontal {
            addView(View(context).apply { background = circle(if (Catalog.isLive) 0xFF7CF29A.toInt() else Ui.SUN) }, lp(dp(8), dp(8)))
            addView(text((if (Catalog.isLive) "Live prices" else "Saved prices") + " · updated $ago · ${Catalog.packages.size} systems",
                12.5f, 0xDDFFFFFF.toInt()), lp(WRAP, WRAP).margins(context, l = 6))
        }, lp().margins(context, t = 4))
    }

    private fun stat(value: String, label: String, color: Int): View = card(12) {
        addView(text(value, 21f, color, bold = true) { maxLines = 1 })
        addView(text(label, 11.5f, Ui.MUTED) { maxLines = 2 })
    }

    private fun shortcut(title: String, sub: String, res: Int, color: Int, onClick: () -> Unit): View = card(14) {
        foreground = context.ripple()
        addView(badge(res, color, 42, square = true))
        addView(text(title, 15.5f, Ui.TEXT, bold = true), lp().margins(context, t = 10))
        addView(text(sub, 12f, Ui.MUTED))
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        Toast.makeText(this, "Refreshing prices…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = Catalog.refresh(this@MainActivity)
            Toast.makeText(this@MainActivity, if (ok) "Prices updated" else "Couldn't refresh. Using saved prices.", Toast.LENGTH_SHORT).show()
            build()
        }
    }
}
