package dev.airpulse.fitbit_air_example

import java.util.Calendar
import java.util.TimeZone

internal enum class RelayPreset(val id: String, val intensity: Int? = null) {
    CURRENT("single"), LOW("low", 2), HIGH("high", 3), DOUBLE("double"), MUTE("mute");
    companion object {
        fun parse(id: String): RelayPreset = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown vibration preset")
    }
}

internal data class RelayAppRule(val enabled: Boolean, val preset: RelayPreset? = null)
/**
 * A WhatsApp chat the user saved. [kind] is "shortcut" (stable conversation ID) or
 * "name" (exact chat name); [preset] null uses the app vibration. Labels stay on this phone.
 */
internal data class SavedChat(
    val packageName: String,
    val label: String,
    val kind: String = "shortcut",
    val preset: RelayPreset? = null,
)
/**
 * [tone] is the message's category when message tone is on, and [keyword] whether a
 * keyword rule chose the vibration; both only label history.
 */
internal data class RelayMatch(
    val preset: RelayPreset,
    val specificity: Int,
    val tone: RelayTone? = null,
    val keyword: Boolean = false,
) {
    /** History label: "keyword", a tone id, or null. */
    val category: String? get() = if (keyword) KEYWORD_CATEGORY else tone?.id

    companion object { const val KEYWORD_CATEGORY = "keyword" }
}
/** Per-tone presets; null keeps the app or chat rule. Off unless the user opts in. */
internal data class RelayToneRules(
    val enabled: Boolean = false,
    val urgent: RelayPreset? = null,
    val negative: RelayPreset? = null,
    val neutral: RelayPreset? = null,
) {
    fun preset(tone: RelayTone): RelayPreset? = when (tone) {
        RelayTone.URGENT -> urgent; RelayTone.NEGATIVE -> negative; RelayTone.NEUTRAL -> neutral
    }
}

internal data class RelaySilence(
    val pauseUntil: Long = 0,
    val quietEnabled: Boolean = false,
    val startMinute: Int = 1320,
    val endMinute: Int = 420,
    val onlyWhileAsleep: Boolean = false,
) {
    init {
        require(pauseUntil >= -1 && startMinute in 0..1439 && endMinute in 0..1439)
        require(!quietEnabled || startMinute != endMinute) { "Choose different start and end times" }
    }
    fun reason(now: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        if (pauseUntil == -1L || pauseUntil > now) return "paused"
        if (!quietEnabled) return null
        val local = Calendar.getInstance(zone).apply { timeInMillis = now }
        val minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE)
        val quiet = if (startMinute < endMinute) minute >= startMinute && minute < endMinute
            else minute >= startMinute || minute < endMinute
        return if (quiet) "quiet_hours" else null
    }
}

internal data class RelayCallRule(
    val enabled: Boolean = false,
    val preset: RelayPreset = RelayPreset.DOUBLE,
) {
    init { require(preset != RelayPreset.MUTE) }

    fun match(): RelayMatch? = if (enabled) RelayMatch(preset, 3) else null
}

internal data class RelayRuleConfig(
    val defaultPreset: RelayPreset = RelayPreset.CURRENT,
    val apps: Map<String, RelayAppRule> = emptyMap(),
    val chats: Map<String, SavedChat> = emptyMap(),
    val silence: RelaySilence = RelaySilence(),
    val tone: RelayToneRules = RelayToneRules(),
    /** Apps where only saved chats buzz; everyone else in them stays silent. */
    val onlySavedChats: Set<String> = emptySet(),
    val calls: RelayCallRule = RelayCallRule(),
    val keywords: List<RelayKeywordRule> = emptyList(),
) {
    init { require(keywords.size <= RelayKeywordRule.MAX_RULES) }

    /** The single WhatsApp switch older versions stored, for settings read by a downgrade. */
    val everyoneElseSilent: Boolean get() = RelayConversationPolicy.whatsappPackages.any { it in onlySavedChats }

    val selectedPackages: Set<String> get() = apps.filterValues { it.enabled }.keys

    /**
     * Order: app off, unsaved chats in [onlySavedChats] apps and muted chats or apps always stay
     * silent; then a matching [keyword] rule decides, then message [tone], then the
     * chat, app and default vibration.
     */
    fun match(packageName: String, identities: Set<String>, tone: RelayTone? = null, keyword: RelayKeywordRule? = null): RelayMatch? {
        val app = apps[packageName]?.takeIf { it.enabled } ?: return null
        // Identities are hashed with the app, so a chat only ever matches in its own app.
        val saved = identities.mapNotNull { id -> chats[id]?.takeIf { it.packageName == packageName } }
        if (packageName in onlySavedChats && saved.isEmpty()) return null
        val overrides = saved.mapNotNull { it.preset }
        // A saved mute always wins if Android supplies multiple identity forms.
        if (RelayPreset.MUTE in overrides) return null
        val base = overrides.firstOrNull() ?: app.preset ?: defaultPreset
        if (base == RelayPreset.MUTE) return null
        // A keyword is the user's explicit choice, so it outranks tone and person rules in a burst.
        if (keyword != null) {
            return if (keyword.preset == RelayPreset.MUTE) null else RelayMatch(keyword.preset, 2, keyword = true)
        }
        val category = tone?.takeIf { this.tone.enabled }
        val toned = category?.let { this.tone.preset(it) }
        val preset = toned ?: base
        // An urgent message outranks person-specific rules within a burst.
        val specificity = if (toned != null && tone == RelayTone.URGENT) 2 else if (overrides.isEmpty()) 0 else 1
        return if (preset == RelayPreset.MUTE) null else RelayMatch(preset, specificity, category)
    }
}

/** Latest wins ties; a person-specific rule outranks an app default. */
internal data class RelayBurstChoice(
    val match: RelayMatch,
    val receivedAt: Long,
    val ringingCall: RelayRingingCalls.Ring? = null,
) {
    fun prefer(other: RelayBurstChoice): RelayBurstChoice =
        if (other.match.specificity > match.specificity ||
            (other.match.specificity == match.specificity && other.receivedAt >= receivedAt)) other else this
}

/** "Everyone else: silent" from before chat rules covered every app applied to WhatsApp only. */
internal fun legacyOnlySavedChats(everyoneElseSilent: Boolean): Set<String> =
    if (everyoneElseSilent) RelayConversationPolicy.whatsappPackages else emptySet()

/** Migration also fails closed after backup/transfer omitted private rule settings. */
internal fun migrateRelayRules(packages: Set<String>, style: String, migratedBefore: Boolean): RelayRuleConfig =
    if (migratedBefore) RelayRuleConfig() else RelayRuleConfig(
        defaultPreset = if (style == "double") RelayPreset.DOUBLE else RelayPreset.CURRENT,
        apps = packages.associateWith { RelayAppRule(true) },
    )
