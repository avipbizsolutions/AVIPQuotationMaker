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

    fun delete(c: Context, v: Visit) { dir(c, v.number).deleteRecursively() }

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
        i.setType("application/pdf").putExtra(Intent.EXTRA_TEXT, message).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(i, "Share"))
    }

    /** Sends the PDF straight to the customer's WhatsApp chat (falls back to the share sheet). */
    fun whatsapp(a: Activity, file: File, number: String, message: String) {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val i = Intent(Intent.ACTION_SEND).setType("application/pdf").setPackage(pkg)
                .putExtra(Intent.EXTRA_STREAM, uri(a, file))
                .putExtra(Intent.EXTRA_TEXT, message)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (digits.isNotEmpty()) i.putExtra("jid", "$digits@s.whatsapp.net")
            try { a.startActivity(i); return } catch (_: ActivityNotFoundException) {}
        }
        share(a, listOf(file), message)
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

    fun visitSummary(c: Context, v: Visit): JSONObject {
        val p = v.pkg
        return JSONObject().put("kind", "visit").put("number", v.number)
            .put("officer", Officer.name(c)).put("officerMobile", Officer.mobile(c))
            .put("customer", v.name).put("mobile", v.mobile).put("address", v.address)
            .put("district", v.district).put("pincode", v.pincode).put("caNo", v.caNo).put("discom", v.discom)
            .put("sanctionedLoad", v.sanctionedLoad).put("monthlyBill", v.monthlyBill)
            .put("pan", v.pan).put("aadhaarLast4", v.aadhaar.filter { it.isDigit() }.takeLast(4))
            .put("roof", listOf(v.roofType, v.floors.let { if (it.isBlank()) "" else "$it floor" }).filter { it.isNotBlank() }.joinToString(", "))
            .put("roofSize", if (v.roofLength.isNotBlank() && v.roofWidth.isNotBlank()) "${v.roofLength} × ${v.roofWidth} ft" else "")
            .put("shading", v.shading)
            .put("location", if (v.hasLocation) "${v.lat},${v.lng}" else "")
            .put("systemType", p?.type?.label ?: "").put("system", p?.name ?: "")
            .put("kwp", p?.kwp ?: 0.0).put("price", p?.price ?: 0.0).put("discount", v.discount)
            .put("finalPrice", v.finalPrice).put("subsidy", Money.subsidy(p, v.subsidyOn))
            .put("status", if (v.isQuoted) "Quotation given" else "Survey only")
            .put("notes", v.siteNotes).put("appVersion", Updater.currentVersionName(c))
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
                    if (!post(endpoint, JSONObject(first.toString()).put("key", SHARED_KEY).toString())) break
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

    private fun post(endpoint: String, body: String): Boolean {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
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
