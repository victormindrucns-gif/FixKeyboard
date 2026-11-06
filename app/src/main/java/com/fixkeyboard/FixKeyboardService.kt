package com.fixkeyboard

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
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

    // ---------- colours ----------
    private val bg = Color.parseColor("#ECEFF1")
    private val keyBg = Color.WHITE
    private val modKeyBg = Color.parseColor("#CFD8DC")
    private val accent = Color.parseColor("#2E7D32")
    private val textCol = Color.parseColor("#212121")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(6).toFloat()
    }

    // ---------- UI ----------

    override fun onCreateInputView(): View {
        lang = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(KEY_LANG, "en") ?: "en"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(3), dp(3), dp(3), dp(6))
        }

        // Suggestion / status bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(42))
        }
        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#546E7A"))
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
        repeat(3) {
            val c = chip("")
            c.layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            c.visibility = View.GONE
            c.setOnClickListener { pickSuggestion(c.text.toString()) }
            chips.add(c); bar.addView(c)
        }
        root.addView(bar)

        // "Better wording" panel – you decide whether to use it
        rephrasePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = rounded(Color.parseColor("#E8F5E9"))
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
            background = rounded(Color.parseColor("#E3F2FD"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), 0, dp(3), dp(4)) }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        repliesHeader = TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#1565C0")); setSingleLine()
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
        text = label; textSize = 15f; setTextColor(textCol); gravity = Gravity.CENTER
        setSingleLine(); ellipsize = TextUtils.TruncateAt.END
        background = rounded(keyBg)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(-2, -1).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    }

    private fun panelButton(label: String, bgc: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER
        background = rounded(bgc)
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { onClick() }
    }

    private fun buildKeys() {
        keysBox.removeAllViews()
        fun chars(s: String) = s.map { it.toString() }
        val bottom = listOf("?123", "🌐", ",", "space", ".", "Fix & Send")
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
                layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            }
            for (k in row) line.addView(makeKey(k))
            keysBox.addView(line)
        }
    }

    private fun makeKey(k: String): TextView {
        val weight = when (k) { "space" -> 3.4f; "Fix & Send" -> 2.6f; "⇧", "⌫", "?123", "ABC" -> 1.5f; else -> 1f }
        val isLetter = k.length == 1 && k[0].isLetter()
        return TextView(this).apply {
            text = when {
                k == "space" -> when (lang) { "ro" -> "Română"; "ru" -> "Русский"; else -> "English" }
                isLetter && shift -> k.uppercase()
                else -> k
            }
            textSize = when (k) { "Fix & Send" -> 14f; "space" -> 12f; else -> 20f }
            gravity = Gravity.CENTER
            setTextColor(if (k == "Fix & Send") Color.WHITE else textCol)
            if (k == "Fix & Send") setTypeface(null, Typeface.BOLD)
            background = rounded(
                when {
                    k == "Fix & Send" -> accent
                    k == "⇧" && shift -> Color.parseColor("#90A4AE")
                    k.length > 1 || k == "⇧" || k == "⌫" || k == "↵" -> modKeyBg
                    else -> keyBg
                }
            )
            layoutParams = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            setOnClickListener { onKey(k) }
            if (k == "⌫") setOnLongClickListener { deleteWord(); true }
        }
    }

    // ---------- lifecycle ----------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::root.isInitialized) return
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
        val apiKey = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getString(MainActivity.KEY_API, "")?.trim().orEmpty()
        if (apiKey.isEmpty()) return

        val last = chat.lines.last()
        repliesHeader.text = "💬 ${last.sender}: ${last.text}"
        repliesPanel.visibility = View.VISIBLE

        replyCache[chat.key]?.let { fillReplies(it); return }

        repliesList.removeAllViews()
        repliesList.addView(TextView(this).apply {
            text = "Thinking of replies…"; textSize = 14f; setTextColor(Color.parseColor("#546E7A"))
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
                background = rounded(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) }
                setOnClickListener {
                    // Put it in the box so you can tweak it, then Fix & Send
                    currentInputConnection?.commitText(r, 1)
                    repliesPanel.visibility = View.GONE
                    replySeq++
                    updateAutoShift(); buildKeys(); updateSuggestions()
                    status("Edit it or tap Fix & Send")
                }
            })
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        main.removeCallbacks(autoCheck)
        checkSeq++
    }

    // ---------- typing ----------

    private fun onKey(k: String) {
        val ic = currentInputConnection ?: return
        when (k) {
            "⇧" -> { shift = !shift; buildKeys() }
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

        val wasShift = shift
        updateAutoShift()
        if (wasShift != shift) buildKeys()
        updateSuggestions()
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

    private fun fullText(): String? =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    private fun cursorAtEnd(): Boolean {
        val et = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        return et.selectionEnd == et.text?.length
    }

    private fun replaceAll(newText: String) {
        val ic = currentInputConnection ?: return
        val old = fullText() ?: return
        ic.beginBatchEdit()
        ic.setSelection(0, old.length)
        ic.commitText(newText, 1)
        ic.endBatchEdit()
    }

    private fun status(s: String) {
        statusView.text = s
        if (chips.none { it.visibility == View.VISIBLE }) statusView.visibility = View.VISIBLE
    }

    // ---------- automatic fixing while typing ----------

    private fun autoFix() {
        val text = fullText() ?: return
        if (text.isBlank() || text.length < 3 || !cursorAtEnd()) return
        val seq = ++checkSeq
        val language = GrammarChecker.languageFor(text, lang)
        Thread {
            try {
                val matches = GrammarChecker.check(text, language)
                main.post {
                    if (seq != checkSeq || fullText() != text || !cursorAtEnd()) return@post
                    // Don't touch the word you're still in the middle of typing
                    val endsClean = text.last().let { it.isWhitespace() || it in ".,!?;:" }
                    val wordStart = if (endsClean) text.length
                        else text.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
                    val safe = matches.filter { it.offset + it.length <= wordStart }
                    val (fixed, n) = GrammarChecker.apply(text, safe)
                    if (n > 0 && fixed != text) {
                        replaceAll(fixed)
                        lastOriginal = text
                        undoChip.visibility = View.VISIBLE
                        status("Fixed $n ✓")
                    } else status("✓ Looks good")
                    updateAutoShift(); buildKeys()
                }
            } catch (e: Exception) {
                main.post { if (seq == checkSeq) status("No internet – can't check right now") }
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
        if (rephraseShowing) return
        if (forceSendArmed) { forceSendArmed = false; send(); return }
        main.removeCallbacks(autoCheck)

        val text = fullText() ?: return
        if (text.isBlank()) { send(); return }
        val seq = ++checkSeq
        status("Checking…")
        val apiKey = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getString(MainActivity.KEY_API, "")?.trim().orEmpty()

        val language = GrammarChecker.languageFor(text, lang)
        Thread {
            try {
                val (fixed, n) = GrammarChecker.apply(text, GrammarChecker.check(text, language))
                var better: String? = null
                if (apiKey.isNotEmpty()) {
                    try { better = Rephraser.rephrase(apiKey, fixed) } catch (e: Exception) { }
                }
                main.post {
                    if (seq != checkSeq || fullText() != text) return@post
                    if (fixed != text) { replaceAll(fixed); lastOriginal = text }
                    if (better != null && norm(better) != norm(fixed)) {
                        showRephrase(better)
                    } else {
                        status(if (n > 0) "Fixed $n ✓" else "✓ Looks good")
                        send()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (seq != checkSeq) return@post
                    status("No internet – tap Fix & Send again to send anyway")
                    forceSendArmed = true
                }
            }
        }.start()
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
            status("✓ Ready – tap the app's Send button")
        }
    }
}
