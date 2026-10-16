package com.avip.quotationmaker

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Launch screen: loads latest prices, installs app updates, then opens the app. */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: View
    private var leaving = false
    private var pendingRelease: ReleaseInfo? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        try {
                            startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
                        } catch (_: Exception) {
                        }
                    }
                    goNext()
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> { toast("Update could not be installed"); goNext() }
            }
        }
    }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val r = pendingRelease
            if (r != null && Updater.canInstall(this)) lifecycleScope.launch { downloadAndInstall(r) } else goNext()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical {
            gravity = Gravity.CENTER
            background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val brandBlock = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(android.widget.FrameLayout(context).apply {
                background = rounded(Color.WHITE, dpf(22f))
                elevation = dpf(6f)
                setPadding(dp(22), dp(18), dp(22), dp(18))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.avip_solar_logo)
                    adjustViewBounds = true
                }, android.widget.FrameLayout.LayoutParams(dp(230), WRAP))
            }, lp(WRAP, WRAP))
            addView(text("AVIP Quotation Maker", 26f, Color.WHITE, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 18))
            addView(text("Site visit to quotation, right at the customer's home", 14f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            addView(text("by AVIP Business Solutions LLP", 12f, Ui.SUN, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 4))
        }
        root.addView(brandBlock)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            max = 100
        }
        root.addView(progress, lp(dp(200), WRAP).margins(this, t = 36))
        status = text("Loading latest prices…", 13f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }
        root.addView(status, lp(WRAP, WRAP).margins(this, t = 10))
        retry = pillButton("Retry", Ui.SUN, Ui.TEXT) { start() }.apply { visibility = View.GONE }
        root.addView(retry, lp(dp(160), WRAP).margins(this, t = 16))
        root.addView(text("v" + Updater.currentVersionName(this), 11f, 0x99FFFFFF.toInt()), lp(WRAP, WRAP).margins(this, t = 28))
        setContentView(root)

        brandBlock.alpha = 0f
        brandBlock.animate().alpha(1f).setDuration(450).start()

        ContextCompat.registerReceiver(
            this, installStatusReceiver, IntentFilter(Updater.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Tell the user once after an update.
        val versionCode = Updater.currentVersionCode(this)
        val lastSeen = prefs.getLong("last_version_code", 0L)
        if (lastSeen in 1 until versionCode) {
            Toast.makeText(this, "Updated to version ${Updater.currentVersionName(this)}", Toast.LENGTH_LONG).show()
        }
        prefs.edit().putLong("last_version_code", versionCode).apply()

        start()
    }

    private fun start() {
        retry.visibility = View.GONE
        status.text = "Loading latest prices…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        val hadCache = Catalog.isLoaded || Catalog.loadCached(this)

        lifecycleScope.launch {
            val minimum = async { delay(1200) }
            val update = async { withTimeoutOrNull(5000) { withContext(Dispatchers.IO) { Updater.fetchLatest() } } }
            val fresh = withTimeoutOrNull(10000) { Catalog.refresh(this@SplashActivity) } ?: false
            Remote.flushAsync(this@SplashActivity)

            val latest = update.await()
            val current = Updater.currentVersionCode(this@SplashActivity)
            if (latest != null && latest.versionCode > current) {
                pendingRelease = latest
                if (Updater.canInstall(this@SplashActivity)) { downloadAndInstall(latest); return@launch }
                if (prefs.getLong("declined_update", 0L) != latest.versionCode) {
                    askInstallPermission(latest); return@launch
                }
            }
            minimum.await()
            if (!fresh && !hadCache) {
                progress.visibility = View.GONE
                status.text = "Couldn't load prices. Check your internet connection."
                retry.visibility = View.VISIBLE
                return@launch
            }
            if (!fresh) toast("Offline: showing prices saved earlier")
            goNext()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installStatusReceiver) } catch (_: Exception) {}
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo) {
        status.text = "Updating to version ${release.versionName}…"
        progress.isIndeterminate = false
        progress.progress = 0
        val apk = withContext(Dispatchers.IO) {
            Updater.download(this@SplashActivity, release) { pct -> runOnUiThread { progress.progress = pct } }
        }
        if (apk == null) { toast("Update download failed"); goNext(); return }
        status.text = "Installing update…"
        try {
            withContext(Dispatchers.IO) { Updater.install(this@SplashActivity, apk) }
        } catch (_: Exception) {
            toast("Update could not be installed"); goNext()
        }
    }

    private fun askInstallPermission(release: ReleaseInfo) {
        AlertDialog.Builder(this)
            .setTitle("New version available")
            .setMessage("Version ${release.versionName} is ready. Allow AVIP Quotation Maker to install its own updates? This is needed only once.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                unknownSourcesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Later") { _, _ ->
                prefs.edit().putLong("declined_update", release.versionCode).apply()
                if (Catalog.isLoaded) goNext() else start()
            }
            .show()
    }

    private fun goNext() {
        if (leaving || isFinishing) return
        if (!Catalog.isLoaded) { start(); return }
        leaving = true
        val next = if (Officer.isComplete(this)) MainActivity::class.java else ProfileActivity::class.java
        startActivity(Intent(this, next))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
