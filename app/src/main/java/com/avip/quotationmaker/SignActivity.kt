package com.avip.quotationmaker

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Full-screen landscape signature pad for the customer. Saves a PNG and returns. */
class SignActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file"
        const val EXTRA_NAME = "name"
    }

    private lateinit var pad: DrawPad
    private lateinit var file: File
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val root = horizontal { setBackgroundColor(Ui.BG); gravity = Gravity.TOP }

        pad = DrawPad(this, compass = false).apply {
            background = rounded(Color.WHITE, dpf(10f), Ui.LINE, dp(1))
            clipToOutline = true
            loadFrom(file)
            onDrawn = { dirty = true }
        }
        root.addView(pad, lp(0, MATCH, 1f).margins(this, l = 8, t = 8, b = 8))

        val tools = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(10), dp(10))
            addView(text("Customer signature", 14f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER })
            if (name.isNotBlank()) addView(text(name, 12f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 2))
            addView(text("Sign with a finger on the white area", 11f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 6, b = 12))
            addView(tool("Undo", R.drawable.ic_back, Ui.BRAND) { pad.undo() })
            addView(tool("Clear", R.drawable.ic_delete, Ui.DANGER) { pad.clear() })
            addView(View(context), lp(MATCH, 0, 1f))
            addView(pillButton("Save", iconRes = R.drawable.ic_check, heightDp = 46) { done() }, lp())
        }
        root.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(tools, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        }, lp(dp(150), MATCH))
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun tool(label: String, res: Int, color: Int, onClick: () -> Unit): View = horizontal {
        background = rounded(Color.WHITE, dpf(12f), Ui.LINE, dp(1))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        foreground = context.ripple()
        addView(icon(res, color, 20))
        addView(text(label, 14f, Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, l = 8))
        setOnClickListener { onClick() }
    }.also { it.layoutParams = lp().margins(this, b = 8) }

    private fun done() {
        if (dirty) {
            if (pad.isEmpty) file.delete() else pad.save(file)
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { done() }
}
