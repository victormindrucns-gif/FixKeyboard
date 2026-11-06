package com.fixkeyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The keyboard.
 *  - Fixes grammar/spelling automatically after each sentence (or when you pause typing).
 *  - Shows word suggestions while you type.
 *  - "Fix & Send" checks the whole message, offers a better-worded version you can accept or reject, then sends.
 */
class FixKeyboardService : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var undoChip: TextView
    private val chips = ArrayList<TextView>()
    private lateinit var rephrasePanel: LinearLayout
    private lateinit var rephraseText: TextView
    private lateinit var keysBox: LinearLayout
    private lateinit var repliesPanel: LinearLayout
    private lateinit var repliesHeader: TextView
    private lateinit var repliesList: LinearLayout
    private var replySeq = 0
    private val replyCache = HashMap<String, List<String>>() // so the same message isn't sent twice

    private val KEY_LANG = "keyboard_lang"
    private var lang = "en" // "en", "ro" or "ru"
    private var shift = true
    private var symbols = false
    private var checkSeq = 0
    private var lastOriginal: String? = null
    private var forceSendArmed = false
    private var rephraseShowing = false

    private val autoCheck = Runnable { autoFix() }

    // Keys we update in place (so Shift doesn't rebuild the whole keyboard)
    private val letterKeys = ArrayList<Pair<TextView, String>>()
    private var shiftKey: TextView? = null
    private var fixKey: TextView? = null
    private var busy = false

    // Hold backspace to keep deleting
    private val repeatDelete = object : Runnable {
        override fun run() { onKey("⌫"); main.postDelayed(this, 55) }
    }

    // Suggestions + auto-capitals refresh once per burst of typing, not on every key
    private val uiRefresh = Runnable {
        val wasShift = shift
        updateAutoShift()
        if (wasShift != shift) refreshShift()
        updateSuggestions()
    }

    // ---------- colours (follow the phone's light / dark mode) ----------
    private var dark = false
    private var bg = 0
    private var keyBg = 0
    private var keyPressed = 0
    private var modKeyBg = 0
    private var modPressed = 0
    private var shadow = 0
    private var accent = 0
    private var accentPressed = 0
    private var textCol = 0
    private var subText = 0
    private var panelGreen = 0
    private var panelBlue = 0
    private var divider = 0

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyTheme() {
        dark = isNight()
        fun c(s: String) = Color.parseColor(s)
        if (dark) {
            bg = c("#141518"); keyBg = c("#3A3B40"); keyPressed = c("#55565C")
            modKeyBg = c("#26272B"); modPressed = c("#3A3B40"); shadow = c("#0A0A0C")
            accent = c("#2FA36B"); accentPressed = c("#248556")
            textCol = c("#F1F1F3"); subText = c("#9A9CA3")
            panelGreen = c("#1D3328"); panelBlue = c("#1C2A3A"); divider = c("#33353A")
        } else {
            bg = c("#E9EBEF"); keyBg = Color.WHITE; keyPressed = c("#D5D8DE")
            modKeyBg = c("#C9CDD4"); modPressed = c("#B3B8C0"); shadow = c("#A9AEB6")
            accent = c("#1E8E5A"); accentPressed = c("#17714A")
            textCol = c("#1C1D21"); subText = c("#5F6368")
            panelGreen = c("#E3F4EA"); panelBlue = c("#E4EEFB"); divider = c("#CDD1D7")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    /** A key face with a soft bottom shadow and a pressed colour. */
    private fun keyDrawable(normal: Int, pressed: Int): Drawable {
        fun face(col: Int) = LayerDrawable(arrayOf(rounded(shadow, 9), rounded(col, 9))).apply {
            setLayerInset(1, 0, 0, 0, dp(1))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(pressed))
            addState(intArrayOf(), face(normal))
        }
    }

    private val keyFont: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldFont: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ---------- UI ----------

    override fun onCreateInputView(): View {
        lang = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(KEY_LANG, "en") ?: "en"
        applyTheme()
        chips.clear()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }

        // Suggestion / status bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(42))
        }
        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(subText); typeface = keyFont
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            gravity = Gravity.CENTER_VERTICAL
            text = "Grammar check on"
        }
        undoChip = chip("↶ Undo fix").apply {
            visibility = View.GONE
            setOnClickListener { undoFix() }
        }
        bar.addView(undoChip)
        bar.addView(statusView)
        repeat(3) { i ->
            val c = TextView(this).apply {
                textSize = 16f; setTextColor(textCol); gravity = Gravity.CENTER; typeface = keyFont
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(modKeyBg, 8))
                }
                // thin divider on the left of the 2nd and 3rd suggestion
                if (i > 0) foreground = LayerDrawable(arrayOf(GradientDrawable().apply { setColor(divider) })).apply {
                    setLayerInset(0, 0, dp(12), 0, dp(12)); setLayerWidth(0, dp(1))
                }
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                visibility = View.GONE
            }
            c.setOnClickListener { pickSuggestion(c.text.toString()) }
            chips.add(c); bar.addView(c)
        }
        root.addView(bar)

        // "Better wording" panel – you decide whether to use it
        rephrasePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(panelGreen)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), 0, dp(3), dp(4)) }
        }
        rephrasePanel.addView(TextView(this).apply {
            text = "Suggested wording:"; textSize = 12f; setTextColor(accent); setTypeface(null, Typeface.BOLD)
        })
        rephraseText = TextView(this).apply {
            textSize = 15f; setTextColor(textCol); setPadding(0, dp(4), 0, dp(8)); maxLines = 6
        }
        rephrasePanel.addView(rephraseText)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(panelButton("✓ Use this", accent, Color.WHITE) { useSuggestion(andSend = true) })
        actions.addView(panelButton("✎ Edit", modKeyBg, textCol) { useSuggestion(andSend = false) })
        actions.addView(panelButton("Keep mine", modKeyBg, textCol) { hideRephrase(); send() })
        rephrasePanel.addView(actions)
        root.addView(rephrasePanel)

        // Smart replies panel – shows reply ideas for the latest incoming message in this app
        repliesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(panelBlue)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), 0, dp(3), dp(4)) }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        repliesHeader = TextView(this).apply {
            textSize = 12f; setTextColor(subText); setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        header.addView(repliesHeader)
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(textCol); setPadding(dp(12), dp(2), dp(4), dp(2))
            setOnClickListener { repliesPanel.visibility = View.GONE; replySeq++ }
        })
        repliesPanel.addView(header)
        repliesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repliesPanel.addView(repliesList)
        root.addView(repliesPanel)

        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keysBox)
        buildKeys()
        return root
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(textCol); gravity = Gravity.CENTER
        setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        background = keyDrawable(modKeyBg, modPressed)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(-2, -1).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    }

    private fun panelButton(label: String, bgc: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER
        background = keyDrawable(bgc, if (bgc == accent) accentPressed else modPressed)
        typeface = boldFont
        layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
    }

    private fun buildKeys() {
        keysBox.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        fun chars(s: String) = s.map { it.toString() }
        val bottom = listOf("?123", "🌐", ",", "space", ".", "Fix & Send")  // 🌐 is drawn as EN / RO / RU
        val rows: List<List<String>> = if (!symbols) when (lang) {
            "ro" -> listOf(
                chars("qwertyuiopă"),
                chars("asdfghjklșț"),
                listOf("⇧") + chars("zxcvbnmâî") + listOf("⌫"),
                bottom
            )
            "ru" -> listOf(
                chars("йцукенгшщзх"),
                chars("фывапролджэ"),
                listOf("⇧") + chars("ячсмитьбюъ") + listOf("⌫"),
                bottom
            )
            else -> listOf(
                chars("qwertyuiop"),
                chars("asdfghjkl"),
                listOf("⇧") + chars("zxcvbnm") + listOf("⌫"),
                bottom
            )
        } else listOf(
            "1234567890".map { it.toString() },
            listOf("@", "#", "$", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?", "ё", "⌫"),
            listOf("ABC", "↵", "space", ".", "Fix & Send")
        )
        for (row in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, dp(54))
            }
            for (k in row) line.addView(makeKey(k))
            keysBox.addView(line)
        }
    }

    private fun makeKey(k: String): TextView {
        val weight = when (k) { "space" -> 3.6f; "Fix & Send" -> 2.2f; "⇧", "⌫", "?123", "ABC" -> 1.5f; "🌐" -> 1.1f; else -> 1f }
        val isLetter = k.length == 1 && k[0].isLetter()
        val isAction = k == "Fix & Send"
        val isMod = !isAction && (k.length > 1 || k == "⇧" || k == "⌫" || k == "↵")
        return TextView(this).apply {
            text = when {
                k == "space" -> when (lang) { "ro" -> "Română"; "ru" -> "Русский"; else -> "English" }
                k == "🌐" -> lang.uppercase()
                isAction -> "Fix ➤"
                k == "⇧" && shift -> "⬆"
                isLetter && shift -> k.uppercase()
                else -> k
            }
            textSize = when {
                isAction -> 16f
                k == "space" -> 13f
                k == "🌐" || k == "?123" || k == "ABC" -> 14f
                isLetter -> 23f
                else -> 21f
            }
            typeface = if (isAction || k == "🌐" || k == "?123" || k == "ABC") boldFont else keyFont
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(when { isAction -> Color.WHITE; k == "space" -> subText; else -> textCol })
            background = when {
                isAction -> keyDrawable(accent, accentPressed)
                k == "⇧" && shift -> keyDrawable(keyPressed, keyPressed)
                isMod -> keyDrawable(modKeyBg, modPressed)
                else -> keyDrawable(keyBg, keyPressed)
            }
            layoutParams = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }
            if (isLetter) letterKeys.add(Pair(this, k))
            if (k == "⇧") shiftKey = this
            if (isAction) { fixKey = this; if (busy) text = "Checking…" }
            // Typing keys fire the moment your finger touches them; action keys fire when you lift
            val onRelease = isAction || k == "🌐" || k == "?123" || k == "ABC"
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (!onRelease) onKey(k)
                        if (k == "⌫") main.postDelayed(repeatDelete, 400)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        main.removeCallbacks(repeatDelete)
                        if (onRelease) onKey(k)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        main.removeCallbacks(repeatDelete)
                    }
                }
                true
            }
        }
    }

    /** Switch letters between upper and lower case without rebuilding the keyboard. */
    private fun refreshShift() {
        for ((tv, k) in letterKeys) tv.text = if (shift) k.uppercase() else k
        shiftKey?.apply {
            text = if (shift) "⬆" else "⇧"
            background = if (shift) keyDrawable(keyPressed, keyPressed) else keyDrawable(modKeyBg, modPressed)
        }
    }

    // ---------- lifecycle ----------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::root.isInitialized) return
        if (isNight() != dark) { setInputView(onCreateInputView()) }
        hideRephrase()
        lastOriginal = null
        undoChip.visibility = View.GONE
        forceSendArmed = false
        symbols = false
        updateAutoShift()
        buildKeys()
        updateSuggestions()
        status("Grammar check on")
        showSmartReplies(info?.packageName)
    }

    // ---------- smart replies ----------

    private fun showSmartReplies(pkg: String?) {
        replySeq++
        repliesPanel.visibility = View.GONE
        val chat = IncomingMessages.recent(pkg) ?: return
        if (!fullText().isNullOrBlank()) return // you've already started typing
        val apiKey = apiKey()
        if (apiKey.isEmpty()) return

        val last = chat.lines.last()
        repliesHeader.text = "💬 ${last.sender}: ${last.text}"
        repliesPanel.visibility = View.VISIBLE

        replyCache[chat.key]?.let { fillReplies(it); return }

        repliesList.removeAllViews()
        repliesList.addView(TextView(this).apply {
            text = "Thinking of replies…"; textSize = 14f; setTextColor(subText)
            setPadding(0, dp(6), 0, dp(6))
        })
        val seq = replySeq
        Thread {
            val result = try { ReplySuggester.suggest(apiKey, chat) } catch (e: Exception) { null }
            main.post {
                if (seq != replySeq) return@post
                if (result.isNullOrEmpty()) { repliesPanel.visibility = View.GONE; return@post }
                replyCache[chat.key] = result
                fillReplies(result)
            }
        }.start()
    }

    private fun fillReplies(replies: List<String>) {
        repliesList.removeAllViews()
        for (r in replies) {
            repliesList.addView(TextView(this).apply {
                text = r; textSize = 15f; setTextColor(textCol)
                background = keyDrawable(keyBg, keyPressed)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) }
                setOnClickListener {
                    // Put it in the box so you can tweak it, then Fix & Send
                    currentInputConnection?.commitText(r, 1)
                    repliesPanel.visibility = View.GONE
                    replySeq++
                    updateAutoShift(); refreshShift(); updateSuggestions()
                    status("Edit it or tap Fix & Send")
                }
            })
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        main.removeCallbacks(autoCheck)
        main.removeCallbacks(repeatDelete)
        checkSeq++
        if (busy) setBusy(false)
    }

    // ---------- typing ----------

    private fun onKey(k: String) {
        val ic = currentInputConnection ?: return
        when (k) {
            "⇧" -> { shift = !shift; refreshShift() }
            "⌫" -> {
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
                afterTyping(null)
            }
            "space" -> { ic.commitText(" ", 1); afterTyping(' ') }
            "?123" -> { symbols = true; buildKeys() }
            "ABC" -> { symbols = false; buildKeys() }
            "🌐" -> {
                lang = when (lang) { "en" -> "ro"; "ro" -> "ru"; else -> "en" }
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putString(KEY_LANG, lang).apply()
                buildKeys(); updateSuggestions()
            }
            "↵" -> { ic.commitText("\n", 1); afterTyping('\n') }
            "Fix & Send" -> fixAndSend()
            else -> {
                val s = if (shift && k[0].isLetter()) k.uppercase() else k
                ic.commitText(s, 1)
                afterTyping(s.last())
            }
        }
    }

    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val start = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
        ic.deleteSurroundingText(before.length - start, 0)
        afterTyping(null)
    }

    private fun afterTyping(c: Char?) {
        forceSendArmed = false
        if (rephraseShowing) hideRephrase()
        if (repliesPanel.visibility == View.VISIBLE) { repliesPanel.visibility = View.GONE; replySeq++ }
        undoChip.visibility = View.GONE
        lastOriginal = null

        // Fix right after a sentence ends, otherwise when you pause for a moment
        main.removeCallbacks(autoCheck)
        val delay = if (c == '.' || c == '!' || c == '?' || c == '\n') 300L else 1500L
        main.postDelayed(autoCheck, delay)

        // Capital after . ! ? straight away; everything else refreshes once you pause for a moment
        if (c == ' ' || c == null) {
            main.removeCallbacks(uiRefresh); uiRefresh.run()
        } else {
            if (shift) { shift = false; refreshShift() }
            main.removeCallbacks(uiRefresh); main.postDelayed(uiRefresh, 60)
        }
    }

    /** Capitalise automatically at the start of a message or sentence. */
    private fun updateAutoShift() {
        val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
        val t = before.trimEnd()
        shift = t.isEmpty() || ((t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) && before.endsWith(" "))
    }

    // ---------- word suggestions ----------

    private fun currentWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(30, 0)?.toString() ?: return ""
        return before.takeLastWhile { it.isLetter() || it == '\'' }
    }

    private fun updateSuggestions() {
        val word = currentWord()
        val list = Words.complete(word, lang)
        for ((i, c) in chips.withIndex()) {
            if (i < list.size) { c.text = list[i]; c.visibility = View.VISIBLE } else c.visibility = View.GONE
        }
        statusView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun pickSuggestion(w: String) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(currentWord().length, 0)
        ic.commitText("$w ", 1)
        afterTyping(' ')
    }

    // ---------- text helpers ----------

    private fun fullText(): String? {
        val ic = currentInputConnection ?: return null
        ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.let { return it.toString() }
        // Some apps don't share the whole box; read around the cursor instead
        val before = ic.getTextBeforeCursor(10000, 0) ?: return null
        val after = ic.getTextAfterCursor(10000, 0) ?: ""
        return before.toString() + after.toString()
    }

    private fun cursorAtEnd(): Boolean {
        val ic = currentInputConnection ?: return false
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et?.text != null) return et.selectionEnd == et.text.length
        return ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    private fun replaceAll(newText: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et?.text != null) {
            ic.setSelection(0, et.text.length)
            ic.commitText(newText, 1)
        } else {
            val b = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
            val a = ic.getTextAfterCursor(10000, 0)?.length ?: 0
            ic.deleteSurroundingText(b, a)
            ic.commitText(newText, 1)
        }
        ic.endBatchEdit()
    }

    /** Shows "Checking…" on the Fix key while it works, so you know it heard you. */
    private fun setBusy(b: Boolean) {
        busy = b
        fixKey?.text = if (b) "Checking…" else "Fix ➤"
        fixKey?.alpha = if (b) 0.75f else 1f
    }

    private fun status(s: String) {
        statusView.text = s
        if (chips.none { it.visibility == View.VISIBLE }) statusView.visibility = View.VISIBLE
    }

    // ---------- automatic fixing while typing ----------

    private fun autoFix() {
        val text = fullText() ?: return
        if (text.isBlank() || text.trim().length < 3 || !cursorAtEnd()) return
        // Don't touch the word you're still in the middle of typing
        val endsClean = text.last().let { it.isWhitespace() || it in ".,!?;:" }
        val key = apiKey()
        if (key.isNotEmpty() && !endsClean) return // Claude checks once the word is finished
        val seq = ++checkSeq
        val language = GrammarChecker.languageFor(text, lang)
        Thread {
            try {
                var fixed: String
                var n = 0
                if (key.isNotEmpty()) {
                    fixed = keepEdges(text, Rephraser.fix(key, text))
                } else {
                    val matches = GrammarChecker.check(text, language)
                    val wordStart = if (endsClean) text.length
                        else text.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
                    val r = GrammarChecker.apply(text, matches.filter { it.offset + it.length <= wordStart })
                    fixed = r.first; n = r.second
                }
                main.post {
                    if (seq != checkSeq || fullText() != text || !cursorAtEnd()) return@post
                    if (fixed != text) {
                        replaceAll(fixed)
                        lastOriginal = text
                        undoChip.visibility = View.VISIBLE
                        status(if (n > 0) "Fixed $n ✓" else "Fixed ✓")
                    } else status("✓ Looks good")
                    updateAutoShift(); refreshShift()
                }
            } catch (e: Exception) {
                main.post { if (seq == checkSeq) status(errorText(e)) }
            }
        }.start()
    }

    private fun undoFix() {
        val orig = lastOriginal ?: return
        main.removeCallbacks(autoCheck)
        checkSeq++
        replaceAll(orig)
        lastOriginal = null
        undoChip.visibility = View.GONE
        status("Undone")
    }

    // ---------- Fix & Send ----------

    private fun fixAndSend() {
        if (busy) return
        // Panel already open: tapping Fix ➤ again sends what's in the box
        if (rephraseShowing) { hideRephrase(); send(); return }
        if (forceSendArmed) { forceSendArmed = false; send(); return }
        main.removeCallbacks(autoCheck)

        val text = fullText()
        if (text == null) { status("Can't read this text box – tap the app's Send"); return }
        if (text.isBlank()) { send(); return }
        val seq = ++checkSeq
        setBusy(true)
        status("Checking…")
        val apiKey = apiKey()

        val language = GrammarChecker.languageFor(text, lang)
        Thread {
            try {
                val fixed: String
                var n = 0
                var better: String? = null
                if (apiKey.isNotEmpty()) {
                    // One Claude call: the corrected message + a smoother version to choose from
                    val r = Rephraser.fixAndImprove(apiKey, text)
                    fixed = keepEdges(text, r.first)
                    better = r.second
                } else {
                    val r = GrammarChecker.apply(text, GrammarChecker.check(text, language))
                    fixed = r.first; n = r.second
                }
                main.post {
                    setBusy(false)
                    if (seq != checkSeq) return@post
                    if (fullText() != text) { status("You kept typing – tap Fix ➤ again"); return@post }
                    if (fixed != text) {
                        replaceAll(fixed); lastOriginal = text
                        undoChip.visibility = View.VISIBLE
                    }
                    if (better != null && norm(better) != norm(fixed)) {
                        showRephrase(better)
                    } else {
                        status(if (fixed != text) (if (n > 0) "Fixed $n ✓" else "Fixed ✓") else "✓ Looks good")
                        send()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    setBusy(false)
                    if (seq != checkSeq) return@post
                    status(errorText(e) + " – tap Fix ➤ again to send anyway")
                    forceSendArmed = true
                }
            }
        }.start()
    }

    private fun apiKey(): String = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        .getString(MainActivity.KEY_API, "")?.trim().orEmpty()

    /** Keep the spaces/new lines the person had at the start and end (the AI tends to trim them). */
    private fun keepEdges(orig: String, fixed: String): String {
        val lead = orig.substring(0, orig.length - orig.trimStart().length)
        val trail = orig.substring(orig.trimEnd().length)
        return lead + fixed.trim() + trail
    }

    private fun errorText(e: Exception): String {
        val m = e.message ?: ""
        return when {
            m.contains("401") || m.contains("403") -> "Claude key not working – check it in the app"
            m.contains("400") || m.contains("402") -> "Claude key problem – check your credit"
            m.contains("429") || m.contains("529") -> "Claude is busy – try again in a moment"
            else -> "No internet – can't check right now"
        }
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun showRephrase(s: String) {
        rephraseText.text = s
        rephrasePanel.visibility = View.VISIBLE
        rephraseShowing = true
        status("Pick one ↓")
    }

    private fun hideRephrase() {
        rephrasePanel.visibility = View.GONE
        rephraseShowing = false
    }

    private fun useSuggestion(andSend: Boolean) {
        replaceAll(rephraseText.text.toString())
        hideRephrase()
        if (andSend) send() else status("Edit it, then tap Fix & Send")
    }

    /** Presses the app's send/go/done action if the text box has one; otherwise tells you it's ready. */
    private fun send() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val sendable = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO ||
            action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEARCH
        if (sendable && !noEnterAction) {
            ic.performEditorAction(action)
            status("Sent ✓")
        } else {
            // e.g. WhatsApp: the keyboard isn't allowed to press the app's own Send button
            status(statusView.text.toString().substringBefore(" –") + " – now tap the app's Send ➤")
        }
    }
}
