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
/** [tone] is the message's category when message tone is on, for history. */
internal data class RelayMatch(val preset: RelayPreset, val specificity: Int, val tone: RelayTone? = null)
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

internal data class RelayRuleConfig(
    val defaultPreset: RelayPreset = RelayPreset.CURRENT,
    val apps: Map<String, RelayAppRule> = emptyMap(),
    val chats: Map<String, SavedChat> = emptyMap(),
    val silence: RelaySilence = RelaySilence(),
    val tone: RelayToneRules = RelayToneRules(),
    val everyoneElseSilent: Boolean = false,
) {
    val selectedPackages: Set<String> get() = apps.filterValues { it.enabled }.keys
    fun match(packageName: String, identities: Set<String>, tone: RelayTone? = null): RelayMatch? {
        val app = apps[packageName]?.takeIf { it.enabled } ?: return null
        val whatsapp = packageName in RelayConversationPolicy.packages
        val saved = if (whatsapp) identities.mapNotNull { chats[it] } else emptyList()
        // "Everyone else: Silent" lets only saved chats through.
        if (whatsapp && everyoneElseSilent && saved.isEmpty()) return null
        val overrides = saved.mapNotNull { it.preset }
        // A saved mute always wins if Android supplies multiple identity forms.
        if (RelayPreset.MUTE in overrides) return null
        val base = overrides.firstOrNull() ?: app.preset ?: defaultPreset
        if (base == RelayPreset.MUTE) return null
        val category = tone?.takeIf { this.tone.enabled }
        val toned = category?.let { this.tone.preset(it) }
        val preset = toned ?: base
        // An urgent message outranks person-specific rules within a burst.
        val specificity = if (toned != null && tone == RelayTone.URGENT) 2 else if (overrides.isEmpty()) 0 else 1
        return if (preset == RelayPreset.MUTE) null else RelayMatch(preset, specificity, category)
    }
}

/** Latest wins ties; a person-specific rule outranks an app default. */
internal data class RelayBurstChoice(val match: RelayMatch, val receivedAt: Long) {
    fun prefer(other: RelayBurstChoice): RelayBurstChoice =
        if (other.match.specificity > match.specificity ||
            (other.match.specificity == match.specificity && other.receivedAt >= receivedAt)) other else this
}

/** Migration also fails closed after backup/transfer omitted private rule settings. */
internal fun migrateRelayRules(packages: Set<String>, style: String, migratedBefore: Boolean): RelayRuleConfig =
    if (migratedBefore) RelayRuleConfig() else RelayRuleConfig(
        defaultPreset = if (style == "double") RelayPreset.DOUBLE else RelayPreset.CURRENT,
        apps = packages.associateWith { RelayAppRule(true) },
    )
