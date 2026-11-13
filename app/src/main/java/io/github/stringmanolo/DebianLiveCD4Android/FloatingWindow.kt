package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Geometry of the floating window, persisted so a pinned window comes back where you left it. */
class FloatState(
    var x: Int, var y: Int,          // top-left of the window's bounding box (screen px)
    var w: Float, var h: Float,      // size of the terminal panel before rotation (px)
    var rot: Float,                  // degrees, clockwise
    var alpha: Float,                // 0.2 .. 1.0
    var pinned: Boolean
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences("float", Context.MODE_PRIVATE).edit()
            .putInt("x", x).putInt("y", y).putFloat("w", w).putFloat("h", h)
            .putFloat("rot", rot).putFloat("alpha", alpha).putBoolean("pinned", pinned).apply()
    }

    companion object {
        fun load(ctx: Context): FloatState {
            val dm = ctx.resources.displayMetrics
            val sp = ctx.getSharedPreferences("float", Context.MODE_PRIVATE)
            return FloatState(
                sp.getInt("x", (12 * dm.density).toInt()),
                sp.getInt("y", (72 * dm.density).toInt()),
                sp.getFloat("w", minOf(dm.widthPixels * 0.92f, 420f * dm.density)),
                sp.getFloat("h", 320f * dm.density),
                sp.getFloat("rot", 0f),
                sp.getFloat("alpha", 1f),
                sp.getBoolean("pinned", false)
            )
        }
    }
}

/**
 * Draggable, freely resizable, rotatable overlay window hosting the terminal (needs "Display over other apps").
 *  - drag the title bar to move, drag the ◢ corner to resize (no upper limit besides 4x the screen)
 *  - ↻ rotates 90° (long-press: 15°); ◐ shows an opacity slider; PIN locks position/size/rotation
 *  - — minimizes: the window is removed and the service notification restores it
 *  - tap the terminal to give the window keyboard focus; tap outside to release it
 */
class FloatingWindow(private val ctx: Context, private val host: Host) {

    interface Host {
        fun hostMinimize()
        fun hostExit()
        fun hostSettings()
        fun hostFont(delta: Int)
    }

    private val themed = ContextThemeWrapper(ctx, R.style.Theme_DebianLiveCD)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val st = FloatState.load(ctx)

    private var session: TerminalSession? = null
    var isShown = false
        private set
    private var windowFocusable = false

    private var bg = Color.BLACK
    private var fg = Color.WHITE
    private val accent = Color.parseColor("#E3B341")

    private val terminalView = TerminalView(themed, null)
    private val extraKeys = ExtraKeysView(themed)
    private val titleBar = LinearLayout(themed)
    private val titleText = TextView(themed)
    private val opacityRow = LinearLayout(themed)
    private val seek = SeekBar(themed)
    private val bottomBar = LinearLayout(themed)
    private val handle = TextView(themed)
    private val inner = LinearLayout(themed)
    private val panel = FrameLayout(themed)
    private val toolButtons = ArrayList<TextView>()
    private val pinBtn: TextView
    private val root: FrameLayout

