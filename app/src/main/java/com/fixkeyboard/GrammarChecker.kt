package com.fixkeyboard

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free grammar + spelling checker using the public LanguageTool API (no account needed).
 * Limits on the free service: about 20 checks per minute, which is plenty for one person typing.
 */
object GrammarChecker {

    data class Match(val offset: Int, val length: Int, val replacements: List<String>, val message: String)

    /** Picks the language to check: Cyrillic text is Russian, Romanian letters or the Romanian keyboard mean Romanian. */
    fun languageFor(text: String, keyboardLang: String): String = when {
        text.any { it in 'Ѐ'..'ӿ' } -> "ru-RU"
        text.any { it in "ăâîșțĂÂÎȘȚşţŞŢ" } -> "ro-RO"
        keyboardLang == "ro" -> "ro-RO"
        keyboardLang == "ru" -> "ru-RU"
        else -> "en-US"
    }

    @Throws(IOException::class)
    fun check(text: String, language: String): List<Match> {
        val conn = URL("https://api.languagetool.org/v2/check").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "text=" + URLEncoder.encode(text, "UTF-8") + "&language=" + language
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.getJSONArray("matches")
            val out = ArrayList<Match>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val reps = m.getJSONArray("replacements")
                val list = (0 until minOf(reps.length(), 3)).map { reps.getJSONObject(it).getString("value") }
                out.add(Match(m.getInt("offset"), m.getInt("length"), list, m.optString("message")))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Applies the top suggestion of every match that has one. Returns the fixed text and how many fixes were made. */
    fun apply(text: String, matches: List<Match>): Pair<String, Int> {
        val sb = StringBuilder(text)
        var count = 0
        var lastStart = Int.MAX_VALUE
        for (m in matches.sortedByDescending { it.offset }) {
            if (m.replacements.isEmpty()) continue
            val end = m.offset + m.length
            if (end > lastStart || end > sb.length) continue // skip overlapping matches
            sb.replace(m.offset, end, m.replacements[0])
            lastStart = m.offset
            count++
        }
        return Pair(sb.toString(), count)
    }
}
