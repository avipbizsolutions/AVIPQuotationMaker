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
    private var signPad: DrawPad? = null
    private var signDirty = false
    private var pendingPhoto: File? = null
    private var photoToBank = false // next photo goes to the bank proof list
    private var recorder: android.media.MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: android.media.MediaPlayer? = null
    private var playing = ""

    private fun photoList() = if (photoToBank) v.bankDocs else v.photos

    // ---------- activity results ----------

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto ?: return@registerForActivityResult
        pendingPhoto = null
        if (ok && f.exists() && f.length() > 0) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { shrinkPhoto(f) }
                photoList().add(f.name); save(); render()
            }
        } else f.delete()
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.take(8).forEach { u -> copyPhoto(u)?.let { photoList().add(it.name) } }
            }
            save(); render()
        }
    }

    private val sketchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val f = VisitStore.file(this, v, "sketch.png")
        v.sketch = if (f.exists()) f.name else ""
        save(); render()
    }

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast("Microphone permission is needed for voice notes")
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val locationPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) captureLocation(silentPerm) else toast("Location permission is needed to tag the site")
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: savedInstanceState?.getString(EXTRA_NUMBER)
        v = number?.let { VisitStore.load(this, it) } ?: VisitStore.create(this).also { VisitStore.save(this, it) }
        if (number != null && v.isQuoted) step = 4

        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar("Site Visit", v.number, onBack = { leave() },
            right = if (v.isQuoted) emptyList() else listOf(barButton(R.drawable.ic_delete) { discardDraft() })))
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
        // tag the site automatically: the executive is standing at the customer's home
        if (!v.hasLocation && !v.isQuoted) autoLocate()
    }

    private var locating = false
    private var silentPerm = false

    private fun autoLocate() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) captureLocation(silent = true)
        else {
            silentPerm = true
            locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_NUMBER, v.number)
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
        stopPlaying()
        savePads()
        save()
    }

    private fun save() = VisitStore.save(this, v)

    private fun leave() {
        savePads(); save()
        if (v.name.isBlank() && v.mobile.isBlank() && v.photos.isEmpty() && v.pkg == null && v.voiceNotes.isEmpty()) VisitStore.delete(this, v)
        finish()
    }

    private fun discardDraft() {
        AlertDialog.Builder(this)
            .setTitle("Delete this draft?")
            .setMessage("${v.name.ifBlank { v.number }} — details, photos and voice notes will be removed from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                stopRecording(); stopPlaying()
                Reminders.cancel(this, v.number)
                VisitStore.delete(this, v)
                toast("Draft deleted")
                finish()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun next() {
        if (step == 0) {
            if (v.name.isBlank()) { toast("Enter the customer's name"); return }
            if (v.mobile.filter { it.isDigit() }.length < 10) { toast("Enter a 10-digit WhatsApp number"); return }
        }
        if (step == 2 && v.pkg == null) { toast("Pick a system first"); return }
        if (step == 2 && (v.pkg?.price ?: 0.0) <= 0) { toast("Enter the price of the custom system"); return }
        if (step == 4) {
            if (v.leadStatus == "Open" && v.followUpAt <= 0) {
                toast("Set the next follow-up date before creating the quotation")
                followUpCard?.let { c -> scroll.post { scroll.smoothScrollTo(0, c.top - dp(12)) } }
                return
            }
            makePdfs(); return
        }
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
        signPad = null
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

        body.addView(sectionTitle("Project funding", "How will the customer pay?"), lp().margins(this, t = 20, b = 10))
        body.addView(tiles(listOf("Own fund" to R.drawable.ic_rupee, "Loan" to R.drawable.ic_bolt), v.funding, columns = 2) {
            v.funding = it; save(); render()
        })
        if (v.isLoan) {
            body.addView(card {
                addView(horizontal {
                    addView(icon(R.drawable.ic_person, Ui.BRAND, 18))
                    addView(text("KYC for the solar loan", 14f, Ui.TEXT, bold = true), lp(0, WRAP, 1f).margins(context, l = 6))
                })
                addView(text("Loan @ ${Fmt.num(Money.loanRate())}% for up to ${Money.maxYears()} years", 12f, Ui.MUTED), lp().margins(context, t = 2))
                field(this, "PAN", v.pan, "ABCDE1234F", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, maxLen = 10) { v.pan = it.uppercase() }
                field(this, "Aadhaar", v.aadhaar, "12 digits", InputType.TYPE_CLASS_NUMBER, maxLen = 12) { v.aadhaar = it }
                addView(text("Aadhaar prints only on the internal site visit report, never on the quotation.", 11.5f, Ui.MUTED), lp().margins(context, t = 6))
            }, lp().margins(this, t = 4))
        }

        body.addView(sectionTitle("Bank proof for subsidy", "Account where the subsidy will be credited"), lp().margins(this, t = 20, b = 10))
        body.addView(chips(listOf("Bank statement", "Passbook", "Cancelled cheque"), v.bankDocType) { v.bankDocType = it })
        body.addView(photoGrid(v.bankDocs, bank = true), lp().margins(this, t = 10))
    }

    // ---------- step 2: site ----------

    private fun siteStep() {
        stepHeader("Site survey", "Location, roof, photos and structure")

        // location
        body.addView(card {
            addView(horizontal {
                addView(badge(R.drawable.ic_location, if (v.hasLocation) Ui.BRAND else if (locating) Ui.SUN else Ui.MUTED, 42))
                addView(vertical {
                    addView(text(when { v.hasLocation -> "Site location tagged"; locating -> "Getting location…"; else -> "Site location not tagged" }, 15f, Ui.TEXT, bold = true))
                    addView(text(when {
                        v.hasLocation -> String.format(java.util.Locale.US, "%.6f, %.6f", v.lat, v.lng)
                        locating -> "Captured automatically — keep Location (GPS) on"
                        else -> "Turn on Location, then tap Capture"
                    }, 12.5f, Ui.MUTED))
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
        ) { picked ->
            val wasGround = v.roofType == "Ground Mount"
            v.roofType = picked
            if (picked == "Ground Mount") v.floors = ""
            if (wasGround != (picked == "Ground Mount")) { save(); render() }
        })
        if (v.roofType != "Ground Mount") {
            body.addView(text("Building height", 13f, Ui.MUTED, bold = true), lp().margins(this, t = 8, b = 6))
            body.addView(chips(listOf("Ground", "G+1", "G+2", "G+3", "G+4 or more"), v.floors) { v.floors = it })
        }

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
        body.addView(photoGrid(v.photos, bank = false))

        // sketch (landscape, full screen)
        body.addView(sectionTitle("Site sketch", "Roof outline, obstacles and panel rows — drawn sideways for more room"), lp().margins(this, t = 20, b = 10))
        val sketchFile = VisitStore.file(this, v, "sketch.png").takeIf { v.sketch.isNotBlank() && it.exists() }
        body.addView(FrameLayout(this).apply {
            background = rounded(Color.WHITE, dpf(14f), Ui.LINE, dp(1))
            clipToOutline = true
            foreground = ripple()
            if (sketchFile != null) {
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    BitmapFactory.decodeFile(sketchFile.absolutePath)?.let { setImageBitmap(it) }
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(tag("TAP TO EDIT", Ui.BRAND), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(10), dp(10)) })
            } else {
                addView(vertical {
                    gravity = Gravity.CENTER
                    addView(icon(R.drawable.ic_compass, Ui.BRAND, 44))
                    addView(text("Draw site sketch", 16f, Ui.BRAND_DARK, bold = true), lp(WRAP, WRAP).margins(context, t = 8))
                    addView(text("Opens full screen in landscape", 12f, Ui.MUTED), lp(WRAP, WRAP).margins(context, t = 2))
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            setOnClickListener {
                sketchLauncher.launch(Intent(context, SketchActivity::class.java)
                    .putExtra(SketchActivity.EXTRA_FILE, VisitStore.file(context, v, "sketch.png").absolutePath))
            }
        }, lp(MATCH, (resources.displayMetrics.widthPixels - dp(32)) * 10 / 16))

        // voice notes
        body.addView(sectionTitle("Voice notes for installation team", "Explain anything that is hard to write"), lp().margins(this, t = 20, b = 10))
        body.addView(voiceCard())

        // structure
        body.addView(sectionTitle("Proposed structure"), lp().margins(this, t = 20, b = 10))
        body.addView(tiles(listOf("Magnelis" to R.drawable.cat_structure, "Normal GI" to R.drawable.ic_ruler), v.structureType, columns = 2) {
            v.structureType = it; save(); render()
        })
        if (v.structureType == "Magnelis") {
            body.addView(text("Module mounting", 13f, Ui.MUTED, bold = true), lp().margins(this, t = 4, b = 6))
            body.addView(tiles(listOf("2P × 3" to R.drawable.ic_grid, "2P × 4" to R.drawable.ic_grid, "2P × 5" to R.drawable.ic_grid),
                v.mountingLayout, columns = 3) { v.mountingLayout = it; save() })
            body.addView(text("2 panels in portrait per column × 3, 4 or 5 columns (6, 8 or 10 panels per table)", 11.5f, Ui.MUTED), lp().margins(this, t = 2))
        }
        if (v.isGi) body.addView(card(12) {
            addView(text("GI structure dimensions", 13f, Ui.MUTED, bold = true), lp().margins(context, b = 8))
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
        }, lp().margins(this, t = 4))

        body.addView(card {
            field(this, "Notes for the installation team", v.siteNotes, "Access, water tank, earthing pit spot, special requests…",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit) { v.siteNotes = it }
        }, lp().margins(this, t = 16))
    }

    private fun photoGrid(list: MutableList<String>, bank: Boolean): View {
        val box = vertical { }
        val items: List<String?> = list.toList() + listOf(null)
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
                            addView(text(if (bank) "Add proof" else "Add photo", 12.5f, Ui.BRAND_DARK, bold = true), lp(WRAP, WRAP).margins(context, t = 4))
                        }, FrameLayout.LayoutParams(MATCH, MATCH))
                        cell.setOnClickListener { addPhoto(bank) }
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
                                    .setPositiveButton("Remove") { _, _ -> f.delete(); list.remove(name); save(); render() }
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

    private fun addPhoto(bank: Boolean) {
        photoToBank = bank
        AlertDialog.Builder(this).setTitle(if (bank) "Add bank proof photo" else "Add site photo")
            .setItems(arrayOf("Take photo with camera", "Choose from gallery")) { _, which ->
                if (which == 0) {
                    val f = VisitStore.file(this, v, (if (bank) "bank_" else "photo_") + "${System.currentTimeMillis()}.jpg")
                    pendingPhoto = f
                    try { takePhoto.launch(VisitStore.uri(this, f)) } catch (_: Exception) { toast("Camera not available") }
                } else pickPhoto.launch("image/*")
            }.show()
    }

    // ---------- voice notes ----------

    private fun voiceCard(): View = card {
        v.voiceNotes.forEachIndexed { i, name ->
            val f = VisitStore.file(context, v, name)
            addView(horizontal {
                val isPlaying = playing == name
                addView(badge(if (isPlaying) R.drawable.ic_close else R.drawable.ic_chat, if (isPlaying) Ui.DANGER else Ui.BLUE, 38).apply {
                    setOnClickListener { if (isPlaying) { stopPlaying(); render() } else play(f, name) }
                })
                addView(vertical {
                    addView(text("Voice note ${i + 1}", 14f, Ui.TEXT, bold = true))
                    addView(text((if (isPlaying) "Playing…  tap ■ to stop" else "Tap to play") + "  ·  " + duration(f), 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(icon(R.drawable.ic_delete, Ui.DANGER, 36).apply {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        AlertDialog.Builder(this@VisitActivity).setTitle("Delete voice note ${i + 1}?")
                            .setPositiveButton("Delete") { _, _ -> stopPlaying(); f.delete(); v.voiceNotes.remove(name); save(); render() }
                            .setNegativeButton("Cancel", null).show()
                    }
                })
            }, lp().margins(context, b = 8))
        }
        val rec = recorder != null
        addView(pillButton(if (rec) "Stop recording" else "Record voice note", if (rec) Ui.DANGER else Ui.BRAND, iconRes = if (rec) R.drawable.ic_close else R.drawable.ic_chat) {
            if (recorder != null) { stopRecording(); render() } else askMic()
        })
        if (rec) addView(text("● Recording… speak clearly near the phone", 12.5f, Ui.DANGER, bold = true) { gravity = Gravity.CENTER }, lp().margins(context, t = 8))
    }

    private fun askMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        stopPlaying()
        val f = VisitStore.file(this, v, "voice_${System.currentTimeMillis()}.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64000)
            r.setAudioSamplingRate(22050)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingFile = f
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
            f.delete(); recorder = null
            toast("Could not start recording")
        }
        render()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val f = recordingFile
        recordingFile = null
        try { r.stop() } catch (_: Exception) { f?.delete() }
        r.release()
        if (f != null && f.exists() && f.length() > 0) { v.voiceNotes += f.name; save() }
    }

    private fun play(f: File, name: String) {
        stopPlaying()
        try {
            player = android.media.MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { stopPlaying(); render() }
                prepare(); start()
            }
            playing = name
        } catch (_: Exception) { toast("Could not play this note") }
        render()
    }

    private fun stopPlaying() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        playing = ""
    }

    private fun duration(f: File): String = try {
        val m = android.media.MediaMetadataRetriever()
        m.setDataSource(f.absolutePath)
        val ms = m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        m.release()
        "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    } catch (_: Exception) { "" }

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
                customOpen = false; customFields = null
                save(); render()
                if (load != null && p.kw > load + 0.01) toast("Note: ${Fmt.num(p.kw)} kW is above the ${Fmt.num(load)} kW sanctioned load — load enhancement will be needed.")
            }, lp().margins(this, t = 12))
        }
        // custom system filled in by the sales executive
        if (v.pkg?.custom == true || customOpen) customForm(type)
        else body.addView(horizontal {
            background = rounded(Color.WHITE, dpf(18f), Ui.LINE, dp(1))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            foreground = context.ripple()
            addView(badge(R.drawable.ic_edit, Ui.MUTED, 42, square = true))
            addView(vertical {
                addView(text("Custom system", 16f, Ui.TEXT, bold = true))
                addView(text("Not in the price list? Fill in brands, panels and price", 12.5f, Ui.MUTED))
            }, lp(0, WRAP, 1f).margins(context, l = 12))
            addView(text("+", 26f, Ui.BRAND, bold = true))
            setOnClickListener { customOpen = true; customFields = null; render() }
        }, lp().margins(this, t = 16))

        // a system chosen earlier that is no longer in the sheet
        v.pkg?.let { p -> if (!p.custom && Catalog.byId(p.id) == null) {
            body.addView(text("Selected earlier (no longer in price sheet):", 12.5f, Ui.MUTED), lp().margins(this, t = 16))
            body.addView(packageCard(p, true, false) {}, lp().margins(this, t = 6))
        } }
    }

    private var customOpen = false
    private var customFields: MutableMap<String, String>? = null

    private fun customForm(type: SystemType) {
        val cur = v.pkg?.takeIf { it.custom }
        val fields = customFields ?: mutableMapOf<String, String>().also { m ->
            if (cur != null) {
                val names = SystemPackage.CUSTOM_FIELDS.filter { cur.type == SystemType.HYBRID || !it.contains("Battery") }
                names.zip(cur.rows).forEach { (k, row) -> m[k] = row.second }
            } else {
                m["Panel Warranty"] = "30 Years"; m["Inverter Warranty"] = "10 Years"
                m["Structure"] = "APL Apollo / Magnelis"; m["DC Wire"] = "4 mm"; m["AC Wire"] = "4 mm"
                m["Earthing Rod"] = "1 meter * 3 pc"
                if (type == SystemType.HYBRID) m["Battery Warranty"] = "10 Years"
            }
            customFields = m
        }
        val summary = text("", 14f, Color.WHITE, bold = true)
        fun rebuild() {
            val pkg = SystemPackage.custom(type, fields)
            if (v.pkg?.custom != true || v.pkg?.type != type) v.discount = 0.0
            v.pkg = pkg
            summary.text = if (pkg.totalWp > 0) "${Fmt.one(pkg.kwp)} kWp ${type.label} · ${pkg.totalPanels} panels" +
                (if (pkg.price > 0) " · ${Fmt.inr(pkg.price)}" else "") else "Fill in the panels to see the size"
        }
        body.addView(vertical {
            background = rounded(Color.WHITE, dpf(18f), Ui.BRAND, dp(3))
            elevation = dpf(6f)
            addView(horizontal {
                background = gradient(dpf(16f), Ui.tierColor("custom"), Ui.TEXT)
                setPadding(dp(16), dp(12), dp(12), dp(12))
                addView(vertical {
                    addView(text("CUSTOM ${type.label.uppercase()} SYSTEM", 11f, 0xDDFFFFFF.toInt(), bold = true) { letterSpacing = 0.1f })
                    addView(summary)
                }, lp(0, WRAP, 1f))
                addView(text("Remove", 13f, Color.WHITE, bold = true) {
                    setPadding(dp(10), dp(6), dp(4), dp(6))
                    setOnClickListener {
                        customOpen = false; customFields = null
                        if (v.pkg?.custom == true) { v.pkg = null; v.discount = 0.0 }
                        save(); render()
                    }
                })
            }, lp().margins(context, l = 2, t = 2, r = 2))
            val form = vertical { setPadding(dp(14), dp(4), dp(14), dp(16)) }
            fun f(parent: LinearLayout, label: String, key: String, hint: String, number: Boolean = false) =
                field(parent, label, fields[key] ?: "", hint,
                    if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                    fields[key] = it; rebuild(); save()
                }
            fun pair(l1: String, k1: String, h1: String, l2: String, k2: String, h2: String, number: Boolean) {
                form.addView(horizontal {
                    val a = vertical { }; val b = vertical { }
                    f(a, l1, k1, h1, number); f(b, l2, k2, h2, number)
                    addView(a, lp(0, WRAP, 1f).margins(context, r = 10)); addView(b, lp(0, WRAP, 1f))
                })
            }
            pair("Panel brand", "Panel Brand", "e.g. Waaree", "Panel warranty", "Panel Warranty", "30 Years", false)
            pair("DCR panel watt (Wp)", "DCR Panel Wattage", "e.g. 545", "No. of DCR panels", "No. of Panels (DCR)", "e.g. 6", true)
            pair("Non-DCR panel watt", "Non-DCR Panel Wattage", "optional", "No. of Non-DCR", "No. of Panels (Non-DCR)", "0", true)
            pair("Inverter", "Inverter Brand and Specification", "e.g. Deye 5kw", "Inverter warranty", "Inverter Warranty", "10 Years", false)
            if (type == SystemType.HYBRID) pair("Battery", "Lithium Battery", "e.g. Deye 100 AH, 51.2 V", "Battery warranty", "Battery Warranty", "10 Years", false)
            f(form, "Structure", "Structure", "e.g. APL Apollo / Magnelis")
            pair("DC wire", "DC Wire", "4 mm", "AC wire", "AC Wire", "4 mm", false)
            f(form, "Earthing", "Earthing Rod", "1 meter * 3 pc")
            f(form, "Price incl. GST (₹) *", "Price (GST Included)", "Final selling price", number = true)
            addView(form)
        }, lp().margins(this, t = 16))
        rebuild()
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
        val centralText = text("", 14f, 0xFF2E7D32.toInt(), bold = true)
        val stateText = text("", 14f, 0xFF2E7D32.toInt(), bold = true)
        var loanRefresh: (() -> Unit)? = null
        fun refresh() {
            finalText.text = Fmt.inr(v.finalPrice)
            discText.text = if (v.discount > 0) "− ${Fmt.inr(v.discount)}" else "No discount"
            val sub = Money.subsidy(p, v.subsidyOn)
            effText.text = Fmt.inr(v.finalPrice - sub)
            effSub.text = if (sub > 0) "after ${Fmt.inr(sub)} total subsidy" else "no subsidy applied"
            val pb = Money.paybackYears(v)
            paybackText.text = if (pb > 0) "${Fmt.one(pb)} yrs" else "—"
            centralText.text = Fmt.inr(Money.central(p, v.subsidyOn))
            stateText.text = Fmt.inr(Money.state(p, v.subsidyOn))
            loanRefresh?.invoke()
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
                    addView(text("Government subsidy", 15f, Ui.TEXT, bold = true))
                    addView(text("On ${Fmt.num(p.dcrKw.takeIf { it > 0 } ?: p.kwp)} kW DCR panels · paid to customer's bank", 12f, Ui.MUTED))
                }, lp(0, WRAP, 1f).margins(context, l = 10))
                addView(SwitchCompat(context).apply {
                    isChecked = v.subsidyOn
                    setOnCheckedChangeListener { _, on -> v.subsidyOn = on; save(); refresh() }
                })
            })
            addView(horizontal {
                addView(text("PM Surya Ghar (central)", 13.5f, Ui.MUTED), lp(0, WRAP, 1f))
                addView(centralText)
            }, lp().margins(context, t = 10))
            addView(horizontal {
                addView(text(Catalog.settings["state_subsidy_name"], 13.5f, Ui.MUTED), lp(0, WRAP, 1f))
                addView(stateText)
            }, lp().margins(context, t = 4))
            addView(View(context).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, dp(1)).margins(context, t = 12, b = 10))
            addView(text("Customer's effective cost", 12.5f, Ui.MUTED))
            addView(effText)
            addView(effSub)
        }, lp().margins(this, t = 12))

        // loan
        if (v.isLoan) {
            val emiText = text("", 26f, Ui.BLUE, bold = true)
            val loanText = text("", 12.5f, Ui.MUTED)
            val compare = text("", 12.5f, Ui.TEXT, bold = true)
            loanRefresh = {
                val amt = Money.loanAmount(v)
                val emi = Money.emi(v)
                emiText.text = "${Fmt.inr(emi)} / month"
                loanText.text = "Loan ${Fmt.inr(amt)} @ ${Fmt.num(Money.loanRate())}% for ${v.loanYears} yrs · interest ${Fmt.inr(emi * v.loanYears * 12 - amt)}"
                val save = Money.yearlySaving(p) / 12
                compare.text = if (emi > 0 && save > 0) "Bill saving ${Fmt.inr(save)}/month covers ${((save / emi) * 100).roundToInt().coerceAtMost(999)}% of the EMI" else ""
            }
            body.addView(sectionTitle("Solar loan", "Interest ${Fmt.num(Money.loanRate())}% · up to ${Money.maxYears()} years"), lp().margins(this, t = 20, b = 10))
            body.addView(card {
                field(this, "Down payment by customer (₹)", v.downPayment, "0", InputType.TYPE_CLASS_NUMBER, R.drawable.ic_rupee) {
                    v.downPayment = it; loanRefresh?.invoke()
                }
                addView(text("Tenure", 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
                v.loanYears = v.loanYears.coerceIn(1, Money.maxYears())
                addView(chips((1..Money.maxYears()).map { "$it yr" + if (it > 1) "s" else "" }, "${v.loanYears} yr" + if (v.loanYears > 1) "s" else "") { picked ->
                    picked.substringBefore(' ').toIntOrNull()?.let { v.loanYears = it; save(); loanRefresh?.invoke() }
                })
                addView(text("Monthly EMI", 12.5f, Ui.MUTED), lp().margins(context, t = 14))
                addView(emiText)
                addView(loanText)
                addView(compare, lp().margins(context, t = 6))
            })
        }

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
            if (v.funding.isNotBlank()) addView(kv("Funding", if (v.isLoan) "Loan · EMI ${Fmt.inr(Money.emi(v))} × ${v.loanYears * 12}" else v.funding))
            addView(kv("Photos · voice notes", "${v.photos.size} · ${v.voiceNotes.size}"))
            if (v.bankDocs.isNotEmpty()) addView(kv("Bank proof", "${v.bankDocType.ifBlank { "Photo" }} (${v.bankDocs.size})"))
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
            body.addView(pillButton("Share PDFs + voice notes (office)", Ui.BRAND_LIGHT, Ui.BRAND_DARK, 46, R.drawable.ic_share) {
                val files = listOf(q, r).filter { it.exists() } + v.voiceNotes.map { VisitStore.file(this, v, it) }.filter { it.exists() }
                VisitStore.share(this, files, "Site visit ${v.number} — ${v.name}, ${v.district}")
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
        body.addView(pad, lp(MATCH, dp(255)))
        body.addView(text("Visiting officer: ${v.officerName.ifBlank { Officer.name(this) }} · ${v.officerMobile.ifBlank { Officer.mobile(this) }}", 12.5f, Ui.MUTED), lp().margins(this, t = 10))

        followUpSection()
    }

    // ---------- follow-up ----------

    private val dayFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.ENGLISH)

    private var followUpCard: View? = null

    private fun followUpSection() {
        val title = sectionTitle("Follow-up", "Required while the lead is Open · reminder at 10:00 AM")
        followUpCard = title
        body.addView(title, lp().margins(this, t = 24, b = 10))
        body.addView(card {
            addView(text("Lead status", 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
            addView(chips(listOf("Open", "Converted", "Lost"), v.leadStatus, Ui.BLUE) { st ->
                v.leadStatus = st.ifBlank { "Open" }
                if (v.leadStatus != "Open") Reminders.cancel(this@VisitActivity, v.number) else Reminders.schedule(this@VisitActivity, v)
                save(); syncVisit(); render()
            })
            if (v.leadStatus == "Open") {
                addView(horizontal {
                    addView(badge(R.drawable.ic_history, if (v.followUpAt > 0) Ui.BRAND else Ui.MUTED, 42))
                    addView(vertical {
                        addView(text(if (v.followUpAt > 0) dayFmt.format(java.util.Date(v.followUpAt)) else "No follow-up date", 15f, Ui.TEXT, bold = true))
                        addView(text(if (v.followUpAt > 0) "Call reminder at 10:00 AM" else "Required — tap to pick the next call date", 12f, if (v.followUpAt > 0) Ui.MUTED else Ui.DANGER))
                    }, lp(0, WRAP, 1f).margins(context, l = 10))
                    addView(text(if (v.followUpAt > 0) "Change" else "Set date", 14f, Ui.BRAND, bold = true) {
                        setPadding(dp(8), dp(8), dp(4), dp(8))
                    })
                    setOnClickListener { pickFollowUpDate { day -> setFollowUp(day) } }
                }, lp().margins(context, t = 14))
                field(this, "What to discuss", v.followUpNote, "e.g. confirm loan papers, roof repair", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) {
                    v.followUpNote = it
                }
            }
            addView(horizontal {
                addView(pillButton("Call ${v.mobile}", Ui.BRAND, heightDp = 46, iconRes = R.drawable.ic_call) {
                    VisitStore.call(this@VisitActivity, v.mobile)
                }, lp(0, WRAP, 1f).margins(context, r = 10))
                addView(pillButton("Log call", Ui.SUN, Ui.TEXT, 46, R.drawable.ic_edit) { logCall() }, lp(WRAP, WRAP))
            }, lp().margins(context, t = 14))
        })
        if (v.followUps.isNotEmpty()) {
            body.addView(card {
                addView(text("Call history", 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
                v.followUps.reversed().forEach { f ->
                    addView(vertical {
                        addView(text("${java.text.SimpleDateFormat("dd MMM, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date(f.at))} · ${f.outcome}", 13.5f, Ui.TEXT, bold = true))
                        if (f.note.isNotBlank()) addView(text(f.note, 12.5f, Ui.MUTED))
                    }, lp().margins(context, b = 8))
                }
            }, lp().margins(this, t = 10))
        }
    }

    private fun pickFollowUpDate(onPicked: (Long) -> Unit) {
        val c = java.util.Calendar.getInstance()
        if (v.followUpAt > 0) c.timeInMillis = v.followUpAt else c.add(java.util.Calendar.DAY_OF_MONTH, 2)
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val day = java.util.Calendar.getInstance().apply { set(y, m, d) }.timeInMillis
            onPicked(day)
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun setFollowUp(day: Long) {
        v.followUpAt = Reminders.tenAm(day)
        v.leadStatus = "Open"
        save()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        Reminders.schedule(this, v)
        syncVisit()
        toast("Reminder set for ${dayFmt.format(java.util.Date(v.followUpAt))}, 10:00 AM")
        render()
    }

    /** Sends the updated status / follow-up date to the office sheet (only for submitted visits). */
    private fun syncVisit() {
        if (v.isQuoted) Remote.queue(this, Remote.visitSummary(this, v))
    }

    private fun logCall() {
        val outcomes = listOf("Interested", "Call later", "Not reachable", "Converted", "Not interested")
        var outcome = ""
        var note = ""
        var next = 0L
        val box = vertical(16) {
            addView(text("Result of the call", 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
            addView(chips(outcomes, "") { outcome = it })
            val noteField = input("Note (optional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            noteField.onChange { note = it }
            addView(noteField, lp().margins(context, t = 12))
            val nextText = text("Next follow-up: not set  (tap to pick)", 14f, Ui.BRAND, bold = true) { setPadding(0, dp(14), 0, dp(4)) }
            nextText.setOnClickListener { pickFollowUpDate { d -> next = Reminders.tenAm(d); nextText.text = "Next follow-up: ${dayFmt.format(java.util.Date(next))}, 10 AM" } }
            addView(nextText)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Log follow-up call").setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (outcome.isBlank()) { toast("Pick the call result"); return@setOnClickListener }
                val staysOpen = outcome != "Converted" && outcome != "Not interested"
                if (staysOpen && next <= 0) { toast("Pick the next follow-up date"); return@setOnClickListener }
                val entry = FollowUp(System.currentTimeMillis(), outcome, note, if (staysOpen) next else 0L)
                v.followUps += entry
                v.leadStatus = when (outcome) { "Converted" -> "Converted"; "Not interested" -> "Lost"; else -> "Open" }
                v.followUpAt = if (staysOpen) next else 0L
                v.followUpNote = ""
                save()
                Reminders.schedule(this, v)
                Remote.queue(this, Remote.followUpEntry(this, v, entry))
                syncVisit()
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
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
            Remote.queueFiles(this@VisitActivity, v)
            Reminders.schedule(this@VisitActivity, v)
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
        silentPerm = false
        if (fine || coarse) captureLocation()
        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun captureLocation(silent: Boolean = false) {
        if (locating) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (providers.isEmpty()) {
            toast("Turn on Location (GPS) to tag the site")
            if (!silent) try { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            return
        }
        locating = true
        if (step == 1) render()
        val wait = if (silent) null else AlertDialog.Builder(this).setMessage("Getting GPS fix…\nStand in the open for best accuracy.").setCancelable(true).show()
        var done = false
        val handler = Handler(Looper.getMainLooper())
        lateinit var listener: LocationListener
        fun deliver(loc: Location?) {
            if (done) return
            done = true
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            locating = false
            if (wait?.isShowing == true) wait?.dismiss()
            if (isFinishing || isDestroyed) return
            if (loc == null) {
                if (!silent) toast("Couldn't get location. Try again outdoors.")
                if (step == 1) render()
                return
            }
            v.lat = loc.latitude; v.lng = loc.longitude
            save()
            if (step == 1) render()
            toast((if (silent) "Site location tagged" else "Location saved") + " (±${loc.accuracy.roundToInt()} m)")
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
        wait?.setOnCancelListener { done = true; locating = false; try { lm.removeUpdates(listener) } catch (_: Exception) {} }
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
