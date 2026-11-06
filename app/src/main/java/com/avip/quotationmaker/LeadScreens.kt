package com.avip.quotationmaker

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- shared pieces

private fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** Screen with the green top bar, a scrolling body and an optional fixed bottom bar. Returns the body. */
fun Activity.leadScreen(title: String, sub: String?, right: List<View> = emptyList(), bottom: View? = null): LinearLayout {
    val body = vertical { setPadding(dp(16), dp(12), dp(16), dp(24)) }
    val root = vertical { setBackgroundColor(Ui.BG) }
    root.addView(topBar(title, sub, right = right))
    root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, lp(MATCH, 0, 1f))
    if (bottom != null) root.addView(vertical {
        setBackgroundColor(Color.WHITE)
        elevation = dpf(8f)
        setPadding(dp(16), dp(10), dp(16), dp(14))
        addView(bottom, lp())
    }, lp())
    setContentView(root)
    return body
}

fun android.content.Context.stagePill(stage: String): TextView {
    val (bg, fg) = Lead.stageColors(stage)
    return text(Lead.stageLabel(stage), 11.5f, fg, bold = true) {
        background = rounded(bg, dpf(10f))
        setPadding(dp(9), dp(3), dp(9), dp(3))
        maxLines = 1
    }
}

private fun android.content.Context.round(res: Int, tint: Int, bg: Int, desc: String, onClick: () -> Unit): View =
    android.widget.FrameLayout(this).apply {
        background = circle(bg)
        contentDescription = desc
        isClickable = true
        foreground = ripple()
        addView(icon(res, tint, 20), android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

/** One lead in a list: name, area, source, stage, next action, call and WhatsApp. */
fun Activity.leadCard(l: Lead, showExec: Boolean = false, onClick: () -> Unit): View = card(12) {
    isClickable = true
    foreground = context.ripple()
    addView(horizontal {
        gravity = Gravity.CENTER_VERTICAL
        addView(vertical {
            addView(text(l.name, 15.5f, Ui.TEXT, bold = true) { maxLines = 1 })
            val bits = listOf(l.area, if (l.bill.isNotBlank()) "₹" + l.bill + tr("/माह", "/mo") else "", l.source,
                if (showExec) l.exec.ifBlank { tr("बिना executive", "Unassigned") } else "").filter { it.isNotBlank() }
            addView(text(bits.joinToString(" · "), 12f, Ui.MUTED) { maxLines = 1 })
        }, lp(0, WRAP, 1f))
        addView(stagePill(l.stage))
    })
    val (line, color) = nextLine(l)
    addView(horizontal {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(line, 12.5f, color, bold = true) { maxLines = 2 }, lp(0, WRAP, 1f))
        if (l.mobile.isNotBlank()) {
            addView(round(R.drawable.ic_call, Ui.BRAND, Ui.BRAND_LIGHT, "Call ${l.name}") { Talk.call(this@leadCard, l.mobile); CallLog.started(this@leadCard, l.id) }, lp(dp(42), dp(42)).margins(context, l = 6))
            addView(round(R.drawable.ic_chat, Ui.BRAND, Ui.BRAND_LIGHT, "WhatsApp ${l.name}") { Talk.whatsapp(this@leadCard, l.mobile, "Namaste ${l.name} ji, AVIP Solar se ${Officer.name(context)}.") }, lp(dp(42), dp(42)).margins(context, l = 6))
        }
    }, lp().margins(context, t = 6))
    setOnClickListener { onClick() }
}

/** What to do next with a lead, and in which colour. */
fun nextLine(l: Lead): Pair<String, Int> = when {
    l.stage == Lead.LOST -> tr("Lost: ", "Lost: ") + l.lostReason to Ui.MUTED
    l.stage == Lead.BOOKED -> tr("Booked ✓", "Booked ✓") + if (l.visitNumber.isNotBlank()) " · " + l.visitNumber else "" to Ui.BRAND
    l.stage == Lead.VISIT && l.visitAt > 0 -> tr("Visit: ", "Visit: ") + When.short(l.visitAt) to 0xFF7A4E00.toInt()
    l.callOverdue -> tr("Call बाकी (overdue): ", "Call overdue: ") + When.short(l.nextAt) to 0xFFB3261E.toInt()
    l.nextAt > 0 -> tr("Call back: ", "Call back: ") + When.short(l.nextAt) + if (l.nextNote.isNotBlank()) " · " + l.nextNote else "" to if (l.callDue) 0xFFB3261E.toInt() else Ui.TEXT
    l.stage == Lead.NEW -> tr("अभी call करें · ", "Call now · ") + When.ago(l.createdAt) to 0xFFB3261E.toInt()
    else -> tr("अगला step तय करें", "Set the next step") to Ui.MUTED
}

/** Remembers a call started from the app, so its result is asked when the person comes back. */
object CallLog {
    fun started(a: Activity, id: String) = a.getSharedPreferences("leads", 0).edit().putString("calling", id).putLong("callingAt", System.currentTimeMillis()).apply()
    fun take(a: Activity): String? {
        val p = a.getSharedPreferences("leads", 0)
        val id = p.getString("calling", null) ?: return null
        p.edit().remove("calling").apply()
        return if (System.currentTimeMillis() - p.getLong("callingAt", 0) < 3 * 3_600_000L) id else null
    }
}

private fun Activity.pickDateTime(initial: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = if (initial > 0) initial else System.currentTimeMillis() + 86_400_000L; if (initial <= 0) { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0) } }
    DatePickerDialog(this, { _, y, m, d ->
        TimePickerDialog(this, { _, h, min ->
            cal.set(y, m, d, h, min, 0); cal.set(Calendar.MILLISECOND, 0); onPick(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 60_000 }.show()
}

private fun req(label: String): CharSequence = android.text.SpannableStringBuilder(label).apply {
    val s = length; append(" *")
    setSpan(android.text.style.ForegroundColorSpan(0xFFB3261E.toInt()), s, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun android.content.Context.label(t: CharSequence) = text(t, 13f, Ui.MUTED, bold = true)

// ---------------------------------------------------------------- 2 · leads list

class LeadsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PICK = "pick"     // pick a lead to book a visit
        const val EXTRA_EXEC = "exec"     // manager: one executive's leads (mobile)
        const val EXTRA_ALL = "all"       // manager: everyone's leads
    }
    private lateinit var list: LinearLayout
    private var stage = ""
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val pick = intent.getBooleanExtra(EXTRA_PICK, false)
        val body = leadScreen(if (pick) tr("किसकी visit book करें?", "Book a visit for…") else "Leads", tr("Pipeline", "Pipeline"),
            right = listOf(barButton(R.drawable.ic_refresh) { refresh(true) }),
            bottom = pillButton(tr("नई Lead", "New lead"), Ui.SUN, Ui.TEXT, heightDp = 52, iconRes = R.drawable.ic_plus) {
                startActivity(Intent(this, LeadFormActivity::class.java).putExtra(LeadFormActivity.EXTRA_BOOK, pick))
            })
        val search = input(tr("नाम / mobile / area खोजें", "Search name / mobile / area"))
        search.onChange { query = it.trim().lowercase(); render() }
        body.addView(search, lp())
        list = vertical { }
        body.addView(list, lp().margins(this, t = 8))
    }

    override fun onResume() { super.onResume(); render(); refresh(false) }

    private fun source(): List<Lead> {
        val exec = intent.getStringExtra(EXTRA_EXEC)
        val all = LeadStore.all(this)
        return when {
            intent.getBooleanExtra(EXTRA_ALL, false) -> all
            exec != null -> all.filter { it.execMobile.filter { c -> c.isDigit() }.takeLast(10) == exec.filter { c -> c.isDigit() }.takeLast(10) }
            else -> LeadStore.mine(this)
        }
    }

    private fun render() {
        list.removeAllViews()
        val pick = intent.getBooleanExtra(EXTRA_PICK, false)
        var leads = source()
        if (pick) leads = leads.filter { it.isOpen }
        val counts = Lead.STAGES.associateWith { s -> leads.count { it.stage == s } }
        val opts = listOf(tr("सब", "All") + " ${leads.count { it.isOpen }}") + Lead.STAGES.map { Lead.stageLabel(it) + " " + counts[it] }
        list.addView(chips(opts, if (stage.isBlank()) opts[0] else opts[Lead.STAGES.indexOf(stage) + 1]) { p ->
            val i = opts.indexOf(p)
            stage = if (i <= 0) "" else Lead.STAGES[i - 1]
            render()
        }, lp().margins(this, b = 10))
        var shown = leads.filter { if (stage.isBlank()) it.isOpen else it.stage == stage }
        if (query.isNotBlank()) shown = shown.filter { (it.name + " " + it.mobile + " " + it.area).lowercase().contains(query) }
        shown = shown.sortedWith(compareByDescending<Lead> { it.callOverdue }.thenByDescending { it.stage == Lead.NEW }
            .thenBy { listOf(it.nextAt, it.visitAt).filter { t -> t > 0 }.minOrNull() ?: Long.MAX_VALUE }.thenByDescending { it.updatedAt })
        if (shown.isEmpty()) list.addView(card(20) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text(tr("कोई lead नहीं। नई Lead डालें।", "No leads here. Add a new lead."), 14f, Ui.MUTED) { gravity = Gravity.CENTER })
        })
        val team = intent.getBooleanExtra(EXTRA_ALL, false) || intent.getStringExtra(EXTRA_EXEC) != null
        shown.take(300).forEach { l ->
            list.addView(leadCard(l, showExec = team) {
                if (pick) startActivity(Intent(this, BookVisitActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id))
                else startActivity(Intent(this, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id))
            }, lp().margins(this, b = 8))
        }
    }

    private fun refresh(show: Boolean) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { LeadStore.refresh(this@LeadsActivity); LeadStore.syncFromVisits(this@LeadsActivity) } }
            if (isFinishing) return@launch
            render()
            if (show) toast(if (r.isSuccess) tr("Update हो गया", "Updated") else tr("Internet नहीं — phone की list दिख रही है", "No internet: showing the list on this phone"))
        }
    }
}

