package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RelayRuleStoreTest {
    @Test fun `settings round trip preserves inheritance mute pause and quiet window`() {
        val expected = RelayRuleConfig(RelayPreset.DOUBLE,
            mapOf("com.whatsapp" to RelayAppRule(true, RelayPreset.LOW), "com.calendar" to RelayAppRule(false)),
            mapOf("private-chat-hash" to RelayPreset.MUTE), RelaySilence(-1, true, 540, 1020))
        val actual = RelayRuleStore.decode(JSONObject(RelayRuleStore.encode(expected).toString()))
        assertEquals(expected, actual)
        assertNull(actual.apps["com.calendar"]!!.preset)
        assertTrue(actual.apps["com.whatsapp"]!!.enabled)
    }
    @Test(expected = IllegalArgumentException::class) fun `newer schema is not treated as an empty default`() {
        val json = RelayRuleStore.encode(RelayRuleConfig()).put("version", 2)
        RelayRuleStore.decode(json)
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid preset is rejected rather than silently matching`() {
        val json = RelayRuleStore.encode(RelayRuleConfig()).put("defaultPreset", "unknown")
        RelayRuleStore.decode(json)
    }
}
