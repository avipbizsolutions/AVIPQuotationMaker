package com.avip.quotationmaker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved visit in a list: customer, system, price, status, follow-up, call / delete. */
fun Activity.visitRow(v: Visit, onChanged: () -> Unit = {}): View = card(14) {
    foreground = context.ripple()
    val p = v.pkg
    addView(horizontal {
        addView(badge(p?.type?.icon ?: R.drawable.ic_home, p?.type?.color ?: Ui.MUTED, 44, square = true))
        addView(vertical {
            addView(text(v.name.ifBlank { "Unnamed customer" }, 15.5f, Ui.TEXT, bold = true) { maxLines = 1 })
            val line2 = listOfNotNull(
                p?.let { "${Fmt.one(it.kwp)} kWp ${it.type.label}" },
                v.district.ifBlank { v.address.substringAfterLast(',').trim() }.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "Survey started" }
            addView(text(line2, 12.5f, Ui.MUTED) { maxLines = 1 })
            addView(text(v.number + " · " + SimpleDateFormat("dd MMM, h:mm a", Locale.ENGLISH).format(Date(v.updatedAt)), 11f, Ui.MUTED) { maxLines = 1 })
        }, lp(0, WRAP, 1f).margins(context, l = 12))
        addView(vertical {
            gravity = Gravity.END
            if (p != null) addView(text(Fmt.inr(v.finalPrice), 15f, Ui.BRAND_DARK, bold = true) { setSingleLine() }, lp(WRAP, WRAP))
            val t = when {
                !v.isQuoted -> tag("DRAFT", Ui.SUN, Ui.TEXT)
                v.leadStatus == "Converted" -> tag("CONVERTED", 0xFF2E7D32.toInt())
                v.leadStatus == "Lost" -> tag("LOST", Ui.MUTED)
                else -> tag("QUOTED", Ui.BRAND)
            }
            addView(t, lp(WRAP, WRAP).margins(context, t = 4))
        })
    })
    // follow-up / actions line
    val hasFollow = v.leadStatus == "Open" && v.followUpAt > 0
    if (hasFollow || v.mobile.isNotBlank() || !v.isQuoted) {
        addView(horizontal {
            if (hasFollow) {
                val due = v.followUpDue
                addView(icon(R.drawable.ic_history, if (due) Ui.DANGER else Ui.BLUE, 16))
                addView(text((if (due) "Follow-up due · " else "Follow-up · ") + SimpleDateFormat("dd MMM", Locale.ENGLISH).format(Date(v.followUpAt)),
                    12.5f, if (due) Ui.DANGER else Ui.BLUE, bold = true), lp(0, WRAP, 1f).margins(context, l = 6))
            } else addView(View(context), lp(0, 1, 1f))
            if (!v.isQuoted) addView(icon(R.drawable.ic_delete, Ui.DANGER, 36).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    AlertDialog.Builder(this@visitRow)
                        .setTitle("Delete draft of ${v.name.ifBlank { v.number }}?")
                        .setMessage("Details, photos and voice notes of this draft will be removed from this phone.")
                        .setPositiveButton("Delete") { _, _ -> VisitStore.delete(this@visitRow, v); onChanged() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            if (v.mobile.filter { it.isDigit() }.length >= 10) addView(horizontal {
                background = rounded(Ui.BRAND_LIGHT, dpf(16f))
                setPadding(dp(12), dp(7), dp(14), dp(7))
                addView(icon(R.drawable.ic_call, Ui.BRAND, 16))
                addView(text("Call", 13.5f, Ui.BRAND_DARK, bold = true), lp(WRAP, WRAP).margins(context, l = 6))
                setOnClickListener { VisitStore.call(this@visitRow, v.mobile) }
            }, lp(WRAP, WRAP).margins(context, l = 6))
        }, lp().margins(context, t = 10))
    }
    setOnClickListener {
        startActivity(Intent(context, VisitActivity::class.java).putExtra(VisitActivity.EXTRA_NUMBER, v.number))
    }
}

/**
 * Big picture card for a ready system: tier colour band, capacity, panels, inverter,
 * battery, warranties and price. [selected] draws a thick border and a tick.
 */
fun Activity.packageCard(p: SystemPackage, selected: Boolean, recommended: Boolean, onClick: () -> Unit): View {
    val tier = Ui.tierColor(p.tier)
    val outer = vertical {
        background = rounded(Color.WHITE, dpf(18f), if (selected) Ui.BRAND else Ui.LINE, dp(if (selected) 3 else 1))
        elevation = dpf(if (selected) 6f else 2f)
        foreground = context.ripple()
        setOnClickListener { onClick() }
    }
    // colour band
    outer.addView(horizontal {
        background = gradient(dpf(16f), tier, blend(tier, Color.BLACK, 0.25f))
        setPadding(dp(16), dp(12), dp(14), dp(12))
        addView(vertical {
            addView(text(p.tier.uppercase(), 11f, 0xDDFFFFFF.toInt(), bold = true) { letterSpacing = 0.12f })
            addView(text("${Fmt.one(p.kwp)} kWp ${p.type.label}", 21f, Color.WHITE, bold = true))
            addView(text(p.name, 12f, 0xCCFFFFFF.toInt()))
        }, lp(0, WRAP, 1f))
        if (recommended) addView(tag("BEST FIT", Ui.SUN, Ui.TEXT), lp(WRAP, WRAP).margins(context, r = 6))
        if (selected) addView(badge(R.drawable.ic_check, Ui.BRAND, 34))
    }, lp().margins(this, l = 2, t = 2, r = 2))

    val body = vertical { setPadding(dp(16), dp(12), dp(16), dp(14)) }
    fun spec(res: Int, color: Int, title: String, sub: String) {
        if (title.isBlank()) return
        body.addView(horizontal {
            addView(badge(res, color, 30))
            addView(vertical {
                addView(text(title, 14f, Ui.TEXT, bold = true) { maxLines = 2 })
                if (sub.isNotBlank()) addView(text(sub, 12f, Ui.MUTED) { maxLines = 1 })
            }, lp(0, WRAP, 1f).margins(context, l = 10))
        }, lp().margins(this, b = 8))
    }
    spec(R.drawable.cat_panel, 0xFF1565C0.toInt(), "${p.panelBrand} panels", listOf(p.panelSummary(), p.panelWarranty.let { if (it.isBlank()) "" else "$it warranty" }).filter { it.isNotBlank() }.joinToString(" · "))
    spec(R.drawable.cat_inverter, 0xFF00897B.toInt(), "${p.inverter} inverter", p.inverterWarranty.let { if (it.isBlank()) "" else "$it warranty" })
    if (p.battery.isNotBlank()) spec(R.drawable.cat_battery, 0xFF2E7D32.toInt(), p.battery, p.batteryWarranty.let { if (it.isBlank()) "Lithium battery" else "Lithium · $it warranty" })
    spec(R.drawable.cat_structure, 0xFF546E7A.toInt(), "${p.structure} structure", listOf(p.v("ac wire").let { if (it.isBlank()) "" else "AC $it" }, p.v("earthing").let { if (it.isBlank()) "" else "Earthing $it" }).filter { it.isNotBlank() }.joinToString(" · "))

    body.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, dp(1)).margins(this, t = 4, b = 10))
    body.addView(horizontal {
        addView(vertical {
            addView(text("Price incl. GST", 11.5f, Ui.MUTED))
            addView(text(Fmt.inr(p.price), 24f, Ui.BRAND_DARK, bold = true))
        }, lp(0, WRAP, 1f))
        val sub = Money.subsidy(p, true)
        if (sub > 0) addView(vertical {
            gravity = Gravity.END
            addView(text("Subsidy up to", 11.5f, Ui.MUTED) { setSingleLine() }, lp(WRAP, WRAP))
            addView(text(Fmt.inr(sub), 16f, 0xFF2E7D32.toInt(), bold = true) { setSingleLine() }, lp(WRAP, WRAP))
        }, lp(WRAP, WRAP))
    })
    outer.addView(body)
    return outer
}

fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() and 0xFF
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Picks the system that best matches the customer: sanctioned load first, then bill. */
fun recommendKw(v: Visit): Double? {
    val load = Fmt.parse(v.sanctionedLoad)
    val bill = Fmt.parse(v.monthlyBill)
    val all = Catalog.packages.map { it.kw }.distinct().sorted()
    if (all.isEmpty()) return null
    val fromBill = bill?.let { b ->
        val units = b / Catalog.settings.num("tariff_per_unit", 7.0)
        units / (Catalog.settings.num("units_per_kw_per_day", 4.0) * 30)
    }
    val target = when {
        load != null && fromBill != null -> minOf(load, fromBill.coerceAtLeast(all.first()))
        load != null -> load
        fromBill != null -> fromBill
        else -> return null
    }
    return all.lastOrNull { it <= target + 0.25 } ?: all.first()
}

/** Shows a tab strip "On-grid | Hybrid" and returns the container. */
fun Activity.typeSwitch(current: SystemType, onPick: (SystemType) -> Unit): View = horizontal {
    background = rounded(Color.WHITE, dpf(16f), Ui.LINE, dp(1))
    setPadding(dp(4), dp(4), dp(4), dp(4))
    SystemType.entries.forEach { t ->
        val on = t == current
        addView(vertical {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = if (on) rounded(t.color, dpf(13f)) else null
            addView(icon(t.icon, if (on) Color.WHITE else t.color, 28))
            addView(text(t.label, 15f, if (on) Color.WHITE else Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, t = 4))
            addView(text(t.blurb, 11f, if (on) 0xDDFFFFFF.toInt() else Ui.MUTED) { gravity = Gravity.CENTER; maxLines = 2 })
            isClickable = true
            setOnClickListener { onPick(t) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
}
