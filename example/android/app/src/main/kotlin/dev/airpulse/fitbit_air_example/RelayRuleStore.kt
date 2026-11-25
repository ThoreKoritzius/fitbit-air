package dev.airpulse.fitbit_air_example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, app-private settings. Chat identities never enter reports or backups. */
internal object RelayRuleStore {
    private val packagePattern = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
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
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return RelayRuleConfig()
        if (json.optInt("version") == 1) {
            // Version 1 kept saved chats and "Selected" mode outside this config.
            val config = runCatching { decode(json, RelayConversations.legacy(context)) }.getOrElse { return RelayRuleConfig() }
            write(context, config)
            RelayConversations.dropLegacy(context)
            return config
        }
        return runCatching { decode(json) }.getOrElse { RelayRuleConfig() }
    }

    /** [legacy] holds version 1's separately stored saved chats and "Selected" flag. */
    internal fun decode(json: JSONObject, legacy: Pair<Map<String, SavedChat>, Boolean>? = null): RelayRuleConfig {
        val version = json.getInt("version")
        require(version == 2 || (version == 1 && legacy != null))
        val apps = json.getJSONObject("apps")
        val chats = json.getJSONObject("chats")
        val quiet = json.getJSONObject("silence")
        // Configs saved before message tone existed decode with it switched off.
        val tone = json.optJSONObject("tone")
        val calls = json.optJSONObject("calls")
        // Before chat rules covered every app, one WhatsApp-only switch stood for this list.
        val onlySaved = json.optJSONArray("onlySavedChats")
        // Configs saved before keyword rules existed decode with none.
        val keywords = json.optJSONArray("keywords")
        fun tonePreset(key: String) = if (tone == null || tone.isNull(key)) null else RelayPreset.parse(tone.getString(key))
        return RelayRuleConfig(
            RelayPreset.parse(json.getString("defaultPreset")),
            apps.keys().asSequence().associateWith { key ->
                val value = apps.getJSONObject(key)
                RelayAppRule(value.getBoolean("enabled"), if (value.isNull("preset")) null else RelayPreset.parse(value.getString("preset")))
            },
            if (version == 1) {
                // Version 1 stored only presets here; labels came from the saved list.
                val presets = chats.keys().asSequence().associateWith { RelayPreset.parse(chats.getString(it)) }
                legacy!!.first.mapValues { (id, chat) -> chat.copy(preset = presets[id]) }
            } else chats.keys().asSequence().associateWith { id ->
                val chat = chats.getJSONObject(id)
                SavedChat(chat.getString("packageName"), chat.getString("label"), chat.getString("kind"),
                    if (chat.isNull("preset")) null else RelayPreset.parse(chat.getString("preset")))
            },
            RelaySilence(quiet.getLong("pauseUntil"), quiet.getBoolean("quietEnabled"), quiet.getInt("startMinute"), quiet.getInt("endMinute"),
                quiet.optBoolean("onlyWhileAsleep")),
            RelayToneRules(tone?.getBoolean("enabled") ?: false, tonePreset("urgent"), tonePreset("negative"), tonePreset("neutral")),
            when {
                version == 1 -> legacyOnlySavedChats(legacy!!.second)
                onlySaved != null -> (0 until onlySaved.length()).map(onlySaved::getString).toSet()
                else -> legacyOnlySavedChats(json.getBoolean("everyoneElseSilent"))
            },
            RelayCallRule(calls?.optBoolean("enabled") ?: false,
                calls?.optString("preset", RelayPreset.CURRENT.id)?.let(RelayPreset::parse) ?: RelayPreset.CURRENT,
                // Saved before calls could ring: ringing is the default.
                calls?.optBoolean("ring", true) ?: true),
            if (keywords == null) emptyList() else (0 until keywords.length()).map { i ->
                val rule = keywords.getJSONObject(i)
                val words = rule.getJSONArray("words")
                RelayKeywordRule((0 until words.length()).map(words::getString), RelayPreset.parse(rule.getString("preset")))
            },
        )
    }

    internal fun encode(config: RelayRuleConfig): JSONObject {
        val apps = JSONObject()
        config.apps.forEach { (key, rule) -> apps.put(key, JSONObject().put("enabled", rule.enabled).put("preset", rule.preset?.id ?: JSONObject.NULL)) }
        val chats = JSONObject()
        config.chats.forEach { (id, chat) ->
            chats.put(id, JSONObject().put("packageName", chat.packageName).put("label", chat.label)
                .put("kind", chat.kind).put("preset", chat.preset?.id ?: JSONObject.NULL))
        }
        val s = config.silence
        val json = JSONObject().put("version", 2).put("defaultPreset", config.defaultPreset.id)
            .put("apps", apps).put("chats", chats).put("onlySavedChats", JSONArray(config.onlySavedChats.sorted()))
            // Kept so an older version reading these settings still knows the WhatsApp choice.
            .put("everyoneElseSilent", config.everyoneElseSilent)
            .put("silence", JSONObject().put("pauseUntil", s.pauseUntil).put("quietEnabled", s.quietEnabled)
                .put("startMinute", s.startMinute).put("endMinute", s.endMinute)
                .put("onlyWhileAsleep", s.onlyWhileAsleep))
        json.put("calls", JSONObject().put("enabled", config.calls.enabled).put("preset", config.calls.preset.id)
            .put("ring", config.calls.ring))
        val t = config.tone
        json.put("tone", JSONObject().put("enabled", t.enabled).put("urgent", t.urgent?.id ?: JSONObject.NULL)
            .put("negative", t.negative?.id ?: JSONObject.NULL).put("neutral", t.neutral?.id ?: JSONObject.NULL))
        json.put("keywords", JSONArray(config.keywords.map { rule ->
            JSONObject().put("words", JSONArray(rule.words)).put("preset", rule.preset.id)
        }))
        return json
    }

    private fun write(context: Context, config: RelayRuleConfig) {
        check(prefs(context).edit().putString("config", encode(config).toString()).commit()) { "Could not save settings" }
    }

    @Synchronized fun update(context: Context, change: (RelayRuleConfig) -> RelayRuleConfig) {
        val next = change(read(context))
        require(next.chats.size <= 64)
        require(next.defaultPreset != RelayPreset.MUTE)
        require(next.onlySavedChats.all { it.matches(packagePattern) })
        require(next.apps.keys.all { it != context.packageName && it.matches(packagePattern) })
        write(context, next)
        CalendarRelayService.active?.settingsChanged()
    }

    fun status(context: Context): Map<String, Any?> {
        val c = read(context)
        return mapOf("defaultPreset" to c.defaultPreset.id,
            "apps" to c.apps.mapValues { (_, r) -> mapOf("enabled" to r.enabled, "preset" to r.preset?.id) },
            "chats" to c.chats.map { (id, chat) ->
                mapOf("id" to id, "packageName" to chat.packageName, "label" to chat.label, "kind" to chat.kind,
                    "preset" to chat.preset?.id)
            },
            "onlySavedChats" to c.onlySavedChats.sorted(),
            "pauseUntil" to c.silence.pauseUntil, "quietEnabled" to c.silence.quietEnabled,
            "startMinute" to c.silence.startMinute, "endMinute" to c.silence.endMinute,
            "onlyWhileAsleep" to c.silence.onlyWhileAsleep,
            "silenceReason" to c.silence.reason(System.currentTimeMillis()),
            "callAlertsSupported" to true, // Every version; kept for older readers.
            "callAlertsEnabled" to c.calls.enabled, "callPreset" to c.calls.preset.id, "callRing" to c.calls.ring,
            "toneEnabled" to c.tone.enabled, "toneUrgent" to c.tone.urgent?.id,
            "toneNegative" to c.tone.negative?.id, "toneNeutral" to c.tone.neutral?.id,
            "keywordRules" to c.keywords.map { mapOf("words" to it.words, "preset" to it.preset.id) })
    }
}