// ---------------------------------------------------------------- 3 · new / edit lead

class LeadFormActivity : AppCompatActivity() {
    companion object { const val EXTRA_BOOK = "thenBook" }
    private lateinit var lead: Lead
    private var isNew = true
    private lateinit var dupNote: TextView
    private var dupOf: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val id = intent.getStringExtra(LeadActivity.EXTRA_ID)
        lead = id?.let { LeadStore.get(this, it) }?.also { isNew = false } ?: Lead(Lead.newId(this)).apply {
            exec = Officer.name(this@LeadFormActivity); execMobile = Officer.mobile(this@LeadFormActivity)
        }
        lateinit var name: EditText; lateinit var mobile: EditText; lateinit var area: EditText; lateinit var bill: EditText
        lateinit var refBy: EditText; lateinit var notes: EditText
        val refBox = vertical { }
        fun save(thenBook: Boolean) {
            if (name.text.toString().trim().length < 2) { name.error = tr("नाम डालें", "Enter the name"); return }
            if (mobile.text.toString().filter { it.isDigit() }.length < 10) { mobile.error = tr("10 अंक का mobile", "10-digit mobile"); return }
            if (area.text.toString().trim().length < 2) { area.error = tr("Area डालें", "Enter the area"); return }
            if (lead.source.isBlank()) { toast(tr("Lead कहाँ से आई, चुनें", "Choose where the lead came from")); return }
            lead.name = name.text.toString().trim(); lead.mobile = mobile.text.toString().trim(); lead.area = area.text.toString().trim()
            lead.bill = bill.text.toString().trim(); lead.referredBy = if (lead.source == "Referral") refBy.text.toString().trim() else ""
            lead.notes = notes.text.toString().trim()
            if (isNew) lead.add("created", tr("Lead आई · ", "Lead added · ") + lead.source + if (lead.referredBy.isNotBlank()) " (${lead.referredBy})" else "", Officer.name(this))
            else lead.add("edit", tr("Details बदले", "Details changed"), Officer.name(this))
            LeadStore.save(this, lead)
            if (thenBook) startActivity(Intent(this, BookVisitActivity::class.java).putExtra(LeadActivity.EXTRA_ID, lead.id))
            else if (isNew) startActivity(Intent(this, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, lead.id))
            finish()
        }
        val body = leadScreen(if (isNew) tr("नई Lead", "New lead") else tr("Lead बदलें", "Edit lead"), tr("Mobile से duplicate check", "Duplicate check by mobile"),
            bottom = horizontal {
                addView(pillButton("Save", Ui.BRAND_LIGHT, Ui.BRAND, heightDp = 52) { save(false) }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton(tr("Save + Visit book", "Save + book visit"), Ui.SUN, Ui.TEXT, heightDp = 52) { save(true) }, lp(0, WRAP, 1.3f))
            })
        body.addView(text(req(tr("ज़रूरी", "Required")), 12f, Ui.MUTED) { gravity = Gravity.END }, lp())
        body.addView(card(14) {
            name = field(this, tr("ग्राहक का नाम *", "Customer name *"), lead.name, tr("जैसे Anita Kumari", "e.g. Anita Kumari"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person, 80) {}
            mobile = field(this, "Mobile *", lead.mobile, "98xxxxxxxx", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 14) { checkDup(it) }
            dupNote = text("", 12.5f, 0xFF7A4E00.toInt(), bold = true) {
                visibility = View.GONE; background = rounded(Ui.SUN_LIGHT, dpf(10f)); setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { dupOf?.let { startActivity(Intent(this@LeadFormActivity, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, it)) } }
            }
            addView(dupNote, lp().margins(context, t = 6))
            area = field(this, tr("Area / गाँव *", "Area / village *"), lead.area, tr("जैसे Kankarbagh, Patna", "e.g. Kankarbagh, Patna"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_location, 120) {}
            bill = field(this, tr("हर महीने बिजली बिल (₹)", "Monthly electricity bill (₹)"), lead.bill, "2400", InputType.TYPE_CLASS_NUMBER, R.drawable.ic_rupee, 7) {}
        }, lp().margins(this, b = 12))
        body.addView(card(14) {
            addView(label(req(tr("Lead कहाँ से आई", "Where the lead came from"))), lp().margins(context, b = 6))
            addView(chips(Lead.SOURCES, lead.source) { lead.source = it; refBox.visibility = if (it == "Referral") View.VISIBLE else View.GONE })
            refBy = field(refBox, tr("Referral किसने दिया", "Referred by"), lead.referredBy, tr("जैसे Ravi Shankar (customer)", "e.g. Ravi Shankar (customer)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person, 80) {}
            refBox.visibility = if (lead.source == "Referral") View.VISIBLE else View.GONE
            addView(refBox, lp())
        }, lp().margins(this, b = 12))
        body.addView(card(14) {
            addView(label(tr("किसके लिए", "For")), lp().margins(context, b = 6))
            addView(chips(listOf("Home", "Shop / Commercial", "Institution"), lead.type) { lead.type = it })
            addView(label(tr("पैसा", "Payment")), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("PM Surya Ghar", "Loan", "Cash"), lead.finance) { lead.finance = it })
            notes = field(this, "Note", lead.notes, tr("जैसे छत 1200 sq ft, शाम को घर पर", "e.g. roof 1200 sq ft, home in the evening"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 500) {}
        }, lp().margins(this, b = 12))
        if (Officer.teamView(this)) body.addView(card(14) {
            addView(label(tr("Executive (कौन देखेगा)", "Executive (who handles it)")), lp().margins(context, b = 6))
            val team = LeadStore.team(this@LeadFormActivity)
            val opts = listOf(tr("बिना executive", "Unassigned")) + team.map { it.first }
            addView(chips(opts, lead.exec.ifBlank { opts[0] }) { pick ->
                val p = team.firstOrNull { it.first == pick }
                lead.exec = p?.first ?: ""; lead.execMobile = p?.second ?: ""
            })
        })
    }

    private fun checkDup(m: String) {
        val d = m.filter { it.isDigit() }.takeLast(10)
        dupNote.visibility = View.GONE; dupOf = null
        if (d.length < 10) return
        LeadStore.all(this).firstOrNull { it.id != lead.id && it.mobile.filter { c -> c.isDigit() }.takeLast(10) == d }?.let { showDup(it.name, it.stage, it.exec, it.id); return }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Remote.call(this@LeadFormActivity, "leadCheck", JSONObject().put("mobile", d).put("id", lead.id)) } }.getOrNull() ?: return@launch
            val f = r.optJSONObject("found") ?: return@launch
            showDup(f.optString("name"), f.optString("stage"), f.optString("exec"), if (f.optBoolean("mine")) f.optString("id") else null)
        }
    }

