package com.avip.quotationmaker

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Hindi-English or English, for the lead screens (Hindi-English is the default). */
fun tr(hi: String, en: String) = if (Lang.english) en else hi

/** One entry in a lead's history (call, visit booked, stage change …). */
data class LeadEvent(val id: String, val at: Long, val by: String, val kind: String, val text: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("by", by).put("kind", kind).put("text", text)
    companion object {
        fun from(o: JSONObject) = LeadEvent(o.optString("id"), o.optLong("at"), o.optString("by"), o.optString("kind"), o.optString("text"))
    }
}

/** An enquiry, from the first call to booking. Kept as JSON on the phone and in the office "Leads" tab. */
class Lead(val id: String) {
    var createdAt = System.currentTimeMillis()
    var name = ""; var mobile = ""; var area = ""; var bill = ""
    var source = ""; var referredBy = ""; var type = ""; var finance = ""; var notes = ""
    var stage = NEW; var lostReason = ""
    var exec = ""; var execMobile = ""
    var nextNote = ""; var nextAt = 0L
    var visitAt = 0L; var visitAddress = ""; var visitLat = 0.0; var visitLng = 0.0; var visitNumber = ""
    var updatedAt = 0L; var createdBy = ""
    val history = mutableListOf<LeadEvent>()

    val isOpen get() = stage != LOST && stage != BOOKED
    val callDue get() = isOpen && nextAt > 0 && nextAt <= endOfToday()
    val callOverdue get() = isOpen && nextAt > 0 && nextAt < startOfToday()

