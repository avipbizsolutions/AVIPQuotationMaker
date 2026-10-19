package com.avip.quotationmaker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Systems and prices come straight from the AVIP Google Sheet ("anyone with the link
 * can view"), read as CSV. Only the customer-price tabs are read — never the costing
 * tabs. The last good copy is kept on the phone, so the app works offline at site.
 *
 * Each price tab has one row per item ("Panel Brand", "Price (GST Included)" …) and
 * one column per system ("3 kw Saathi", "5 Kw Smart" …).
 */
object Catalog {

    /**
     * "AVIP Customer Prices (App)": holds only the customer-price tabs (pulled from the
     * costing sheet with IMPORTRANGE) and App Settings, so the costing sheet can stay private.
     */
    const val SHEET_ID = "1g4CSk_r3YaaBZ9RO4vGIO8n14GrVrKaejB35HNPCjXc"
    /** Old location, used only until the new sheet is shared. */
    private const val OLD_SHEET_ID = "1DxfN4bjham7oLE-Su-NMhbRhporjH0681RzBFg-YeCs"
    private const val SETTINGS_TAB = "App Settings"

    /** Tab ids per sheet, so renaming a tab doesn't break the app. Names are the fallback. */
    private val GIDS = mapOf(
        SHEET_ID to mapOf(SystemType.ONGRID to "0", SystemType.HYBRID to "1"),
        OLD_SHEET_ID to mapOf(SystemType.ONGRID to "118618691", SystemType.HYBRID to "1588324922"),
    )

    @Volatile var packages: List<SystemPackage> = emptyList()
        private set
    @Volatile var settings: AppSettings = AppSettings(emptyMap())
        private set
    @Volatile var updatedAt: Long = 0L
        private set
    @Volatile var isLive: Boolean = false
        private set

    val isLoaded get() = packages.isNotEmpty()

    fun of(type: SystemType) = packages.filter { it.type == type }
    fun byId(id: String?) = packages.firstOrNull { it.id == id }

    private fun byGid(sheet: String, gid: String) =
        "https://docs.google.com/spreadsheets/d/$sheet/export?format=csv&gid=$gid"

    private fun byName(sheet: String, tab: String) =
        "https://docs.google.com/spreadsheets/d/$sheet/gviz/tq?tqx=out:csv&headers=0&sheet=" +
            URLEncoder.encode(tab, "UTF-8").replace("+", "%20")

    private fun file(c: Context, name: String) = File(c.filesDir, "sheet_$name.csv")

    /** Loads the copy saved on the phone. Returns false if there is none. */
    fun loadCached(c: Context): Boolean = try {
        val list = SystemType.entries.flatMap { t ->
            file(c, t.name).takeIf { it.exists() }?.let { parseTab(t, it.readText()) } ?: emptyList()
        }
        if (list.isEmpty()) false else {
            packages = list
            file(c, "settings").takeIf { it.exists() }?.let { settings = parseSettings(it.readText()) }
            updatedAt = file(c, SystemType.ONGRID.name).lastModified()
            isLive = false
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Downloads fresh prices. Returns true on success; keeps the old copy on failure. */
    suspend fun refresh(c: Context): Boolean = withContext(Dispatchers.IO) {
        refreshFrom(c, SHEET_ID) || refreshFrom(c, OLD_SHEET_ID)
    }

    private fun refreshFrom(c: Context, sheet: String): Boolean {
        return try {
            val texts = mutableMapOf<SystemType, String>()
            val list = mutableListOf<SystemPackage>()
            for (t in SystemType.entries) {
                val csv = tabCsv(sheet, t)
                val parsed = parseTab(t, csv)
                if (parsed.isNotEmpty()) { texts[t] = csv; list += parsed }
            }
            if (list.none { it.type == SystemType.ONGRID }) error("no on-grid systems")
            val sets = try { download(byName(sheet, SETTINGS_TAB)) } catch (_: Exception) { "" }
            packages = list
            settings = parseSettings(sets)
            texts.forEach { (t, csv) -> file(c, t.name).writeText(csv) }
            if (sets.isNotBlank()) file(c, "settings").writeText(sets)
            updatedAt = System.currentTimeMillis()
            isLive = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tabCsv(sheet: String, t: SystemType): String {
        val gid = GIDS[sheet]?.get(t)
        if (gid != null) {
            try {
                val csv = download(byGid(sheet, gid))
                if (parseTab(t, csv).isNotEmpty()) return csv
            } catch (_: Exception) {
            }
        }
        return download(byName(sheet, t.tab))
    }

    private fun download(address: String): String {
        var url = URL(address)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    url = URL(url, conn.getHeaderField("Location") ?: error("bad redirect"))
                    redirects++
                    continue
                }
                if (code != 200) error("HTTP $code")
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (text.trimStart().startsWith("<")) error("not csv") // sign-in page = sheet not shared
                return text
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Column-wise tab → one package per column. */
    fun parseTab(type: SystemType, csv: String): List<SystemPackage> {
        val rows = Csv.parse(csv).map { r -> r.map { it.trim() } }
        val head = rows.indexOfFirst { r ->
            val first = r.firstOrNull()?.lowercase() ?: ""
            first == "category name" || first == "particulars" || first == "system" ||
                (r.drop(1).count { Regex("""\d.*kw""", RegexOption.IGNORE_CASE).containsMatchIn(it) } >= 2)
        }
        if (head < 0) return emptyList()
        val names = rows[head]
        val body = rows.drop(head + 1).filter { it.isNotEmpty() && it[0].isNotBlank() }
        return (1 until names.size).mapNotNull { col ->
            val name = names[col]
            if (name.isBlank()) return@mapNotNull null
            SystemPackage(type, name, body.map { r -> r[0] to (r.getOrNull(col) ?: "") })
        }.filter { it.available }
    }

    private const val USERS_GID = "3"

    /** Last 10 digits, so "+91 98765-43210" and "9876543210" match. */
    fun mobileKey(s: String) = s.filter { it.isDigit() }.takeLast(10)

    /**
     * Mobiles allowed to use the app: the "App Users" tab of the price workbook
     * (Mobile | Name | Active). A row with Active = "No" is left out.
     * Returns null when the list could not be read (no internet, tab missing).
     */
    suspend fun allowedMobiles(): Set<String>? = withContext(Dispatchers.IO) {
        try {
            val rows = Csv.parse(download(byGid(SHEET_ID, USERS_GID)))
            // only trust the real tab: its first header must be "Mobile"
            if (rows.isEmpty() || rows[0].firstOrNull()?.trim()?.lowercase()?.startsWith("mobile") != true) return@withContext null
            rows.drop(1).filter { r -> !(r.getOrNull(2)?.trim()?.lowercase() ?: "").startsWith("n") }
                .map { mobileKey(it.getOrNull(0) ?: "") }.filter { it.length == 10 }.toSet()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSettings(csv: String): AppSettings {
        val rows = Csv.parse(csv)
        // A missing tab can come back as some other tab: only trust a Key/Value table.
        if (rows.isEmpty() || rows[0].firstOrNull()?.trim()?.lowercase() != "key") return AppSettings(emptyMap())
        val map = mutableMapOf<String, String>()
        rows.drop(1).forEach { r -> if (r.size >= 2 && r[0].isNotBlank()) map[r[0].trim()] = r[1].trim() }
        return AppSettings(map)
    }
}

/** Work that may outlive a screen, such as a price refresh started on the launch screen. */
object AppScope : kotlinx.coroutines.CoroutineScope by kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.Main,
)

/** Minimal RFC-4180 CSV reader (quotes, escaped quotes, newlines inside cells). */
object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}