    private fun showDup(name: String, stage: String, exec: String, id: String?) {
        dupOf = id
        dupNote.visibility = View.VISIBLE
        dupNote.text = tr("यह number पहले से है: $name · $stage · ${exec.ifBlank { "बिना executive" }} के पास।", "This number already exists: $name · $stage · with ${exec.ifBlank { "nobody yet" }}.") +
            if (id != null) tr("  Lead खोलें ›", "  Open lead ›") else ""
    }
}

// ---------------------------------------------------------------- 4 · lead details (+ 5 · after a call)

class LeadActivity : AppCompatActivity() {
    companion object { const val EXTRA_ID = "leadId" }
    private lateinit var body: LinearLayout
    private lateinit var l: Lead

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        l = LeadStore.get(this, intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        body = leadScreen(l.name, listOf(l.area, l.source).filter { it.isNotBlank() }.joinToString(" · "),
            right = listOf(barButton(R.drawable.ic_edit) { startActivity(Intent(this, LeadFormActivity::class.java).putExtra(EXTRA_ID, l.id)) }),
            bottom = horizontal {
                addView(pillButton(tr("Call का नतीजा", "Call result"), Ui.BRAND_LIGHT, Ui.BRAND, heightDp = 52) { callResult() }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton(tr("Site Visit शुरू", "Start site visit"), Ui.SUN, Ui.TEXT, heightDp = 52) { startVisit() }, lp(0, WRAP, 1.1f))
            })
    }