    private val lp = WindowManager.LayoutParams(
        1, 1,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0

    init {
        lp.gravity = Gravity.TOP or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

        extraKeys.sessionProvider = { session }
        terminalView.setTerminalViewClient(
            TermViewClient({ extraKeys }, { focusAndShowKeyboard() }, { d -> host.hostFont(d) })
        )

        // ---- title bar (drag handle + buttons)
        titleText.text = "DebianLiveCD"
        titleText.textSize = 12f
        titleText.gravity = Gravity.CENTER_VERTICAL
        titleText.setPadding(dp(8), 0, dp(8), 0)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        tool("⚙") { host.hostSettings() }
        pinBtn = tool("PIN") { st.pinned = !st.pinned; refreshPin(); st.save(ctx) }
        tool("◐") {
            opacityRow.visibility = if (opacityRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        tool("↻", { rotateBy(15f) }) { rotateBy(90f) }
        tool("—") { hide(); host.hostMinimize() }
        tool("✕") { host.hostExit() }
        titleBar.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startRawX = ev.rawX; startRawY = ev.rawY; startX = st.x; startY = st.y }
                MotionEvent.ACTION_MOVE -> if (!st.pinned) {
                    st.x = startX + (ev.rawX - startRawX).toInt()
                    st.y = startY + (ev.rawY - startRawY).toInt()
                    applyGeometry()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> st.save(ctx)
            }
            true
        }

        // ---- opacity row
        opacityRow.orientation = LinearLayout.HORIZONTAL
        opacityRow.gravity = Gravity.CENTER_VERTICAL
        opacityRow.visibility = View.GONE
        opacityRow.setPadding(dp(8), 0, dp(8), 0)
        seek.max = 80
        seek.progress = ((st.alpha - 0.2f) * 100f).toInt().coerceIn(0, 80)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { st.alpha = 0.2f + p / 100f; panel.alpha = st.alpha }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { st.save(ctx) }
        })
        opacityRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // ---- bottom bar with the resize handle
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.END
        handle.text = "◢"
        handle.textSize = 14f
        handle.gravity = Gravity.CENTER
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startRawX = ev.rawX; startRawY = ev.rawY }
                MotionEvent.ACTION_MOVE -> if (!st.pinned) {
                    resizeBy(ev.rawX - startRawX, ev.rawY - startRawY)
                    startRawX = ev.rawX; startRawY = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> st.save(ctx)
            }
            true
        }
        bottomBar.addView(handle, LinearLayout.LayoutParams(dp(44), dp(22)))

        // ---- assemble
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        inner.addView(opacityRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        inner.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        inner.addView(extraKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inner.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
        panel.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root = object : FrameLayout(themed) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) this@FloatingWindow.setWindowFocusable(false)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(st.w.toInt(), st.h.toInt(), Gravity.CENTER))
        root.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) setWindowFocusable(false)
            false
        }

        applyAppearance(AppConfig.load(ctx))
        refreshPin()
    }

    // ------------------------------------------------------------------ public API

    fun show(s: TerminalSession) {
        if (session !== s) {
            session = s
            terminalView.attachSession(s)
        }
        if (!isShown) {
            windowFocusable = false
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            applyGeometry(false)
            wm.addView(root, lp)
            isShown = true
        }
        terminalView.onScreenUpdated()
    }

    fun hide() {
        if (!isShown) return
        try { wm.removeView(root) } catch (_: Exception) {}
        isShown = false
    }

    fun onScreenUpdated() { if (isShown) terminalView.onScreenUpdated() }

    fun invalidateColors() { if (isShown) terminalView.invalidate() }

    fun applyAppearance(cfg: AppConfig) {
        val th = Themes.byId(cfg.themeId)
        bg = th.bgInt
        fg = th.fgInt
        val bar = blend(bg, fg, 0.10f)
        panel.background = GradientDrawable().apply {
            setColor(bg)
            setStroke(dp(1), blend(bg, fg, 0.35f))
        }
        titleBar.setBackgroundColor(bar)
        titleText.setTextColor(fg)
        opacityRow.setBackgroundColor(bar)
        bottomBar.setBackgroundColor(bar)
        handle.setTextColor(fg)
        for (b in toolButtons) b.setTextColor(fg)
        refreshPin()
        extraKeys.applyTheme(bg, fg)
        terminalView.setTextSize((cfg.fontSp * ctx.resources.displayMetrics.scaledDensity).toInt())
        terminalView.setTypeface(
            if (cfg.fontBold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        )
        terminalView.onScreenUpdated()
    }

    // ------------------------------------------------------------------ geometry

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Bounding box of a w x h rectangle rotated by [rot] degrees. */
    private fun bbox(w: Float, h: Float, rot: Float): Pair<Int, Int> {
        val r = Math.toRadians(rot.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        return Pair(ceil(w * c + h * s).toInt(), ceil(w * s + h * c).toInt())
    }

    private fun applyGeometry(update: Boolean = true) {
        val (bw, bh) = bbox(st.w, st.h, st.rot)
        lp.width = bw
        lp.height = bh
        lp.x = st.x
        lp.y = st.y
        val plp = panel.layoutParams as FrameLayout.LayoutParams
        plp.width = st.w.toInt()
        plp.height = st.h.toInt()
        plp.gravity = Gravity.CENTER
        panel.layoutParams = plp
        panel.rotation = st.rot
        panel.alpha = st.alpha
        if (isShown && update) wm.updateViewLayout(root, lp)
    }

    private fun rotateBy(deg: Float) {
        if (st.pinned) return
        val (bw, bh) = bbox(st.w, st.h, st.rot)
        val cx = st.x + bw / 2f
        val cy = st.y + bh / 2f
        st.rot = ((st.rot + deg) % 360f + 360f) % 360f
        val (nbw, nbh) = bbox(st.w, st.h, st.rot)
        st.x = (cx - nbw / 2f).toInt()
        st.y = (cy - nbh / 2f).toInt()
        applyGeometry()
        st.save(ctx)
    }

    /** Resize in the panel's own (rotated) axes, keeping its top-left corner fixed on screen. */
    private fun resizeBy(ddx: Float, ddy: Float) {
        val r = Math.toRadians(st.rot.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val lx = ddx * c + ddy * s
        val ly = -ddx * s + ddy * c
        val (bw, bh) = bbox(st.w, st.h, st.rot)
        val cx = st.x + bw / 2f
        val cy = st.y + bh / 2f
        val tlx = cx + (-st.w / 2f) * c - (-st.h / 2f) * s
        val tly = cy + (-st.w / 2f) * s + (-st.h / 2f) * c
        val dm = ctx.resources.displayMetrics
        val nw = (st.w + lx).coerceIn(dp(200).toFloat(), dm.widthPixels * 4f)
        val nh = (st.h + ly).coerceIn(dp(150).toFloat(), dm.heightPixels * 4f)
        val ncx = tlx + (nw / 2f) * c - (nh / 2f) * s
        val ncy = tly + (nw / 2f) * s + (nh / 2f) * c
        st.w = nw
        st.h = nh
        val (nbw, nbh) = bbox(nw, nh, st.rot)
        st.x = (ncx - nbw / 2f).toInt()
        st.y = (ncy - nbh / 2f).toInt()
        applyGeometry()
    }

    // ------------------------------------------------------------------ focus / keyboard

    private fun setWindowFocusable(on: Boolean) {
        windowFocusable = on
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (isShown) wm.updateViewLayout(root, lp)
        if (!on) {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(root.windowToken, 0)
        }
    }

    private fun focusAndShowKeyboard() {
        if (!windowFocusable) setWindowFocusable(true)
        terminalView.requestFocus()
        terminalView.post {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ------------------------------------------------------------------ small helpers

    private fun refreshPin() {
        pinBtn.setTextColor(if (st.pinned) accent else fg)
    }

    private fun tool(label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
        val t = TextView(themed)
        t.text = label
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.minWidth = dp(34)
        t.setPadding(dp(4), 0, dp(4), 0)
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        titleBar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        toolButtons.add(t)
        return t
    }
}
