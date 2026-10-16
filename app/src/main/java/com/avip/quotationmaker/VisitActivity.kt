package com.avip.quotationmaker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The site visit, step by step:
 * 1 Customer → 2 Site survey → 3 System → 4 Price → 5 Sign & share.
 * Everything is saved on the phone as you go, so a visit can be finished later.
 */
class VisitActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NUMBER = "number"
        private val STEPS = listOf(
            "Customer" to R.drawable.ic_person,
            "Site" to R.drawable.ic_home,
            "System" to R.drawable.cat_panel,
            "Price" to R.drawable.ic_rupee,
            "Finish" to R.drawable.ic_sign,
        )
    }

    private lateinit var v: Visit
    private var step = 0
    private lateinit var stepBar: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var backBtn: View
    private lateinit var nextBtn: TextView
    private var sketchPad: DrawPad? = null
    private var signPad: DrawPad? = null
    private var sketchDirty = false
    private var signDirty = false
    private var pendingPhoto: File? = null

    // ---------- activity results ----------

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto ?: return@registerForActivityResult
        pendingPhoto = null
        if (ok && f.exists() && f.length() > 0) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { shrinkPhoto(f) }
                v.photos += f.name; save(); render()
            }
        } else f.delete()
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.take(8).forEach { u -> copyPhoto(u)?.let { v.photos += it.name } }
            }
            save(); render()
        }
    }

    private val locationPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) captureLocation() else toast("Location permission is needed to tag the site")
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: savedInstanceState?.getString(EXTRA_NUMBER)
        v = number?.let { VisitStore.load(this, it) } ?: VisitStore.create(this).also { VisitStore.save(this, it) }
        if (number != null && v.isQuoted) step = 4

        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar("Site Visit", v.number, onBack = { leave() }))
        stepBar = horizontal {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(10), dp(6), dp(8))
            elevation = dpf(3f)
        }
        root.addView(stepBar)
        body = vertical { setPadding(dp(16), dp(14), dp(16), dp(28)) }
        scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        root.addView(scroll, lp(MATCH, 0, 1f))

        // bottom navigation
        root.addView(horizontal {
            setBackgroundColor(Color.WHITE)
            elevation = dpf(10f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            backBtn = text("‹  Back", 15f, Ui.BRAND, bold = true) {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, dpf(25f), Ui.BRAND, dp(2))
                minHeight = dp(50)
                setOnClickListener { go(step - 1) }
            }
            addView(backBtn, lp(0, WRAP, 1f).margins(context, r = 10))
            nextBtn = text("Next  ›", 16f, Color.WHITE, bold = true) {
                gravity = Gravity.CENTER
                background = rounded(Ui.BRAND, dpf(25f))
                minHeight = dp(50)
                setOnClickListener { next() }
            }
            addView(nextBtn, lp(0, WRAP, 1.6f))
        })
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (step > 0 && !v.isQuoted) go(step - 1) else leave() }
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_NUMBER, v.number)
    }

    override fun onPause() {
        super.onPause()
        savePads()
        save()
    }

    private fun save() = VisitStore.save(this, v)

    private fun leave() {
        savePads(); save()
        if (v.name.isBlank() && v.mobile.isBlank() && v.photos.isEmpty() && v.pkg == null) VisitStore.delete(this, v)
        finish()
    }

    private fun next() {
        if (step == 0) {
            if (v.name.isBlank()) { toast("Enter the customer's name"); return }
            if (v.mobile.filter { it.isDigit() }.length < 10) { toast("Enter a 10-digit WhatsApp number"); return }
        }
        if (step == 2 && v.pkg == null) { toast("Pick a system first"); return }
        if (step == 4) { makePdfs(); return }
        go(step + 1)
    }

    private fun go(to: Int) {
        if (to < 0) { leave(); return }
        if (to > 0 && v.name.isBlank()) { toast("Enter the customer's name first"); step = 0; render(); return }
        if (to >= 3 && v.pkg == null) { toast("Pick a system first"); step = 2; render(); return }
        savePads(); save()
        step = to.coerceIn(0, 4)
        render()
        scroll.scrollTo(0, 0)
    }

    private fun savePads() {
        sketchPad?.let { pad ->
            if (sketchDirty) {
                val f = VisitStore.file(this, v, "sketch.png")
                if (pad.isEmpty) { f.delete(); v.sketch = "" } else if (pad.save(f)) v.sketch = f.name
                sketchDirty = false
            }
        }
        signPad?.let { pad ->
            if (signDirty) {
                val f = VisitStore.file(this, v, "customer_sign.png")
                if (pad.isEmpty) { f.delete(); v.customerSign = "" } else if (pad.save(f)) v.customerSign = f.name
                signDirty = false
            }
        }
    }

    // ---------- rendering ----------

    private fun render() {
        savePads() // keep a sketch / signature in progress when the screen is rebuilt
        renderStepBar()
        body.removeAllViews()
        sketchPad = null; signPad = null
        when (step) {
            0 -> customerStep()
            1 -> siteStep()
            2 -> systemStep()
            3 -> priceStep()
            else -> finishStep()
        }
        backBtn.visibility = if (step == 0) View.GONE else View.VISIBLE
        nextBtn.text = when (step) {
            4 -> if (v.isQuoted) "Re-create PDFs" else "Create Quotation"
            3 -> "Next: Sign  ›"
            else -> "Next  ›"
        }
        nextBtn.background = rounded(if (step == 4) Ui.SUN else Ui.BRAND, dpf(25f))
        nextBtn.setTextColor(if (step == 4) Ui.TEXT else Color.WHITE)
    }

    private fun renderStepBar() {
        stepBar.removeAllViews()
        STEPS.forEachIndexed { i, (label, res) ->
            val done = i < step
            val on = i == step
            val color = when { on -> Ui.BRAND; done -> 0xFF5FAE7C.toInt(); else -> 0xFFC5CEC8.toInt() }
            stepBar.addView(vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(FrameLayout(context).apply {
                    background = if (on) circle(Ui.BRAND) else if (done) circle(0xFF5FAE7C.toInt()) else rounded(Color.WHITE, dpf(19f), color, dp(2))
                    addView(ImageView(context).apply {
                        setImageResource(if (done) R.drawable.ic_check else res)
                        imageTintList = android.content.res.ColorStateList.valueOf(if (on || done) Color.WHITE else color)
                    }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                }, lp(dp(38), dp(38)))
                addView(text(label, 11f, if (on) Ui.BRAND else Ui.MUTED, bold = on), lp(WRAP, WRAP).margins(context, t = 3))
                isClickable = true
                setOnClickListener { if (i != step) go(i) }
            }, lp(0, WRAP, 1f))
        }
    }

    private fun stepHeader(title: String, sub: String) {
        body.addView(sectionTitle(title, sub), lp().margins(this, b = 12))
    }

    // ---------- step 1: customer ----------

    private fun customerStep() {
        stepHeader("Customer details", "As on the electricity bill. * = required")
        body.addView(card {
            field(this, "Customer name *", v.name, "Full name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person) { v.name = it }
            field(this, "WhatsApp mobile number *", v.mobile, "10-digit number", InputType.TYPE_CLASS_PHONE, R.drawable.ic_chat, 13) { v.mobile = it }
            field(this, "Address", v.address, "House, mohalla, village / town", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.drawable.ic_location) { v.address = it }
            addView(horizontal {
                val l = vertical { }; val r = vertical { }
                field(l, "District", v.district, "e.g. Patna", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) { v.district = it }
                field(r, "PIN code", v.pincode, "6 digits", InputType.TYPE_CLASS_NUMBER, maxLen = 6) { v.pincode = it }
                addView(l, lp(0, WRAP, 1.4f).margins(context, r = 10)); addView(r, lp(0, WRAP, 1f))
            })
        })

        body.addView(sectionTitle("Electricity connection", "Helps pick the right system size"), lp().margins(this, t = 20, b = 10))
        body.addView(card {
            addView(text("DISCOM", 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
            addView(chips(listOf("NBPDCL", "SBPDCL"), v.discom) { v.discom = it })
            field(this, "Consumer / CA number", v.caNo, "From the bill", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, R.drawable.ic_bolt) { v.caNo = it }
            addView(text("Connection", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("Single phase", "Three phase"), v.phase) { v.phase = it })
            addView(horizontal {
                val l = vertical { }; val r = vertical { }
                field(l, "Sanctioned load (kW)", v.sanctionedLoad, "e.g. 3", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { v.sanctionedLoad = it }
                field(r, "Monthly bill (₹)", v.monthlyBill, "average", InputType.TYPE_CLASS_NUMBER) { v.monthlyBill = it }
                addView(l, lp(0, WRAP, 1f).margins(context, r = 10)); addView(r, lp(0, WRAP, 1f))
            })
        })

        body.addView(sectionTitle("KYC for PM Surya Ghar", "Optional now — needed for the application"), lp().margins(this, t = 20, b = 10))
        body.addView(card {
            field(this, "PAN", v.pan, "ABCDE1234F", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, maxLen = 10) { v.pan = it.uppercase() }
            field(this, "Aadhaar", v.aadhaar, "12 digits", InputType.TYPE_CLASS_NUMBER, maxLen = 12) { v.aadhaar = it }
            addView(text("Aadhaar prints only on the internal site visit report, never on the quotation.", 11.5f, Ui.MUTED), lp().margins(context, t = 6))
        })
    }

    // ---------- step 2: site ----------

    private fun siteStep() {
        stepHeader("Site survey", "Location, roof, photos and structure")

        // location
        body.addView(card {
            addView(horizontal {
                addView(badge(R.drawable.ic_location, if (v.hasLocation) Ui.BRAND else Ui.MUTED, 42))
                addView(vertical {
                    addView(text(if (v.hasLocation) "Site location saved" else "Tag site location", 15f, Ui.TEXT, bold = true))
                    addView(text(if (v.hasLocation) String.format(java.util.Locale.US, "%.6f, %.6f", v.lat, v.lng) else "Stand on the roof and tap Capture", 12.5f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 12))
            })
            addView(horizontal {
                addView(pillButton(if (v.hasLocation) "Re-capture" else "Capture GPS", heightDp = 42, iconRes = R.drawable.ic_location) { askLocation() }, lp(0, WRAP, 1f))
                if (v.hasLocation) addView(pillButton("Map", Ui.BRAND_LIGHT, Ui.BRAND_DARK, 42) { VisitStore.openMap(this@VisitActivity, v.lat, v.lng) }, lp(WRAP, WRAP).margins(context, l = 10))
            }, lp().margins(context, t = 12))
        })

        // roof
        body.addView(sectionTitle("Roof"), lp().margins(this, t = 20, b = 10))
        body.addView(tiles(
            listOf("RCC Roof" to R.drawable.ic_home, "Tin Shed" to R.drawable.cat_structure, "Asbestos" to R.drawable.ic_grid, "Ground Mount" to R.drawable.ic_sun),
            v.roofType, columns = 4,
        ) { v.roofType = it })
        body.addView(text("Building height", 13f, Ui.MUTED, bold = true), lp().margins(this, t = 8, b = 6))
        body.addView(chips(listOf("Ground", "G+1", "G+2", "G+3", "G+4 or more"), v.floors) { v.floors = it })

        val areaNote = text("", 13f, Ui.BRAND_DARK, bold = true)
        fun updateArea() {
            val a = (Fmt.parse(v.roofLength) ?: 0.0) * (Fmt.parse(v.roofWidth) ?: 0.0)
            val perKw = Catalog.settings.num("sqft_per_kw", 100.0)
            areaNote.text = if (a > 0) "≈ ${a.roundToInt()} sq ft shadow-free  ·  fits about ${Fmt.one(a / perKw)} kW" else ""
            areaNote.visibility = if (a > 0) View.VISIBLE else View.GONE
        }
        body.addView(card {
            addView(horizontal {
                addView(icon(R.drawable.ic_ruler, Ui.BRAND, 18))
                addView(text("Shadow-free roof area (feet)", 13f, Ui.MUTED, bold = true), lp(0, WRAP, 1f).margins(context, l = 6))
            })
            addView(horizontal {
                addView(input("Length", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, v.roofLength).apply { onChange { v.roofLength = it; updateArea() } }, lp(0, WRAP, 1f))
                addView(text("×", 20f, Ui.MUTED, bold = true) { gravity = Gravity.CENTER }, lp(dp(32), WRAP))
                addView(input("Width", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, v.roofWidth).apply { onChange { v.roofWidth = it; updateArea() } }, lp(0, WRAP, 1f))
            }, lp().margins(context, t = 8))
            addView(areaNote, lp().margins(context, t = 8))
            addView(text("Shading", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("No shade", "Partial shade", "Heavy shade"), v.shading) { v.shading = it })
            addView(text("Structure height", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(listOf("Standard", "Elevated (6-7 ft)", "Extra high"), v.structureHeight) { v.structureHeight = it })
            field(this, "Cable run, inverter to meter (metres)", v.cableRun, "approx.", InputType.TYPE_CLASS_NUMBER) { v.cableRun = it }
        })
        updateArea()

        // photos
        body.addView(sectionTitle("Site photos", "Roof, meter, inverter wall, access stairs"), lp().margins(this, t = 20, b = 10))
        body.addView(photoGrid())

        // sketch
        body.addView(horizontal {
            addView(sectionTitle("Site sketch", "Draw the roof, obstacles and panel rows"), lp(0, WRAP, 1f))
            addView(text("Undo", 14f, Ui.BRAND, bold = true) { setPadding(dp(10), dp(8), dp(6), dp(8)); setOnClickListener { sketchPad?.undo() } })
            addView(text("Clear", 14f, Ui.DANGER, bold = true) { setPadding(dp(10), dp(8), dp(6), dp(8)); setOnClickListener { sketchPad?.clear() } })
        }, lp().margins(this, t = 20, b = 10))
        val pad = DrawPad(this, compass = true).apply {
            background = rounded(Color.WHITE, dpf(14f), Ui.LINE, dp(1))
            if (v.sketch.isNotBlank()) loadFrom(VisitStore.file(context, v, v.sketch))
            onDrawn = { sketchDirty = true }
            clipToOutline = true
        }
        sketchPad = pad
        body.addView(pad, lp(MATCH, dp(280)))

        // structure
        body.addView(sectionTitle("Proposed structure", "Quantity, length and section size"), lp().margins(this, t = 20, b = 10))
        body.addView(card(12) {
            addView(horizontal {
                addView(text("", 12f), lp(0, WRAP, 1.3f))
                listOf("Qty", "Length (ft)", "Size").forEach { addView(text(it, 12f, Ui.MUTED, bold = true) { gravity = Gravity.CENTER }, lp(0, WRAP, 1f)) }
            }, lp().margins(context, b = 6))
            v.structure.forEach { part ->
                addView(horizontal {
                    addView(text(part.name, 13.5f, Ui.TEXT, bold = true), lp(0, WRAP, 1.3f))
                    fun cell(value: String, type: Int, hint: String, set: (String) -> Unit) =
                        addView(input(hint, type, value).apply {
                            setPadding(dp(8), dp(10), dp(8), dp(10)); gravity = Gravity.CENTER; textSize = 14f
                            onChange(set)
                        }, lp(0, WRAP, 1f).margins(context, l = 4))
                    cell(part.qty, InputType.TYPE_CLASS_NUMBER, "0") { part.qty = it }
                    cell(part.length, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "ft") { part.length = it }
                    cell(part.size, InputType.TYPE_CLASS_TEXT, "40×40") { part.size = it }
                }, lp().margins(context, b = 6))
            }
        })

        body.addView(card {
            field(this, "Notes for the installation team", v.siteNotes, "Access, water tank, earthing pit spot, special requests…",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit) { v.siteNotes = it }
        }, lp().margins(this, t = 16))
    }

    private fun photoGrid(): View {
        val box = vertical { }
        val items: List<String?> = v.photos.toList() + listOf(null)
        items.chunked(3).forEach { row ->
            box.addView(horizontal {
                row.forEach { name ->
                    val cell = FrameLayout(context).apply {
                        background = rounded(if (name == null) Ui.BRAND_LIGHT else Color.WHITE, dpf(14f), Ui.LINE, dp(1))
                        clipToOutline = true
                    }
                    if (name == null) {
                        cell.addView(vertical {
                            gravity = Gravity.CENTER
                            addView(icon(R.drawable.ic_camera, Ui.BRAND, 32))
                            addView(text("Add photo", 12.5f, Ui.BRAND_DARK, bold = true), lp(WRAP, WRAP).margins(context, t = 4))
                        }, FrameLayout.LayoutParams(MATCH, MATCH))
                        cell.setOnClickListener { addPhoto() }
                    } else {
                        val f = VisitStore.file(context, v, name)
                        cell.addView(ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            thumb(f)?.let { setImageBitmap(it) }
                        }, FrameLayout.LayoutParams(MATCH, MATCH))
                        cell.addView(ImageView(context).apply {
                            setImageResource(R.drawable.ic_close)
                            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                            background = circle(0x99000000.toInt())
                            setPadding(dp(5), dp(5), dp(5), dp(5))
                            setOnClickListener {
                                AlertDialog.Builder(this@VisitActivity).setTitle("Remove this photo?")
                                    .setPositiveButton("Remove") { _, _ -> f.delete(); v.photos.remove(name); save(); render() }
                                    .setNegativeButton("Cancel", null).show()
                            }
                        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
                    }
                    addView(cell, lp(0, dp(104), 1f).margins(context, r = 8))
                }
                repeat(3 - row.size) { addView(View(context), lp(0, 1, 1f).margins(context, r = 8)) }
            }, lp().margins(this, b = 8))
        }
        return box
    }

    private fun addPhoto() {
        AlertDialog.Builder(this).setTitle("Add site photo")
            .setItems(arrayOf("Take photo with camera", "Choose from gallery")) { _, which ->
                if (which == 0) {
                    val f = VisitStore.file(this, v, "photo_${System.currentTimeMillis()}.jpg")
                    pendingPhoto = f
                    try { takePhoto.launch(VisitStore.uri(this, f)) } catch (_: Exception) { toast("Camera not available") }
                } else pickPhoto.launch("image/*")
            }.show()
    }

    // ---------- step 3: system ----------

    private var browseType: SystemType? = null
    private var browseKw: String? = null // null = follow the recommendation, "" = show all

    private fun systemStep() {
        stepHeader("Choose the system", "Tap a card to select it for the quotation")
        val type = browseType ?: v.pkg?.type ?: SystemType.ONGRID
        body.addView(typeSwitch(type) { browseType = it; browseKw = null; render() })

        val best = recommendKw(v)
        if (best != null) {
            val reasons = listOfNotNull(
                v.sanctionedLoad.takeIf { it.isNotBlank() }?.let { "sanctioned load $it kW" },
                v.monthlyBill.takeIf { it.isNotBlank() }?.let { "bill ₹$it/month" },
            ).joinToString(" and ")
            body.addView(horizontal {
                background = rounded(Ui.SUN_LIGHT, dpf(14f), Ui.SUN, dp(1))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(icon(R.drawable.ic_bolt, Ui.SUN, 22))
                addView(text("Best fit: ${Fmt.num(best)} kW  (from $reasons)", 13.5f, Ui.TEXT, bold = true), lp(0, WRAP, 1f).margins(context, l = 10))
            }, lp().margins(this, t = 12))
        }

        val list = Catalog.of(type)
        val sizes = list.map { it.kw }.distinct().sorted()
        val kwLabel = browseKw ?: (v.pkg?.takeIf { it.type == type }?.kw ?: best)
            ?.let { k -> sizes.firstOrNull { it == k }?.let { Fmt.num(it) + " kW" } } ?: ""
        body.addView(chips(sizes.map { Fmt.num(it) + " kW" }, kwLabel) { browseKw = it; render() }, lp().margins(this, t = 12))

        val shown = list.filter { kwLabel.isBlank() || Fmt.num(it.kw) + " kW" == kwLabel }
        val load = Fmt.parse(v.sanctionedLoad)
        if (list.isEmpty()) body.addView(text("No ${type.label} systems in the price sheet.", 14f, Ui.MUTED), lp().margins(this, t = 20))
        shown.forEach { p ->
            val selected = v.pkg?.id == p.id
            body.addView(packageCard(p, selected, best != null && p.kw == best) {
                if (v.pkg?.id != p.id) { v.pkg = p; v.discount = 0.0 }
                save(); render()
                if (load != null && p.kw > load + 0.01) toast("Note: ${Fmt.num(p.kw)} kW is above the ${Fmt.num(load)} kW sanctioned load — load enhancement will be needed.")
            }, lp().margins(this, t = 12))
        }
        // a system chosen earlier that is no longer in the sheet
        v.pkg?.let { p -> if (Catalog.byId(p.id) == null) {
            body.addView(text("Selected earlier (no longer in price sheet):", 12.5f, Ui.MUTED), lp().margins(this, t = 16))
            body.addView(packageCard(p, true, false) {}, lp().margins(this, t = 6))
        } }
    }

    // ---------- step 4: price ----------

    private fun priceStep() {
        val p = v.pkg ?: return
        stepHeader("Price & savings", "Set the discount and show the customer the numbers")

        val finalText = text(Fmt.inr(v.finalPrice), 32f, Color.WHITE, bold = true)
        val discText = text("", 14f, Ui.TEXT, bold = true)
        val effText = text("", 22f, 0xFF2E7D32.toInt(), bold = true)
        val effSub = text("", 12f, Ui.MUTED)
        val paybackText = text("", 20f, Ui.BLUE, bold = true)
        fun refresh() {
            finalText.text = Fmt.inr(v.finalPrice)
            discText.text = if (v.discount > 0) "− ${Fmt.inr(v.discount)}" else "No discount"
            val sub = Money.subsidy(p, v.subsidyOn)
            effText.text = Fmt.inr(v.finalPrice - sub)
            effSub.text = if (sub > 0) "after ${Fmt.inr(sub)} PM Surya Ghar subsidy" else "no subsidy applied"
            val pb = Money.paybackYears(v)
            paybackText.text = if (pb > 0) "${Fmt.one(pb)} yrs" else "—"
        }

        // hero
        body.addView(vertical {
            background = gradient(dpf(20f), Ui.BRAND_DARK, Ui.BRAND)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            elevation = dpf(4f)
            addView(horizontal {
                addView(badge(p.type.icon, Ui.tierColor(p.tier), 40))
                addView(vertical {
                    addView(text("${Fmt.one(p.kwp)} kWp ${p.type.label} · ${p.tier}", 15f, Color.WHITE, bold = true))
                    addView(text("${p.panelBrand} · ${p.inverter}", 12f, 0xCCFFFFFF.toInt()) { maxLines = 1 })
                }, lp(0, WRAP, 1f).margins(context, l = 10))
            })
            addView(text("Final price (incl. GST)", 12.5f, 0xCCFFFFFF.toInt()), lp().margins(context, t = 14))
            addView(finalText)
            addView(text("List price ${Fmt.inr(p.price)}", 12f, 0xAAFFFFFF.toInt()))
        })

        // discount
        val max = p.maxDiscount
        body.addView(card {
            addView(horizontal {
                addView(text("Discount", 15f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(discText)
            })
            if (max <= 0) {
                addView(text("No discount allowed on this system.", 12.5f, Ui.MUTED), lp().margins(context, t = 4))
            } else {
                val stepAmt = if (max >= 5000) 500.0 else 100.0
                val stepsCount = (max / stepAmt).toInt()
                val seek = SeekBar(context).apply {
                    this.max = stepsCount
                    progress = (v.discount / stepAmt).roundToInt().coerceIn(0, stepsCount)
                    progressTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
                    thumbTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) { v.discount = (value * stepAmt).coerceAtMost(max); refresh() }
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) { save() }
                    })
                }
                addView(seek, lp().margins(context, t = 10))
                addView(horizontal {
                    addView(text("₹0", 11.5f, Ui.MUTED), lp(0, WRAP, 1f))
                    addView(text("Max ${Fmt.inr(max)}", 11.5f, Ui.MUTED))
                })
            }
        }, lp().margins(this, t = 14))

        // subsidy & effective cost
        body.addView(card {
            addView(horizontal {
                addView(badge(R.drawable.ic_rupee, 0xFF2E7D32.toInt(), 38))
                addView(vertical {
                    addView(text("PM Surya Ghar subsidy", 15f, Ui.TEXT, bold = true))
                    addView(text("On ${Fmt.num(p.dcrKw.takeIf { it > 0 } ?: p.kwp)} kW DCR panels · paid to customer's bank", 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(SwitchCompat(context).apply {
                    isChecked = v.subsidyOn
                    setOnCheckedChangeListener { _, on -> v.subsidyOn = on; save(); refresh() }
                })
            })
            addView(View(context).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, dp(1)).margins(context, t = 12, b = 10))
            addView(text("Customer's effective cost", 12.5f, Ui.MUTED))
            addView(effText)
            addView(effSub)
        }, lp().margins(this, t = 12))

        // savings
        val units = Money.yearlyUnits(p)
        val saving = Money.yearlySaving(p)
        body.addView(sectionTitle("Savings estimate", "≈ ${Fmt.num(Catalog.settings.num("units_per_kw_per_day", 4.0))} units/kW/day at ₹${Fmt.num(Catalog.settings.num("tariff_per_unit", 7.0))}/unit"), lp().margins(this, t = 20, b = 10))
        body.addView(horizontal {
            fun tile(value: String, label: String, color: Int, valueView: TextView? = null) = card(12) {
                addView(valueView ?: text(value, 19f, color, bold = true) { maxLines = 1 })
                addView(text(label, 11.5f, Ui.MUTED))
            }
            addView(tile("${(units / 12).roundToInt()}", "units / month", Ui.SUN), lp(0, WRAP, 1f).margins(context, r = 8))
            addView(tile(Fmt.inr(saving / 12), "saved / month", 0xFF2E7D32.toInt()), lp(0, WRAP, 1f).margins(context, r = 8))
            addView(tile("", "payback", Ui.BLUE, paybackText), lp(0, WRAP, 1f))
        })
        body.addView(card {
            val life = saving * 25
            addView(text("25-year savings", 13f, Ui.MUTED, bold = true))
            addView(text(Fmt.inr(life), 24f, 0xFF2E7D32.toInt(), bold = true))
            addView(text("vs. a one-time cost of ${Fmt.inr(v.finalPrice)}", 12f, Ui.MUTED))
            val ratio = if (life > 0) (v.finalPrice / life).toFloat().coerceIn(0.05f, 1f) else 1f
            addView(horizontal {
                addView(View(context).apply { background = rounded(Ui.SUN, dpf(6f)) }, lp(0, dp(14), ratio))
                addView(View(context).apply { background = rounded(0xFF2E7D32.toInt(), dpf(6f)) }, lp(0, dp(14), 1f - ratio).margins(context, l = 4))
            }, lp().margins(context, t = 10))
            addView(horizontal {
                addView(text("■ Cost", 11.5f, Ui.SUN, bold = true), lp(0, WRAP, 1f))
                addView(text("■ Savings beyond cost", 11.5f, 0xFF2E7D32.toInt(), bold = true))
            }, lp().margins(context, t = 4))
        }, lp().margins(this, t = 10))
        refresh()
    }

    // ---------- step 5: finish ----------

    private fun finishStep() {
        val p = v.pkg ?: return
        stepHeader(if (v.isQuoted) "Quotation ready" else "Review & sign", if (v.isQuoted) "Share it with the customer" else "Customer signs, then create the PDFs")

        body.addView(card {
            addView(kv("Customer", v.name, true))
            addView(kv("WhatsApp", v.mobile))
            if (v.address.isNotBlank() || v.district.isNotBlank()) addView(kv("Address", listOf(v.address, v.district).filter { it.isNotBlank() }.joinToString(", ")))
            addView(kv("System", "${Fmt.one(p.kwp)} kWp ${p.type.label} · ${p.tier}", true))
            addView(kv("Panels", "${p.panelBrand} · ${p.panelSummary()}"))
            addView(kv("Inverter", p.inverter))
            if (p.battery.isNotBlank()) addView(kv("Battery", p.battery))
            if (v.discount > 0) addView(kv("Discount", "− " + Fmt.inr(v.discount)))
            addView(kv("Final price", Fmt.inr(v.finalPrice), true))
            val sub = Money.subsidy(p, v.subsidyOn)
            if (sub > 0) addView(kv("After subsidy", Fmt.inr(v.finalPrice - sub)))
            addView(kv("Site photos", "${v.photos.size}"))
        })

        if (v.isQuoted) {
            val q = VisitStore.quotationPdf(this, v)
            val r = VisitStore.reportPdf(this, v)
            body.addView(pillButton("Send quotation on WhatsApp", 0xFF25D366.toInt(), iconRes = R.drawable.ic_chat) {
                if (!q.exists()) { makePdfs(); return@pillButton }
                VisitStore.whatsapp(this, q, v.mobile, customerMessage())
            }, lp().margins(this, t = 16))
            body.addView(horizontal {
                addView(pillButton("Quotation", Color.WHITE, Ui.BRAND, 46, R.drawable.ic_pdf) { if (q.exists()) VisitStore.open(this@VisitActivity, q) else makePdfs() }.apply {
                    background = rounded(Color.WHITE, dpf(23f), Ui.BRAND, dp(2))
                }, lp(0, WRAP, 1f).margins(context, r = 10))
                addView(pillButton("Site report", Color.WHITE, Ui.BRAND, 46, R.drawable.ic_pdf) { if (r.exists()) VisitStore.open(this@VisitActivity, r) else makePdfs() }.apply {
                    background = rounded(Color.WHITE, dpf(23f), Ui.BRAND, dp(2))
                }, lp(0, WRAP, 1f))
            }, lp().margins(this, t = 10))
            body.addView(pillButton("Share both PDFs (office)", Ui.BRAND_LIGHT, Ui.BRAND_DARK, 46, R.drawable.ic_share) {
                VisitStore.share(this, listOf(q, r).filter { it.exists() }, "Site visit ${v.number} — ${v.name}, ${v.district}")
            }, lp().margins(this, t = 10))
            body.addView(text("Changed something? Go back to any step, then tap Re-create PDFs.", 12f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 12, b = 4))
        }

        body.addView(horizontal {
            addView(sectionTitle("Customer signature", "On the site visit report and quotation"), lp(0, WRAP, 1f))
            addView(text("Clear", 14f, Ui.DANGER, bold = true) { setPadding(dp(10), dp(8), dp(6), dp(8)); setOnClickListener { signPad?.clear() } })
        }, lp().margins(this, t = 20, b = 10))
        val pad = DrawPad(this, compass = false).apply {
            background = rounded(Color.WHITE, dpf(14f), Ui.LINE, dp(1))
            if (v.customerSign.isNotBlank()) loadFrom(VisitStore.file(context, v, v.customerSign))
            onDrawn = { signDirty = true }
            clipToOutline = true
        }
        signPad = pad
        body.addView(pad, lp(MATCH, dp(170)))
        body.addView(text("Visiting officer: ${v.officerName.ifBlank { Officer.name(this) }} · ${v.officerMobile.ifBlank { Officer.mobile(this) }}", 12.5f, Ui.MUTED), lp().margins(this, t = 10))
    }

    private fun customerMessage(): String {
        val p = v.pkg
        val company = Catalog.settings["brand_name"]
        return "Namaste ${v.name}, thank you for your time today. Please find your $company quotation for a " +
            "${p?.let { Fmt.one(it.kwp) + " kWp " + it.type.label } ?: ""} solar system (${v.number}). " +
            "For any question call ${v.officerName.ifBlank { Officer.name(this) }} on ${v.officerMobile.ifBlank { Officer.mobile(this) }}."
    }

    private fun makePdfs() {
        savePads(); save()
        val dialog = AlertDialog.Builder(this).setMessage("Creating PDFs…").setCancelable(false).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (v.quotedAt == 0L) v.quotedAt = System.currentTimeMillis()
                    Pdfs(this@VisitActivity).quotation(v, VisitStore.quotationPdf(this@VisitActivity, v))
                    Pdfs(this@VisitActivity).siteReport(v, VisitStore.reportPdf(this@VisitActivity, v))
                    true
                } catch (e: Exception) {
                    false
                }
            }
            dialog.dismiss()
            if (!ok) { toast("Could not create the PDF"); return@launch }
            save()
            Remote.queue(this@VisitActivity, Remote.visitSummary(this@VisitActivity, v))
            step = 4
            render()
            scroll.scrollTo(0, 0)
            toast("Quotation ready")
        }
    }

    // ---------- location ----------

    private fun askLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) captureLocation()
        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun captureLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (providers.isEmpty()) {
            toast("Turn on Location (GPS) in phone settings")
            try { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            return
        }
        val wait = AlertDialog.Builder(this).setMessage("Getting GPS fix…\nStand in the open for best accuracy.").setCancelable(true).show()
        var done = false
        val handler = Handler(Looper.getMainLooper())
        lateinit var listener: LocationListener
        fun deliver(loc: Location?) {
            if (done) return
            done = true
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            if (wait.isShowing) wait.dismiss()
            if (loc == null) { toast("Couldn't get location. Try again outdoors."); return }
            v.lat = loc.latitude; v.lng = loc.longitude
            save(); render()
            toast("Location saved (±${loc.accuracy.roundToInt()} m)")
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (location.accuracy <= 50f || location.provider == LocationManager.NETWORK_PROVIDER) deliver(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        // a recent fix is good enough
        val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 120_000 && it.accuracy <= 50f }
            .minByOrNull { it.accuracy }
        if (recent != null) { deliver(recent); return }
        providers.forEach { try { lm.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {} }
        handler.postDelayed({
            if (!done) {
                val any = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
                deliver(any)
            }
        }, 25_000L)
        wait.setOnCancelListener { done = true; try { lm.removeUpdates(listener) } catch (_: Exception) {} }
    }

    // ---------- photos ----------

    private fun thumb(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeFile(f.absolutePath, o)
    } catch (_: Exception) { null }

    /** Downscale to max 1600 px, fix rotation, JPEG 82 — keeps PDFs small for WhatsApp. */
    private fun shrinkPhoto(f: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            var bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            val rotation = when (ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val scale = 1600f / maxOf(bmp.width, bmp.height)
            if (rotation != 0f || scale < 1f) {
                val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (_: Exception) {
        }
    }

    private fun copyPhoto(u: Uri): File? = try {
        val f = VisitStore.file(this, v, "photo_${System.currentTimeMillis()}_${(0..999).random()}.jpg")
        contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        shrinkPhoto(f)
        f
    } catch (_: Exception) { null }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
