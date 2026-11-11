package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

/** Home: one big "New Site Visit" button, quick stats, price list and recent visits. */
class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    private val locPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RouteLog.locationGranted(this)) { askBackgroundLocation(); RouteLog.ensure(this) }
    }
    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { RouteLog.ensure(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val root = vertical { setBackgroundColor(Ui.BG) }
        content = vertical(16)
        root.addView(ScrollView(this).apply { addView(content) }, lp(MATCH, 0, 1f))
        setContentView(root)
        checkUpdateInBackground()
        checkAccess()
    }

    /**
     * Checks the login with the office each time the app opens online: someone disabled or
     * removed in the Team Directory is logged out; a role change (team view) applies at once.
     */
    private fun checkAccess() {
        lifecycleScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Remote.call(this@MainActivity, "me") }
            }
            if (isFinishing) return@launch
            r.onSuccess { o ->
                val user = o.optJSONObject("user") ?: return@onSuccess
                val before = Officer.teamView(this@MainActivity)
                Officer.refresh(this@MainActivity, user)
                if (before != Officer.teamView(this@MainActivity)) build()
                Remote.flushAsync(this@MainActivity)
            }
            val e = r.exceptionOrNull() as? RemoteException ?: return@launch // offline: keep working
            if (!e.sessionExpired) return@launch
            Officer.signOut(this@MainActivity)
            startActivity(Intent(this@MainActivity, ProfileActivity::class.java)
                .putExtra(ProfileActivity.EXTRA_REVOKED, e.message)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    private fun checkUpdateInBackground() {
        lifecycleScope.launch {
            val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updater.fetchLatest() } ?: return@launch
            if (latest.versionCode <= Updater.currentVersionCode(this@MainActivity) || isFinishing) return@launch
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(L("Update required"))
                .setMessage(L("Version ${latest.versionName} of AVIP Solar Sales is available and must be installed to continue. It takes a few seconds."))
                .setCancelable(false)
                .setPositiveButton(L("Update now")) { _, _ ->
                    startActivity(Intent(this@MainActivity, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (VisitStore.applyReset(this)) Toast.makeText(this, L("Visit records were reset by the office"), Toast.LENGTH_LONG).show()
        LeadStore.syncFromVisits(this)
        build()
        Remote.flushAsync(this)
        refreshLeads()
        setupRouteLogging()
    }

    /**
     * Field route logging. Staff are informed in writing; the app also asks for a one-time
     * acknowledgement and shows a standing "on duty" notice while recording. Only founders
     * can open the report.
     */
    private fun setupRouteLogging() {
        if (!RouteLog.enabled(this)) { RouteLog.ensure(this); return }
        if (!RouteLog.hasConsented(this)) {
            AlertDialog.Builder(this)
                .setTitle(L("Location recording"))
                .setMessage(L("On working days (9 AM to 8 PM) this app records your location for route and attendance, as informed by the office in writing. A notice stays on while it records. Only the founders can see the report."))
                .setCancelable(false)
                .setPositiveButton(L("I understand")) { _, _ ->
                    RouteLog.setConsented(this)
                    if (!RouteLog.locationGranted(this)) locPerms.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    else { askBackgroundLocation(); RouteLog.ensure(this) }
                }
                .show()
            return
        }
        if (!RouteLog.locationGranted(this)) locPerms.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        else RouteLog.ensure(this)
    }

    /** All-day recording is reliable only with "Allow all the time"; ask once on Android 10+. */
    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle(L("Allow location all the time"))
            .setMessage(L("For the full-day route, set location to Allow all the time on the next screen. Without that, the route records only while the app is open."))
            .setPositiveButton(L("Continue")) { _, _ -> bgPerm.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            .setNegativeButton(L("Later")) { _, _ -> }
            .show()
    }


    private fun build() {
        content.removeAllViews()
        content.setPadding(0, 0, 0, dp(24))
        content.addView(hero())

        val inner = vertical { setPadding(dp(16), 0, dp(16), 0) }
        content.addView(inner)

        // Main actions: a new lead, a visit to book, a site visit
        inner.addView(horizontal {
            addView(pillButton(tr("नई Lead", "New lead"), Ui.SUN, Ui.TEXT, heightDp = 54, iconRes = R.drawable.ic_plus) {
                startActivity(Intent(context, LeadFormActivity::class.java))
            }, lp(0, WRAP, 1f).margins(context, r = 8))
            addView(pillButton(tr("Visit book", "Book visit"), Ui.BRAND, heightDp = 54, iconRes = R.drawable.ic_history) {
                startActivity(Intent(context, LeadsActivity::class.java).putExtra(LeadsActivity.EXTRA_PICK, true))
            }, lp(0, WRAP, 1f))
        }, lp().margins(this, t = 16, b = 12))
        leadsSection(inner)
        inner.addView(horizontal {
            background = gradient(dpf(18f), Ui.SUN, 0xFFFFB84D.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            foreground = context.ripple()
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(R.drawable.ic_sun, Ui.BRAND_DARK, 44))
            addView(vertical {
                addView(text("New Site Visit", 17f, Ui.TEXT, bold = true))
                addView(text("Survey  →  Pick system  →  Quotation PDF", 12f, 0xFF4A3A10.toInt()))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("›", 30f, Ui.TEXT, bold = true))
            setOnClickListener { startActivity(Intent(context, VisitActivity::class.java)) }
        }, lp().margins(this, b = 14))

        // Stats (only the logged-in user's own visits)
        val visits = VisitStore.mine(this)
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
        }, lp().margins(this, b = 10))

        inner.addView(shortcut("Documents", tr("Brochure, rate card, forms — field में काम के", "Brochures, rate card, forms for the field"), R.drawable.ic_grid, 0xFF0E6B3A.toInt()) {
            startActivity(Intent(context, DocumentsActivity::class.java))
        }, lp().margins(this, b = 14))

        if (Officer.teamView(this)) inner.addView(horizontal {
            background = rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            foreground = context.ripple()
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(R.drawable.ic_person, Ui.BRAND_DARK, 42, square = true))
            addView(vertical {
                addView(text("Team Visits", 15.5f, Ui.TEXT, bold = true))
                addView(text("Every executive's visits, follow-ups and conversions", 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("›", 28f, Ui.BRAND, bold = true))
            setOnClickListener { startActivity(Intent(context, TeamVisitsActivity::class.java)) }
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

    /** Today's visits and calls, calls due, and the lead pipeline. */
    private fun leadsSection(inner: LinearLayout) {
        val mine = LeadStore.mine(this).filter { it.isOpen }
        val today = Lead.startOfToday()
        val todayItems = mine.flatMap { l ->
            listOfNotNull(
                if (l.stage == Lead.VISIT && l.visitAt in today..Lead.endOfToday()) Triple(l.visitAt, l, true) else null,
                if (l.nextAt in today..Lead.endOfToday()) Triple(l.nextAt, l, false) else null,
            )
        }.sortedBy { it.first }
        inner.addView(card(14) {
            addView(horizontal {
                addView(text(tr("आज · ${todayItems.count { it.third }} visit, ${todayItems.count { !it.third }} call", "Today · ${todayItems.count { it.third }} visits, ${todayItems.count { !it.third }} calls"), 16f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(text("Schedule ›", 13.5f, Ui.BRAND, bold = true) { setPadding(dp(8), dp(4), 0, dp(4)) })
            })
            if (todayItems.isEmpty()) addView(text(tr("आज कोई visit या call तय नहीं।", "No visits or calls set for today."), 13f, Ui.MUTED), lp().margins(context, t = 4))
            todayItems.take(4).forEach { (at, l, visit) ->
                addView(horizontal {
                    setPadding(0, dp(8), 0, dp(2))
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(at)), 12.5f, if (visit) 0xFF7A4E00.toInt() else Ui.BLUE, bold = true), lp(dp(70), WRAP))
                    addView(vertical {
                        addView(text((if (visit) "Visit · " else "Call · ") + l.name, 14f, Ui.TEXT, bold = true) { maxLines = 1 })
                        addView(text(if (visit) l.visitAddress.ifBlank { l.area } else l.area, 12f, Ui.MUTED) { maxLines = 1 })
                    }, lp(0, WRAP, 1f))
                    setOnClickListener { startActivity(Intent(context, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id)) }
                })
            }
            foreground = context.ripple()
            setOnClickListener { startActivity(Intent(context, ScheduleActivity::class.java)) }
        }, lp().margins(this, b = 10))
        val due = mine.filter { it.callDue || it.stage == Lead.NEW }
        if (due.isNotEmpty()) inner.addView(horizontal {
            background = rounded(Color.WHITE, dpf(16f), 0xFFF2C4BF.toInt(), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            foreground = context.ripple()
            addView(badge(R.drawable.ic_call, Ui.DANGER, 40))
            addView(vertical {
                addView(text(tr("${due.size} call बाकी", "${due.size} calls to make"), 15f, Ui.DANGER, bold = true))
                val over = due.count { it.callOverdue }; val fresh = due.count { it.stage == Lead.NEW }
                addView(text(listOfNotNull(if (over > 0) tr("$over overdue", "$over overdue") else null, if (fresh > 0) tr("$fresh नई lead", "$fresh new leads") else null).joinToString(" · "), 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("›", 26f, Ui.BRAND, bold = true))
            setOnClickListener { startActivity(Intent(context, LeadsActivity::class.java)) }
        }, lp().margins(this, b = 10))
        val all = LeadStore.mine(this)
        inner.addView(card(14) {
            addView(horizontal {
                addView(text(tr("मेरी Leads · ${mine.size}", "My leads · ${mine.size}"), 16f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(text(tr("सब देखें ›", "See all ›"), 13.5f, Ui.BRAND, bold = true))
            })
            addView(horizontal {
                listOf(Lead.NEW to "New", Lead.CALLED to "Called", Lead.VISIT to "Visit", Lead.QUOTED to "Quoted", Lead.BOOKED to "Booked").forEach { (st, lab) ->
                    val (bg, fg) = Lead.stageColors(st)
                    val n = if (st == Lead.QUOTED) all.count { it.stage == Lead.QUOTED || it.stage == Lead.NEGOTIATION } else all.count { it.stage == st }
                    addView(vertical {
                        gravity = Gravity.CENTER
                        background = rounded(bg, dpf(12f))
                        setPadding(0, dp(8), 0, dp(8))
                        addView(text("$n", 19f, fg, bold = true))
                        addView(text(lab, 10.5f, Ui.MUTED, bold = true))
                    }, lp(0, WRAP, 1f).margins(context, r = 5))
                }
            }, lp().margins(context, t = 8))
            foreground = context.ripple()
            setOnClickListener { startActivity(Intent(context, LeadsActivity::class.java)) }
        }, lp().margins(this, b = 10))
        if (Officer.founder(this)) inner.addView(horizontal {
            background = rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            foreground = context.ripple()
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(R.drawable.ic_location, 0xFF6A1B9A.toInt(), 40, square = true))
            addView(vertical {
                addView(text("Day Route Report", 15f, Ui.TEXT, bold = true))
                addView(text(tr("हर executive का दिन का route — सिर्फ founders", "Each executive's day route — founders only"), 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("›", 26f, Ui.BRAND, bold = true))
            setOnClickListener { startActivity(Intent(context, RouteReportActivity::class.java)) }
        }, lp().margins(this, b = 10))
        if (Officer.teamView(this)) inner.addView(horizontal {
            background = rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            foreground = context.ripple()
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(R.drawable.ic_person, Ui.BLUE, 40, square = true))
            val un = LeadStore.all(this@MainActivity).count { it.execMobile.isBlank() && it.isOpen }
            addView(vertical {
                addView(text("Team Leads", 15f, Ui.TEXT, bold = true))
                addView(text(if (un > 0) tr("$un lead assign करनी हैं", "$un leads to assign") else tr("Executive pipeline और assign", "Executive pipeline and assigning"), 12f, if (un > 0) Ui.DANGER else Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("›", 26f, Ui.BRAND, bold = true))
            setOnClickListener { startActivity(Intent(context, TeamLeadsActivity::class.java)) }
        }, lp().margins(this, b = 14))
    }

    /** Leads from the office in the background; the home screen redraws when they arrive. */
    private fun refreshLeads() {
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { LeadStore.refresh(this@MainActivity); LeadStore.syncFromVisits(this@MainActivity) }.isSuccess
            }
            if (ok && !isFinishing) build()
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
            addView(text("Sales", 15f, Color.WHITE, bold = true) { maxLines = 1 }, lp(0, WRAP, 1f).margins(context, l = 10))
            addView(langToggle(), lp(WRAP, WRAP).margins(context, r = 2))
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
        Toast.makeText(this, L("Refreshing prices…"), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = Catalog.refresh(this@MainActivity)
            Toast.makeText(this@MainActivity, L(if (ok) "Prices updated" else "Couldn't refresh. Using saved prices."), Toast.LENGTH_SHORT).show()
            build()
        }
    }
}
