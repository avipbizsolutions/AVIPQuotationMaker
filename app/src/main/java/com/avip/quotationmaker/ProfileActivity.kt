package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** One-time sign-in: the sales executive's name and mobile go on every quotation. */
class ProfileActivity : AppCompatActivity() {

    companion object { const val EXTRA_REVOKED = "revoked" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = !Officer.isComplete(this)
        var name = Officer.name(this)
        var mobile = Officer.mobile(this)
        var code = Officer.code(this)
        var busy = false

        val root = vertical { setBackgroundColor(Ui.BG) }
        root.addView(topBar(if (first) "Welcome" else "My profile", "Sales executive details", showBack = !first))
        val body = vertical(18) {
            addView(vertical {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(badge(R.drawable.ic_person, Ui.BRAND, 72))
                addView(text("Your details print on every quotation\nso the customer knows whom to call.", 14f, Ui.MUTED) {
                    gravity = Gravity.CENTER
                }, lp(WRAP, WRAP).margins(context, t = 12))
            }, lp().margins(context, b = 8))
            addView(card {
                field(this, "Full name *", name, "e.g. Rahul Kumar", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person) { name = it }
                field(this, "Mobile number *", mobile, "10-digit mobile", InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13) { mobile = it }
                field(this, "Employee code (optional)", code, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) { code = it }
            })
            addView(pillButton(if (first) "Start" else "Save", iconRes = R.drawable.ic_check) {
                if (name.isBlank()) { toast("Please enter your name"); return@pillButton }
                if (mobile.filter { it.isDigit() }.length < 10) { toast("Please enter a 10-digit mobile number"); return@pillButton }
                val save = {
                    Officer.save(this@ProfileActivity, name, mobile, code)
                    if (first) startActivity(Intent(this@ProfileActivity, MainActivity::class.java))
                    finish()
                }
                // number unchanged: no need to check again
                if (!first && Catalog.mobileKey(mobile) == Catalog.mobileKey(Officer.mobile(this@ProfileActivity))) { save(); return@pillButton }
                if (busy) return@pillButton
                busy = true
                toast("Checking your number…")
                lifecycleScope.launch {
                    val allowed = Catalog.allowedMobiles()
                    busy = false
                    when {
                        allowed == null -> AlertDialog.Builder(this@ProfileActivity)
                            .setTitle("Can't check your number")
                            .setMessage("Connect to the internet and try again. Your number is checked against AVIP's list of sales team members.")
                            .setPositiveButton("OK", null).show()
                        Catalog.mobileKey(mobile) !in allowed -> AlertDialog.Builder(this@ProfileActivity)
                            .setTitle("Number not registered")
                            .setMessage("${mobile.trim()} is not on AVIP's sales team list. Contact your Sales Head to register you.")
                            .setPositiveButton("OK", null).show()
                        else -> save()
                    }
                }
            }, lp().margins(context, t = 20))
            if (!first) addView(pillButton("Log out", bg = Color.WHITE, fg = Ui.DANGER, iconRes = R.drawable.ic_logout) {
                AlertDialog.Builder(this@ProfileActivity)
                    .setTitle("Log out?")
                    .setMessage("Your site visits stay saved on this phone. To log in again, your number must be on AVIP's sales team list.")
                    .setPositiveButton("Log out") { _, _ ->
                        Officer.signOut(this@ProfileActivity)
                        startActivity(Intent(this@ProfileActivity, ProfileActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                    .setNegativeButton("Cancel", null).show()
            }.apply { background = rounded(Color.WHITE, dpf(25f), Ui.DANGER, dp(1)) }, lp().margins(context, t = 12))
        }
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setContentView(root)
        if (intent.getBooleanExtra(EXTRA_REVOKED, false)) AlertDialog.Builder(this)
            .setTitle("Access removed")
            .setMessage("Your number is no longer on AVIP's sales team list. Ask your Sales Head if this is a mistake.")
            .setPositiveButton("OK", null).show()
        window.statusBarColor = Ui.BRAND_DARK
        root.setBackgroundColor(Ui.BG)
        body.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