    fun add(kind: String, text: String, by: String) {
        history.add(LeadEvent("e" + System.currentTimeMillis() + (10..99).random(), System.currentTimeMillis(), by, kind, text))
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("createdAt", createdAt).put("name", name).put("mobile", mobile)
        .put("area", area).put("bill", bill).put("source", source).put("referredBy", referredBy).put("type", type)
        .put("finance", finance).put("notes", notes).put("stage", stage).put("lostReason", lostReason)
        .put("exec", exec).put("execMobile", execMobile).put("nextNote", nextNote).put("nextAt", nextAt)
        .put("visitAt", visitAt).put("visitAddress", visitAddress).put("visitLat", visitLat).put("visitLng", visitLng)
        .put("visitNumber", visitNumber).put("updatedAt", updatedAt).put("createdBy", createdBy)
        .put("history", JSONArray().apply { history.forEach { put(it.json()) } })

    companion object {
        const val NEW = "New"; const val CALLED = "Called"; const val VISIT = "Visit booked"; const val QUOTED = "Quoted"
        const val NEGOTIATION = "Negotiation"; const val BOOKED = "Booked"; const val LOST = "Lost"
        val STAGES = listOf(NEW, CALLED, VISIT, QUOTED, NEGOTIATION, BOOKED, LOST)
        val SOURCES = listOf("Facebook", "WhatsApp", "Call", "Referral", "Walk-in", "Website", "Patna list")
        val LOST_REASONS = listOf("Price high", "Chose competitor", "No roof / shade", "Loan not possible", "Not now", "Wrong number", "Other")

        fun from(o: JSONObject) = Lead(o.optString("id")).apply {
            createdAt = o.optLong("createdAt", System.currentTimeMillis()); name = o.optString("name"); mobile = o.optString("mobile")
            area = o.optString("area"); bill = o.optString("bill"); source = o.optString("source"); referredBy = o.optString("referredBy")
            type = o.optString("type"); finance = o.optString("finance"); notes = o.optString("notes")
            stage = o.optString("stage", NEW).ifBlank { NEW }; lostReason = o.optString("lostReason")
            exec = o.optString("exec"); execMobile = o.optString("execMobile").trimStart('\'')
            nextNote = o.optString("nextNote"); nextAt = o.optLong("nextAt"); visitAt = o.optLong("visitAt")
            visitAddress = o.optString("visitAddress"); visitLat = o.optDouble("visitLat", 0.0); visitLng = o.optDouble("visitLng", 0.0)
            visitNumber = o.optString("visitNumber"); updatedAt = o.optLong("updatedAt"); createdBy = o.optString("createdBy")
            val h = o.optJSONArray("history")
            for (i in 0 until (h?.length() ?: 0)) history.add(LeadEvent.from(h!!.getJSONObject(i)))
        }

        fun newId(c: Context): String = "LD-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()) + "-" +
            (Officer.code(c).ifBlank { Officer.mobile(c).takeLast(4) }).uppercase().replace(Regex("[^A-Z0-9]"), "")

        fun stageLabel(s: String) = when (s) {
            NEW -> "New"; CALLED -> "Called"; VISIT -> tr("Visit booked", "Visit booked"); QUOTED -> "Quoted"
            NEGOTIATION -> "Negotiation"; BOOKED -> "Booked"; LOST -> "Lost"; else -> s
        }

        /** background, text colour */
        fun stageColors(s: String): Pair<Int, Int> = when (s) {
            NEW -> 0xFFE6EFFA.toInt() to 0xFF1565C0.toInt()
            CALLED -> 0xFFEEF1EF.toInt() to Ui.TEXT
            VISIT -> 0xFFFFF4E0.toInt() to 0xFF7A4E00.toInt()
            QUOTED, NEGOTIATION -> 0xFFF1EBF8.toInt() to 0xFF6B3FA0.toInt()
            BOOKED -> Ui.BRAND_LIGHT to Ui.BRAND
            else -> 0xFFFCE8E6.toInt() to 0xFFB3261E.toInt()
        }

        fun startOfToday(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun endOfToday(): Long = startOfToday() + 86_400_000L - 1
    }
}

/**
 * Leads on this phone: the last list from the office, plus changes made here that are still
 * waiting in the upload queue (so nothing typed offline disappears on refresh).
 */
object LeadStore {
    private const val PREFS = "leads"

    fun all(c: Context): List<Lead> {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("list", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { Lead.from(arr.getJSONObject(it)) }
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    private fun write(c: Context, list: List<Lead>) {
        val arr = JSONArray(); list.forEach { arr.put(it.json()) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    /** Saves a lead on the phone at once and queues it for the office (sent when there is internet). */
    fun save(c: Context, l: Lead) {
        l.updatedAt = System.currentTimeMillis()
        if (l.createdBy.isBlank()) l.createdBy = Officer.name(c)
        val list = all(c).filter { it.id != l.id } + l
        write(c, list)
        Remote.queue(c, JSONObject().put("kind", "leadSave").put("lead", l.json()))
        LeadAlarms.schedule(c, l)
    }

    /** True while a change of this lead is still waiting to be sent. */
    private fun waiting(c: Context): Map<String, JSONObject> = Remote.pending(c)
        .filter { it.optString("kind") == "leadSave" }
        .associate { it.getJSONObject("lead").optString("id") to it.getJSONObject("lead") }

    /** New list from the office; local changes not yet sent win. Blocking: off the main thread. */
    fun refresh(c: Context): JSONObject {
        val r = Remote.call(c, "leads")
        val arr = r.optJSONArray("leads") ?: JSONArray()
        val server = (0 until arr.length()).map { Lead.from(arr.getJSONObject(it)) }
        val local = waiting(c)
        val merged = server.map { s -> local[s.id]?.let { Lead.from(it) } ?: s } +
            local.filterKeys { k -> server.none { it.id == k } }.values.map { Lead.from(it) }
        write(c, merged)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("team", (r.optJSONArray("team") ?: JSONArray()).toString())
            .putLong("refreshedAt", System.currentTimeMillis()).apply()
        merged.forEach { LeadAlarms.schedule(c, it) }
        return r
    }

    /** Sales people the manager can assign leads to (from the last refresh). */
    fun team(c: Context): List<Pair<String, String>> {
        val arr = try { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("team", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("name") to it.optString("mobile") }
    }

    /** Leads of this phone's user (managers see everyone's on the Team screen). */
    fun mine(c: Context): List<Lead> {
        val me = Officer.mobile(c).filter { it.isDigit() }.takeLast(10)
        return all(c).filter { l -> l.execMobile.filter { it.isDigit() }.takeLast(10) == me || (l.execMobile.isBlank() && l.createdBy == Officer.name(c)) }
    }

    /** A site visit started from a lead moves it on here at once (the office does the same). */
    fun syncFromVisits(c: Context) {
        val visits = VisitStore.all(c).filter { it.leadId.isNotBlank() }
        if (visits.isEmpty()) return
        val list = all(c)
        var changed = false
        for (v in visits) {
            val l = list.firstOrNull { it.id == v.leadId } ?: continue
            val next = when {
                v.leadStatus == "Converted" -> Lead.BOOKED
                v.leadStatus == "Lost" -> Lead.LOST
                v.isQuoted && Lead.STAGES.indexOf(l.stage) < Lead.STAGES.indexOf(Lead.QUOTED) -> Lead.QUOTED
                else -> l.stage
            }
            if (next != l.stage || l.visitNumber != v.number) {
                if (next != l.stage) l.add("stage", "Stage: $next (site visit ${v.number})", Officer.name(c))
                l.stage = next; l.visitNumber = v.number
                if (next == Lead.BOOKED || next == Lead.LOST) l.nextAt = 0
                changed = true
            }
        }
        if (changed) write(c, list)
    }

    fun refreshedAt(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("refreshedAt", 0L)
}

/** Reminders: a call-back at its time, and a visit one hour before. */
object LeadAlarms {
    private const val CHANNEL = "leads"
    const val EXTRA_ID = "leadId"
    const val EXTRA_WHAT = "what"

    private fun pi(c: Context, id: String, what: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        c, (id + what).hashCode(),
        Intent(c, LeadReminderReceiver::class.java).setAction("com.avip.quotationmaker.LEAD").putExtra(EXTRA_ID, id).putExtra(EXTRA_WHAT, what),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun set(c: Context, id: String, what: String, at: Long) {
        cancel(c, id, what)
        if (at <= System.currentTimeMillis()) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(c, id, what, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    private fun cancel(c: Context, id: String, what: String) {
        val p = pi(c, id, what, PendingIntent.FLAG_NO_CREATE) ?: return
        c.getSystemService(AlarmManager::class.java)?.cancel(p); p.cancel()
    }

    fun schedule(c: Context, l: Lead) {
        val mine = l.execMobile.filter { it.isDigit() }.takeLast(10) == Officer.mobile(c).filter { it.isDigit() }.takeLast(10)
        if (!mine || !l.isOpen) { cancel(c, l.id, "call"); cancel(c, l.id, "visit"); return }
        if (l.nextAt > 0) set(c, l.id, "call", l.nextAt) else cancel(c, l.id, "call")
        if (l.visitAt > 0 && l.stage == Lead.VISIT) set(c, l.id, "visit", l.visitAt - 3_600_000L) else cancel(c, l.id, "visit")
    }

    fun rescheduleAll(c: Context) = LeadStore.all(c).forEach { schedule(c, it) }

    fun notify(c: Context, l: Lead, what: String) {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        Lang.load(c)
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Lead calls and visits", NotificationManager.IMPORTANCE_HIGH))
        val nid = (l.id + what).hashCode()
        val open = PendingIntent.getActivity(c, nid, Intent(c, LeadActivity::class.java).putExtra(LeadActivity.EXTRA_ID, l.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val call = PendingIntent.getActivity(c, nid + 1, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + l.mobile.filter { it.isDigit() || it == '+' }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val time = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date(if (what == "visit") l.visitAt else l.nextAt))
        val title = if (what == "visit") tr("1 घंटे में Visit: ${l.name} · $time", "Visit in 1 hour: ${l.name} · $time")
            else tr("Call करें: ${l.name}", "Call now: ${l.name}")
        val body = if (what == "visit") l.visitAddress.ifBlank { l.area } else listOf(l.area, l.nextNote).filter { it.isNotBlank() }.joinToString(" · ")
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_update).setColor(Ui.BRAND)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open)
            .addAction(R.drawable.ic_call, "Call ${l.mobile}", call).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(c).notify(nid, n) } catch (_: SecurityException) {}
    }
}

class LeadReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { LeadAlarms.rescheduleAll(context); RouteLog.ensure(context) }
            "com.avip.quotationmaker.ROUTE_TICK" -> RouteLog.ensure(context)
            else -> {
                val l = LeadStore.get(context, intent.getStringExtra(LeadAlarms.EXTRA_ID) ?: return) ?: return
                if (l.isOpen) LeadAlarms.notify(context, l, intent.getStringExtra(LeadAlarms.EXTRA_WHAT) ?: "call")
            }
        }
    }
}

object Talk {
    fun call(a: Activity, mobile: String) = VisitStore.call(a, mobile)

    /** Opens WhatsApp to this number with the message typed in (the person taps send). */
    fun whatsapp(a: Activity, mobile: String, message: String) {
        var d = mobile.filter { it.isDigit() }
        if (d.length == 10) d = "91$d"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d?text=" + Uri.encode(message)))
        try { a.startActivity(i) } catch (_: Exception) { android.widget.Toast.makeText(a, "WhatsApp not found", android.widget.Toast.LENGTH_SHORT).show() }
    }

    fun directions(a: Activity, l: Lead) {
        val uri = if (l.visitLat != 0.0 && l.visitLng != 0.0) Uri.parse("google.navigation:q=${l.visitLat},${l.visitLng}")
            else Uri.parse("geo:0,0?q=" + Uri.encode(listOf(l.visitAddress, l.area).filter { it.isNotBlank() }.joinToString(", ")))
        try { a.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + Uri.encode(l.visitAddress.ifBlank { l.area }))))
        }
    }
}

object When {
    private val time = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
    private val day = SimpleDateFormat("dd MMM", Locale.ENGLISH)
    fun short(ms: Long): String {
        if (ms <= 0) return ""
        val today = Lead.startOfToday()
        return when {
            ms in today..(today + 86_399_999L) -> tr("आज ", "Today ") + time.format(Date(ms))
            ms in (today + 86_400_000L)..(today + 172_799_999L) -> tr("कल ", "Tomorrow ") + time.format(Date(ms))
            ms in (today - 86_400_000L) until today -> tr("कल (बीता) ", "Yesterday ") + time.format(Date(ms))
            else -> day.format(Date(ms)) + " " + time.format(Date(ms))
        }
    }
    fun ago(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return when {
            m < 1 -> tr("अभी", "just now"); m < 60 -> tr("$m मिनट पहले", "$m min ago")
            m < 1440 -> tr("${m / 60} घंटे पहले", "${m / 60} h ago"); else -> tr("${m / 1440} दिन पहले", "${m / 1440} days ago")
        }
    }
}
