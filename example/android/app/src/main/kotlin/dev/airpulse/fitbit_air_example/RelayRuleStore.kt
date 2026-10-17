package dev.airpulse.fitbit_air_example

import android.content.Context
import org.json.JSONObject

/** Versioned, app-private settings. Chat identities never enter reports or backups. */
internal object RelayRuleStore {
    private fun prefs(context: Context) = context.getSharedPreferences("relay_rules", Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): RelayRuleConfig {
        val raw = prefs(context).getString("config", null)
        if (raw == null) {
            val legacy = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            val config = migrateRelayRules(RelayAppSelection.selected(context), RelayAppSelection.pulseStyle(context), legacy.getBoolean("rules_migrated", false))
            write(context, config)
            check(legacy.edit().putBoolean("rules_migrated", true).commit()) { "Could not finish migrating settings" }
            return config
        }
        // Corrupt or newer configs fail closed; never silently enable all chats/apps.
        return runCatching { decode(JSONObject(raw)) }.getOrElse { RelayRuleConfig() }
    }

    internal fun decode(json: JSONObject): RelayRuleConfig {
        require(json.getInt("version") == 1)
        val apps = json.getJSONObject("apps")
        val chats = json.getJSONObject("chats")
        val quiet = json.getJSONObject("silence")
        return RelayRuleConfig(
            RelayPreset.parse(json.getString("defaultPreset")),
            apps.keys().asSequence().associateWith { key ->
                val value = apps.getJSONObject(key)
                RelayAppRule(value.getBoolean("enabled"), if (value.isNull("preset")) null else RelayPreset.parse(value.getString("preset")))
            },
            chats.keys().asSequence().associateWith { RelayPreset.parse(chats.getString(it)) },
            RelaySilence(quiet.getLong("pauseUntil"), quiet.getBoolean("quietEnabled"), quiet.getInt("startMinute"), quiet.getInt("endMinute")),
        )
    }

    internal fun encode(config: RelayRuleConfig): JSONObject {
        val apps = JSONObject()
        config.apps.forEach { (key, rule) -> apps.put(key, JSONObject().put("enabled", rule.enabled).put("preset", rule.preset?.id ?: JSONObject.NULL)) }
        val chats = JSONObject()
        config.chats.forEach { (key, preset) -> chats.put(key, preset.id) }
        val s = config.silence
        val json = JSONObject().put("version", 1).put("defaultPreset", config.defaultPreset.id)
            .put("apps", apps).put("chats", chats)
            .put("silence", JSONObject().put("pauseUntil", s.pauseUntil).put("quietEnabled", s.quietEnabled)
                .put("startMinute", s.startMinute).put("endMinute", s.endMinute))
        return json
    }

    private fun write(context: Context, config: RelayRuleConfig) {
        check(prefs(context).edit().putString("config", encode(config).toString()).commit()) { "Could not save settings" }
    }

    @Synchronized fun update(context: Context, change: (RelayRuleConfig) -> RelayRuleConfig) {
        val next = change(read(context))
        require(next.apps.size <= 128 && next.chats.size <= 64)
        require(next.selectedPackages.size <= 64)
        require(next.defaultPreset != RelayPreset.MUTE)
        require(next.apps.keys.all { it != context.packageName && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) })
        write(context, next)
        CalendarRelayService.active?.settingsChanged()
    }

    fun status(context: Context): Map<String, Any?> {
        val c = read(context)
        return mapOf("defaultPreset" to c.defaultPreset.id,
            "apps" to c.apps.mapValues { (_, r) -> mapOf("enabled" to r.enabled, "preset" to r.preset?.id) },
            "chats" to c.chats.mapValues { it.value.id },
            "pauseUntil" to c.silence.pauseUntil, "quietEnabled" to c.silence.quietEnabled,
            "startMinute" to c.silence.startMinute, "endMinute" to c.silence.endMinute,
            "silenceReason" to c.silence.reason(System.currentTimeMillis()))
    }
}
