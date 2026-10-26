package com.avip.quotationmaker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The sales executive using this phone. */
object Officer {
    private fun p(c: Context) = c.getSharedPreferences("officer", Context.MODE_PRIVATE)
    fun name(c: Context) = p(c).getString("name", "") ?: ""
    fun mobile(c: Context) = p(c).getString("mobile", "") ?: ""
    fun code(c: Context) = p(c).getString("code", "") ?: ""
    fun isComplete(c: Context) = name(c).isNotBlank() && mobile(c).filter { it.isDigit() }.length >= 10
    fun save(c: Context, name: String, mobile: String, code: String) {
        p(c).edit().putString("name", name.trim()).putString("mobile", mobile.trim()).putString("code", code.trim()).apply()
        Remote.queue(c, JSONObject().put("kind", "officer").put("name", name.trim()).put("mobile", mobile.trim())
            .put("code", code.trim()).put("installId", installId(c)).put("appVersion", Updater.currentVersionName(c)))
    }

    /** Removes the saved sign-in (number taken off the App Users list). */
    fun signOut(c: Context) { p(c).edit().remove("name").remove("mobile").remove("code").remove("team").apply() }

    /** May see every executive's visits (App Users → "Sees all team visits"). */
    fun teamView(c: Context) = p(c).getBoolean("team", false)
    fun setTeamView(c: Context, on: Boolean) { p(c).edit().putBoolean("team", on).apply() }

    fun installId(c: Context): String {
        val prefs = p(c)
        prefs.getString("install_id", null)?.let { return it }
        val id = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "")
            .ifBlank { System.currentTimeMillis().toString(36) }
        prefs.edit().putString("install_id", id).apply()
        return id
    }
}

/** Site visits (JSON + photos + PDFs), one folder per visit in private storage. */
object VisitStore {

    private fun root(c: Context) = File(c.filesDir, "visits").apply { mkdirs() }
    private fun safe(number: String) = number.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun dir(c: Context, number: String) = File(root(c), safe(number)).apply { mkdirs() }
    fun file(c: Context, v: Visit, name: String) = File(dir(c, v.number), name)
    fun quotationPdf(c: Context, v: Visit) = File(dir(c, v.number), "Quotation-${safe(v.number)}.pdf")
    fun reportPdf(c: Context, v: Visit) = File(dir(c, v.number), "Site-Visit-${safe(v.number)}.pdf")

    /** e.g. AVQ-261017-03 (sales executive initials keep numbers unique across phones). */
    fun nextNumber(c: Context): String {
        val p = c.getSharedPreferences("numbers", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyMMdd", Locale.ENGLISH).format(Date())
        val initials = Officer.name(c).split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifBlank { "AV" }
        val key = "AVQ-$initials-$day"
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        return "$key-${n.toString().padStart(2, '0')}"
    }

    fun create(c: Context): Visit = Visit(nextNumber(c), System.currentTimeMillis()).apply {
        officerName = Officer.name(c)
        officerMobile = Officer.mobile(c)
    }

    fun save(c: Context, v: Visit) {
        v.updatedAt = System.currentTimeMillis()
        File(dir(c, v.number), "visit.json").writeText(v.toJson().toString())
    }

    fun load(c: Context, number: String): Visit? = runCatching {
        Visit.fromJson(JSONObject(File(dir(c, number), "visit.json").readText()))
    }.getOrNull()

    fun all(c: Context): List<Visit> = root(c).listFiles().orEmpty().mapNotNull { d ->
        File(d, "visit.json").takeIf { it.exists() }?.let { runCatching { Visit.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }.sortedByDescending { it.updatedAt }

    fun delete(c: Context, v: Visit) {
        Reminders.cancel(c, v.number)
        dir(c, v.number).deleteRecursively()
    }

    /**
     * Office reset: App Settings key "reset_visits_before" = "yyyy-MM-dd HH:mm" (India time).
     * Each phone, once per new value, deletes the visits it created before that moment and
     * drops their unsent uploads. Returns true when something was cleared.
     */
    fun applyReset(c: Context): Boolean {
        val stamp = Catalog.settings["reset_visits_before"]
        if (stamp.isBlank()) return false
        val p = c.getSharedPreferences("numbers", Context.MODE_PRIVATE)
        if (p.getString("reset_done", "") == stamp) return false
        val cutoff = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }.parse(stamp)?.time
        }.getOrNull() ?: return false
        val old = all(c).filter { it.createdAt in 1 until cutoff }
        old.forEach { delete(c, it) }
        Remote.dropVisitUploads(c, old.map { it.number }.toSet())
        p.edit().putString("reset_done", stamp).apply()
        return old.isNotEmpty()
    }

    // ---- sharing ----

    fun uri(c: Context, file: File): Uri = FileProvider.getUriForFile(c, c.packageName + ".files", file)

    fun open(a: Activity, file: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri(a, file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { a.startActivity(i) } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, "No PDF viewer found. Use Share instead.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(a: Activity, files: List<File>, message: String) {
        val uris = ArrayList(files.map { uri(a, it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.setType(if (files.all { it.name.endsWith(".pdf") }) "application/pdf" else "*/*")
            .putExtra(Intent.EXTRA_TEXT, message).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "Share"))
    }

    /** Sends the PDF(s) straight to the customer's WhatsApp chat (falls back to the share sheet). */
    fun whatsapp(a: Activity, file: File, number: String, message: String) = whatsapp(a, listOf(file), number, message)

    fun whatsapp(a: Activity, files: List<File>, number: String, message: String) {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        val uris = ArrayList(files.map { uri(a, it) })
        // WhatsApp Business first (the company number); personal WhatsApp only if Business isn't installed
        for (pkg in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            val i = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))
                .setType("application/pdf").setPackage(pkg)
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (digits.isNotEmpty()) i.putExtra("jid", "$digits@s.whatsapp.net")
            try {
                a.startActivity(i)
                if (pkg == "com.whatsapp") android.widget.Toast.makeText(a, "WhatsApp Business not found — opened personal WhatsApp", android.widget.Toast.LENGTH_LONG).show()
                return
            } catch (_: ActivityNotFoundException) {}
        }
        share(a, files, message)
    }

    fun call(a: Activity, number: String) {
        try { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))) } catch (_: Exception) {}
    }

    fun openMap(a: Activity, lat: Double, lng: Double) {
        try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))) } catch (_: Exception) {}
    }
}

