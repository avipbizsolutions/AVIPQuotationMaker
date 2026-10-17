package com.avip.quotationmaker

import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** On-grid or hybrid (with battery) system. */
enum class SystemType(val label: String, val tab: String, val icon: Int, val color: Int, val blurb: String) {
    ONGRID("On-grid", "Ongrid Project", R.drawable.cat_inverter, 0xFF1565C0.toInt(),
        "Solar + grid. Lowest cost, best savings, full subsidy."),
    HYBRID("Hybrid", "Hybrid Project", R.drawable.cat_hybrid, 0xFF6A1B9A.toInt(),
        "Solar + grid + lithium battery. Power during power cuts.");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ONGRID
    }
}

/**
 * One column of the "Ongrid Project" / "Hybrid Project" tab: a ready system
 * such as "3 kw Saathi". [rows] keeps every row of the sheet in order, so rows the
 * team adds later still show up in the app and on the PDF.
 */
data class SystemPackage(
    val type: SystemType,
    val name: String,
    val rows: List<Pair<String, String>>,
    /** Filled in by the sales executive instead of coming from the sheet. */
    val custom: Boolean = false,
) {
    /** Value of the first row whose label contains all [words] (case-insensitive). */
    fun v(vararg words: String): String = rows.firstOrNull { (label, _) ->
        val l = label.lowercase(); words.all { l.contains(it) }
    }?.second?.trim() ?: ""

    fun n(vararg words: String): Double = Fmt.parse(v(*words)) ?: 0.0

    val id get() = if (custom) "CUSTOM" else "${type.name}:$name"
    val kw: Double get() = if (custom) kwp else Regex("""(\d+(?:\.\d+)?)""").find(name)?.value?.toDoubleOrNull() ?: 0.0
    val tier: String get() = when {
        custom -> "Custom"
        name.contains("exec", true) -> "Executive"
        name.contains("smart", true) -> "Smart"
        name.contains("saathi", true) -> "Saathi"
        else -> name.substringAfter(' ').substringAfter(' ').trim().ifBlank { "Standard" }
    }
    val panelBrand get() = v("panel brand").ifBlank { v("topcon panel").ifBlank { v("panel") } }
    val panelWarranty get() = v("panel warranty")
    val inverter get() = v("inverter brand").ifBlank { v("hybrid inverter").ifBlank { v("inverter") } }
    val inverterWarranty get() = v("inverter warranty")
    val battery get() = rows.firstOrNull { it.first.contains("battery", true) && !it.first.contains("warranty", true) }?.second?.trim() ?: ""
    val batteryWarranty get() = v("battery warranty")
    val structure get() = v("structure")
    val dcrWp get() = dcrRows().first
    val dcrCount get() = dcrRows().second
    val nonDcrWp get() = nonDcrRows().first
    val nonDcrCount get() = nonDcrRows().second
    val totalPanels: Int get() = n("total no").toInt().takeIf { it > 0 } ?: (dcrCount + nonDcrCount)
    val totalWp: Double get() = n("total panel wattage").takeIf { it > 0 } ?: (dcrWp * dcrCount + nonDcrWp * nonDcrCount)
    val kwp: Double get() = totalWp / 1000.0
    val price: Double get() = n("price")
    val maxDiscount: Double get() = n("discount")
    val available: Boolean get() = price > 0

    /** DCR (made-in-India cell) capacity in kW — the part the PM Surya Ghar subsidy is paid on. */
    val dcrKw: Double get() = dcrWp * dcrCount / 1000.0

    // "No. of Panels" appears twice: after the DCR wattage and after the Non-DCR wattage.
    private fun dcrRows(): Pair<Double, Int> = pairAfter { it.contains("dcr panel wattage") && !it.contains("non") }
    private fun nonDcrRows(): Pair<Double, Int> = pairAfter { it.contains("non") && it.contains("wattage") }
    private fun pairAfter(match: (String) -> Boolean): Pair<Double, Int> {
        val i = rows.indexOfFirst { match(it.first.lowercase()) }
        if (i < 0) return 0.0 to 0
        val wp = Fmt.parse(rows[i].second) ?: 0.0
        val count = rows.getOrNull(i + 1)?.takeIf { it.first.lowercase().contains("no") }
            ?.let { Fmt.parse(it.second)?.toInt() } ?: 0
        return wp to if (wp > 0) count else 0
    }

    /** Panel line for display: "5 × 600 W DCR + 4 × 625 W". */
    fun panelSummary(): String = listOfNotNull(
        if (dcrCount > 0) "$dcrCount × ${Fmt.num(dcrWp)} W DCR" else null,
        if (nonDcrCount > 0) "$nonDcrCount × ${Fmt.num(nonDcrWp)} W Non-DCR" else null,
    ).joinToString(" + ").ifBlank { "$totalPanels panels" }

    /** Rows shown as the specification (everything except numbers used elsewhere). */
    fun specRows(): List<Pair<String, String>> = rows.filter { (label, value) ->
        val l = label.lowercase()
        value.isNotBlank() && !l.contains("price") && !l.contains("discount") &&
            !l.contains("wattage") && !l.startsWith("no.") && !l.startsWith("no ") && !l.contains("total")
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("name", name).put("custom", custom)
        .put("rows", JSONArray().apply { rows.forEach { put(JSONArray().put(it.first).put(it.second)) } })

    companion object {
        fun fromJson(o: JSONObject): SystemPackage {
            val arr = o.optJSONArray("rows") ?: JSONArray()
            return SystemPackage(
                SystemType.of(o.optString("type")), o.optString("name"),
                (0 until arr.length()).map { arr.getJSONArray(it).let { r -> r.optString(0) to r.optString(1) } },
                o.optBoolean("custom", false),
            )
        }

        /** Labels of a custom system, in the same words as the sheet so every getter works. */
        val CUSTOM_FIELDS = listOf(
            "Panel Brand", "Panel Warranty", "DCR Panel Wattage", "No. of Panels (DCR)",
            "Non-DCR Panel Wattage", "No. of Panels (Non-DCR)", "Inverter Brand and Specification",
            "Inverter Warranty", "Lithium Battery", "Battery Warranty", "Structure",
            "DC Wire", "AC Wire", "Earthing Rod", "Price (GST Included)",
        )

        /** Builds a custom system from the sales executive's entries (label → value). */
        fun custom(type: SystemType, f: Map<String, String>): SystemPackage {
            val rows = CUSTOM_FIELDS.filter { type == SystemType.HYBRID || !it.contains("Battery") }
                .map { it.replace(" (DCR)", "").replace(" (Non-DCR)", "") to (f[it] ?: "").trim() }
            val dcr = (Fmt.parse(f["DCR Panel Wattage"]) ?: 0.0) * (Fmt.parse(f["No. of Panels (DCR)"]) ?: 0.0)
            val non = (Fmt.parse(f["Non-DCR Panel Wattage"]) ?: 0.0) * (Fmt.parse(f["No. of Panels (Non-DCR)"]) ?: 0.0)
            val count = (Fmt.parse(f["No. of Panels (DCR)"]) ?: 0.0) + (Fmt.parse(f["No. of Panels (Non-DCR)"]) ?: 0.0)
            val total = dcr + non
            val all = rows + listOf(
                "Total No. of Panels" to Fmt.num(count),
                "Total Panel Wattage" to Fmt.num(total),
                "Maximum Discount" to "0",
            )
            return SystemPackage(type, "${Fmt.one(total / 1000)} kW Custom", all, custom = true)
        }
    }
}

/** Key/value pairs from the optional "App Settings" tab, with built-in defaults. */
class AppSettings(private val map: Map<String, String>) {
    operator fun get(key: String): String = map[key]?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULTS[key] ?: ""
    fun num(key: String, default: Double): Double = Fmt.parse(get(key)) ?: default
    fun list(prefix: String): List<String> = (1..12).map { get("${prefix}_$it") }.filter { it.isNotBlank() }

    companion object {
        val DEFAULTS = mapOf(
            "company_name" to "AVIP Business Solutions LLP",
            "brand_name" to "AVIP Solar",
            "tagline" to "Energy for Future · One stop solution for all your Solar requirement",
            "phone" to "+91 92881 76289",
            "whatsapp" to "919288176289",
            "email" to "contact@avipsolar.in",
            "website" to "avipsolar.in",
            "gstin" to "10ABNFA7064N1Z7",
            "address" to "AIIMS - Naubatpur New Nahar Road, Patna - 801109, Bihar",
            "bank_account_name" to "AVIP Business Solutions LLP",
            "bank_account_no" to "44191136525",
            "bank_ifsc" to "SBIN0001217",
            "bank_branch" to "State Bank of India, Bihta",
            "quote_validity_days" to "7",
            // PM Surya Ghar central subsidy (on DCR capacity)
            "subsidy_per_kw_first_2" to "30000",
            "subsidy_per_kw_3rd" to "18000",
            "subsidy_max" to "78000",
            "subsidy_hybrid" to "yes",
            "state_subsidy" to "20000",
            "name_transfer_fee" to "1500",
            // GST billing of a solar system: goods share at 5%, services share at 18%
            "gst_goods_share" to "70",
            "gst_goods_rate" to "5",
            "gst_service_rate" to "18",
            "state_subsidy_name" to "Bihar Govt. subsidy",
            // solar loan
            "loan_rate" to "5.75",
            "loan_max_years" to "10",
            // savings estimate
            "units_per_kw_per_day" to "4",
            "tariff_per_unit" to "7",
            "installs_count" to "300+",
            "q_terms_1" to "Price includes GST, transport, standard structure, wiring, earthing, lightning arrester and installation.",
            "q_terms_2" to "PM Surya Ghar central subsidy and the Bihar Government subsidy are paid directly into the customer's bank account after installation, net-metering and inspection. AVIP helps with the full application.",
            "q_terms_3" to "Extra structure height, civil work or cable length beyond the standard design will be charged separately.",
            "q_terms_4" to "Warranty as per manufacturer terms and conditions.",
            "q_terms_5" to "Payment schedule as agreed at the time of booking.",
            // added to the terms only when the customer takes a bank loan
            "q_loan_terms_1" to "Stamp paper charges for the loan agreement to be executed between the bank and the customer shall be borne by the customer.",
        )
    }
}

/** Front legs / back legs / rafters / purlins of the proposed structure. */
data class StructurePart(val name: String, var qty: String = "", var length: String = "", var size: String = "") {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("qty", qty).put("length", length).put("size", size)
    val filled get() = qty.isNotBlank() || length.isNotBlank() || size.isNotBlank()

    companion object {
        val NAMES = listOf("Front Legs", "Back Legs", "Rafters", "Purlins")
        fun fromJson(o: JSONObject) = StructurePart(o.optString("name"), o.optString("qty"), o.optString("length"), o.optString("size"))
    }
}

/** Everything captured during one site visit, saved as JSON on the phone. */
class Visit(
    val number: String,
    val createdAt: Long,
) {
    var updatedAt: Long = createdAt
    var quotedAt: Long = 0L

    // customer
    var name = ""
    var mobile = ""
    var address = ""
    var district = ""
    var pincode = ""
    var pan = ""
    var aadhaar = ""
    var caNo = ""
    var discom = ""
    var phase = ""
    var sanctionedLoad = ""
    var monthlyBill = ""
    var funding = "" // "Loan" or "Own fund"
    var nameChange = "" // "Not needed" / "Name correction" / "Name transfer"
    var downPayment = ""
    var loanAmount = "" // asked at the customer step when funding is Loan (required)
    var loanYears = 10
    var bankDocType = "" // Bank statement / Passbook / Cancelled cheque
    val bankDocs = mutableListOf<String>()

    // site
    var lat = 0.0
    var lng = 0.0
    var roofType = ""
    var floors = ""
    var roofLength = ""
    var roofWidth = ""
    var shading = ""
    var structureHeight = ""
    var cableRun = ""
    var siteNotes = ""
    var structureType = "" // "Magnelis" or "Normal GI"
    var mountingLayout = "" // Magnelis: "2P × 3" / "2P × 4" / "2P × 5"
    val voiceNotes = mutableListOf<String>()
    val photos = mutableListOf<String>() // file names inside the visit folder
    var sketch = "" // file name of the site sketch
    val structure: MutableList<StructurePart> = StructurePart.NAMES.map { StructurePart(it) }.toMutableList()

    // system & price
    var pkg: SystemPackage? = null
    var discount = 0.0
    var subsidyOn = true
    var solarCapacityNote = ""

    // finish
    var customerSign = ""
    var officerName = ""
    var officerMobile = ""

    // follow-up
    var followUpAt = 0L // reminder time (10 AM on the chosen day), 0 = none
    var followUpNote = ""
    var leadStatus = "Open" // Open / Converted / Lost
    val followUps = mutableListOf<FollowUp>()

    val isLoan get() = funding == "Loan"
    val isGi get() = structureType == "Normal GI"
    val followUpDue get() = leadStatus == "Open" && followUpAt > 0 && followUpAt <= endOfToday()
    val hasLocation get() = lat != 0.0 || lng != 0.0
    val isQuoted get() = quotedAt > 0
    val nameTransferFee: Double get() =
        if (nameChange == "Name transfer") Catalog.settings.num("name_transfer_fee", 1500.0) else 0.0
    val nameCorrection get() = nameChange == "Name correction"
    /** System price after discount (GST included). Name transfer is paid separately. */
    val finalPrice: Double get() = ((pkg?.price ?: 0.0) - discount).coerceAtLeast(0.0)

    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number); put("createdAt", createdAt); put("updatedAt", updatedAt); put("quotedAt", quotedAt)
        put("name", name); put("mobile", mobile); put("address", address); put("district", district)
        put("pincode", pincode); put("pan", pan); put("aadhaar", aadhaar); put("caNo", caNo)
        put("discom", discom); put("phase", phase); put("sanctionedLoad", sanctionedLoad); put("monthlyBill", monthlyBill)
        put("lat", lat); put("lng", lng); put("roofType", roofType); put("floors", floors)
        put("roofLength", roofLength); put("roofWidth", roofWidth); put("shading", shading)
        put("structureHeight", structureHeight); put("cableRun", cableRun); put("siteNotes", siteNotes)
        put("photos", JSONArray(photos)); put("sketch", sketch)
        put("structure", JSONArray().apply { structure.forEach { put(it.toJson()) } })
        put("pkg", pkg?.toJson() ?: JSONObject.NULL); put("discount", discount); put("subsidyOn", subsidyOn)
        put("solarCapacityNote", solarCapacityNote)
        put("customerSign", customerSign); put("officerName", officerName); put("officerMobile", officerMobile)
        put("funding", funding); put("loanAmount", loanAmount); put("nameChange", nameChange); put("downPayment", downPayment); put("loanYears", loanYears)
        put("bankDocType", bankDocType); put("bankDocs", JSONArray(bankDocs))
        put("structureType", structureType); put("mountingLayout", mountingLayout); put("voiceNotes", JSONArray(voiceNotes))
        put("followUpAt", followUpAt); put("followUpNote", followUpNote); put("leadStatus", leadStatus)
        put("followUps", JSONArray().apply { followUps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Visit = Visit(o.optString("number"), o.optLong("createdAt")).apply {
            updatedAt = o.optLong("updatedAt", createdAt); quotedAt = o.optLong("quotedAt")
            name = o.optString("name"); mobile = o.optString("mobile"); address = o.optString("address")
            district = o.optString("district"); pincode = o.optString("pincode"); pan = o.optString("pan")
            aadhaar = o.optString("aadhaar"); caNo = o.optString("caNo"); discom = o.optString("discom")
            phase = o.optString("phase"); sanctionedLoad = o.optString("sanctionedLoad"); monthlyBill = o.optString("monthlyBill")
            lat = o.optDouble("lat", 0.0); lng = o.optDouble("lng", 0.0)
            roofType = o.optString("roofType"); floors = o.optString("floors")
            roofLength = o.optString("roofLength"); roofWidth = o.optString("roofWidth"); shading = o.optString("shading")
            structureHeight = o.optString("structureHeight"); cableRun = o.optString("cableRun"); siteNotes = o.optString("siteNotes")
            o.optJSONArray("photos")?.let { a -> for (i in 0 until a.length()) photos += a.optString(i) }
            sketch = o.optString("sketch")
            o.optJSONArray("structure")?.let { a ->
                val parts = (0 until a.length()).map { StructurePart.fromJson(a.getJSONObject(it)) }
                if (parts.isNotEmpty()) { structure.clear(); structure.addAll(parts) }
            }
            pkg = o.optJSONObject("pkg")?.let { SystemPackage.fromJson(it) }
            discount = o.optDouble("discount", 0.0); subsidyOn = o.optBoolean("subsidyOn", true)
            solarCapacityNote = o.optString("solarCapacityNote")
            customerSign = o.optString("customerSign"); officerName = o.optString("officerName"); officerMobile = o.optString("officerMobile")
            funding = o.optString("funding"); loanAmount = o.optString("loanAmount"); nameChange = o.optString("nameChange"); downPayment = o.optString("downPayment"); loanYears = o.optInt("loanYears", 10)
            bankDocType = o.optString("bankDocType")
            o.optJSONArray("bankDocs")?.let { a -> for (i in 0 until a.length()) bankDocs += a.optString(i) }
            structureType = o.optString("structureType")
            mountingLayout = o.optString("mountingLayout")
            o.optJSONArray("voiceNotes")?.let { a -> for (i in 0 until a.length()) voiceNotes += a.optString(i) }
            followUpAt = o.optLong("followUpAt"); followUpNote = o.optString("followUpNote")
            leadStatus = o.optString("leadStatus", "Open").ifBlank { "Open" }
            o.optJSONArray("followUps")?.let { a -> for (i in 0 until a.length()) followUps += FollowUp.fromJson(a.getJSONObject(i)) }
        }
    }
}

/** One follow-up call: when, what happened, and any note. */
data class FollowUp(val at: Long, val outcome: String, val note: String, val nextAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("outcome", outcome).put("note", note).put("nextAt", nextAt)
    companion object {
        fun fromJson(o: JSONObject) = FollowUp(o.optLong("at"), o.optString("outcome"), o.optString("note"), o.optLong("nextAt"))
    }
}

fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59); set(java.util.Calendar.SECOND, 59)
}.timeInMillis

