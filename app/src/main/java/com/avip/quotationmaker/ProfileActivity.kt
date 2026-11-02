package com.avip.quotationmaker

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Login with mobile number + 4-digit PIN from the AVIP Team Directory: one login for
 * AVIP Solar Sales, Installation and Expense. With the office (temporary) PIN the
 * person first sets a PIN of their own. When logged in, shows the profile and Log out.
 * Name and mobile print on every quotation and come from the directory.
 */
class ProfileActivity : AppCompatActivity() {

    /** Message shown when the office logged this phone out (access removed, PIN reset…). */
    companion object { const val EXTRA_REVOKED = "revoked" }

    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BRAND_DARK
        when {
            Officer.token(this).isNotBlank() && Officer.mustSetPin(this) -> showSetPin()
            Officer.isComplete(this) -> showProfile()
            else -> showLogin()
        }
        intent.getStringExtra(EXTRA_REVOKED)?.let { msg ->
            AlertDialog.Builder(this).setTitle("Please log in again")
                .setMessage(msg.ifBlank { "Your login has changed." } + "\n\nYour site visits are safe on this phone.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun frame(title: String, subtitle: String, showBack: Boolean = false): LinearLayout {
        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar(title, subtitle, showBack = showBack))
        body = vertical(18)
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setContentView(root)
        body.addView(vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(badge(R.drawable.ic_person, Ui.BRAND, 72))
        }, lp().margins(this, b = 8))
        return body
    }

    private fun showLogin() {
        val b = frame("Login", "AVIP Solar Sales")
        b.addView(text("Use the same mobile number and PIN as AVIP Solar Installation and Expense.", 14f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, b = 8))
        var mobile = ""
        var pin = ""
        b.addView(card {
            field(this, "Mobile number", "", "10-digit mobile", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13) { mobile = it }
            field(this, "4-digit PIN", "", "••••", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, R.drawable.ic_check, 4) { pin = it }
        })
        b.addView(pillButton("Login", iconRes = R.drawable.ic_check) {
            val m = mobile.filter { it.isDigit() }.takeLast(10)
            if (m.length != 10) { toast("Enter your 10-digit mobile number"); return@pillButton }
            if (pin.trim().length != 4) { toast("Enter your 4-digit PIN"); return@pillButton }
            work("Logging in…", {
                Remote.call(this, "login", JSONObject().put("mobile", m).put("pin", pin.trim()).put("token", "")
                    .put("deviceId", deviceId()).put("appVersion", Updater.currentVersionName(this)))
            }) { o ->
                val must = o.optBoolean("mustSetPin")
                Officer.save(this, o.getJSONObject("user"), o.optString("token"), must)
                if (must) showSetPin() else openMain()
            }
        }, lp().margins(this, t = 20))
        b.addView(text("New to the team or forgot your PIN? Ask the office for a temporary PIN.", 13f, Ui.MUTED) {
            gravity = Gravity.CENTER
        }, lp().margins(this, t = 16))
    }

    private fun showSetPin() {
        val b = frame("Set your own PIN", "Hello, " + Officer.name(this))
        b.addView(text("First login: replace the office PIN with a PIN only you know. The same PIN works in AVIP Solar Installation and Expense.", 14f, Ui.TEXT), lp().margins(this, b = 8))
        var p1 = ""
        var p2 = ""
        val numPass = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        b.addView(card {
            field(this, "New 4-digit PIN", "", "••••", numPass, R.drawable.ic_check, 4) { p1 = it }
            field(this, "Type it again", "", "••••", numPass, R.drawable.ic_check, 4) { p2 = it }
        })
        b.addView(text("Easy PINs like 1234, 0000 or 1111 are not allowed. The office cannot see your PIN.", 13f, Ui.MUTED), lp().margins(this, t = 10))
        b.addView(pillButton("Save PIN", iconRes = R.drawable.ic_check) {
            if (p1.trim().length != 4) { toast("PIN must be 4 digits"); return@pillButton }
            if (p1 != p2) { toast("The two PINs do not match"); return@pillButton }
            work("Saving PIN…", { Remote.call(this, "setPin", JSONObject().put("newPin", p1.trim())) }) { o ->
                Officer.save(this, o.getJSONObject("user"), o.optString("token"), false)
                toast("PIN saved")
                openMain()
            }
        }, lp().margins(this, t = 20))
        b.addView(text("Log out", 14f, Ui.MUTED, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { Officer.signOut(this@ProfileActivity); showLogin() }
        }, lp())
    }

    private fun showProfile() {
        val b = frame("My profile", "From the AVIP Team Directory", showBack = true)
        b.addView(card {
            addView(kv("Name", Officer.name(context), bold = true))
            addView(kv("Mobile", Officer.mobile(context)))
            if (Officer.designation(context).isNotBlank()) addView(kv("Designation", Officer.designation(context)))
            addView(kv("Employee ID", Officer.code(context)))
            addView(kv("Role in this app", Officer.role(context)))
        })
        b.addView(text("Your name and mobile print on every quotation. To change them, ask the office to update the Team Directory.", 13f, Ui.MUTED), lp().margins(this, t = 10))
        b.addView(pillButton("Log out", bg = Color.WHITE, fg = Ui.DANGER, iconRes = R.drawable.ic_logout) {
            AlertDialog.Builder(this)
                .setTitle("Log out?")
                .setMessage("Your site visits stay saved on this phone. You will need your mobile number and PIN to log in again.")
                .setPositiveButton("Log out") { _, _ ->
                    Officer.signOut(this)
                    startActivity(Intent(this, ProfileActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        }.apply { background = rounded(Color.WHITE, dpf(25f), Ui.DANGER, dp(1)) }, lp().margins(this, t = 20))
    }

    /** Runs [call] off the main thread with a small wait box; shows errors in plain words. */
    private fun work(message: String, call: () -> JSONObject, done: (JSONObject) -> Unit) {
        val box = horizontal {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            addView(ProgressBar(context), lp(dp(36), dp(36)))
            addView(text(message, 15f, Ui.TEXT), lp(WRAP, WRAP).margins(context, l = 16))
        }
        val dialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
        dialog.show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { call() } }
            if (!isFinishing && !isDestroyed) try { dialog.dismiss() } catch (_: Exception) {}
            r.onSuccess(done).onFailure { e ->
                if (e is RemoteException && e.sessionExpired) { Officer.signOut(this@ProfileActivity); showLogin() }
                AlertDialog.Builder(this@ProfileActivity).setTitle("Could not log in")
                    .setMessage(e.message ?: "Something went wrong.").setPositiveButton("OK", null).show()
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String = try {
        android.os.Build.MODEL + " · " + (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "").take(8)
    } catch (_: Exception) { android.os.Build.MODEL }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
