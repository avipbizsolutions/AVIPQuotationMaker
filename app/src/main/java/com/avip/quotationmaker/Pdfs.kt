package com.avip.quotationmaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The two A4 PDFs of a visit:
 *  • [quotation] — for the customer: system, picture of how it connects, bill of
 *    materials, price, subsidy, savings, PM Surya Ghar steps, terms, signatures.
 *  • [siteReport] — for the office: the paper "Customer Solar Site Visit Report"
 *    with GPS, roof details, sketch, structure table and photos.
 */
class Pdfs(private val context: Context) {

    private val pageW = 595
    private val pageH = 842
    private val margin = 36f
    private val right get() = pageW - margin
    private val width get() = right - margin

    private val brand = 0xFF0E6B3A.toInt()
    private val brandDark = 0xFF08402A.toInt()
    private val brandLight = 0xFFE8F3EC.toInt()
    private val sun = 0xFFF7A21B.toInt()
    private val sunLight = 0xFFFFF4E0.toInt()
    private val ink = 0xFF17211B.toInt()
    private val muted = 0xFF5F6B64.toInt()
    private val rule = 0xFFD5DDD8.toInt()
    private val green = 0xFF2E7D32.toInt()
    private val blue = 0xFF1565C0.toInt()

    private val s get() = Catalog.settings
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    private lateinit var doc: PdfDocument
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = 0f
    private var pageNo = 0
    private var footer = ""

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            textAlign = align
        }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun stroke(color: Int, w: Float = 0.8f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w
    }

    // =====================================================================
    // Quotation
    // =====================================================================

    fun quotation(v: Visit, out: File) {
        val p = v.pkg ?: error("no system")
        start("${s["brand_name"]}  ·  ${s["phone"]}  ·  ${s["email"]}  ·  ${s["website"]}")
        val date = Date(if (v.quotedAt > 0) v.quotedAt else System.currentTimeMillis())
        val days = s.num("quote_validity_days", 7.0).toInt()
        letterhead("QUOTATION", listOf(
            "No: ${v.number}", "Date: ${dateFmt.format(date)}",
        ), "Valid till ${dateFmt.format(Date(date.time + days * 86_400_000L))}")

        // ---- customer | system ----
        val half = (width - 12f) / 2f
        val top = y
        var ly = top + 14f
        canvas.drawText("PREPARED FOR", margin + 10f, ly, paint(7.5f, muted, true)); ly += 14f
        ly = wrapText(v.name, margin + 10f, ly, half - 20f, paint(12f, ink, true), 14f)
        val cust = listOf(
            "WhatsApp: ${v.mobile}",
            listOf(v.address, v.district, v.pincode).filter { it.isNotBlank() }.joinToString(", "),
            listOfNotNull(v.caNo.takeIf { it.isNotBlank() }?.let { "CA No: $it" }, v.discom.takeIf { it.isNotBlank() }).joinToString("  ·  "),
            listOfNotNull(v.sanctionedLoad.takeIf { it.isNotBlank() }?.let { "Sanctioned load: $it kW" }, v.phase.takeIf { it.isNotBlank() }).joinToString("  ·  "),
            v.funding.takeIf { it.isNotBlank() }?.let { "Funding: $it" } ?: "",
        ).filter { it.isNotBlank() }
        cust.forEach { ly = wrapText(it, margin + 10f, ly, half - 20f, paint(8.5f), 11f) }

        val rx = margin + half + 22f
        val prepLines = wrap("Prepared by ${v.officerName}  ·  ${v.officerMobile}", half - 20f, paint(8.5f)).size
        val ry = top + 14f + 20f + 14f + 15f + 11f + prepLines * 11f
        val bottom = maxOf(ly, ry) + 4f
        canvas.drawRoundRect(RectF(margin, top, margin + half, bottom), 6f, 6f, stroke(rule))
        canvas.drawRoundRect(RectF(margin + half + 12f, top, right, bottom), 6f, 6f, fill(brandLight))
        redrawSystemBox(p, v, rx, top, half)
        y = bottom + 14f

        // ---- diagram ----
        systemDiagram(p)

        // ---- bill of materials ----
        heading("What you get")
        bomTable(p, v)

        // ---- price ----
        priceBlock(v, p)
        if (v.isLoan) loanBlock(v, p)

        // ---- savings ----
        savingsBlock(v, p)

        // ---- PM Surya Ghar steps ----
        if (Money.subsidy(p, true) > 0) processSteps(v.isLoan)

        // ---- trust line ----
        val trust = "${s["installs_count"]} homes powered across Bihar under PM Surya Ghar  ·  Quality brands: Microtek, Adani, Waaree, Deye, APL Apollo, Finolex"
        need(28f)
        canvas.drawRoundRect(RectF(margin, y, right, y + 22f), 6f, 6f, fill(sunLight))
        canvas.drawText(trust, pageW / 2f, y + 14.5f, paint(8.2f, ink, true, Paint.Align.CENTER))
        y += 32f

        // ---- bank & terms ----
        textSection("Bank details for payment", listOf(
            "A/c Name: ${s["bank_account_name"]}",
            "A/c No: ${s["bank_account_no"]}   ·   IFSC: ${s["bank_ifsc"]}   ·   ${s["bank_branch"]}",
            "GSTIN: ${s["gstin"]}",
        ))
        val terms = if (v.isLoan) s.list("q_terms").map {
            it.replace("customer's bank account", "customer's loan account").replace("bank account", "loan account")
        } + s.list("q_loan_terms") else s.list("q_terms")
        if (terms.isNotEmpty()) textSection("Terms & conditions", terms.mapIndexed { i, t -> "${i + 1}. $t" })
        textSection("Note", listOf("Savings and payback are estimates based on typical Bihar sunshine and your tariff; actual generation depends on weather, shading and usage. Subsidy is as per the PM Surya Ghar scheme rules on the date of application."))

        signatures(v, "Customer acceptance")
        finish(out)
    }

    private fun redrawSystemBox(p: SystemPackage, v: Visit, rx: Float, top: Float, half: Float) {
        var ry = top + 14f
        canvas.drawText("YOUR SOLAR SYSTEM", rx, ry, paint(7.5f, muted, true)); ry += 20f
        canvas.drawText("${Fmt.one(p.kwp)} kWp ${p.type.label}", rx, ry, paint(17f, brand, true)); ry += 14f
        val tierW = paint(8f, Color.WHITE, true).measureText(p.tier.uppercase()) + 14f
        canvas.drawRoundRect(RectF(rx, ry - 9f, rx + tierW, ry + 4f), 6f, 6f, fill(Ui.tierColor(p.tier)))
        canvas.drawText(p.tier.uppercase(), rx + 7f, ry, paint(8f, Color.WHITE, true))
        canvas.drawText(p.name, rx + tierW + 8f, ry, paint(8.5f, muted)); ry += 15f
        canvas.drawText("${p.totalPanels} panels  ·  ${p.panelBrand}", rx, ry, paint(8.5f)); ry += 11f
        wrapText("Prepared by ${v.officerName}  ·  ${v.officerMobile}", rx, ry, half - 20f, paint(8.5f), 11f)
    }

    /** Panels → inverter (→ battery) → home → net meter → grid, drawn with shapes. */
    private fun systemDiagram(p: SystemPackage) {
        val h = 128f
        need(h + 10f)
        val top = y
        canvas.drawRoundRect(RectF(margin, top, right, top + h), 8f, 8f, fill(0xFFF7FAF8.toInt()))
        val cy = top + 58f
        val hybrid = p.type == SystemType.HYBRID
        val n = if (hybrid) 6 else 5
        val step = (width - 40f) / (n - 1)
        val xs = (0 until n).map { margin + 20f + it * step }
        val red = 0xFFD64545.toInt()
        val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; strokeWidth = 2f; style = Paint.Style.STROKE }
        val dc = Paint(wire).apply { color = 0xFF263238.toInt() }
        for (i in 0 until n - 1) {
            canvas.drawLine(xs[i] + 22f, cy - 2f, xs[i + 1] - 22f, cy - 2f, if (i == 0) wire else dc)
            canvas.drawLine(xs[i] + 22f, cy + 2f, xs[i + 1] - 22f, cy + 2f, if (i == 0) dc else Paint(wire).apply { color = blue })
            arrow(xs[i + 1] - 26f, cy, sun)
        }
        // 1 panels
        val px = xs[0]
        val panel = fill(0xFF1A3A6B.toInt())
        for (r in 0..1) for (c in 0..2) {
            val l = px - 24f + c * 16.5f; val t = cy - 26f + r * 22f
            canvas.drawRect(l, t, l + 15f, t + 20f, panel)
            canvas.drawLine(l + 7.5f, t, l + 7.5f, t + 20f, stroke(0xFF5C7FB0.toInt(), 0.5f))
            canvas.drawLine(l, t + 10f, l + 15f, t + 10f, stroke(0xFF5C7FB0.toInt(), 0.5f))
        }
        // sun
        canvas.drawCircle(px + 26f, top + 14f, 7f, fill(sun))
        label(px, "${p.totalPanels} Solar Panels", p.panelBrand, top)

        // 2 inverter
        val ix = xs[1]
        canvas.drawRoundRect(RectF(ix - 18f, cy - 24f, ix + 18f, cy + 24f), 4f, 4f, fill(Color.WHITE))
        canvas.drawRoundRect(RectF(ix - 18f, cy - 24f, ix + 18f, cy + 24f), 4f, 4f, stroke(muted, 1.2f))
        canvas.drawRect(ix - 11f, cy - 15f, ix + 11f, cy - 4f, fill(0xFF263238.toInt()))
        canvas.drawText("~", ix, cy + 12f, paint(14f, brand, true, Paint.Align.CENTER))
        label(ix, if (hybrid) "Hybrid Inverter" else "Inverter", p.inverter, top)

        var idx = 2
        if (hybrid) {
            val bx = xs[idx++]
            canvas.drawRoundRect(RectF(bx - 14f, cy - 22f, bx + 14f, cy + 24f), 3f, 3f, fill(green))
            canvas.drawRect(bx - 5f, cy - 26f, bx + 5f, cy - 22f, fill(green))
            canvas.drawText("Li", bx, cy + 6f, paint(11f, Color.WHITE, true, Paint.Align.CENTER))
            label(bx, "Lithium Battery", p.battery.substringBefore(',').trim(), top)
        }
        // home
        val hx = xs[idx++]
        val roof = Path().apply { moveTo(hx - 24f, cy - 4f); lineTo(hx, cy - 26f); lineTo(hx + 24f, cy - 4f); close() }
        canvas.drawPath(roof, fill(0xFFB5462E.toInt()))
        canvas.drawRect(hx - 18f, cy - 4f, hx + 18f, cy + 24f, fill(0xFFF3E3C3.toInt()))
        canvas.drawRect(hx - 5f, cy + 8f, hx + 5f, cy + 24f, fill(0xFF8D6E63.toInt()))
        label(hx, "Your Home", "uses solar first", top)
        // meter
        val mx = xs[idx++]
        canvas.drawRoundRect(RectF(mx - 15f, cy - 20f, mx + 15f, cy + 22f), 5f, 5f, fill(Color.WHITE))
        canvas.drawRoundRect(RectF(mx - 15f, cy - 20f, mx + 15f, cy + 22f), 5f, 5f, stroke(muted, 1.2f))
        canvas.drawRect(mx - 10f, cy - 13f, mx + 10f, cy - 3f, fill(0xFF263238.toInt()))
        canvas.drawText("kWh", mx, cy - 5.5f, paint(5.5f, 0xFF7CF29A.toInt(), true, Paint.Align.CENTER))
        canvas.drawText("⇄", mx, cy + 14f, paint(11f, blue, true, Paint.Align.CENTER))
        label(mx, "Net Meter", "export extra units", top)
        // grid
        val gx = xs[idx]
        val pole = stroke(0xFF455A64.toInt(), 2f)
        canvas.drawLine(gx, cy - 28f, gx, cy + 26f, pole)
        canvas.drawLine(gx - 14f, cy - 20f, gx + 14f, cy - 20f, pole)
        canvas.drawLine(gx - 10f, cy - 10f, gx + 10f, cy - 10f, pole)
        label(gx, "DISCOM Grid", "backup & net credit", top)

        // warranty ribbons
        val pw = p.panelWarranty; val iw = p.inverterWarranty
        if (pw.isNotBlank()) ribbon(px, top + h - 4f, "$pw warranty")
        if (iw.isNotBlank()) ribbon(ix, top + h - 4f, "$iw warranty")
        if (hybrid && p.batteryWarranty.isNotBlank()) ribbon(xs[2], top + h - 4f, "${p.batteryWarranty} warranty")
        y = top + h + 12f
    }

    private fun arrow(x: Float, cy: Float, color: Int) {
        val path = Path().apply { moveTo(x - 5f, cy - 5f); lineTo(x + 1f, cy); lineTo(x - 5f, cy + 5f); close() }
        canvas.drawPath(path, fill(color))
    }

    private fun label(x: Float, title: String, sub: String, top: Float) {
        canvas.drawText(title, x, top + 98f, paint(7.8f, ink, true, Paint.Align.CENTER))
        val subP = paint(6.8f, muted, align = Paint.Align.CENTER)
        var t = sub
        while (t.length > 3 && subP.measureText(t) > 92f) t = t.dropLast(2)
        if (t != sub) t = t.trimEnd() + "…"
        canvas.drawText(t, x, top + 107f, subP)
    }

    private fun ribbon(x: Float, yy: Float, text: String) {
        val p = paint(6.5f, Color.WHITE, true, Paint.Align.CENTER)
        val w = p.measureText(text) + 10f
        val r = RectF(x - w / 2, yy - 10f, x + w / 2, yy)
        canvas.drawRoundRect(r, 4f, 4f, fill(green))
        canvas.drawText(text, x, r.bottom - 2.6f, p)
    }

    private fun bomTable(p: SystemPackage, v: Visit) {
        data class R(val item: String, val spec: String, val qty: String, val warranty: String, val highlight: Boolean = false)
        val rows = mutableListOf<R>()
        val tech = if (p.rows.any { it.first.contains("topcon", true) }) "TOPCon " else ""
        if (p.dcrCount > 0) rows += R("${tech}Solar Panels (DCR)", "${p.panelBrand} · ${Fmt.num(p.dcrWp)} Wp · made-in-India cells", "${p.dcrCount} nos", p.panelWarranty)
        if (p.nonDcrCount > 0) rows += R("${tech}Solar Panels (Non-DCR)", "${p.panelBrand} · ${Fmt.num(p.nonDcrWp)} Wp", "${p.nonDcrCount} nos", p.panelWarranty)
        if (p.dcrCount == 0 && p.nonDcrCount == 0) rows += R("Solar Panels", p.panelBrand, "${p.totalPanels} nos", p.panelWarranty)
        rows += R(if (p.type == SystemType.HYBRID) "Hybrid Inverter" else "Grid-tie Inverter", p.inverter, "1 no", p.inverterWarranty)
        if (p.battery.isNotBlank()) rows += R("Lithium Battery", p.battery, "1 no", p.batteryWarranty)
        if (p.type == SystemType.HYBRID) rows += R("MCB Changeover", "Grid / backup switching", "1 no", "")
        p.v("acdb").takeIf { it.isNotBlank() }?.let { rows += R("ACDB + DCDB", "$it · with protection", "1 set", "") }
        val structureSpec = when {
            v.structureType == "Magnelis" -> "Magnelis (zinc-magnesium coated) · " + p.structure.ifBlank { "APL Apollo" } +
                (if (v.mountingLayout.isNotBlank()) " · ${v.mountingLayout} module mounting" else "")
            v.isGi -> "Hot-dip GI · " + p.structure.ifBlank { "APL Apollo" }
            else -> p.structure
        }
        if (structureSpec.isNotBlank()) rows += R("Mounting Structure", structureSpec, "1 set", "")
        p.v("dc wire").takeIf { it.isNotBlank() }?.let { rows += R("DC Cable", it, "As required", "") }
        p.v("ac wire").takeIf { it.isNotBlank() }?.let { rows += R("AC Cable", it, "As required", "") }
        p.v("earthing").takeIf { it.isNotBlank() }?.let { rows += R("Earthing", it, "1 set", "") }
        rows += R("Lightning Arrester", "Standard", "1 no", "")
        // rows the team adds to the sheet later
        val known = listOf("panel", "inverter", "battery", "acdb", "structure", "dc wire", "ac wire", "earthing", "category", "particulars")
        p.specRows().filter { (l, _) -> known.none { l.lowercase().contains(it) } }.forEach { (l, value) -> rows += R(l, value, "", "") }
        rows += R("Installation & Commissioning", "Transport, fitting, testing", "Included", "")
        rows += R("PM Surya Ghar Paperwork", "Application, net-meter & subsidy support", "Included", "")
        if (v.nameCorrection) rows += R("Name Correction", "Electricity connection name correction · complimentary AVIP service", "FREE", "", highlight = true)
        if (v.nameTransferFee > 0) rows += R("Name Transfer", "Electricity connection name transfer with DISCOM · paid separately", Fmt.inr(v.nameTransferFee), "", highlight = true)

        val cols = floatArrayOf(22f, 150f, 0f, 62f, 66f)
        cols[2] = width - cols.sum()
        val titles = listOf("#", "Item", "Brand / Specification", "Qty", "Warranty")
        fun header() {
            canvas.drawRect(margin, y, right, y + 18f, fill(brand))
            var x = margin
            titles.forEachIndexed { i, t ->
                canvas.drawText(t, if (i == 0) x + cols[i] / 2 else x + 6f, y + 12.3f, paint(7.8f, Color.WHITE, true, if (i == 0) Paint.Align.CENTER else Paint.Align.LEFT))
                x += cols[i]
            }
            y += 18f
        }
        need(40f); header()
        val sp = paint(8f, ink)
        rows.forEachIndexed { i, r ->
            val specLines = wrap(r.spec, cols[2] - 10f, sp)
            val itemLines = wrap(r.item, cols[1] - 10f, paint(8.3f, ink, true))
            val h = 7f + maxOf(specLines.size, itemLines.size) * 10.5f + 3f
            if (y + h > pageH - 60f) { newPage(); header() }
            if (r.highlight) {
                canvas.drawRect(margin, y, right, y + h, fill(sunLight))
                canvas.drawRect(margin, y, margin + 3f, y + h, fill(sun))
            } else if (i % 2 == 1) canvas.drawRect(margin, y, right, y + h, fill(0xFFF6F9F7.toInt()))
            var x = margin
            val base = y + 13f
            canvas.drawText("${i + 1}", x + cols[0] / 2, base, paint(8f, muted, align = Paint.Align.CENTER)); x += cols[0]
            itemLines.forEachIndexed { k, t -> canvas.drawText(t, x + 6f, base + k * 10.5f, paint(8.3f, ink, true)) }; x += cols[1]
            specLines.forEachIndexed { k, t -> canvas.drawText(t, x + 6f, base + k * 10.5f, sp) }; x += cols[2]
            canvas.drawText(r.qty, x + 6f, base, if (r.highlight) paint(8.5f, green, true) else paint(8f, ink)); x += cols[3]
            if (r.warranty.isNotBlank()) canvas.drawText(r.warranty, x + 6f, base, paint(8f, green, true))
            y += h
            canvas.drawLine(margin, y, right, y, stroke(rule, 0.5f))
        }
        y += 12f
    }

    private fun priceBlock(v: Visit, p: SystemPackage) {
        val sub = Money.subsidy(p, v.subsidyOn)
        val central = Money.central(p, v.subsidyOn)
        val state = Money.state(p, v.subsidyOn)
        val leftW = width * 0.56f
        val g = Money.gst(v.finalPrice)
        val wordsLines = wrap(Fmt.words(v.finalPrice), leftW - 24f, paint(7.3f)).size
        val priceLines = 1 + (if (v.discount > 0) 1 else 0) + (if (v.nameCorrection) 1 else 0)
        val leftH = 18f + priceLines * 13f + 6f + 20f + wordsLines * 9f + 10f + 12f + 3 * 11.5f + 6f
        val h = maxOf(leftH, if (sub > 0) 112f else 96f)
        need(h + 10f + (if (v.nameTransferFee > 0) 30f else 0f))
        val top = y
        // left: price
        canvas.drawRoundRect(RectF(margin, top, margin + leftW, top + h), 8f, 8f, fill(brand))
        var ly = top + 18f
        fun line(l: String, value: String) {
            canvas.drawText(l, margin + 12f, ly, paint(8.8f, 0xDDFFFFFF.toInt()))
            canvas.drawText(value, margin + leftW - 12f, ly, paint(8.8f, Color.WHITE, align = Paint.Align.RIGHT))
            ly += 13f
        }
        line("System price (incl. GST)", Fmt.inr(p.price))
        if (v.discount > 0) line("Special discount", "− " + Fmt.inr(v.discount))
        if (v.nameCorrection) {
            canvas.drawText("★ Name correction (connection)", margin + 12f, ly, paint(8.8f, sun, true))
            canvas.drawText("FREE", margin + leftW - 12f, ly, paint(9f, sun, true, Paint.Align.RIGHT))
            ly += 13f
        }
        ly += 6f
        canvas.drawText("FINAL PRICE (incl. GST)", margin + 12f, ly + 2f, paint(8f, sun, true))
        canvas.drawText(Fmt.inr(v.finalPrice), margin + leftW - 12f, ly + 6f, paint(20f, Color.WHITE, true, Paint.Align.RIGHT))
        ly += 20f
        ly = wrapText(Fmt.words(v.finalPrice), margin + 12f, ly, leftW - 24f, paint(7.3f, 0xCCFFFFFF.toInt()), 9f)
        // GST billing (composite supply: goods share at goods rate, services share at service rate)
        ly += 2f
        canvas.drawLine(margin + 12f, ly, margin + leftW - 12f, ly, stroke(0x55FFFFFF, 0.6f))
        ly += 10f
        canvas.drawText("GST BILLING", margin + 12f, ly, paint(7f, sun, true))
        ly += 11.5f
        val gl = paint(7.6f, 0xDDFFFFFF.toInt())
        val gr = paint(7.6f, Color.WHITE, align = Paint.Align.RIGHT)
        val rx0 = margin + leftW - 12f
        canvas.drawText("${Fmt.num(g.goodsShare)}% supply · taxable ${Fmt.inr(g.goodsTaxable)} @ ${Fmt.num(g.goodsRate)}%", margin + 12f, ly, gl)
        canvas.drawText("GST ${Fmt.inr(g.goodsGst)}", rx0, ly, gr); ly += 11.5f
        canvas.drawText("${Fmt.num(100 - g.goodsShare)}% services · taxable ${Fmt.inr(g.serviceTaxable)} @ ${Fmt.num(g.serviceRate)}%", margin + 12f, ly, gl)
        canvas.drawText("GST ${Fmt.inr(g.serviceGst)}", rx0, ly, gr); ly += 11.5f
        canvas.drawText("Total taxable value ${Fmt.inr(g.taxable)}", margin + 12f, ly, paint(7.6f, Color.WHITE, true))
        canvas.drawText("Total GST ${Fmt.inr(g.totalGst)}", rx0, ly, paint(7.6f, Color.WHITE, true, Paint.Align.RIGHT))

        // right: subsidy & effective cost
        val rx = margin + leftW + 10f
        canvas.drawRoundRect(RectF(rx, top, right, top + h), 8f, 8f, fill(if (sub > 0) 0xFFEAF6EC.toInt() else 0xFFF3F5F4.toInt()))
        var ry = top + 18f
        if (sub > 0) {
            val rr = right - 12f
            canvas.drawText("GOVERNMENT SUBSIDY", rx + 12f, ry, paint(7.5f, green, true)); ry += 14f
            canvas.drawText("PM Surya Ghar (central)", rx + 12f, ry, paint(8f, ink))
            canvas.drawText("− " + Fmt.inr(central), rr, ry, paint(8.5f, green, true, Paint.Align.RIGHT)); ry += 12f
            if (state > 0) {
                canvas.drawText(s["state_subsidy_name"], rx + 12f, ry, paint(8f, ink))
                canvas.drawText("− " + Fmt.inr(state), rr, ry, paint(8.5f, green, true, Paint.Align.RIGHT)); ry += 12f
            }
            canvas.drawLine(rx + 12f, ry - 6f, rr, ry - 6f, stroke(rule, 0.6f))
            canvas.drawText("Total subsidy", rx + 12f, ry + 3f, paint(8f, ink, true))
            canvas.drawText("− " + Fmt.inr(sub), rr, ry + 3f, paint(10f, green, true, Paint.Align.RIGHT)); ry += 10f
            canvas.drawText(if (v.isLoan) "credited directly to your loan account" else "credited to your bank account", rx + 12f, ry + 2f, paint(6.8f, muted)); ry += 16f
            canvas.drawText("YOUR EFFECTIVE COST", rx + 12f, ry, paint(7.5f, ink, true)); ry += 17f
            canvas.drawText(Fmt.inr(v.finalPrice - sub), rx + 12f, ry, paint(17f, brandDark, true))
        } else {
            canvas.drawText("PRICE INCLUDES", rx + 12f, ry, paint(7.5f, ink, true)); ry += 14f
            listOf("GST", "Transport & installation", "Wiring, earthing, LA", "Paperwork support").forEach {
                canvas.drawText("✓  $it", rx + 12f, ry, paint(8.5f, ink)); ry += 12f
            }
        }
        y = top + h + 8f
        // name transfer is not part of the price: shown as a separate payment
        if (v.nameTransferFee > 0) {
            canvas.drawRoundRect(RectF(margin, y, right, y + 22f), 6f, 6f, fill(sunLight))
            canvas.drawRect(margin, y, margin + 3f, y + 22f, fill(sun))
            canvas.drawText("Name transfer on electricity connection — payable separately (not included in the final price)", margin + 12f, y + 14.5f, paint(8f, ink, true))
            canvas.drawText(Fmt.inr(v.nameTransferFee), right - 10f, y + 14.5f, paint(10f, brandDark, true, Paint.Align.RIGHT))
            y += 30f
        }
        y += 6f
    }

    private fun loanBlock(v: Visit, p: SystemPackage) {
        val amt = Money.loanAmount(v)
        val emi = Money.emi(v)
        if (amt <= 0 || emi <= 0) return
        val months = v.loanYears * 12
        need(62f)
        val top = y
        canvas.drawRoundRect(RectF(margin, top, right, top + 52f), 8f, 8f, fill(0xFFEAF1FB.toInt()))
        canvas.drawText("SOLAR LOAN", margin + 12f, top + 15f, paint(7.5f, blue, true))
        canvas.drawText("${Fmt.inr(emi)} / month", margin + 12f, top + 35f, paint(17f, blue, true))
        canvas.drawText("for $months months", margin + 12f, top + 46f, paint(7.5f, muted))
        val cols = listOf(
            "Loan amount" to Fmt.inr(amt),
            "Own contribution" to Fmt.inr(Money.downPayment(v)),
            "Interest rate" to "${Fmt.num(Money.loanRate(amt))}% p.a.",
            "Total interest" to Fmt.inr(emi * months - amt),
        )
        val startX = margin + 150f
        val w = (right - 12f - startX) / cols.size
        cols.forEachIndexed { i, (l, value) ->
            val x = startX + i * w
            canvas.drawText(l.uppercase(), x, top + 20f, paint(6.5f, muted, true))
            canvas.drawText(value, x, top + 35f, paint(10f, ink, true))
        }
        val save = Money.yearlySaving(p) / 12
        if (save > 0) canvas.drawText("Your electricity saving of about ${Fmt.inr(save)} a month pays a big part of this EMI.", startX, top + 47f, paint(7f, muted))
        y = top + 62f
    }

    private fun savingsBlock(v: Visit, p: SystemPackage) {
        val units = Money.yearlyUnits(p)
        val saving = Money.yearlySaving(p)
        val payback = Money.paybackYears(v)
        heading("Your estimated savings")
        need(64f)
        val stats = listOf(
            "${Fmt.one(p.kwp)} kWp" to "system size",
            "${(units / 12).toInt()} units" to "generated per month",
            Fmt.inr(saving / 12) to "saved per month",
            (if (payback > 0) "${Fmt.one(payback)} years" else "—") to "payback",
            Fmt.inr(Money.lifetimeSaving(p)) to "saved in ${Money.savingsYears()} years",
        )
        val gap = 6f
        val w = (width - gap * (stats.size - 1)) / stats.size
        stats.forEachIndexed { i, (value, label) ->
            val x = margin + i * (w + gap)
            canvas.drawRoundRect(RectF(x, y, x + w, y + 46f), 6f, 6f, fill(if (i == stats.size - 1) 0xFFEAF6EC.toInt() else 0xFFF6F9F7.toInt()))
            canvas.drawText(value, x + w / 2, y + 21f, paint(11.5f, if (i == stats.size - 1) green else brandDark, true, Paint.Align.CENTER))
            canvas.drawText(label, x + w / 2, y + 35f, paint(7.2f, muted, align = Paint.Align.CENTER))
        }
        y += 56f
        canvas.drawText(
            "Based on about ${Fmt.num(s.num("units_per_kw_per_day", 4.0))} units per kW per day and ₹${Fmt.num(s.num("tariff_per_unit", 7.0))} per unit.",
            margin, y, paint(7.2f, muted),
        )
        y += 14f
    }

    private fun processSteps(loan: Boolean) {
        heading("How PM Surya Ghar works — we handle it with you")
        val steps = listOf(
            "Register" to "on pmsuryaghar.gov.in with your CA No",
            "Approval" to "DISCOM feasibility for your connection",
            "Install" to "AVIP installs & commissions the system",
            "Net meter" to "DISCOM fits net meter & inspects",
            "Subsidy" to (if (loan) "central + Bihar subsidy to your loan account" else "central + Bihar subsidy to your bank"),
        )
        need(66f)
        val w = width / steps.size
        val cy = y + 14f
        canvas.drawLine(margin + w / 2, cy, right - w / 2, cy, stroke(sun, 2f))
        steps.forEachIndexed { i, (t, d) ->
            val cx = margin + w * i + w / 2
            canvas.drawCircle(cx, cy, 11f, fill(if (i == steps.size - 1) green else brand))
            canvas.drawText("${i + 1}", cx, cy + 4f, paint(10f, Color.WHITE, true, Paint.Align.CENTER))
            canvas.drawText(t, cx, cy + 25f, paint(8.5f, ink, true, Paint.Align.CENTER))
            var yy = cy + 35f
            wrap(d, w - 10f, paint(7f, muted)).forEach { line ->
                canvas.drawText(line, cx, yy, paint(7f, muted, align = Paint.Align.CENTER)); yy += 8.5f
            }
        }
        y += 70f
    }

    // =====================================================================
    // Site visit report
    // =====================================================================

    fun siteReport(v: Visit, out: File) {
        val p = v.pkg
        start("${s["company_name"]}  ·  Site visit ${v.number}")
        letterhead("SITE VISIT REPORT", listOf("No: ${v.number}", "Date: ${dateFmt.format(Date(v.createdAt))}"), "Customer Solar Site Visit Report")

        grid(listOf(
            "Date of visit" to dateFmt.format(Date(v.createdAt)),
            "WhatsApp mobile" to v.mobile,
            "Consumer name" to v.name,
            "Address" to listOf(v.address, v.district, v.pincode).filter { it.isNotBlank() }.joinToString(", "),
            "Name correction / transfer" to v.nameChange.let { if (it == "Name transfer") "Name transfer (${Fmt.inr(v.nameTransferFee)})" else if (it == "Name correction") "Name correction (free)" else it },
            "Project funding" to (if (v.isLoan) "Loan (${v.loanYears} yrs, EMI ${Fmt.inr(Money.emi(v))})" else v.funding),
            "Bank proof for subsidy" to (if (v.bankDocs.isEmpty() || !v.subsidyOn) "" else "${v.bankDocType.ifBlank { "Photo" }} — ${v.bankDocs.size} photo(s) attached"),
            "PAN" to (if (v.isLoan) v.pan else ""),
            "Aadhaar" to (if (v.isLoan) v.aadhaar else ""),
            "Consumer / CA No" to v.caNo,
            "DISCOM · Connection" to listOf(v.discom, v.phase).filter { it.isNotBlank() }.joinToString(" · "),
            "Smart meter" to v.smartMeter,
            "Sanctioned load" to v.sanctionedLoad.let { if (it.isBlank()) "" else "$it kW" },
            "Monthly bill" to v.monthlyBill.let { if (it.isBlank()) "" else "₹$it" },
        ).filter { it.second.isNotBlank() || it.first !in setOf("PAN", "Aadhaar", "Bank proof for subsidy") })

        heading("Proposed solar system")
        grid(listOf(
            "Solar capacity" to (p?.let { "${Fmt.one(it.kwp)} kWp ${it.type.label} (${it.name})" } ?: "Not selected"),
            "Panel brand & capacity" to (p?.let { "${it.panelBrand} · ${it.panelSummary()}" } ?: ""),
            "Inverter brand & capacity" to (p?.inverter ?: ""),
            "Battery" to (p?.battery ?: ""),
            "Structure" to (v.structureType.ifBlank { p?.structure ?: "" }),
            "Quoted price" to (p?.let { Fmt.inr(v.finalPrice) + if (v.discount > 0) " (after ${Fmt.inr(v.discount)} discount)" else "" } ?: ""),
        ).filter { it.second.isNotBlank() })

        heading("Site details")
        val area = (Fmt.parse(v.roofLength) ?: 0.0) * (Fmt.parse(v.roofWidth) ?: 0.0)
        grid(listOf(
            "GPS location" to (if (v.hasLocation) String.format(Locale.US, "%.6f, %.6f", v.lat, v.lng) else "Not captured"),
            "Map" to (if (v.hasLocation) String.format(Locale.US, "maps.google.com/?q=%.6f,%.6f", v.lat, v.lng) else ""),
            "Roof type" to v.roofType,
            "Building height" to v.floors,
            "Shadow-free area" to (if (area > 0) "${v.roofLength} × ${v.roofWidth} ft  (≈ ${area.toInt()} sq ft)" else ""),
            "Shading" to v.shading,
            "Structure height" to v.structureHeight,
            "Cable run (inverter → meter)" to v.cableRun.let { if (it.isBlank()) "" else "$it m" },
        ).filter { it.second.isNotBlank() })

        // sketch
        heading("Diagram of solar site location")
        val sketch = v.sketch.takeIf { it.isNotBlank() }?.let { VisitStore.file(context, v, it) }?.takeIf { it.exists() }
        if (sketch != null) image(sketch, width, 230f, border = true)
        else {
            need(60f)
            canvas.drawRoundRect(RectF(margin, y, right, y + 50f), 6f, 6f, stroke(rule))
            canvas.drawText("No sketch drawn", pageW / 2f, y + 29f, paint(9f, muted, align = Paint.Align.CENTER))
            y += 60f
        }

        // structure
        heading("Proposed structure design")
        if (v.isGi) structureTable(v)
        else grid(listOf(
            "Structure type" to v.structureType.ifBlank { "Not selected" },
            "Module mounting" to (if (v.structureType == "Magnelis") v.mountingLayout.ifBlank { "Not selected" } else "—"),
        ))

        val follow = listOf(
            "Lead status" to v.leadStatus,
            "Desired installation date" to (if (v.leadStatus == "Converted" && v.installAt > 0) dateFmt.format(Date(v.installAt)) else ""),
            "Next follow-up" to (if (v.followUpAt > 0 && v.leadStatus == "Open") dateFmt.format(Date(v.followUpAt)) + ", 10:00 AM" else ""),
            "Follow-up note" to v.followUpNote,
            "Voice notes" to (if (v.voiceNotes.isEmpty()) "" else "${v.voiceNotes.size} recording(s) — shared with this report"),
            "Site videos" to (if (v.videos.isEmpty()) "" else "${v.videos.size} video(s) — shared with this report"),
        ).filter { it.second.isNotBlank() }
        if (follow.size > 1) { heading("Follow-up"); grid(follow) }

        if (v.siteNotes.isNotBlank()) textSection("Notes for installation team", listOf(v.siteNotes))

        // photos
        val photos = v.photos.map { VisitStore.file(context, v, it) }.filter { it.exists() }
        if (photos.isNotEmpty()) {
            heading("Site photos (${photos.size})")
            val gap = 10f
            val w = (width - gap) / 2
            photos.chunked(2).forEach { pair ->
                val sizes = pair.map { bitmapSize(it) }
                val hs = sizes.map { (bw, bh) -> if (bw > 0) (w * bh / bw).coerceAtMost(220f) else 160f }
                val h = hs.maxOrNull() ?: 160f
                need(h + gap)
                pair.forEachIndexed { i, f ->
                    val x = margin + i * (w + gap)
                    drawImage(f, RectF(x, y, x + w, y + hs[i]))
                }
                y += h + gap
            }
        }

        val bank = if (!v.subsidyOn) emptyList() else v.bankDocs.map { VisitStore.file(context, v, it) }.filter { it.exists() }
        if (bank.isNotEmpty()) {
            heading("Bank proof for subsidy — ${v.bankDocType.ifBlank { "photo" }}")
            bank.forEach { image(it, width, 300f, border = true) }
        }

        if (v.isLoan) listOf("PAN card" to v.panDocs, "Aadhaar" to v.aadhaarDocs).forEach { (title, names) ->
            val files = names.map { VisitStore.file(context, v, it) }.filter { it.exists() }
            if (files.isNotEmpty()) {
                heading("$title — loan KYC proof")
                files.forEach { image(it, width, 260f, border = true) }
            }
        }

        signatures(v, "Customer signature")
        finish(out)
    }

    private fun grid(items: List<Pair<String, String>>) {
        val colW = (width - 12f) / 2
        items.chunked(2).forEach { row ->
            val heights = row.map { (_, value) -> 14f + wrap(value.ifBlank { "—" }, colW - 12f, paint(9f, ink, true)).size * 11f }
            val h = (heights.maxOrNull() ?: 24f) + 4f
            need(h + 4f)
            row.forEachIndexed { i, (label, value) ->
                val x = margin + i * (colW + 12f)
                canvas.drawRoundRect(RectF(x, y, x + colW, y + h), 5f, 5f, fill(0xFFF6F9F7.toInt()))
                canvas.drawText(label.uppercase(), x + 6f, y + 10f, paint(6.8f, muted, true))
                wrapText(value.ifBlank { "—" }, x + 6f, y + 22f, colW - 12f, paint(9f, ink, true), 11f)
            }
            y += h + 4f
        }
        y += 6f
    }

    private fun structureTable(v: Visit) {
        val cols = floatArrayOf(120f, 0f, 0f, 0f)
        val rest = (width - cols[0]) / 3
        cols[1] = rest; cols[2] = rest; cols[3] = rest
        need(20f + v.structure.size * 18f)
        val titles = listOf("Part", "Quantity", "Length (ft)", "Section size")
        canvas.drawRect(margin, y, right, y + 18f, fill(brand))
        var x = margin
        titles.forEachIndexed { i, t -> canvas.drawText(t, x + 6f, y + 12.3f, paint(8f, Color.WHITE, true)); x += cols[i] }
        y += 18f
        v.structure.forEachIndexed { r, part ->
            if (r % 2 == 1) canvas.drawRect(margin, y, right, y + 18f, fill(0xFFF6F9F7.toInt()))
            x = margin
            listOf(part.name, part.qty, part.length, part.size).forEachIndexed { i, t ->
                canvas.drawText(t.ifBlank { if (i == 0) "" else "—" }, x + 6f, y + 12.5f, paint(8.5f, ink, i == 0)); x += cols[i]
            }
            y += 18f
            canvas.drawLine(margin, y, right, y, stroke(rule, 0.5f))
        }
        y += 12f
    }

    // =====================================================================
    // shared pieces
    // =====================================================================

    private fun start(footerText: String) {
        footer = footerText
        doc = PdfDocument()
        pageNo = 0
        page = null
        newPage()
    }

    private fun finish(out: File) {
        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun letterhead(title: String, lines: List<String>, under: String) {
        val headTop = y
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.avip_solar_logo)
        val textLeft = margin
        val titleW = 160f
        val headW = right - titleW - textLeft - 10f
        var hy = headTop
        if (logo != null) {
            val lh = 44f
            val lw = lh * logo.width / logo.height
            canvas.drawBitmap(logo, null, RectF(margin, headTop, margin + lw, headTop + lh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            hy = headTop + lh + 12f
        } else hy += 15f
        hy = wrapText(s["company_name"], textLeft, hy, headW, paint(10.5f, ink, true), 12.5f)
        hy = wrapText(s["address"], textLeft, hy, headW, paint(8f, ink), 10.5f)
        hy = wrapText(listOf(s["phone"], s["email"], s["website"]).filter { it.isNotBlank() }.joinToString("  ·  "), textLeft, hy, headW, paint(8f, ink), 10.5f)
        hy = wrapText("GSTIN: ${s["gstin"]}", textLeft, hy, headW, paint(8f, ink, true), 10.5f)

        val tb = RectF(right - titleW, headTop, right, headTop + 60f)
        canvas.drawRoundRect(tb, 8f, 8f, fill(brand))
        canvas.drawText(title, tb.centerX(), tb.top + 20f, paint(if (title.length > 12) 11.5f else 15f, Color.WHITE, true, Paint.Align.CENTER))
        lines.forEachIndexed { i, l -> canvas.drawText(l, tb.centerX(), tb.top + 35f + i * 12f, paint(8f, Color.WHITE, align = Paint.Align.CENTER)) }
        canvas.drawText(under, tb.centerX(), tb.bottom + 11f, paint(7.5f, muted, align = Paint.Align.CENTER))
        y = maxOf(hy, headTop + 74f) + 4f
        canvas.drawRect(margin, y, right, y + 3f, fill(sun))
        y += 14f
    }

    private fun heading(t: String) {
        need(40f)
        canvas.drawRect(margin, y + 1f, margin + 3f, y + 12f, fill(sun))
        canvas.drawText(t.uppercase(), margin + 9f, y + 10.5f, paint(8.8f, brand, true))
        y += 20f
    }

    private fun textSection(title: String, lines: List<String>) {
        val p = paint(8f, ink)
        val needed = 22f + lines.sumOf { wrap(it, width, p).size.toDouble() * 10.5 }.toFloat()
        need(minOf(needed, 120f))
        canvas.drawText(title.uppercase(), margin, y + 10f, paint(7.8f, brand, true))
        y += 22f
        lines.forEach { y = wrapText(it, margin, y, width, p, 10.5f) }
        y += 6f
    }

    private fun signatures(v: Visit, leftLabel: String) {
        need(90f)
        y += 10f
        val w = (width - 40f) / 2
        // customer
        val sign = v.customerSign.takeIf { it.isNotBlank() }?.let { VisitStore.file(context, v, it) }?.takeIf { it.exists() }
        if (sign != null) drawImage(sign, RectF(margin, y, margin + w, y + 50f), fit = true)
        canvas.drawLine(margin, y + 54f, margin + w, y + 54f, stroke(ink, 0.6f))
        canvas.drawText(leftLabel, margin, y + 66f, paint(8.5f, ink, true))
        canvas.drawText(v.name, margin, y + 77f, paint(8f, muted))
        // officer
        val ox = right - w
        canvas.drawText("For ${s["company_name"]}", ox, y + 14f, paint(8.5f, ink, true))
        canvas.drawLine(ox, y + 54f, right, y + 54f, stroke(ink, 0.6f))
        canvas.drawText("Visiting Officer", ox, y + 66f, paint(8.5f, ink, true))
        canvas.drawText("${v.officerName}  ·  ${v.officerMobile}", ox, y + 77f, paint(8f, muted))
        y += 90f
    }

    private fun need(h: Float) { if (y + h > pageH - 50f) newPage() }

    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        canvas = page!!.canvas
        canvas.drawRect(0f, 0f, pageW.toFloat(), 6f, fill(brand))
        canvas.drawRect(pageW * 0.7f, 0f, pageW.toFloat(), 6f, fill(sun))
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val c = p.canvas
        c.drawLine(margin, pageH - 34f, right, pageH - 34f, stroke(rule, 0.6f))
        c.drawText(footer, margin, pageH - 22f, paint(7.3f, muted))
        c.drawText("Page $pageNo", right, pageH - 22f, paint(7.3f, muted, align = Paint.Align.RIGHT))
        doc.finishPage(p)
        page = null
    }

    private fun wrapText(text: String, x: Float, startY: Float, w: Float, p: Paint, lineH: Float): Float {
        if (text.isBlank()) return startY
        var yy = startY
        wrap(text, w, p).forEach { line ->
            if (yy > pageH - 50f) { newPage(); yy = y + lineH }
            canvas.drawText(line, x, yy, p)
            yy += lineH
        }
        return yy
    }

    private fun wrap(text: String, w: Float, p: Paint): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { para ->
            var line = ""
            para.split(' ').forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= w) line = candidate
                else {
                    if (line.isNotEmpty()) out += line
                    var rest = word
                    while (p.measureText(rest) > w && rest.length > 1) {
                        val n = p.breakText(rest, true, w, null).coerceAtLeast(1)
                        out += rest.substring(0, n); rest = rest.substring(n)
                    }
                    line = rest
                }
            }
            out += line
        }
        return out
    }

    private fun bitmapSize(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth to o.outHeight
    }

    /** Draws an image at full content width (max [maxH]), keeping its shape. */
    private fun image(f: File, w: Float, maxH: Float, border: Boolean) {
        val (bw, bh) = bitmapSize(f)
        if (bw <= 0) return
        var h = w * bh / bw
        var ww = w
        if (h > maxH) { ww = w * maxH / h; h = maxH }
        need(h + 10f)
        val x = margin + (width - ww) / 2
        val r = RectF(x, y, x + ww, y + h)
        drawImage(f, r)
        if (border) canvas.drawRect(r, stroke(rule))
        y += h + 10f
    }

    private fun drawImage(f: File, r: RectF, fit: Boolean = false) {
        val o = BitmapFactory.Options().apply { inSampleSize = 1 }
        val (bw, bh) = bitmapSize(f)
        // decode at about 2× the printed size: sharp but small
        val target = (r.width() * 2).toInt().coerceAtLeast(200)
        while (bw / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, o) ?: return
        val dst = if (fit && bmp.width > 0) {
            val scale = minOf(r.width() / bmp.width, r.height() / bmp.height)
            val dw = bmp.width * scale; val dh = bmp.height * scale
            RectF(r.left, r.bottom - dh, r.left + dw, r.bottom)
        } else r
        canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
    }

    private fun drawableBitmap(res: Int, size: Int): Bitmap? {
        val d = ContextCompat.getDrawable(context, res) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

}
