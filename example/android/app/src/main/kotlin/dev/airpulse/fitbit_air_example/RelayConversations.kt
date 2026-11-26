package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import org.json.JSONArray

/**
 * Finds chats from the selected apps' conversation notifications so the user can save them; saved chats
 * live in [RelayRuleStore]. Metadata only: no message text is read, stored or exported.
 */
internal object RelayConversations {
    private data class Chat(val id: String, val packageName: String, val label: String, val kind: String = "shortcut") {
        fun toMap() = mapOf("id" to id, "packageName" to packageName, "label" to label, "kind" to kind)
    }
    private val candidates = linkedMapOf<String, Chat>()
    private val window = ConversationCaptureWindow()
    private val diagnostics = linkedMapOf<String, Int>()
    private var unsupported = 0
    /** Apps that were on when finding started; chats are only offered from these. */
    private var capturePackages = emptySet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { finishCapture() }
    private val clearResults = Runnable { candidates.clear() }
    private fun legacyPrefs(context: Context) = context.getSharedPreferences("relay_conversations", Context.MODE_PRIVATE)
    private fun modePrefs(context: Context) = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)

    /** Saved chats and the "Selected" mode from before they moved into [RelayRuleStore]. */
    fun legacy(context: Context): Pair<Map<String, SavedChat>, Boolean> {
        val chats = runCatching {
            val array = JSONArray(legacyPrefs(context).getString("allowed", "[]"))
            (0 until minOf(array.length(), 64)).associate { index ->
                val item = array.getJSONObject(index)
                item.getString("id") to SavedChat(item.getString("packageName"), item.getString("label"), item.optString("kind", "shortcut"))
            }
        }.getOrDefault(emptyMap())
        return chats to modePrefs(context).getBoolean("restricted_conversations", false)
    }

    fun dropLegacy(context: Context) {
        legacyPrefs(context).edit().remove("allowed").apply()
        modePrefs(context).edit().remove("restricted_conversations").apply()
    }

    private fun title(notification: Notification): String? =
        (notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()

    private fun shortcut(notification: Notification) = if (Build.VERSION.SDK_INT >= 26) notification.shortcutId else null

    /** MessagingStyle or the message category: the notification is about a conversation. */
    private fun conversational(notification: Notification) =
        notification.extras.containsKey(Notification.EXTRA_MESSAGES) || notification.category == Notification.CATEGORY_MESSAGE

    private fun options(packageName: String, notification: Notification, appLabel: String?): List<ConversationIdentity> =
        RelayConversationIdentity.options(packageName, shortcut(notification), title(notification),
            conversational(notification), appLabel)

    fun identities(packageName: String, notification: Notification, appLabel: String?): Set<String> =
        runCatching { options(packageName, notification, appLabel).map { it.id }.toSet() }.getOrDefault(emptySet())

    private fun count(key: String) { diagnostics[key] = minOf(1000, (diagnostics[key] ?: 0) + 1) }
    fun captureError() { if (window.listening(SystemClock.elapsedRealtime())) count("errors") }

    fun capture(context: Context, enabled: Boolean) {
        if (enabled) {
            check(CalendarRelayService.hasAccess(context) && CalendarNotificationListener.connected) {
                "Enable notification access and wait for connection first."
            }
        }
        if (!enabled) { finishCapture(); return }
        capturePackages = RelayRuleStore.read(context).selectedPackages
        candidates.clear()
        diagnostics.clear()
        diagnostics["seen"] = 0
        unsupported = 0
        handler.removeCallbacks(expiry)
        handler.removeCallbacks(clearResults)
        window.start(SystemClock.elapsedRealtime())
        handler.postDelayed(expiry, ConversationCaptureWindow.LISTEN_MS)
        CalendarNotificationListener.captureExisting()
    }

    private fun finishCapture() {
        handler.removeCallbacks(expiry)
        handler.removeCallbacks(clearResults)
        window.finish(SystemClock.elapsedRealtime())
        handler.postDelayed(clearResults, 300_000)
    }

    fun stopCapture() {
        handler.removeCallbacks(expiry)
        handler.removeCallbacks(clearResults)
        window.clear()
        candidates.clear()
    }

    fun observe(packageName: String, notification: Notification, ongoing: Boolean, appLabel: String?, existing: Boolean = false) {
        if (!window.listening(SystemClock.elapsedRealtime()) || packageName !in capturePackages) return
        // Only conversation-like notifications count; a calendar reminder is not a missed chat.
        if (!conversational(notification) && shortcut(notification).isNullOrBlank() &&
            packageName !in RelayConversationPolicy.whatsappPackages) return
        count("seen")
        if (existing) count("existing")
        if (ongoing) { count("ongoing"); return }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) { count("summary"); return }
        val options = options(packageName, notification, appLabel)
        val identity = options.firstOrNull()
        if (identity?.kind != "shortcut") { unsupported = minOf(unsupported + 1, 1000); count("missingId") }
        if (identity == null) { count("unusable"); return }
        if (identity.kind == "name") count("nameOnly") else count("identified")
        // Presentation only. Never inspect EXTRA_TEXT or message bodies.
        val label = title(notification)?.filterNot { it.isISOControl() }?.take(120)
            ?.takeIf { it.isNotBlank() } ?: "Conversation (name unavailable)"
        if (candidates.size < 32 || candidates.containsKey(identity.id)) {
            candidates[identity.id] = Chat(identity.id, packageName, label, identity.kind)
        }
    }

    /** A chat found in the last capture, while results are still kept (five minutes). */
    fun candidate(id: String): SavedChat? = candidates[id]?.takeIf { window.selectable(SystemClock.elapsedRealtime()) }
        ?.let { SavedChat(it.packageName, it.label, it.kind) }

    fun status(): Map<String, Any> {
        val remaining = window.remaining(SystemClock.elapsedRealtime())
        if (!window.selectable(SystemClock.elapsedRealtime())) candidates.clear()
        return mapOf("captureRemainingMillis" to remaining, "unsupported" to unsupported,
            "diagnostics" to diagnostics.toMap(), "candidates" to candidates.values.map { it.toMap() })
    }
}
