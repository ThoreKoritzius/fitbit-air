package dev.airpulse.fitbit_air_example

/** Match only explicitly selected rule IDs. No inspection of sender-message history. */
internal object RelayConversationPolicy {
    val whatsappPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    val otherApps = mapOf(
        "com.google.android.apps.dynamite" to setOf("chat", "google chat"),
        "org.telegram.messenger" to setOf("telegram"),
        "org.telegram.messenger.web" to setOf("telegram"),
        "com.google.android.apps.messaging" to setOf("messages", "google messages"),
    )
    val packages = whatsappPackages + otherApps.keys

    fun isAppTitle(packageName: String, title: String): Boolean {
        val generic = if (packageName in whatsappPackages) setOf("whatsapp", "whatsapp business")
            else otherApps[packageName].orEmpty()
        return title.trim().lowercase() in generic
    }

    fun accepts(packageName: String, restricted: Boolean, allowed: Set<String>, id: String?): Boolean =
        packageName !in packages || !restricted || (id != null && id in allowed)
}

internal data class ConversationIdentity(val id: String, val kind: String)

/** Name rules require an explicit UI opt-in; they are never promoted to stable IDs. */
internal object RelayConversationIdentity {
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun options(packageName: String, shortcut: String?, title: String?, messaging: Boolean): List<ConversationIdentity> {
        if (packageName !in RelayConversationPolicy.packages) return emptyList()
        val result = mutableListOf<ConversationIdentity>()
        if (!shortcut.isNullOrBlank()) {
            // Preserve IDs selected by the first version of this feature.
            result += ConversationIdentity(hash("$packageName\u0000$shortcut"), "shortcut")
        }
        if (messaging && !title.isNullOrBlank() && title.length <= 512 &&
            !RelayConversationPolicy.isAppTitle(packageName, title)) {
            result += ConversationIdentity(hash("name\u0000$packageName\u0000$title"), "name")
        }
        return result
    }
}

/** Monotonic deadlines; delayed Android callbacks must never extend retention. */
internal class ConversationCaptureWindow {
    private var listeningUntil = 0L
    private var retainingUntil = 0L
    fun start(now: Long) { listeningUntil = now + 60_000; retainingUntil = listeningUntil + 300_000 }
    fun finish(now: Long) { listeningUntil = 0; retainingUntil = minOf(retainingUntil, now + 300_000) }
    fun clear() { listeningUntil = 0; retainingUntil = 0 }
    fun listening(now: Long) = now < listeningUntil
    fun selectable(now: Long) = now < retainingUntil
    fun remaining(now: Long) = maxOf(0, listeningUntil - now)
}
