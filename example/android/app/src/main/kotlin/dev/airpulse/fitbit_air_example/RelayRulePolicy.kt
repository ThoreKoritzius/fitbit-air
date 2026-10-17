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
internal data class RelayMatch(val preset: RelayPreset, val specificity: Int)
internal data class RelaySilence(
    val pauseUntil: Long = 0,
    val quietEnabled: Boolean = false,
    val startMinute: Int = 1320,
    val endMinute: Int = 420,
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
    val chats: Map<String, RelayPreset> = emptyMap(),
    val silence: RelaySilence = RelaySilence(),
) {
    val selectedPackages: Set<String> get() = apps.filterValues { it.enabled }.keys
    fun match(packageName: String, identities: Set<String>, restricted: Boolean, allowed: Set<String>): RelayMatch? {
        val app = apps[packageName]?.takeIf { it.enabled } ?: return null
        val whatsapp = packageName in RelayConversationPolicy.packages
        if (whatsapp && restricted && identities.none { it in allowed }) return null
        val overrides = if (whatsapp) identities.filter { it in allowed }.mapNotNull { chats[it] } else emptyList()
        // A saved mute always wins if Android supplies multiple identity forms.
        if (RelayPreset.MUTE in overrides) return null
        val preset = overrides.firstOrNull() ?: app.preset ?: defaultPreset
        return if (preset == RelayPreset.MUTE) null else RelayMatch(preset, if (overrides.isEmpty()) 0 else 1)
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