    override fun onResume() {
        super.onResume()
        LeadStore.syncFromVisits(this)
        l = LeadStore.get(this, l.id) ?: run { finish(); return }
        render()
        // back from a call started here: ask how it went
        if (CallLog.take(this) == l.id) body.post { callResult() }
    }

    private fun save(event: String, kind: String) {
        l.add(kind, event, Officer.name(this))
        LeadStore.save(this, l)
        render()
    }

    private fun render() {
        body.removeAllViews()
        // stage progress
        body.addView(card(14) {
            val steps = listOf(Lead.NEW, Lead.CALLED, Lead.VISIT, Lead.QUOTED, Lead.NEGOTIATION, Lead.BOOKED)
            val at = steps.indexOf(l.stage)
            addView(horizontal {
                steps.forEachIndexed { i, s ->
                    addView(vertical {
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(View(context).apply { background = rounded(if (l.stage != Lead.LOST && i <= at) Ui.BRAND else Ui.LINE, dpf(3f)) }, lp(MATCH, dp(6)))
                        addView(text(when (s) { Lead.VISIT -> "Visit"; Lead.NEGOTIATION -> "Nego."; else -> Lead.stageLabel(s) }, 10.5f,
                            if (l.stage != Lead.LOST && i <= at) Ui.BRAND else Ui.MUTED, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 4))
                    }, lp(0, WRAP, 1f).margins(context, r = 3))
                }
            })
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                addView(stagePill(l.stage))
                val (line, color) = nextLine(l)
                addView(text(line, 12.5f, color, bold = true) { gravity = Gravity.END; maxLines = 2 }, lp(0, WRAP, 1f).margins(context, l = 8))
            }, lp().margins(context, t = 8))
        }, lp().margins(this, b = 10))
        // call / WhatsApp / directions
        body.addView(horizontal {
            addView(pillButton("Call", Ui.BRAND, iconRes = R.drawable.ic_call, heightDp = 46) { CallLog.started(this@LeadActivity, l.id); Talk.call(this@LeadActivity, l.mobile) }, lp(0, WRAP, 1f).margins(context, r = 6))
            addView(pillButton("WhatsApp", Ui.BRAND_LIGHT, Ui.BRAND, iconRes = R.drawable.ic_chat, heightDp = 46) {
                Talk.whatsapp(this@LeadActivity, l.mobile, "Namaste ${l.name} ji, AVIP Solar se ${Officer.name(context)} bol raha hoon.")
            }, lp(0, WRAP, 1f).margins(context, r = 6))
            addView(pillButton(tr("रास्ता", "Directions"), Ui.BRAND_LIGHT, Ui.BRAND, iconRes = R.drawable.ic_location, heightDp = 46) { Talk.directions(this@LeadActivity, l) }, lp(0, WRAP, 1f))
        }, lp().margins(this, b = 10))
        // visit
        if (l.stage == Lead.VISIT && l.visitAt > 0) body.addView(card(14) {
            background = rounded(Ui.SUN_LIGHT, dpf(16f), Ui.SUN, dp(1))
            addView(text(tr("Visit: ", "Visit: ") + When.short(l.visitAt), 16f, Ui.TEXT, bold = true))
            addView(text(l.visitAddress.ifBlank { l.area } + " · " + l.exec, 13f, Ui.MUTED), lp().margins(context, t = 2))
            addView(horizontal {
                addView(text(tr("समय बदलें", "Change time"), 13.5f, Ui.BRAND, bold = true) {
                    setPadding(0, dp(10), dp(16), dp(4))
                    setOnClickListener { startActivity(Intent(this@LeadActivity, BookVisitActivity::class.java).putExtra(EXTRA_ID, l.id)) }
                })
                addView(text(tr("Customer को WhatsApp", "WhatsApp the customer"), 13.5f, Ui.BRAND, bold = true) {
                    setPadding(0, dp(10), 0, dp(4))
                    setOnClickListener { Talk.whatsapp(this@LeadActivity, l.mobile, BookVisitActivity.message(this@LeadActivity, l)) }
                })
            })
        }, lp().margins(this, b = 10))
        // details
        body.addView(card(14) {
            addView(kv("Mobile", l.mobile, bold = true))
            if (l.bill.isNotBlank()) addView(kv(tr("बिजली बिल", "Electricity bill"), "₹" + l.bill + tr(" / माह", " / month")))
            if (l.type.isNotBlank() || l.finance.isNotBlank()) addView(kv(tr("किसके लिए", "For"), listOf(l.type, l.finance).filter { it.isNotBlank() }.joinToString(" · ")))
            addView(kv("Executive", l.exec.ifBlank { tr("बिना executive", "Unassigned") }))
            if (l.referredBy.isNotBlank()) addView(kv("Referral", l.referredBy))
            if (l.visitNumber.isNotBlank()) addView(kv("Site visit", l.visitNumber))
            if (l.notes.isNotBlank()) addView(text(l.notes, 13.5f, Ui.TEXT), lp().margins(context, t = 6))
        }, lp().margins(this, b = 10))
        // more actions
        body.addView(horizontal {
            if (l.stage == Lead.QUOTED) addView(text(tr("Negotiation में", "Mark negotiation"), 13.5f, Ui.BRAND, bold = true) {
                setPadding(dp(4), dp(10), dp(16), dp(10))
                setOnClickListener { l.stage = Lead.NEGOTIATION; save(tr("Stage: Negotiation", "Stage: Negotiation"), "stage") }
            })
            if (l.isOpen) addView(text(tr("Visit book करें", "Book a visit"), 13.5f, Ui.BRAND, bold = true) {
                setPadding(dp(4), dp(10), dp(16), dp(10))
                setOnClickListener { startActivity(Intent(this@LeadActivity, BookVisitActivity::class.java).putExtra(EXTRA_ID, l.id)) }
            })
            if (l.isOpen) addView(text(tr("Lost करें", "Mark lost"), 13.5f, 0xFFB3261E.toInt(), bold = true) {
                setPadding(dp(4), dp(10), dp(16), dp(10)); setOnClickListener { markLost() }
            })
            if (l.stage == Lead.LOST) addView(text(tr("फिर से खोलें", "Reopen"), 13.5f, Ui.BRAND, bold = true) {
                setPadding(dp(4), dp(10), dp(16), dp(10))
                setOnClickListener { l.stage = Lead.CALLED; l.lostReason = ""; save(tr("फिर से खोला", "Reopened"), "stage") }
            })
        }, lp().margins(this, b = 6))
        // history
        body.addView(card(14) {
            addView(text("History", 15f, Ui.TEXT, bold = true))
            if (l.history.isEmpty()) addView(text(tr("अभी कुछ नहीं", "Nothing yet"), 13f, Ui.MUTED))
            l.history.sortedByDescending { it.at }.take(40).forEach { e ->
                addView(horizontal {
                    setPadding(0, dp(8), 0, dp(4))
                    val c = when (e.kind) { "call" -> 0xFF1565C0.toInt(); "visit" -> Ui.SUN; "lost" -> 0xFFB3261E.toInt(); "created" -> Ui.MUTED; else -> Ui.BRAND }
                    addView(View(context).apply { background = circle(c) }, lp(dp(10), dp(10)).margins(context, t = 5, r = 10))
                    addView(vertical {
                        addView(text(e.text, 13.5f, Ui.TEXT))
                        addView(text(When.short(e.at) + " · " + e.by, 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                })
            }
        })
    }

    private fun startVisit() {
        val existing = VisitStore.all(this).firstOrNull { it.leadId == l.id }
        if (existing != null) startActivity(Intent(this, VisitActivity::class.java).putExtra(VisitActivity.EXTRA_NUMBER, existing.number))
        else {
            l.add("visit", tr("Site visit शुरू", "Site visit started"), Officer.name(this)); LeadStore.save(this, l)
            startActivity(Intent(this, VisitActivity::class.java).putExtra(VisitActivity.EXTRA_LEAD, l.id))
        }
    }

    /** 5 · After a call: Interested → book visit · call back · not reachable · not interested. */
    private fun callResult() {
        val opts = listOf(
            tr("Interested — visit book करें", "Interested: book a visit"),
            tr("बाद में call करें", "Call back later"),
            tr("Call नहीं लगा / नहीं उठाया", "Not reachable / no answer"),
            tr("Interested नहीं", "Not interested"),
        )
        var pick = -1
        val note = input(tr("Note (अगर हो)", "Note (optional)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val box = vertical(18) { }
        val rows = vertical { }
        opts.forEachIndexed { i, o ->
            rows.addView(text(o, 15f, Ui.TEXT, bold = true) {
                background = rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1)); setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    pick = i
                    for (k in 0 until rows.childCount) (rows.getChildAt(k) as TextView).background =
                        if (k == i) rounded(Ui.BRAND_LIGHT, dpf(12f), Ui.BRAND, dp(2)) else rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1))
                }
            }, lp().margins(this, b = 6))
        }
        box.addView(rows, lp())
        box.addView(note, lp().margins(this, t = 6))
        val d = AlertDialog.Builder(this).setTitle(req(tr("Call का नतीजा", "Result of the call"))).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pick < 0) { toast(tr("नतीजा चुनें", "Pick the result")); return@setOnClickListener }
                val n = note.text.toString().trim()
                val suffix = if (n.isNotBlank()) " · $n" else ""
                d.dismiss()
                if (l.stage == Lead.NEW) l.stage = Lead.CALLED
                when (pick) {
                    0 -> { l.nextAt = 0; l.nextNote = ""; save(tr("Call: Interested", "Call: interested") + suffix, "call")
                        startActivity(Intent(this, BookVisitActivity::class.java).putExtra(EXTRA_ID, l.id)) }
                    1 -> pickDateTime(0) { t -> l.nextAt = t; l.nextNote = n; save(tr("Call: बाद में call · ", "Call: call back · ") + When.short(t) + suffix, "call") }
                    2 -> { val t = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0) }.timeInMillis
                        l.nextAt = t; l.nextNote = tr("फिर call करें", "Try again"); save(tr("Call नहीं लगा · कल 10 बजे फिर", "Not reachable · try tomorrow 10 am") + suffix, "call") }
                    3 -> markLost(n)
                }
            }
        }
        d.show()
    }

    private fun markLost(note: String = "") {
        var reason = ""
        val box = vertical(18) {
            addView(chips(Lead.LOST_REASONS, "") { reason = it })
        }
        val d = AlertDialog.Builder(this).setTitle(req(tr("Lost का कारण", "Why was it lost?"))).setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (reason.isBlank()) { toast(tr("कारण चुनें", "Pick a reason")); return@setOnClickListener }
                d.dismiss()
                l.stage = Lead.LOST; l.lostReason = reason; l.nextAt = 0
                save("Lost: $reason" + if (note.isNotBlank()) " · $note" else "", "lost")
            }
        }
        d.show()
    }
}

