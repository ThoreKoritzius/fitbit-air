package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RelayRuleStoreTest {
    @Test fun callRuleRoundTripsAndDefaultsOffForOlderSettings() {
        val expected = RelayRuleConfig(calls = RelayCallRule(true, RelayPreset.LOW))
        val encoded = RelayRuleStore.encode(expected)
        assertEquals(expected, RelayRuleStore.decode(encoded))
        encoded.remove("calls")
        assertEquals(RelayCallRule(), RelayRuleStore.decode(encoded).calls)
    }

    @Test fun `settings round trip preserves inheritance mute pause and quiet window`() {
        val expected = RelayRuleConfig(RelayPreset.DOUBLE,
            mapOf("com.whatsapp" to RelayAppRule(true, RelayPreset.LOW), "com.calendar" to RelayAppRule(false)),
            mapOf("private-chat-hash" to SavedChat("com.whatsapp", "Chat", "name", RelayPreset.MUTE),
                "other-hash" to SavedChat("com.whatsapp.w4b", "Shop")),
            RelaySilence(-1, true, 540, 1020), everyoneElseSilent = true)
        val actual = RelayRuleStore.decode(JSONObject(RelayRuleStore.encode(expected).toString()))
        assertEquals(expected, actual)
        assertNull(actual.apps["com.calendar"]!!.preset)
        assertTrue(actual.apps["com.whatsapp"]!!.enabled)
    }
    @Test fun `tone rules round trip and older configs decode with tone off`() {
        val expected = RelayRuleConfig(tone = RelayToneRules(true, RelayPreset.DOUBLE, null, RelayPreset.MUTE))
        assertEquals(expected, RelayRuleStore.decode(JSONObject(RelayRuleStore.encode(expected).toString())))
        val legacy = RelayRuleStore.encode(RelayRuleConfig(RelayPreset.LOW)).apply { remove("tone") }
        assertEquals(RelayToneRules(), RelayRuleStore.decode(legacy).tone)
    }
    @Test fun `sleep rule round trips and defaults off for existing settings`() {
        val expected = RelayRuleConfig(silence = RelaySilence(onlyWhileAsleep = true))
        val encoded = RelayRuleStore.encode(expected)
        assertEquals(expected, RelayRuleStore.decode(encoded))
        encoded.getJSONObject("silence").remove("onlyWhileAsleep")
        assertFalse(RelayRuleStore.decode(encoded).silence.onlyWhileAsleep)
    }
    @Test(expected = IllegalArgumentException::class) fun `newer schema is not treated as an empty default`() {
        val json = RelayRuleStore.encode(RelayRuleConfig()).put("version", 3)
        RelayRuleStore.decode(json)
    }
    @Test fun `version 1 merges the separately saved chats and Selected mode`() {
        val json = RelayRuleStore.encode(RelayRuleConfig()).put("version", 1)
            .put("chats", JSONObject().put("alice-hash", "high"))
        json.remove("everyoneElseSilent")
        val legacy = mapOf("alice-hash" to SavedChat("com.whatsapp", "Alice"), "bob-hash" to SavedChat("com.whatsapp", "Bob", "name"))
        val config = RelayRuleStore.decode(json, legacy to true)
        assertEquals(SavedChat("com.whatsapp", "Alice", preset = RelayPreset.HIGH), config.chats["alice-hash"])
        assertEquals(SavedChat("com.whatsapp", "Bob", "name"), config.chats["bob-hash"])
        assertTrue(config.everyoneElseSilent)
    }
    @Test(expected = IllegalArgumentException::class) fun `version 1 needs the legacy chat list`() {
        RelayRuleStore.decode(RelayRuleStore.encode(RelayRuleConfig()).put("version", 1))
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid preset is rejected rather than silently matching`() {
        val json = RelayRuleStore.encode(RelayRuleConfig()).put("defaultPreset", "unknown")
        RelayRuleStore.decode(json)
    }
}
