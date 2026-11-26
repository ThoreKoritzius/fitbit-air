package dev.airpulse.fitbit_air_example

/**
 * Chat rules work for any app that posts conversation notifications, such as
 * WhatsApp, Signal, Telegram or Google Messages. No inspection of sender-message history.
 */
internal object RelayConversationPolicy {
    /** Kept from before chat rules covered every app; "Everyone else: silent" meant these. */
    val whatsappPackages = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** Titles that name the app rather than a chat, so they can never become a name rule. */
    private val genericTitles = setOf("whatsapp", "whatsapp business")

    fun isAppTitle(title: String, appLabel: String?): Boolean {
        val normalized = title.trim().lowercase()
        return normalized in genericTitles || (appLabel != null && normalized == appLabel.trim().lowercase())
    }
}

internal data class ConversationIdentity(val id: String, val kind: String)

/** Name rules require an explicit UI opt-in; they are never promoted to stable IDs. */
internal object RelayConversationIdentity {
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /**
     * [messaging] is whether the notification looks like a conversation (MessagingStyle or
     * the message category); only then may its title become a name rule.
     */
    fun options(packageName: String, shortcut: String?, title: String?, messaging: Boolean, appLabel: String? = null): List<ConversationIdentity> {
        val result = mutableListOf<ConversationIdentity>()
        if (!shortcut.isNullOrBlank()) {
            // Preserve IDs selected by the first version of this feature.
            result += ConversationIdentity(hash("$packageName\u0000$shortcut"), "shortcut")
        }
        if (messaging && !title.isNullOrBlank() && title.length <= 512 &&
            !RelayConversationPolicy.isAppTitle(title, appLabel)) {
            result += ConversationIdentity(hash("name\u0000$packageName\u0000$title"), "name")
        }
        return result
    }
}

/** Monotonic deadlines; delayed Android callbacks must never extend retention. */
internal class ConversationCaptureWindow {
    private var listeningUntil = 0L
    private var retainingUntil = 0L
    fun start(now: Long) { listeningUntil = now + LISTEN_MS; retainingUntil = listeningUntil + 300_000 }
    fun finish(now: Long) { listeningUntil = 0; retainingUntil = minOf(retainingUntil, now + 300_000) }
    fun clear() { listeningUntil = 0; retainingUntil = 0 }
    fun listening(now: Long) = now < listeningUntil
    fun selectable(now: Long) = now < retainingUntil
    fun remaining(now: Long) = maxOf(0, listeningUntil - now)

    companion object {
        /** Long enough for someone to be asked to send a message and reply. */
        const val LISTEN_MS = 10 * 60_000L
    }
}
