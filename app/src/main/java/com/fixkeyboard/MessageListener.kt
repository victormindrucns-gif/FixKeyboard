package com.fixkeyboard

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads incoming message notifications (WhatsApp, Messages, Telegram, Messenger, Viber…) so the keyboard
 * can suggest replies. Messages are kept in memory only, never saved to storage.
 */
class MessageListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        // Only chat-like notifications
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!isMessage) return

        val extras = n.extras
        val chat = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return

        // Newer apps put a list of recent messages in the notification; take up to the last 5 for context
        val lines = ArrayList<IncomingMessages.Line>()
        @Suppress("DEPRECATION")
        val msgs: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null) {
            for (p in msgs.takeLast(5)) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString() ?: continue
                val sender = b.getCharSequence("sender")?.toString() ?: chat
                lines.add(IncomingMessages.Line(sender, text))
            }
        }
        if (lines.isEmpty()) {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            lines.add(IncomingMessages.Line(chat, text))
        }
        IncomingMessages.put(sbn.packageName, IncomingMessages.Chat(chat, lines, System.currentTimeMillis()))
    }
}

/** Latest incoming chat per app, shared between the listener and the keyboard (same app process). */
object IncomingMessages {
    data class Line(val sender: String, val text: String)
    data class Chat(val title: String, val lines: List<Line>, val time: Long) {
        val key get() = "$title|${lines.lastOrNull()?.text}|$time"
    }

    private val latest = HashMap<String, Chat>()

    @Synchronized fun put(pkg: String, chat: Chat) { latest[pkg] = chat }

    /** The latest chat for this app, if it arrived in the last 15 minutes. */
    @Synchronized fun recent(pkg: String?): Chat? {
        val c = latest[pkg ?: return null] ?: return null
        return if (System.currentTimeMillis() - c.time < 15 * 60 * 1000) c else null
    }
}
