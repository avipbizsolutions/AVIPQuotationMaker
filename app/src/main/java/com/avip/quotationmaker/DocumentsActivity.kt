package com.avip.quotationmaker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Documents for the field team — brochures, the rate card, subsidy forms, company profile.
 * The office keeps them in one shared Google Drive folder (set in the script); dropping a file
 * in there makes it appear here, with each sub-folder as a category. If the folder is not set up,
 * the app falls back to the "Documents" tab of the price sheet. Tapping a document opens it in
 * Drive or the browser to view or download; Drive keeps an opened file for offline use.
 */
class DocumentsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var holder: LinearLayout
    private var query = ""
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        body = leadScreen("Documents", tr("Field के लिए ज़रूरी documents", "Important documents for the field"),
            right = listOf(barButton(R.drawable.ic_refresh) { refresh() }))
        val search = input(tr("Document खोजें", "Search documents"))
        search.onChange { query = it.trim().lowercase(); render() }
        body.addView(search, lp().margins(this, b = 4))
        holder = vertical { }
        body.addView(holder, lp())
        render()
        refresh()
    }

    /** Folder documents when we have them, otherwise the price-sheet list. */
    private fun docs(): List<SalesDoc> = DocStore.cached(this).ifEmpty { Catalog.documents }

    private fun render() {
        holder.removeAllViews()
        if (loading && docs().isEmpty()) { holder.addView(text(tr("आ रहा है…", "Loading…"), 14f, Ui.MUTED)); return }
        var list = docs()
        if (query.isNotBlank()) list = list.filter { (it.title + " " + it.category + " " + it.note).lowercase().contains(query) }
        if (list.isEmpty()) {
            holder.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(if (docs().isEmpty()) tr("अभी कोई document नहीं।", "No documents yet.") else tr("कुछ नहीं मिला।", "Nothing found."), 14.5f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER })
                if (docs().isEmpty()) addView(text(tr("Office shared Drive folder में files डालेगा, फिर यहाँ दिखेंगी।", "The office drops files into the shared Drive folder and they appear here."), 12.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            }, lp().margins(this, t = 6))
            return
        }
        list.groupBy { it.category.ifBlank { "General" } }.forEach { (cat, items) ->
            holder.addView(text(cat, 13f, Ui.MUTED, bold = true) { setPadding(dp(4), dp(12), 0, dp(6)); isAllCaps = true; letterSpacing = 0.04f }, lp())
            items.forEach { d -> holder.addView(docRow(d), lp().margins(this, b = 8)) }
        }
        holder.addView(text(tr("Tap करके document खुलेगा (view / download)।", "Tap a document to open it (view or download)."), 11.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 4))
    }

    private fun docRow(d: SalesDoc): View = card(12) {
        isClickable = true
        foreground = context.ripple()
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(R.drawable.ic_grid, docColor(d), 40, square = true))
            addView(vertical {
                addView(text(d.title, 15f, Ui.TEXT, bold = true))
                val sub = listOf(d.note.takeIf { it.isNotBlank() && it.length <= 6 }?.uppercase() ?: d.note).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) addView(text(sub, 12f, Ui.MUTED) { maxLines = 2 })
            }, lp(0, WRAP, 1f).margins(context, l = 12, r = 8))
            addView(icon(R.drawable.ic_grid, Ui.BRAND, 18))
        })
        setOnClickListener { open(d) }
    }

    private fun docColor(d: SalesDoc): Int {
        val t = (d.title + " " + d.category).lowercase()
        return when {
            "rate" in t || "price" in t || "cost" in t -> 0xFF1565C0.toInt()
            "form" in t || "subsidy" in t || "surya" in t || "loan" in t -> 0xFF6A1B9A.toInt()
            "brochure" in t || "profile" in t || "catalog" in t || "datasheet" in t -> 0xFFF7A21B.toInt()
            else -> 0xFF0E6B3A.toInt()
        }
    }

    private fun open(d: SalesDoc) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, tr("यह link नहीं खुला", "Could not open this link"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            // the shared Drive folder (via the office script), with the price sheet as a fallback
            withContext(Dispatchers.IO) {
                runCatching { Remote.call(this@DocumentsActivity, "docs", JSONObject()) }
                    .onSuccess { DocStore.save(this@DocumentsActivity, it.optJSONArray("documents")) }
                runCatching { Catalog.refresh(this@DocumentsActivity) }
            }
            loading = false
            if (isFinishing) return@launch
            render()
            if (docs().isEmpty()) Toast.makeText(this@DocumentsActivity, tr("Internet नहीं, या अभी documents नहीं", "No internet, or no documents yet"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** Documents listed from the shared Drive folder, kept on the phone so they show offline. */
object DocStore {
    private fun file(c: Context) = File(c.filesDir, "folder_docs.json")

    fun save(c: Context, arr: JSONArray?) {
        if (arr == null) return
        file(c).writeText(arr.toString())
    }

    fun cached(c: Context): List<SalesDoc> = try {
        val arr = JSONArray(file(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val title = o.optString("title").trim(); val link = o.optString("link").trim()
            if (title.isBlank() || link.isBlank()) null
            else SalesDoc(title, o.optString("category").trim().ifBlank { "General" }, link, o.optString("note").trim())
        }
    } catch (_: Exception) { emptyList() }
}