/** Subsidy, savings, loan and payback for a chosen system. */
object Money {
    /** PM Surya Ghar central subsidy, paid on DCR capacity. */
    fun central(p: SystemPackage?, on: Boolean): Double {
        if (p == null || !on) return 0.0
        val s = Catalog.settings
        if (p.type == SystemType.HYBRID && !s["subsidy_hybrid"].lowercase().startsWith("y")) return 0.0
        val kw = p.dcrKw.takeIf { it > 0 } ?: p.kwp
        val first = minOf(kw, 2.0) * s.num("subsidy_per_kw_first_2", 30000.0)
        val third = (minOf(kw, 3.0) - 2.0).coerceAtLeast(0.0) * s.num("subsidy_per_kw_3rd", 18000.0)
        return round(minOf(first + third, s.num("subsidy_max", 78000.0)))
    }

    /** Bihar Government top-up, given along with the central subsidy. */
    fun state(p: SystemPackage?, on: Boolean): Double =
        if (central(p, on) > 0) Catalog.settings.num("state_subsidy", 20000.0) else 0.0

    fun subsidy(p: SystemPackage?, on: Boolean): Double = central(p, on) + state(p, on)

    /** Units a year (kWh). */
    fun yearlyUnits(p: SystemPackage?): Double =
        (p?.kwp ?: 0.0) * Catalog.settings.num("units_per_kw_per_day", 4.0) * 365

