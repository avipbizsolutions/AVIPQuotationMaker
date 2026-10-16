package com.avip.quotationmaker

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** All saved site visits, searchable, with Draft / Quoted filter. Long-press to delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private var query = ""
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar("All visits", "Tap to open · long-press to delete"))
        val head = vertical { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        head.addView(input("Search name, mobile, number, place").apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
            compoundDrawablePadding = dp(8)
            onChange { query = it.trim().lowercase(); fill() }
        })
        head.addView(chips(listOf("Follow-up due", "Draft", "Quoted", "Converted", "Lost"), "") { filter = it; fill() }, lp().margins(this, t = 10))
        root.addView(head)
        list = vertical { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); fill() }

    private fun fill() {
        list.removeAllViews()
        val items = VisitStore.all(this).filter { v ->
            when (filter) {
                "Follow-up due" -> v.followUpDue
                "Draft" -> !v.isQuoted
                "Quoted" -> v.isQuoted && v.leadStatus == "Open"
                "Converted" -> v.leadStatus == "Converted"
                "Lost" -> v.leadStatus == "Lost"
                else -> true
            } &&
                (query.isBlank() || listOf(v.name, v.mobile, v.number, v.address, v.district, v.caNo, v.pkg?.name ?: "")
                    .joinToString(" ").lowercase().contains(query))
        }
        if (items.isEmpty()) {
            list.addView(text("No visits found", 14f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 40))
            return
        }
        items.forEach { v ->
            list.addView(visitRow(v) { fill() }.apply {
                setOnLongClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("Delete ${v.name.ifBlank { v.number }}?")
                        .setMessage("Photos, signature and PDFs of this visit will be removed from this phone.")
                        .setPositiveButton("Delete") { _, _ -> VisitStore.delete(this@HistoryActivity, v); fill() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
            }, lp().margins(this, b = 10))
        }
    }
}
