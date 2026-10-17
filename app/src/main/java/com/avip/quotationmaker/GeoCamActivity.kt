package com.avip.quotationmaker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Site photo camera with the AVIP GPSCam features: live GPS + address, the stamp
 * burned into every photo (mini map with pin, address, lat/long, date/time, facing
 * direction, north arrow, customer and visit no.), GPS in the photo's EXIF, grid &
 * level, pinch / .5x–5x zoom, tap to focus, flash and HD/SD. Photos are saved into
 * the visit (and a copy in the phone gallery under Pictures/AVIP Surya Sales).
 * Stays open for several shots; "Done" returns them all.
 */
class GeoCamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIR = "dir"
        const val EXTRA_LINES = "lines"
        const val EXTRA_FILES = "files"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        private const val PREFS = "geocam"
        private const val GALLERY_FOLDER = "AVIP Surya Sales"
    }

    private data class Snapshot(val loc: Location?, val place: PlaceInfo, val time: Date, val heading: Float?)

    private lateinit var dir: File
    private var lines: List<String> = emptyList()
    private val saved = ArrayList<String>()

    private lateinit var previewView: PreviewView
    private lateinit var grid: GridOverlayView
    private lateinit var info: TextView
    private lateinit var shutter: View
    private lateinit var thumb: ImageView
    private lateinit var doneBtn: TextView
    private lateinit var gridBtn: TextView
    private lateinit var hdBtn: TextView
    private lateinit var flashBtn: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var focusRing: View
    private val zoomChips = mutableListOf<Pair<TextView, Float>>()

    private lateinit var heading: HeadingProvider
    private lateinit var tilt: TiltProvider
    private lateinit var lm: LocationManager
    private lateinit var executor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var hd = true
    private var flash = false
    private var busy = false
    private var lastZoom: Float? = null
    private val hideZoom = Runnable { zoomLabel.visibility = View.GONE }

    private var current: Location? = null
    private var geocoded: Location? = null
    private var place = PlaceInfo.EMPTY
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera() else { toast("Camera permission is needed for site photos"); finishWithResult() }
        if (hasLocation()) startLocation() else info.text = "Location permission is off — photos will have no GPS stamp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return }).apply { mkdirs() }
        lines = intent.getStringArrayListExtra(EXTRA_LINES) ?: arrayListOf()
        hd = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("hd", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        executor = Executors.newSingleThreadExecutor()
        buildUi()
        heading = HeadingProvider(this) { }
        tilt = TiltProvider(this) { r -> grid.setRoll(r) }
        applyGrid(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("grid", true))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult()
        })

        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (has(Manifest.permission.CAMERA)) {
            startCamera()
            if (!hasLocation()) perms.launch(need.drop(1).toTypedArray())
        } else perms.launch(need.toTypedArray())
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        grid = GridOverlayView(this)
        root.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        focusRing = View(this).apply {
            background = rounded(Color.TRANSPARENT, dpf(30f), Color.WHITE, dp(2))
            visibility = View.GONE
        }
        root.addView(focusRing, FrameLayout.LayoutParams(dp(60), dp(60)))
        zoomLabel = text("1.0x", 22f, Color.WHITE, bold = true) {
            setShadowLayer(6f, 0f, 0f, Color.BLACK); visibility = View.GONE
        }
        root.addView(zoomLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // top bar
        root.addView(horizontal {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0x66000000)
            addView(text("✕", 22f, Color.WHITE, bold = true) {
                setPadding(dp(10), dp(4), dp(14), dp(4)); setOnClickListener { finishWithResult() }
            })
            addView(text("Site photo · GPS stamped", 15f, Color.WHITE, bold = true), lp(0, WRAP, 1f))
            gridBtn = chip("Grid") { applyGrid(grid.visibility != View.VISIBLE) }
            flashBtn = chip("Flash") { flash = !flash; capture?.flashMode = flashMode(); paintChips() }
            hdBtn = chip("HD") { toggleHd() }
            addView(gridBtn); addView(flashBtn); addView(hdBtn)
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // bottom panel
        val bottom = vertical {
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(18))
            info = text("Waiting for GPS…  step outside for a quick fix", 12.5f, Color.WHITE) { maxLines = 4 }
            addView(info)
            addView(horizontal {
                gravity = Gravity.CENTER
                listOf(0.5f, 1f, 2f, 5f).forEach { r ->
                    val c = text(if (r < 1) ".5x" else "${r.toInt()}x", 13f, Color.WHITE, bold = true) {
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener { setZoom(r) }
                    }
                    zoomChips += c to r
                    addView(c, lp(WRAP, WRAP).margins(context, l = 4, r = 4))
                }
            }, lp().margins(context, t = 10))
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(0x33FFFFFF, dpf(10f), Color.WHITE, dp(2))
                    clipToOutline = true
                }
                addView(thumb, lp(dp(56), dp(56)))
                addView(View(context), lp(0, 1, 1f))
                shutter = FrameLayout(context).apply {
                    background = circle(Color.WHITE)
                    addView(View(context).apply { background = rounded(Color.WHITE, dpf(30f), Color.BLACK, dp(3)) },
                        FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
                    setOnClickListener { takePhoto() }
                }
                addView(shutter, lp(dp(74), dp(74)))
                addView(View(context), lp(0, 1, 1f))
                doneBtn = text("Done", 15f, Ui.TEXT, bold = true) {
                    gravity = Gravity.CENTER
                    background = rounded(Ui.SUN, dpf(22f))
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener { finishWithResult() }
                }
                addView(doneBtn, lp(WRAP, WRAP))
            }, lp().margins(context, t = 12))
        }
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
        setupGestures()
        paintChips()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = text(label, 12.5f, Color.WHITE, bold = true) {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, 0, 0) }
    }

    private fun paintChips() {
        fun paint(tv: TextView, on: Boolean) {
            tv.background = if (on) rounded(Ui.SUN, dpf(14f)) else rounded(0x33FFFFFF, dpf(14f))
            tv.setTextColor(if (on) Ui.TEXT else Color.WHITE)
        }
        paint(gridBtn, grid.visibility == View.VISIBLE)
        paint(flashBtn, flash)
        hdBtn.text = if (hd) "HD" else "SD"
        paint(hdBtn, hd)
        doneBtn.text = if (saved.isEmpty()) "Done" else "Done (${saved.size})"
    }

    private fun applyGrid(on: Boolean) {
        grid.visibility = if (on) View.VISIBLE else View.GONE
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("grid", on).apply()
        if (::tilt.isInitialized) { if (on) tilt.start() else tilt.stop() }
        if (::gridBtn.isInitialized) paintChips()
    }

    private fun toggleHd() {
        if (busy) return
        hd = !hd
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hd", hd).apply()
        toast(if (hd) "HD: full-size photos" else "SD: smaller photos, faster to share")
        paintChips()
        bind()
    }

    private fun flashMode() = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() { super.onStart(); if (hasLocation()) startLocation() }

    override fun onResume() {
        super.onResume()
        heading.start()
        previewView.post {
            tilt.screenRotation = when (previewView.display?.rotation) {
                Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
            }
        }
        if (grid.visibility == View.VISIBLE) tilt.start()
    }

    override fun onPause() { super.onPause(); heading.stop(); tilt.stop() }

    override fun onStop() { super.onStop(); try { lm.removeUpdates(locationListener) } catch (_: Exception) {} }

    override fun onDestroy() { super.onDestroy(); executor.shutdown() }

    private fun finishWithResult() {
        if (busy) { toast("Saving the photo…"); return }
        val data = Intent().putStringArrayListExtra(EXTRA_FILES, saved)
        current?.let { data.putExtra(EXTRA_LAT, it.latitude).putExtra(EXTRA_LNG, it.longitude) }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ provider = future.get(); bind() }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val keepZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setFlashMode(flashMode())
        if (!hd) builder.setResolutionSelector(
            ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            ).build()
        )
        val cap = builder.build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
            capture = cap
            lastZoom = null
            camera?.cameraInfo?.zoomState?.observe(this) { onZoom(it) }
            keepZoom?.let { setZoom(it) }
        } catch (e: Exception) {
            toast("Camera could not start: ${e.message ?: ""}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                setZoom(z * d.scaleFactor); return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { focusAt(e.x, e.y); return true }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress && e.pointerCount == 1) tap.onTouchEvent(e)
            true
        }
    }

    private fun setZoom(r: Float) {
        val cam = camera ?: return
        val s = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(r.coerceIn(s.minZoomRatio, s.maxZoomRatio))
    }

    private fun onZoom(s: ZoomState) {
        zoomChips.forEach { (c, r) ->
            c.visibility = if (r >= s.minZoomRatio - 0.01f && r <= s.maxZoomRatio + 0.01f) View.VISIBLE else View.GONE
            val on = abs(s.zoomRatio - r) < 0.05f
            c.background = if (on) rounded(Ui.SUN, dpf(14f)) else rounded(0x33FFFFFF, dpf(14f))
            c.setTextColor(if (on) Ui.TEXT else Color.WHITE)
        }
        val prev = lastZoom
        lastZoom = s.zoomRatio
        if (prev != null && abs(prev - s.zoomRatio) > 0.01f) {
            zoomLabel.text = String.format(Locale.US, "%.1fx", s.zoomRatio)
            zoomLabel.visibility = View.VISIBLE
            zoomLabel.removeCallbacks(hideZoom)
            zoomLabel.postDelayed(hideZoom, 1200)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        focusRing.translationX = x - dp(30)
        focusRing.translationY = y - dp(30)
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().cancel()
        focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
    }

    // ---------------------------------------------------------------- photo

    private fun takePhoto() {
        val cap = capture ?: return
        if (busy) return
        setBusy(true)
        val snap = Snapshot(current, place, Date(), heading.heading)
        if (snap.loc == null) toast("No GPS fix yet — photo will have no location")
        cap.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = try { image.toBitmap() } finally { image.close() }
                runOnUiThread { process(bmp, rotation, snap) }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { setBusy(false); toast("Capture failed: ${e.message ?: ""}") }
            }
        })
    }

    private fun process(raw: Bitmap, rotation: Int, snap: Snapshot) {
        val maxDim = if (hd) 2560 else 1280 // big enough for the PDF, small enough for WhatsApp
        lifecycleScope.launch {
            try {
                val small = withContext(Dispatchers.IO) {
                    val photo = prepare(raw, rotation, maxDim)
                    val map = snap.loc?.let { MapTiles.render(it.latitude, it.longitude, zoom = 17, sizePx = 512) }
                    Stamp.draw(photo, map, snap.loc, snap.place, snap.time, snap.heading,
                        extra = lines, brand = Catalog.settings["brand_name"].ifBlank { "AVIP Solar" })
                    val name = "site_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(snap.time) + ".jpg"
                    val f = File(dir, name)
                    f.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, if (hd) 88 else 82, it) }
                    writeExif(f, snap)
                    copyToGallery(f, snap)
                    saved += name
                    val t = Bitmap.createScaledBitmap(photo, 160, (160f * photo.height / photo.width).toInt(), true)
                    photo.recycle()
                    t
                }
                thumb.setImageBitmap(small)
                paintChips()
                toast("Photo saved")
            } catch (e: Exception) {
                toast("Could not save: ${e.message ?: ""}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun prepare(src: Bitmap, rotation: Int, maxDim: Int): Bitmap {
        val scale = min(1f, maxDim.toFloat() / max(src.width, src.height))
        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(scale, scale) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        if (out.isMutable) return out
        val copy = out.copy(Bitmap.Config.ARGB_8888, true)
        out.recycle()
        return copy
    }

    private fun writeExif(f: File, snap: Snapshot) {
        try {
            val exif = ExifInterface(f.absolutePath)
            snap.loc?.let { exif.setGpsInfo(it) }
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(snap.time))
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, lines.joinToString(" | "))
            exif.saveAttributes()
        } catch (_: Exception) {
        }
    }

    /** A copy in the phone gallery (Android 10+), like AVIP GPSCam. */
    private fun copyToGallery(f: File, snap: Snapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, snap.time.time)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + GALLERY_FOLDER)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        } catch (_: Exception) {
        }
    }

    private fun setBusy(v: Boolean) {
        busy = v
        shutter.isEnabled = !v
        shutter.alpha = if (v) 0.5f else 1f
    }

    // ---------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { info.text = "Location (GPS) is off — turn it on for the GPS stamp"; return }
        for (p in providers) {
            try {
                lm.getLastKnownLocation(p)?.let { onLocation(it) }
                lm.requestLocationUpdates(p, 2000L, 1f, locationListener, mainLooper)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun onLocation(loc: Location) {
        val cur = current
        if (cur != null && loc.time - cur.time <= 10_000 && loc.accuracy > cur.accuracy) return
        current = loc
        heading.declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
        showInfo()
        val g = geocoded
        if (g == null || g.distanceTo(loc) > 30f) {
            geocoded = loc
            lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) { Geocoding.lookup(this@GeoCamActivity, loc) }
                if (p != null) { place = p; showInfo() } else geocoded = null
            }
        }
    }

    private fun showInfo() {
        val loc = current ?: return
        info.text = buildString {
            if (place.address.isNotBlank()) appendLine(place.address)
            append(Stamp.formatLatLon(loc))
            append("   ±${loc.accuracy.roundToInt()} m")
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