    fun yearlySaving(p: SystemPackage?): Double = yearlyUnits(p) * Catalog.settings.num("tariff_per_unit", 7.0)

    fun paybackYears(v: Visit): Double {
        val saving = yearlySaving(v.pkg)
        if (saving <= 0) return 0.0
        return (v.finalPrice - subsidy(v.pkg, v.subsidyOn)) / saving
    }

    /** GST split of a GST-inclusive amount. */
    class Gst(
        val goodsShare: Double, val goodsRate: Double, val serviceRate: Double,
        val taxable: Double, val goodsTaxable: Double, val goodsGst: Double,
        val serviceTaxable: Double, val serviceGst: Double,
    ) {
        val totalGst get() = goodsGst + serviceGst
    }

    fun gst(inclusive: Double): Gst {
        val s = Catalog.settings
        val share = s.num("gst_goods_share", 70.0).coerceIn(0.0, 100.0)
        val gr = s.num("gst_goods_rate", 5.0)
        val sr = s.num("gst_service_rate", 18.0)
        val effective = share / 100 * gr / 100 + (100 - share) / 100 * sr / 100
        val taxable = inclusive / (1 + effective)
        val goodsTaxable = taxable * share / 100
        val serviceTaxable = taxable - goodsTaxable
        return Gst(share, gr, sr, taxable, goodsTaxable, goodsTaxable * gr / 100, serviceTaxable, serviceTaxable * sr / 100)
    }