/**
 * Sends sales-executive sign-ups and visit summaries to the office Google Sheet
 * through the Apps Script web app (URL in App Settings → visit_log_url).
 * Queued on the phone and retried, so it works with patchy signal at site.
 */
object Remote {
    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-quote-7Qm4"
    private const val PREFS = "remote"
    @Volatile private var sending = false

    fun queue(c: Context, item: JSONObject) {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
            arr.put(item)
            val trimmed = JSONArray()
            for (i in maxOf(0, arr.length() - 300) until arr.length()) trimmed.put(arr.get(i))
            p.edit().putString("pending", trimmed.toString()).apply()
        }
        flushAsync(c)
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) // ISO: the script turns it into a real date

    fun visitSummary(c: Context, v: Visit): JSONObject {
        val p = v.pkg
        return JSONObject().put("kind", "visit").put("number", v.number)
            .put("visitDate", day.format(Date(v.createdAt)))
            .put("officer", v.officerName.ifBlank { Officer.name(c) }).put("officerMobile", v.officerMobile.ifBlank { Officer.mobile(c) })
            .put("customer", v.name).put("mobile", v.mobile).put("address", v.address)
            .put("district", v.district).put("pincode", v.pincode).put("caNo", v.caNo).put("discom", v.discom)
            .put("sanctionedLoad", v.sanctionedLoad).put("monthlyBill", v.monthlyBill)
            .put("funding", v.funding)
            .put("pan", if (v.isLoan) v.pan else "").put("aadhaarLast4", if (v.isLoan) v.aadhaar.filter { it.isDigit() }.takeLast(4) else "")
            .put("roof", listOf(v.roofType, v.floors).filter { it.isNotBlank() }.joinToString(", "))
            .put("roofSize", if (v.roofLength.isNotBlank() && v.roofWidth.isNotBlank()) "E-W ${v.roofLength} ft x N-S ${v.roofWidth} ft" else "")
            .put("shading", v.shading).put("structure", listOf(v.structureType, if (v.structureType == "Magnelis") v.mountingLayout else "").filter { it.isNotBlank() }.joinToString(" "))
            .put("location", if (v.hasLocation) String.format(Locale.US, "%.6f,%.6f", v.lat, v.lng) else "")
            .put("systemType", p?.type?.label ?: "").put("system", (p?.name ?: "") + if (p?.custom == true) " (custom)" else "")
            .put("kwp", p?.kwp ?: 0.0).put("price", p?.price ?: 0.0).put("discount", v.discount)
            .put("finalPrice", v.finalPrice)
            .put("centralSubsidy", Money.central(p, v.subsidyOn)).put("stateSubsidy", Money.state(p, v.subsidyOn))
            .put("loanAmount", if (v.isLoan) Money.loanAmount(v) else 0.0).put("emi", if (v.isLoan) Money.emi(v) else 0.0)
            .put("loanYears", if (v.isLoan) v.loanYears else 0)
            .put("leadStatus", v.leadStatus)
            .put("followUp", if (v.followUpAt > 0 && v.leadStatus == "Open") day.format(Date(v.followUpAt)) else "")
            .put("followUpNote", v.followUpNote)
            .put("calls", v.followUps.size)
            .put("lastCall", v.followUps.lastOrNull()?.let { "${day.format(Date(it.at))}: ${it.outcome}" + if (it.note.isBlank()) "" else " — ${it.note}" } ?: "")
            .put("notes", listOf(
                when { v.nameCorrection -> "Name correction (free)"; v.nameTransferFee > 0 -> "Name transfer (+${v.nameTransferFee.toInt()})"; else -> "" },
                if (v.smartMeter.isNotBlank()) "Smart meter: ${v.smartMeter}" else "",
                if (v.leadStatus == "Converted" && v.installAt > 0) "Installation wanted: ${day.format(Date(v.installAt))}" else "",
                v.siteNotes,
            ).filter { it.isNotBlank() }.joinToString(" | ")).put("voiceNotes", v.voiceNotes.size).put("photos", v.photos.size)
            .put("appVersion", Updater.currentVersionName(c))
    }

    fun followUpEntry(c: Context, v: Visit, f: FollowUp): JSONObject = JSONObject().put("kind", "followup")
        .put("number", v.number).put("officer", v.officerName.ifBlank { Officer.name(c) })
        .put("customer", v.name).put("mobile", v.mobile)
        .put("callDate", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date(f.at)))
        .put("outcome", f.outcome).put("note", f.note)
        .put("nextDate", if (f.nextAt > 0) day.format(Date(f.nextAt)) else "").put("leadStatus", v.leadStatus)

    /** PDFs and voice notes go to the office Google Drive (one folder per visit). Sent one by one. */
    fun queueFiles(c: Context, v: Visit) {
        val files = listOf(VisitStore.quotationPdf(c, v), VisitStore.reportPdf(c, v)) +
            v.voiceNotes.map { VisitStore.file(c, v, it) } +
            v.videos.map { VisitStore.file(c, v, it) }.filter { it.length() <= 12_000_000L } // big videos: share by hand
        files.filter { it.exists() }.forEach { f ->
            queue(c, JSONObject().put("kind", "file").put("number", v.number).put("customer", v.name)
                .put("officer", v.officerName).put("name", f.name).put("path", f.absolutePath)
                .put("mime", when { f.name.endsWith(".pdf") -> "application/pdf"; f.name.startsWith("video_") -> "video/mp4"; else -> "audio/mp4" }))
        }
    }

    fun flushAsync(context: Context) {
        val c = context.applicationContext
        val endpoint = Catalog.settings["visit_log_url"]
        if (endpoint.isBlank() || !endpoint.startsWith("https://") || sending) return
        sending = true
        Thread {
            try {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                while (true) {
                    val first = synchronized(this) {
                        val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
                        if (arr.length() == 0) null else arr.getJSONObject(0)
                    } ?: break
                    val item = JSONObject(first.toString()).put("key", SHARED_KEY)
                    val path = item.optString("path")
                    val sent = if (item.optString("kind") == "file") {
                        val f = File(path)
                        // file deleted or too big: drop it from the queue
                        if (!f.exists() || f.length() > 25_000_000L) true
                        else post(endpoint, item.put("data", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)).toString())
                    } else post(endpoint, item.toString())
                    if (!sent) break
                    synchronized(this) {
                        val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
                        val rest = JSONArray()
                        for (i in 1 until arr.length()) rest.put(arr.get(i))
                        p.edit().putString("pending", rest.toString()).apply()
                    }
                }
            } catch (_: Exception) {
            } finally {
                sending = false
            }
        }.start()
    }

    /** Removes queued uploads of the given visits (after an office reset). */
    fun dropVisitUploads(c: Context, numbers: Set<String>) {
        if (numbers.isEmpty()) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val arr = JSONArray(p.getString("pending", "[]") ?: "[]")
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("number") !in numbers) keep.put(o)
            }
            p.edit().putString("pending", keep.toString()).apply()
        }
    }

    /** Every executive's visits from the office sheet. Throws with a readable message on failure. */
    fun teamVisits(c: Context): JSONArray {
        val endpoint = Catalog.settings["visit_log_url"]
        if (!endpoint.startsWith("https://")) error("Office log is not set up")
        val body = JSONObject().put("key", SHARED_KEY).put("kind", "team")
            .put("mobile", Officer.mobile(c)).put("installId", Officer.installId(c)).toString()
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = try { JSONObject(text) } catch (_: Exception) { error("The office log needs updating (new Apps Script version)") }
            if (!o.optBoolean("ok")) error(o.optString("error").ifBlank { "Could not load team visits" }.let {
                if (it == "unknown kind") "The office log needs updating (new Apps Script version)" else it })
            return o.optJSONArray("visits") ?: JSONArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun post(endpoint: String, body: String): Boolean {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code in 200..299 && text.contains("\"ok\":true")
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
