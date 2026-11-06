package com.fixkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: turn the keyboard on, paste the API key, and try it out. */
class MainActivity : Activity() {

    companion object {
        const val PREFS = "fix"
        const val KEY_API = "api_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(s: String, size: Float = 15f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2)
        }

        box.addView(label("Fix Keyboard", 24f))
        box.addView(label("Fixes your grammar as you type, suggests words, and offers a better-worded version before you send. You always choose: Use this, Edit, or Keep mine."))

        box.addView(Button(this).apply {
            text = "1. Turn on Fix Keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        box.addView(Button(this).apply {
            text = "2. Switch to Fix Keyboard"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })

        box.addView(label("3. Claude API key (for real fixes)", 17f))
        box.addView(label("Paste your Claude API key from console.anthropic.com. With it, Claude fixes your spelling, grammar and punctuation, suggests better wording, and writes smart replies. Without it, only a basic free checker is used."))
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val keyField = EditText(this).apply {
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(KEY_API, ""))
        }
        box.addView(keyField)
        box.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                prefs.edit().putString(KEY_API, keyField.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })

        box.addView(label("4. Smart replies (optional)", 17f))
        box.addView(label("When someone messages you, the keyboard suggests 3 replies in your style. It needs to see your message notifications to know what they said. Messages stay in memory only and are sent to Claude just to write the suggestions. Needs the API key above."))
        notifStatus = label("")
        box.addView(notifStatus)
        box.addView(Button(this).apply {
            text = "Allow notification access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        box.addView(label("Try it here:", 17f))
        box.addView(EditText(this).apply {
            hint = "type something like: i going to the store later do you want anything"
            minLines = 2
        })

        setContentView(ScrollView(this).apply { addView(box) })
    }

    private lateinit var notifStatus: TextView

    override fun onResume() {
        super.onResume()
        if (!::notifStatus.isInitialized) return
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        notifStatus.text = if (enabled) "✓ Smart replies are on" else "Smart replies are off"
    }
}
