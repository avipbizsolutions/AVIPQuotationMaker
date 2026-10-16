package com.avip.quotationmaker

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Full-screen landscape drawing of the site (roof outline, obstacles, panel rows)
 * with N / E / S / W marks. Saves to the visit folder and returns.
 */
class SketchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE = "file"
    }

    private lateinit var pad: DrawPad
    private lateinit var file: File
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        val root = horizontal { setBackgroundColor(Ui.BG); gravity = Gravity.TOP }

        pad = DrawPad(this, compass = true).apply {
            background = rounded(Color.WHITE, dpf(10f), Ui.LINE, dp(1))
            clipToOutline = true
            loadFrom(file)
            onDrawn = { dirty = true }
        }
        root.addView(pad, lp(0, MATCH, 1f).margins(this, l = 8, t = 8, b = 8))

        // tool column
        val tools = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(10), dp(10))
            addView(text("Site sketch", 14f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER })
            addView(text("Roof, tank, stairs, shade, panel rows", 11f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 2, b = 12))
            addView(tool("Undo", R.drawable.ic_back, Ui.BRAND) { pad.undo() })
            addView(tool("Clear", R.drawable.ic_delete, Ui.DANGER) { pad.clear() })
            addView(View(context), lp(MATCH, 0, 1f))
            addView(pillButton("Save", iconRes = R.drawable.ic_check, heightDp = 46) { done() }, lp())
        }
        root.addView(tools, lp(dp(150), MATCH))
        setContentView(root)
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
