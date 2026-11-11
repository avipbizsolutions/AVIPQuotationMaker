package com.avip.quotationmaker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Documents the office keeps for the field team — brochures, the rate card, subsidy forms,
 * company profile and so on. The list and the links live in the "Documents" tab of the price
 * sheet, so the office changes them without an app update. Tapping one opens it (Google Drive
 * or the browser) to view or download; a document opened once is cached by Drive for offline use.
 */
class DocumentsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        body = leadScreen("Documents", tr("Field के लिए ज़रूरी documents", "Important documents for the field"),
            right = listOf(barButton(R.drawable.ic_refresh) { refresh() }))
        val search = input(tr("Document खोजें", "Search documents"))
        search.onChange { query = it.trim().lowercase(); render() }
        body.addView(search, lp().margins(this, b = 4))
        val holder = vertical { }
        body.addView(holder, lp())
        this.holder = holder
        render()
        if (!Catalog.isLoaded || Catalog.documents.isEmpty()) refresh()
    }

    private lateinit var holder: LinearLayout

    private fun render() {
        holder.removeAllViews()
        var docs = Catalog.documents
        if (query.isNotBlank()) docs = docs.filter { (it.title + " " + it.category + " " + it.note).lowercase().contains(query) }
        if (docs.isEmpty()) {
            holder.addView(card(20) {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text(if (Catalog.documents.isEmpty()) tr("अभी कोई document नहीं।", "No documents yet.") else tr("कुछ नहीं मिला।", "Nothing found."), 14.5f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER })
                if (Catalog.documents.isEmpty()) addView(text(tr("Office price sheet के \"Documents\" tab में Title और Google Drive link डालें।", "The office adds a Title and a Google Drive link in the \"Documents\" tab of the price sheet."), 12.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            }, lp().margins(this, t = 6))
            return
        }
        docs.groupBy { it.category }.forEach { (cat, list) ->
            holder.addView(text(cat, 13f, Ui.MUTED, bold = true) { setPadding(dp(4), dp(12), 0, dp(6)); isAllCaps = true; letterSpacing = 0.04f }, lp())
            list.forEach { d -> holder.addView(docRow(d), lp().margins(this, b = 8)) }
        }
        holder.addView(text(tr("Tap करके document खुलेगा (view / download)।", "Tap a document to open it (view or download)."), 11.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 4))
    }

    private fun docRow(d: SalesDoc): View = card(12) {
        isClickable = true
        foreground = context.ripple()
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(badge(docIcon(d.title), docColor(d.title), 40, square = true))
            addView(vertical {
                addView(text(d.title, 15f, Ui.TEXT, bold = true))
                if (d.note.isNotBlank()) addView(text(d.note, 12f, Ui.MUTED) { maxLines = 2 })
            }, lp(0, WRAP, 1f).margins(context, l = 12, r = 8))
            addView(icon(R.drawable.ic_grid, Ui.BRAND, 18))
        })
        setOnClickListener { open(d) }
    }

    private fun docIcon(title: String) = R.drawable.ic_grid
    private fun docColor(title: String): Int {
        val t = title.lowercase()
        return when {
            "rate" in t || "price" in t || "cost" in t -> 0xFF1565C0.toInt()
            "form" in t || "subsidy" in t || "pm surya" in t -> 0xFF6A1B9A.toInt()
            "brochure" in t || "profile" in t || "catalog" in t -> 0xFFF7A21B.toInt()
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
        lifecycleScope.launch {
            val ok = Catalog.refresh(this@DocumentsActivity)
            if (isFinishing) return@launch
            render()
            if (!ok) Toast.makeText(this@DocumentsActivity, tr("Internet नहीं — पहले की list दिख रही है", "No internet: showing the saved list"), Toast.LENGTH_SHORT).show()
        }
    }
}