// ---------------------------------------------------------------- 6 · book a visit

class BookVisitActivity : AppCompatActivity() {
    companion object {
        /** WhatsApp confirmation for the customer (Roman Hindi, works on any phone). */
        fun message(a: Activity, l: Lead): String {
            val d = SimpleDateFormat("EEE, dd MMM 'ko' hh:mm a", Locale.ENGLISH).format(Date(l.visitAt))
            val who = l.exec.ifBlank { Officer.name(a) }
            val mob = if (l.execMobile.isNotBlank()) l.execMobile else Officer.mobile(a)
            return "Namaste ${l.name} ji,\nAVIP Solar se $who aapke yahan $d solar site visit ke liye aayenge.\n" +
                (if (l.visitAddress.isNotBlank()) "Pata: ${l.visitAddress}\n" else "") +
                "Kripya bijli ka ek bill taiyar rakhein.\nKoi badlav ho to call karein: $mob\n– AVIP Solar, 92881 76289"
        }
    }
    private lateinit var l: Lead
    private var day = 0L
    private var hour = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        l = LeadStore.get(this, intent.getStringExtra(LeadActivity.EXTRA_ID) ?: "") ?: run { finish(); return }
        if (l.visitAt > 0) { day = Calendar.getInstance().apply { timeInMillis = l.visitAt; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis; hour = Calendar.getInstance().apply { timeInMillis = l.visitAt }.get(Calendar.HOUR_OF_DAY) }
        else day = Lead.startOfToday()
        build()
    }