    fun loanRate(): Double = Catalog.settings.num("loan_rate", 5.75)
    fun maxYears(): Int = Catalog.settings.num("loan_max_years", 10.0).toInt().coerceAtLeast(1)

    fun loanAmount(v: Visit): Double =
        (Fmt.parse(v.loanAmount)?.takeIf { it > 0 }
            ?: (v.finalPrice - (Fmt.parse(v.downPayment) ?: 0.0))).coerceAtLeast(0.0)

    /** What the customer pays from own funds when taking a loan. */
    fun downPayment(v: Visit): Double = (v.finalPrice - loanAmount(v)).coerceAtLeast(0.0)

    /** Monthly EMI on [principal] at the loan rate for [years]. */
    fun emi(principal: Double, years: Int): Double {
        if (principal <= 0 || years <= 0) return 0.0
        val r = loanRate() / 12 / 100
        val n = years * 12
        if (r == 0.0) return principal / n
        val f = Math.pow(1 + r, n.toDouble())
        return principal * r * f / (f - 1)
    }

    fun emi(v: Visit): Double = emi(loanAmount(v), v.loanYears.coerceIn(1, maxYears()))
}

object Fmt {
    private val sym = DecimalFormatSymbols(Locale.ENGLISH)

    fun parse(s: String?): Double? =
        s?.replace(",", "")?.replace("₹", "")?.replace("%", "")?.trim()?.toDoubleOrNull()

