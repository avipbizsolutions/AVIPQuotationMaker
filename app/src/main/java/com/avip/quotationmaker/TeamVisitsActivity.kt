package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Every sales executive's visits, read from the office Google Sheet.
 * Only for numbers marked "Sees all team visits" in the App Users tab.
 */
class TeamVisitsActivity : AppCompatActivity() {

    private lateinit var head: LinearLayout
    private lateinit var list: LinearLayout
    private var all: List<JSONObject> = emptyList()
    private var query = ""
    private var status = ""
    private var exec = ""
    private var expanded = mutableSetOf<String>()
    private var loadedAt = 0L
    private var error = ""
    private var loading = false

    private val cache get() = File(filesDir, "team_visits.json")
    // the sheet shows dates as 26-Oct-2026 or 10/26/2026 depending on the column format
    private val sheetDates = listOf("dd-MMM-yyyy", "M/d/yyyy", "yyyy-MM-dd").map { SimpleDateFormat(it, Locale.ENGLISH).apply { isLenient = false } }
    private val niceDate = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar("Team Visits", "All sales executives", right = listOf(barButton(R.drawable.ic_refresh) { load() })))
        head = vertical { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        root.addView(ScrollView(this).apply {
            addView(vertical {
                addView(head)
                list = vertical { setPadding(dp(16), dp(8), dp(16), dp(24)) }
                addView(list)
            })
        }, lp(MATCH, 0, 1f))
        setContentView(root)

        if (!Officer.teamView(this)) { finish(); return }
        try {
            if (cache.exists()) { all = parse(JSONArray(cache.readText())); loadedAt = cache.lastModified(); indexExecs() }
        } catch (_: Exception) {}
        buildHead()
        fill()
        load()
    }

    private fun parse(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun load() {
        if (loading) return
        loading = true; error = ""
        buildHead()
        lifecycleScope.launch {
            try {
                val arr = withContext(Dispatchers.IO) { Remote.teamVisits(this@TeamVisitsActivity) }
                all = parse(arr)
                indexExecs()
                loadedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) { cache.writeText(arr.toString()) }
            } catch (e: Exception) {
                error = if (e is java.io.IOException) "No internet. Showing the last saved list." else (e.message ?: "Could not load")
            }
            loading = false
            buildHead(); fill()
        }
    }

    private fun s(o: JSONObject, k: String) = o.optString(k, "").trim()
    private fun date(o: JSONObject, k: String): Date? {
        val t = s(o, k).substringBefore(' ')
        if (t.isBlank()) return null
        for (f in sheetDates) try { return f.parse(t) } catch (_: Exception) {}
        return null
    }

    /** Date for display, e.g. 26 Oct 2026. */
    private fun nice(o: JSONObject, k: String) = date(o, k)?.let { niceDate.format(it) } ?: s(o, k)

    /** One name per executive (by mobile), the most recent one they used. */
    private var execNames: Map<String, String> = emptyMap()
    private fun ex(o: JSONObject) = execNames[Catalog.mobileKey(s(o, "Exec Mobile"))] ?: s(o, "Sales Executive")
    private fun indexExecs() {
        execNames = all.filter { Catalog.mobileKey(s(it, "Exec Mobile")).length == 10 && s(it, "Sales Executive").isNotBlank() }
            .groupBy { Catalog.mobileKey(s(it, "Exec Mobile")) }
            .mapValues { (_, list) -> list.maxByOrNull { date(it, "Received")?.time ?: 0L }?.let { s(it, "Sales Executive") } ?: "" }
    }

    private fun followUpDue(o: JSONObject): Boolean {
        if (s(o, "Lead Status").ifBlank { "Open" } != "Open") return false
        val d = date(o, "Next Follow-up") ?: return false
        val end = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
        return !d.after(end.time)
    }

    private fun buildHead() {
        head.removeAllViews()
        if (loading) head.addView(text("Loading team visits…", 13f, Ui.BRAND, bold = true), lp().margins(this, b = 8))
        if (error.isNotBlank()) head.addView(horizontal {
            background = rounded(0xFFFDECEC.toInt(), dpf(12f), Ui.DANGER, dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(text(error, 13f, Ui.DANGER, bold = true))
        }, lp().margins(this, b = 10))
        // Google account used to open the PDFs (remembered; tap to change)
        head.addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            val acc = driveAccount
            addView(icon(R.drawable.ic_person, Ui.MUTED, 16))
            addView(text(if (acc.isBlank()) "PDFs open with: ask each time" else "PDFs open with: $acc", 12f, Ui.MUTED) { maxLines = 1 },
                lp(0, WRAP, 1f).margins(context, l = 6))
            addView(text(if (acc.isBlank()) "Choose" else "Change", 12.5f, Ui.BRAND, bold = true) {
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener { chooseAccount(null) }
            })
        }, lp().margins(this, b = 8))
        if (loadedAt > 0 && !loading) head.addView(text("Updated " + SimpleDateFormat("dd MMM, h:mm a", Locale.ENGLISH).format(Date(loadedAt)), 11.5f, Ui.MUTED), lp().margins(this, b = 8))

        // summary for this month
        val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.time
        val month = all.filter { date(it, "Visit Date")?.before(monthStart) == false }
        head.addView(horizontal {
            fun stat(v: String, l: String, c: Int) = addView(card(10) {
                addView(text(v, 19f, c, bold = true) { maxLines = 1 })
                addView(text(l, 11f, Ui.MUTED) { maxLines = 2 })
            }, lp(0, WRAP, 1f).margins(context, r = 6))
            stat("${month.size}", "Visits this month", Ui.BRAND)
            stat("${month.count { s(it, "Lead Status") == "Converted" }}", "Converted this month", Ui.BLUE)
            stat("${all.count { followUpDue(it) }}", "Follow-ups due", Ui.DANGER)
        }, lp().margins(this, b = 10))

        head.addView(input("Search customer, mobile, visit no, place").apply {
            setText(query)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            compoundDrawablePadding = dp(8)
            onChange { query = it.trim().lowercase(); fill() }
        })
        head.addView(chips(listOf("Open", "Converted", "Follow-up due"), status) { status = it; fill() }, lp().margins(this, t = 10))
        val execs = all.map { ex(it) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        if (execs.size > 1) {
            head.addView(text("Sales executive", 12f, Ui.MUTED, bold = true), lp().margins(this, t = 10))
            head.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips(execs, exec, Ui.BLUE) { exec = it; fill() })
            }, lp().margins(this, t = 4))
        }
    }

    private fun fill() {
        list.removeAllViews()
        val items = all.filter { o ->
            when (status) {
                "Open" -> s(o, "Lead Status").ifBlank { "Open" } == "Open"
                "Converted" -> s(o, "Lead Status") == "Converted"
                "Follow-up due" -> followUpDue(o)
                else -> true
            } && (exec.isBlank() || ex(o) == exec) &&
                (query.isBlank() || listOf("Customer", "WhatsApp", "Visit No", "Address", "District", "CA No", "System", "Sales Executive")
                    .joinToString(" ") { s(o, it) }.lowercase().contains(query))
        }
        list.addView(text("${items.size} visit${if (items.size == 1) "" else "s"}", 13f, Ui.MUTED, bold = true), lp().margins(this, b = 8))
        if (items.isEmpty()) {
            list.addView(text(if (all.isEmpty() && !loading) "No team visits yet" else "No visits found", 14f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 30))
            return
        }
        items.take(300).forEach { list.addView(row(it), lp().margins(this, b = 10)) }
        if (items.size > 300) list.addView(text("Showing the first 300 — search to narrow down", 12.5f, Ui.MUTED) { gravity = Gravity.CENTER })
    }

    private fun row(o: JSONObject): View = card(14) {
        val no = s(o, "Visit No")
        val st = s(o, "Lead Status").ifBlank { "Open" }
        val isOpen = no in expanded
        foreground = context.ripple()
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(s(o, "Customer").ifBlank { no }, 16f, Ui.TEXT, bold = true) { maxLines = 1 }, lp(0, WRAP, 1f))
            addView(tag(st, if (st == "Converted") Ui.BLUE else if (followUpDue(o)) Ui.DANGER else Ui.BRAND))
        })
        addView(text(listOf(nice(o, "Visit Date"), ex(o), s(o, "District")).filter { it.isNotBlank() }.joinToString("  ·  "), 12.5f, Ui.MUTED),
            lp().margins(context, t = 2))
        val sys = listOf(s(o, "System").ifBlank { s(o, "System Type") }, s(o, "Final Price").takeIf { it.isNotBlank() && it != "0" }?.let { "₹$it" } ?: "")
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        if (sys.isNotBlank()) addView(text(sys, 13.5f, Ui.TEXT, bold = true), lp().margins(context, t = 6))
        val install = Regex("Installation wanted: ([^\\n|]+)").find(s(o, "Site Notes"))?.groupValues?.get(1)?.trim()
        when {
            st == "Converted" && install != null -> addView(text("Installation wanted: " + (runCatching { niceDate.format(sheetDates[2].parse(install)!!) }.getOrNull() ?: install), 12.5f, Ui.BLUE, bold = true), lp().margins(context, t = 4))
            st == "Open" && s(o, "Next Follow-up").isNotBlank() -> addView(text("Follow-up: ${nice(o, "Next Follow-up")}" +
                s(o, "Follow-up Note").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(), 12.5f, if (followUpDue(o)) Ui.DANGER else Ui.MUTED, bold = followUpDue(o)) { maxLines = if (isOpen) 6 else 1 },
                lp().margins(context, t = 4))
        }
        if (isOpen) {
            addView(View(context).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, dp(1)).margins(context, t = 10, b = 8))
            listOf("Visit No", "WhatsApp", "Address", "PIN", "CA No", "DISCOM", "Sanctioned Load (kW)", "Monthly Bill", "Roof", "Structure",
                "kWp", "List Price", "Discount", "Funding", "Loan Amount", "EMI", "Loan Years", "Calls", "Last Call", "Exec Mobile", "Site Notes")
                .forEach { k -> s(o, k).takeIf { it.isNotBlank() && it != "0" }?.let { addView(kv(k, it), lp().margins(context, b = 3)) } }
            val actions = horizontal { }
            fun act(label: String, color: Int, go: () -> Unit) = actions.addView(text(label, 13f, Color.WHITE, bold = true) {
                gravity = Gravity.CENTER
                background = rounded(color, dpf(16f))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { go() }
            }, lp(WRAP, WRAP).margins(context, r = 8))
            val phone = s(o, "WhatsApp").filter { it.isDigit() }
            if (phone.length >= 10) act("Call", Ui.BRAND) { openLink(Uri.parse("tel:$phone")) }
            s(o, "Quotation PDF").takeIf { it.startsWith("http") }?.let { u -> act("Quotation", Ui.BLUE) { openDrive(u) } }
            s(o, "Site Report PDF").takeIf { it.startsWith("http") }?.let { u -> act("Report", 0xFF6A1B9A.toInt()) { openDrive(u) } }
            s(o, "Map").takeIf { it.startsWith("http") }?.let { u -> act("Map", Ui.SUN) { openLink(Uri.parse(u)) } }
            addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, lp().margins(context, t = 10))
        }
        setOnClickListener { if (isOpen) expanded -= no else expanded += no; fill() }
    }

    // ---- Google account for Drive PDFs ----

    private val prefs get() = getSharedPreferences("team_view", MODE_PRIVATE)
    private val driveAccount get() = prefs.getString("drive_account", "") ?: ""
    private var pendingLink: String? = null

    private val accountPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val name = r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (r.resultCode == RESULT_OK && !name.isNullOrBlank()) {
            prefs.edit().putString("drive_account", name).apply()
            buildHead()
        }
        pendingLink?.let { pendingLink = null; if (!name.isNullOrBlank()) openDrive(it) }
    }

    private fun chooseAccount(thenOpen: String?) {
        pendingLink = thenOpen
        val cur = driveAccount.takeIf { it.isNotBlank() }?.let { android.accounts.Account(it, "com.google") }
        try {
            accountPicker.launch(android.accounts.AccountManager.newChooseAccountIntent(cur, null, arrayOf("com.google"), null, null, null, null))
        } catch (_: Exception) {
            thenOpen?.let { openLink(Uri.parse(it)) }
        }
    }

    /** Opens a Drive file in the browser signed in as the remembered account (no account prompt each time). */
    private fun openDrive(url: String) {
        val acc = driveAccount
        if (acc.isBlank()) { chooseAccount(url); return }
        val u = Uri.parse(url).buildUpon().appendQueryParameter("authuser", acc).build()
        // the Drive app ignores the account and asks again, so prefer a browser
        for (pkg in listOf("com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.microsoft.emmx")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u).setPackage(pkg)); return } catch (_: Exception) {}
        }
        openLink(u)
    }

    private fun openLink(u: Uri) {
        try { startActivity(Intent(if (u.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
    }
}