    private lateinit var addr: EditText
    private var execName = ""; private var execMobile = ""

    private fun build() {
        if (execName.isBlank()) { execName = l.exec.ifBlank { Officer.name(this) }; execMobile = l.execMobile.ifBlank { Officer.mobile(this) } }
        val label = if (hour >= 0) tr("Book · ", "Book · ") + SimpleDateFormat("EEE dd MMM", Locale.ENGLISH).format(Date(day)) + ", " + slot(hour) else tr("दिन और समय चुनें", "Pick the day and time")
        val body = leadScreen(tr("Visit book करें", "Book a visit"), listOf(l.name, l.area).filter { it.isNotBlank() }.joinToString(" · "),
            bottom = pillButton(label, if (hour >= 0) Ui.BRAND else Ui.LINE, if (hour >= 0) Color.WHITE else Ui.MUTED, heightDp = 52) { book() })
        val oldAddr = if (::addr.isInitialized) addr.text.toString() else l.visitAddress.ifBlank { l.area }
        // day
        body.addView(card(14) {
            addView(text(req(tr("दिन", "Day")), 15f, Ui.TEXT, bold = true), lp().margins(context, b = 8))
            addView(android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(horizontal {
                    for (i in 0 until 14) {
                        val d = Lead.startOfToday() + i * 86_400_000L
                        val on = d == day
                        addView(vertical {
                            gravity = Gravity.CENTER
                            background = if (on) rounded(Ui.BRAND, dpf(12f)) else rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1))
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            addView(text(if (i == 0) tr("आज", "Today") else SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(d)), 11f, if (on) Color.WHITE else Ui.MUTED, bold = true))
                            addView(text(SimpleDateFormat("d", Locale.ENGLISH).format(Date(d)), 17f, if (on) Color.WHITE else Ui.TEXT, bold = true))
                            isClickable = true
                            setOnClickListener { day = d; hour = -1; build() }
                        }, lp(dp(58), WRAP).margins(context, r = 6))
                    }
                })
            })
        }, lp().margins(this, b = 10))
        // time
        val busy = busyHours()
        body.addView(card(14) {
            addView(text(req(tr("समय", "Time")), 15f, Ui.TEXT, bold = true), lp().margins(context, b = 8))
            (9..19).chunked(4).forEach { row ->
                addView(horizontal {
                    row.forEach { h ->
                        val past = day + h * 3_600_000L < System.currentTimeMillis()
                        val taken = h in busy
                        val on = h == hour
                        addView(text(slot(h), 13.5f, if (on) Color.WHITE else if (taken || past) Ui.MUTED else Ui.TEXT, bold = true) {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(10), 0, dp(10))
                            background = when { on -> rounded(Ui.BRAND, dpf(12f)); taken || past -> rounded(0xFFEEF1EF.toInt(), dpf(12f)); else -> rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1)) }
                            if (taken) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            isClickable = !past
                            setOnClickListener {
                                if (taken) toast(tr("उस समय $execName की दूसरी visit है", "$execName has another visit then"))
                                hour = h; build()
                            }
                        }, lp(0, WRAP, 1f).margins(context, r = 6, b = 6))
                    }
                    repeat(4 - row.size) { addView(View(context), lp(0, 1, 1f).margins(context, r = 6)) }
                })
            }
            addView(text(tr("कटा हुआ समय: उस समय दूसरी visit है।", "Struck-out time: another visit is booked then."), 11.5f, Ui.MUTED))
        }, lp().margins(this, b = 10))
        body.addView(card(14) {
            addr = field(this, tr("पता / landmark *", "Address / landmark *"), oldAddr, tr("जैसे Bihta bazar, Durga mandir के पास", "e.g. Bihta bazar, near Durga mandir"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_location, 300) {}
        }, lp().margins(this, b = 10))
        if (Officer.teamView(this)) body.addView(card(14) {
            addView(text(tr("कौन जाएगा", "Who will go"), 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
            val team = LeadStore.team(this@BookVisitActivity).ifEmpty { listOf(Officer.name(context) to Officer.mobile(context)) }
            addView(chips(team.map { it.first }, execName) { p -> team.firstOrNull { it.first == p }?.let { execName = it.first; execMobile = it.second; build() } })
        }, lp().margins(this, b = 10))
        body.addView(text(tr("Reminder: visit से 1 घंटा पहले। Book के बाद customer को WhatsApp भेज सकते हैं।", "Reminder 1 hour before the visit. After booking you can WhatsApp the customer."), 12.5f, Ui.MUTED))
    }

    private fun slot(h: Int) = String.format(Locale.ENGLISH, "%d:00 %s", if (h % 12 == 0) 12 else h % 12, if (h < 12) "am" else "pm")

    /** Hours on the chosen day when this executive already has another visit. */
    private fun busyHours(): Set<Int> {
        val me = execMobile.filter { it.isDigit() }.takeLast(10)
        return LeadStore.all(this).filter { it.id != l.id && it.stage == Lead.VISIT && it.visitAt in day until day + 86_400_000L &&
            it.execMobile.filter { c -> c.isDigit() }.takeLast(10) == me }
            .map { Calendar.getInstance().apply { timeInMillis = it.visitAt }.get(Calendar.HOUR_OF_DAY) }.toSet()
    }

    private fun book() {
        if (hour < 0) { toast(tr("समय चुनें", "Pick a time")); return }
        if (addr.text.toString().trim().length < 3) { addr.error = tr("पता डालें", "Enter the address"); return }
        val changed = l.visitAt > 0
        l.visitAt = day + hour * 3_600_000L
        l.visitAddress = addr.text.toString().trim()
        if (Officer.teamView(this)) { l.exec = execName; l.execMobile = execMobile }
        if (Lead.STAGES.indexOf(l.stage) < Lead.STAGES.indexOf(Lead.VISIT) || l.stage == Lead.LOST) l.stage = Lead.VISIT
        l.nextAt = 0; l.nextNote = ""
        l.add("visit", (if (changed) tr("Visit का समय बदला: ", "Visit moved to: ") else tr("Visit book: ", "Visit booked: ")) + When.short(l.visitAt) + " · " + l.exec, Officer.name(this))
        LeadStore.save(this, l)
        AlertDialog.Builder(this).setTitle(tr("Visit book हो गई", "Visit booked"))
            .setMessage(tr("Customer को WhatsApp पर confirmation भेजें?", "Send the customer a WhatsApp confirmation?"))
            .setPositiveButton(tr("WhatsApp भेजें", "Send WhatsApp")) { _, _ -> Talk.whatsapp(this, l.mobile, message(this, l)); finish() }
            .setNegativeButton(tr("नहीं", "No")) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}

// ---------------------------------------------------------------- 7 · my schedule

class ScheduleActivity : AppCompatActivity() {
    private var day = Lead.startOfToday()
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        body = leadScreen(tr("मेरी Schedule", "My schedule"), tr("Visits और call-backs", "Visits and call-backs"),
            bottom = pillButton(tr("Visit book करें", "Book a visit"), Ui.BRAND, heightDp = 52, iconRes = R.drawable.ic_plus) {
                startActivity(Intent(this, LeadsActivity::class.java).putExtra(LeadsActivity.EXTRA_PICK, true))
            })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        body.removeAllViews()
        val mine = LeadStore.mine(this).filter { it.isOpen }
        body.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(horizontal {
                for (i in 0 until 14) {
                    val d = Lead.startOfToday() + i * 86_400_000L
                    val on = d == day
                    val n = mine.count { (it.stage == Lead.VISIT && it.visitAt in d until d + 86_400_000L) || it.nextAt in d until d + 86_400_000L }
                    addView(vertical {
                        gravity = Gravity.CENTER
                        background = if (on) rounded(Ui.BRAND, dpf(12f)) else rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        addView(text(if (i == 0) tr("आज", "Today") else SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(d)), 11f, if (on) Color.WHITE else Ui.MUTED, bold = true))
                        addView(text(SimpleDateFormat("d", Locale.ENGLISH).format(Date(d)), 17f, if (on) Color.WHITE else Ui.TEXT, bold = true))
                        addView(text(if (n > 0) "• $n" else " ", 10.5f, if (on) Color.WHITE else Ui.BRAND, bold = true))
                        isClickable = true
                        setOnClickListener { day = d; render() }
                    }, lp(dp(56), WRAP).margins(context, r = 6))
                }
            })
        }, lp().margins(this, b = 10))
        data class Item(val at: Long, val l: Lead, val visit: Boolean)
        val items = mine.flatMap { l ->
            listOfNotNull(
                if (l.stage == Lead.VISIT && l.visitAt in day until day + 86_400_000L) Item(l.visitAt, l, true) else null,
                if (l.nextAt in day until day + 86_400_000L) Item(l.nextAt, l, false) else null,
            )
        }.sortedBy { it.at }
        val overdue = if (day == Lead.startOfToday()) mine.filter { it.callOverdue } else emptyList()
        body.addView(text(SimpleDateFormat("EEEE, d MMM", Locale.ENGLISH).format(Date(day)) + " · " +
            tr("${items.count { it.visit }} visit · ${items.count { !it.visit }} call", "${items.count { it.visit }} visits · ${items.count { !it.visit }} calls"), 13.5f, Ui.TEXT, bold = true), lp().margins(this, b = 8))
        if (overdue.isNotEmpty()) {
            body.addView(text(tr("पुराने call बाकी (${overdue.size})", "Overdue calls (${overdue.size})"), 14f, 0xFFB3261E.toInt(), bold = true), lp().margins(this, b = 6))
            overdue.forEach { l -> body.addView(leadCard(l) { open(l) }, lp().margins(this, b = 8)) }
        }
        if (items.isEmpty() && overdue.isEmpty()) body.addView(card(20) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text(tr("इस दिन कुछ नहीं। Visit book करें।", "Nothing on this day. Book a visit."), 14f, Ui.MUTED) { gravity = Gravity.CENTER })
        })
        items.forEach { it2 ->
            val color = if (it2.visit) Ui.SUN else 0xFF1565C0.toInt()
            body.addView(horizontal {
                addView(text(SimpleDateFormat("hh:mm", Locale.ENGLISH).format(Date(it2.at)) + "\n" + SimpleDateFormat("a", Locale.ENGLISH).format(Date(it2.at)), 12.5f, Ui.MUTED, bold = true) { setPadding(0, dp(10), 0, 0) }, lp(dp(52), WRAP))
                addView(card(12) {
                    isClickable = true
                    foreground = context.ripple()
                    addView(horizontal {
                        addView(View(context).apply { background = rounded(color, dpf(2f)) }, lp(dp(4), MATCH).margins(context, r = 10))
                        addView(vertical {
                            addView(text((if (it2.visit) "Visit · " else "Call back · ") + it2.l.name, 14.5f, Ui.TEXT, bold = true))
                            addView(text(if (it2.visit) it2.l.visitAddress.ifBlank { it2.l.area } else listOf(it2.l.area, it2.l.nextNote).filter { s -> s.isNotBlank() }.joinToString(" · "), 12.5f, Ui.MUTED))
                        }, lp(0, WRAP, 1f))
                        addView(round(if (it2.visit) R.drawable.ic_location else R.drawable.ic_call, Ui.BRAND, Ui.BRAND_LIGHT, if (it2.visit) "Directions" else "Call") {
                            if (it2.visit) Talk.directions(this@ScheduleActivity, it2.l) else { CallLog.started(this@ScheduleActivity, it2.l.id); Talk.call(this@ScheduleActivity, it2.l.mobile) }
                        }, lp(dp(42), dp(42)))
                    })
                    setOnClickListener { open(it2.l) }
                }, lp(0, WRAP, 1f))
            }, lp().margins(this, b = 8))
        }
    }

    private fun open(l: Lead) = startActivity(Intent(this, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id))
}