    /** Indian grouping: ₹1,23,45,678 */
    fun inr(v: Double): String {
        val neg = v < 0
        val whole = round(abs(v)).toLong()
        val s = whole.toString()
        val grouped = if (s.length <= 3) s else {
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$rest,$last3"
        }
        return (if (neg) "-" else "") + "₹" + grouped
    }

    fun num(v: Double): String = DecimalFormat("0.##", sym).format(v)
    fun one(v: Double): String = DecimalFormat("0.#", sym).format(v)

    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen"
    )
    private val tens = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

    private fun below100(n: Int): String =
        if (n < 20) ones[n] else (tens[n / 10] + if (n % 10 != 0) " " + ones[n % 10] else "")

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        return listOfNotNull(if (h > 0) ones[h] + " Hundred" else null, if (r > 0) below100(r) else null).joinToString(" ")
    }

    /** "Rupees One Lakh Eighty Five Thousand Only" */
    fun words(amount: Double): String {
        var n = round(abs(amount)).toLong()
        if (n == 0L) return "Rupees Zero Only"
        val parts = mutableListOf<String>()
        val crore = n / 10_000_000; n %= 10_000_000
        val lakh = n / 100_000; n %= 100_000
        val thousand = n / 1000; n %= 1000
        if (crore > 0) parts += below1000(crore.toInt().coerceAtMost(999)) + " Crore"
        if (lakh > 0) parts += below100(lakh.toInt()) + " Lakh"
        if (thousand > 0) parts += below100(thousand.toInt()) + " Thousand"
        if (n > 0) parts += below1000(n.toInt())
        return "Rupees " + parts.joinToString(" ") + " Only"
    }

    /** "XXXX XXXX 1234" — Aadhaar is never printed in full. */
    fun maskAadhaar(a: String): String {
        val d = a.filter { it.isDigit() }
        return if (d.length < 4) "" else "XXXX XXXX " + d.takeLast(4)
    }
}
