package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Tiny programmatic-UI helpers so the settings tabs stay readable. */
class Ui(private val act: Activity) {
    val text = Color.parseColor("#C9D1D9")
    val dim = Color.parseColor("#8B949E")

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    fun page(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    fun header(p: LinearLayout, s: String, top: Int = 18): TextView {
        val t = TextView(act)
        t.text = s; t.textSize = 16f; t.setTextColor(Color.parseColor("#58A6FF"))
        p.addView(t, lp(top)); return t
    }

    fun note(p: LinearLayout, s: String, top: Int = 4): TextView {
        val t = TextView(act)
        t.text = s; t.textSize = 12f; t.setTextColor(dim)
        p.addView(t, lp(top)); return t
    }

    fun label(p: LinearLayout, s: String, top: Int = 8): TextView {
        val t = TextView(act)
        t.text = s; t.textSize = 13f; t.setTextColor(text)
        p.addView(t, lp(top)); return t
    }

    fun check(p: LinearLayout, s: String, on: Boolean, change: (Boolean) -> Unit): CheckBox {
        val c = CheckBox(act)
        c.text = s; c.isChecked = on; c.setTextColor(text)
        c.setOnCheckedChangeListener { b, v -> if (b.isPressed) change(v) }
        p.addView(c, lp(2)); return c
    }

    fun button(p: LinearLayout, s: String, top: Int = 8, click: () -> Unit): Button {
        val b = Button(act)
        b.text = s; b.isAllCaps = false
        b.setOnClickListener { click() }
        p.addView(b, lp(top)); return b
    }

    fun spinner(p: LinearLayout, items: List<String>, selected: Int, pick: (Int) -> Unit): Spinner {
        val sp = Spinner(act)
        sp.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        var first = true
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                pick(pos)
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        p.addView(sp, lp(2)); return sp
    }

    fun radios(p: LinearLayout, items: List<String>, selected: Int, pick: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(act)
        items.forEachIndexed { i, s ->
            val r = RadioButton(act)
            r.id = View.generateViewId(); r.text = s; r.setTextColor(text)
            g.addView(r)
            if (i == selected) g.check(r.id)
        }
        g.setOnCheckedChangeListener { grp, id ->
            val idx = (0 until grp.childCount).indexOfFirst { grp.getChildAt(it).id == id }
            if (idx >= 0 && grp.findViewById<View>(id)?.isPressed == true) pick(idx)
        }
        p.addView(g, lp(2)); return g
    }

    fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

    fun confirm(title: String, msg: String, yes: String = "OK", onYes: () -> Unit) {
        AlertDialog.Builder(act).setTitle(title).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }.setNegativeButton("Cancel", null).show()
    }

    fun choose(title: String, items: List<String>, pick: (Int) -> Unit) {
        AlertDialog.Builder(act).setTitle(title).setItems(items.toTypedArray()) { _, i -> pick(i) }
            .setNegativeButton("Cancel", null).show()
    }

    fun prompt(title: String, hint: String, initial: String = "", password: Boolean = false, onOk: (String) -> Unit) {
        val e = EditText(act)
        e.hint = hint; e.setText(initial)
        if (password) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    /** Two fields: new passphrase + repeat. */
    fun newPassphrase(title: String, onOk: (CharArray) -> Unit) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val a = EditText(act).apply { hint = "Passphrase (12+ characters recommended)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val b = EditText(act).apply { hint = "Repeat"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(a); box.addView(b)
        AlertDialog.Builder(act).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val x = a.text.toString()
                if (x.length < 6) toast("Passphrase too short")
                else if (x != b.text.toString()) toast("The passphrases do not match")
                else onOk(x.toCharArray())
            }.setNegativeButton("Cancel", null).show()
    }

    /** Unlocks the key if it is passphrase-protected; [then] runs once the key is usable. */
    fun ensureKeyUnlocked(then: () -> Unit) {
        if (!KeyManager.hasKey() || KeyManager.isUnlocked()) { then(); return }
        prompt("Unlock encryption key", "Passphrase", password = true) { p ->
            if (KeyManager.unlock(p.toCharArray())) then() else toast("Wrong passphrase")
        }
    }

    /** Runs [work] on a thread behind a non-cancelable progress dialog. */
    fun job(title: String, work: (Progress) -> Unit, done: (Throwable?) -> Unit = {}) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val msg = TextView(act).apply { text = "…"; textSize = 13f }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box.addView(msg); box.addView(bar)
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(box).setCancelable(false).create()
        dlg.show()
        Thread {
            var err: Throwable? = null
            try {
                work { pct, m -> act.runOnUiThread { bar.progress = pct; msg.text = m } }
            } catch (t: Throwable) {
                err = t
            }
            act.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (err != null) toast("Failed: ${err.message ?: err.javaClass.simpleName}")
                done(err)
            }
        }.start()
    }
}
