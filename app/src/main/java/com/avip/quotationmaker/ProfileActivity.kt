package com.avip.quotationmaker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** One-time sign-in: the sales executive's name and mobile go on every quotation. */
class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = !Officer.isComplete(this)
        var name = Officer.name(this)
        var mobile = Officer.mobile(this)
        var code = Officer.code(this)

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
                Officer.save(this@ProfileActivity, name, mobile, code)
                if (first) startActivity(Intent(this@ProfileActivity, MainActivity::class.java))
                finish()
            }, lp().margins(context, t = 20))
        }
        root.addView(ScrollView(this).apply { addView(body) }, lp(MATCH, 0, 1f))
        setContentView(root)
        window.statusBarColor = Ui.BRAND_DARK
        root.setBackgroundColor(Ui.BG)
        body.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
