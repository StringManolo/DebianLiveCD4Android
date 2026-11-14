package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** PIN entry: mode "unlock" (checks the stored PIN) or "set" (choose a new PIN; "duress" sets the duress PIN). */
class PinActivity : Activity() {

    private lateinit var msg: TextView
    private lateinit var field: EditText
    private lateinit var confirm: EditText
    private var mode = MODE_UNLOCK
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Cfg.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0E11"))
            setPadding(48, 48, 48, 48)
        }
        msg = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#C9D1D9")); gravity = Gravity.CENTER
            text = when (mode) {
                MODE_SET -> "Choose a PIN (4 to 12 digits)"
                MODE_DURESS -> "Choose a duress PIN. Entering it wipes the data (if wipe-on-duress is enabled)."
                else -> "Enter PIN"
            }
        }
        field = pinField("PIN")
        confirm = pinField("Repeat PIN")
        val ok = Button(this).apply { text = "OK"; setOnClickListener { submit() } }
        root.addView(msg)
        root.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 })
        if (mode != MODE_UNLOCK) root.addView(confirm, LinearLayout.LayoutParams(-1, -2))
        root.addView(ok, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        setContentView(root)
    }

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        textSize = 22f
    }

    private fun submit() {
        val pin = field.text.toString()
        if (mode == MODE_UNLOCK) {
            val wait = AppLock.waitSeconds()
            if (wait > 0) { msg.text = "Too many attempts. Wait $wait s."; return }
            when (AppLock.attempt(pin)) {
                AppLock.Result.OK -> { setResult(RESULT_OK); finish() }
                AppLock.Result.WRONG -> { msg.text = "Wrong PIN"; field.setText("") }
                AppLock.Result.DURESS, AppLock.Result.WIPE -> {
                    // look like a normal failure while the wipe runs
                    startService(Intent(this, TerminalService::class.java).setAction(TerminalService.ACTION_WIPE).putExtra("reason", "PIN policy"))
                    msg.text = "Wrong PIN"; field.setText("")
                }
            }
            return
        }
        if (pin.length !in 4..12) { msg.text = "The PIN must have 4 to 12 digits"; return }
        if (pin != confirm.text.toString()) { msg.text = "The PINs do not match"; return }
        if (mode == MODE_DURESS) {
            if (AppLock.verify(pin)) { msg.text = "The duress PIN must differ from the real PIN"; return }
            AppLock.setDuress(pin)
        } else {
            AppLock.setPin(pin)
        }
        setResult(RESULT_OK)
        finish()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_SET = "set"
        const val MODE_DURESS = "duress"
    }
}