// ---------------------------------------------------------------- 8 · manager: team leads

class TeamLeadsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        if (!Officer.teamView(this)) { finish(); return }
        body = leadScreen("Team Leads", tr("Assign और pipeline", "Assign and pipeline"),
            right = listOf(barButton(R.drawable.ic_refresh) { refresh() }),
            bottom = horizontal {
                addView(pillButton(tr("सारी Leads", "All leads"), Ui.BRAND_LIGHT, Ui.BRAND, heightDp = 52) {
                    startActivity(Intent(this@TeamLeadsActivity, LeadsActivity::class.java).putExtra(LeadsActivity.EXTRA_ALL, true))
                }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton(tr("नई Lead", "New lead"), Ui.SUN, Ui.TEXT, heightDp = 52, iconRes = R.drawable.ic_plus) {
                    startActivity(Intent(this@TeamLeadsActivity, LeadFormActivity::class.java))
                }, lp(0, WRAP, 1f))
            })
    }

    override fun onResume() { super.onResume(); render(); refresh() }

    private fun refresh() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { LeadStore.refresh(this@TeamLeadsActivity) } }
            if (!isFinishing) render()
        }
    }

    private fun render() {
        body.removeAllViews()
        val all = LeadStore.all(this)
        val unassigned = all.filter { it.execMobile.isBlank() && it.isOpen }.sortedByDescending { it.createdAt }
        body.addView(card(14) {
            addView(horizontal {
                addView(text(tr("बिना executive की Leads (${unassigned.size})", "Unassigned leads (${unassigned.size})"), 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            })
            if (unassigned.isEmpty()) addView(text(tr("सब leads assign हैं ✓", "All leads are assigned ✓"), 13f, Ui.MUTED), lp().margins(context, t = 4))
            unassigned.take(30).forEach { l ->
                addView(horizontal {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                    addView(vertical {
                        addView(text(l.name, 14f, Ui.TEXT, bold = true))
                        addView(text(listOf(l.area, l.source, When.ago(l.createdAt)).filter { it.isNotBlank() }.joinToString(" · "), 12f, Ui.MUTED))
                        setOnClickListener { startActivity(Intent(this@TeamLeadsActivity, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id)) }
                    }, lp(0, WRAP, 1f))
                    addView(pillButton("Assign", Ui.BRAND, heightDp = 38) { assign(l) })
                })
            }
        }, lp().margins(this, b = 10))
        // per executive
        val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val byExec = all.filter { it.execMobile.isNotBlank() }.groupBy { it.execMobile.filter { c -> c.isDigit() }.takeLast(10) }
        body.addView(card(14) {
            addView(text(tr("Executive pipeline", "Executive pipeline"), 15f, Ui.TEXT, bold = true), lp().margins(context, b = 6))
            addView(row("Executive", tr("खुली", "Open"), "Visit", "Quote", tr("Booked*", "Booked*"), Ui.MUTED, true))
            byExec.entries.sortedByDescending { e -> e.value.count { it.isOpen } }.forEach { (mob, ls) ->
                addView(row(ls.first().exec.substringBefore(' '), "${ls.count { it.isOpen }}", "${ls.count { it.stage == Lead.VISIT }}",
                    "${ls.count { it.stage == Lead.QUOTED || it.stage == Lead.NEGOTIATION }}", "${ls.count { it.stage == Lead.BOOKED && it.updatedAt >= monthStart }}", Ui.TEXT, false).apply {
                    isClickable = true; foreground = context.ripple()
                    setOnClickListener { startActivity(Intent(this@TeamLeadsActivity, LeadsActivity::class.java).putExtra(LeadsActivity.EXTRA_EXEC, mob)) }
                })
            }
            addView(text(tr("* इस महीने · tap करके उस executive की leads", "* this month · tap a row for that executive's leads"), 11.5f, Ui.MUTED), lp().margins(context, t = 6))
        }, lp().margins(this, b = 10))
        val overdue = all.filter { it.callOverdue }
        if (overdue.isNotEmpty()) body.addView(card(14) {
            background = rounded(Color.WHITE, dpf(16f), 0xFFF2C4BF.toInt(), dp(1))
            addView(text(tr("${overdue.size} follow-up overdue", "${overdue.size} follow-ups overdue"), 15f, 0xFFB3261E.toInt(), bold = true))
            addView(text(overdue.groupBy { it.exec.ifBlank { "—" } }.entries.joinToString(" · ") { it.key.substringBefore(' ') + " " + it.value.size }, 12.5f, Ui.MUTED))
        }, lp().margins(this, b = 10))
        val lost = all.filter { it.stage == Lead.LOST && it.updatedAt >= monthStart }
        if (lost.isNotEmpty()) body.addView(card(14) {
            addView(text(tr("इस महीने Lost के कारण", "Why leads were lost this month"), 15f, Ui.TEXT, bold = true))
            lost.groupBy { it.lostReason.ifBlank { "Other" } }.entries.sortedByDescending { it.value.size }.forEach { addView(kv(it.key, "${it.value.size}")) }
        })
    }

    private fun row(a: String, b: String, c: String, d: String, e: String, color: Int, head: Boolean): View = horizontal {
        setPadding(0, dp(8), 0, dp(8))
        val sz = if (head) 11.5f else 13.5f
        addView(text(a, sz, color, bold = true) { maxLines = 1 }, lp(0, WRAP, 1.5f))
        listOf(b, c, d, e).forEach { v -> addView(text(v, sz, if (!head && v == e) Ui.BRAND else color, bold = head || v == e) { gravity = Gravity.CENTER }, lp(0, WRAP, 1f)) }
    }

    private fun assign(l: Lead) {
        val team = LeadStore.team(this)
        if (team.isEmpty()) { toast(tr("Team list नहीं मिली — refresh करें", "Team list not loaded: refresh first")); return }
        AlertDialog.Builder(this).setTitle(tr("${l.name} किसे दें?", "Assign ${l.name} to…"))
            .setItems(team.map { it.first }.toTypedArray()) { _, i ->
                l.exec = team[i].first; l.execMobile = team[i].second
                l.add("assign", tr("Assign: ", "Assigned to ") + l.exec, Officer.name(this))
                LeadStore.save(this, l)
                toast(tr("${l.exec} को दी गई", "Assigned to ${l.exec}"))
                render()
            }.show()
    }
}
