package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import com.termux.terminal.TerminalSession

/** Two-row extra keys bar: ESC TAB CTRL ALT HOME END PGUP PGDN and arrows. Tap modifier = one-shot, long-press = lock. */
class ExtraKeysView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private enum class Mod { OFF, ONCE, LOCK }

    var sessionProvider: (() -> TerminalSession?)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val colKey = Color.parseColor("#1B222A")
    private val colPressed = Color.parseColor("#2A3440")
    private val colOnce = Color.parseColor("#2F6F4F")
    private val colLock = Color.parseColor("#8A5A1C")
    private val colText = Color.parseColor("#C9D1D9")

    private var ctrl = Mod.OFF
    private var alt = Mod.OFF
    private val ctrlButton: Button
    private val altButton: Button

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#12171C"))

        ctrlButton = modButton("CTRL", { ctrl }, { ctrl = it })
        altButton = modButton("ALT", { alt }, { alt = it })

        row(
            key("ESC") { "\u001b" }, key("/") { "/" }, key("-") { "-" },
            key("HOME") { cursorSeq('H') }, key("↑", true) { cursorSeq('A') },
            key("END") { cursorSeq('F') }, key("PGUP", true) { tildeSeq(5) }
        )
        row(
            key("TAB") { "\t" }, ctrlButton, altButton,
            key("←", true) { cursorSeq('D') }, key("↓", true) { cursorSeq('B') },
            key("→", true) { cursorSeq('C') }, key("PGDN", true) { tildeSeq(6) }
        )
    }

    fun consumeCtrl(): Boolean {
        val active = ctrl != Mod.OFF
        if (ctrl == Mod.ONCE) { ctrl = Mod.OFF; refreshMods() }
        return active
    }

    fun consumeAlt(): Boolean {
        val active = alt != Mod.OFF
        if (alt == Mod.ONCE) { alt = Mod.OFF; refreshMods() }
        return active
    }

    // ---- sequences ----
    private fun modParam(): Int {
        var m = 1
        if (alt != Mod.OFF) m += 2
        if (ctrl != Mod.OFF) m += 4
        return m
    }

    private fun cursorSeq(c: Char): String {
        val m = modParam()
        if (m > 1) return "\u001b[1;$m$c"
        val app = sessionProvider?.invoke()?.emulator?.isCursorKeysApplicationMode ?: false
        return if (app) "\u001bO$c" else "\u001b[$c"
    }

    private fun tildeSeq(n: Int): String {
        val m = modParam()
        return if (m > 1) "\u001b[$n;$m~" else "\u001b[$n~"
    }

    private fun fire(seq: () -> String) {
        val s = seq()
        sessionProvider?.invoke()?.write(s)
        if (ctrl == Mod.ONCE) ctrl = Mod.OFF
        if (alt == Mod.ONCE) alt = Mod.OFF
        refreshMods()
    }

    // ---- view building ----
    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun bg(c: Int): StateListDrawable {
        fun shape(col: Int) = GradientDrawable().apply { setColor(col); cornerRadius = dp(4).toFloat() }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(colPressed))
            addState(intArrayOf(), shape(c))
        }
    }

    private fun button(label: String): Button = Button(context).apply {
        text = label
        setAllCaps(false)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colText)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        background = bg(colKey)
        stateListAnimator = null
    }

    private fun key(label: String, repeat: Boolean = false, seq: () -> String): Button {
        val b = button(label)
        if (!repeat) {
            b.setOnClickListener { fire(seq) }
            return b
        }
        val rep = object : Runnable {
            override fun run() { fire(seq); handler.postDelayed(this, 60) }
        }
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; fire(seq); handler.postDelayed(rep, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(rep) }
            }
            true
        }
        return b
    }

    private fun modButton(label: String, get: () -> Mod, set: (Mod) -> Unit): Button {
        val b = button(label)
        b.setOnClickListener { set(if (get() == Mod.OFF) Mod.ONCE else Mod.OFF); refreshMods() }
        b.setOnLongClickListener { set(Mod.LOCK); refreshMods(); true }
        return b
    }

    private fun refreshMods() {
        fun color(m: Mod) = when (m) { Mod.OFF -> colKey; Mod.ONCE -> colOnce; Mod.LOCK -> colLock }
        ctrlButton.background = bg(color(ctrl))
        altButton.background = bg(color(alt))
    }

    private fun row(vararg buttons: Button) {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (b in buttons) {
            r.addView(b, LayoutParams(0, dp(40), 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
        }
        addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
