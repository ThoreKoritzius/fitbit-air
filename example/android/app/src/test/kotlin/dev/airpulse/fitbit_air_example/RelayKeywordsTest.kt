package dev.airpulse.fitbit_air_example

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class RelayKeywordsTest {
    private val urgent = RelayKeywordRule(listOf("urgent", "call me", "🚨"), RelayPreset.DOUBLE)
    private val news = RelayKeywordRule(listOf("unsubscribe"), RelayPreset.MUTE)
    private val rules = listOf(urgent, news)
    private fun match(text: String?, list: List<RelayKeywordRule> = rules) = RelayKeywordMatcher.firstMatch(list, text)

    @Test fun `words and phrases match whole words ignoring case`() {
        assertEquals(0, match("This is URGENT."))
        assertEquals(0, match("Please call me when you can"))
        assertNull(match("Insurgent movie tonight"))
        assertNull(match("call them, not me"))
        assertEquals(1, match("Click here to Unsubscribe"))
    }
    @Test fun `emoji match anywhere and ignore presentation selectors`() {
        assertEquals(0, match("server down🚨🚨"))
        val warning = listOf(RelayKeywordRule(listOf("⚠️"), RelayPreset.HIGH))
        assertEquals(0, match("⚠ disk full", warning))
        assertEquals(0, match("disk⚠️full", warning))
        assertNull(match("all good 👍", warning))
    }
    @Test fun `trailing star matches word forms and umlauts fold`() {
        val forms = listOf(RelayKeywordRule(listOf("ruf* mich", "anruf*", "büro"), RelayPreset.HIGH))
        assertEquals(0, match("Rufst mich an?", forms))
        assertEquals(0, match("Anrufen bitte", forms))
        assertNull(match("Rufst du an?", forms))
        assertEquals(0, match("Bin im Buero", forms))
        assertEquals(0, match("Bin im BÜRO", forms))
        assertNull(match("Ruhig hier", forms))
    }
    @Test fun `first rule in order wins and empty text never matches`() {
        assertEquals(0, match("urgent: unsubscribe now"))
        assertNull(match(null))
        assertNull(match("   "))
        assertNull(match("urgent", emptyList()))
    }
    @Test fun `keyword decides over tone and chat vibration but never unmutes`() {
        val whatsapp = "com.whatsapp"
        val config = RelayRuleConfig(apps = mapOf(whatsapp to RelayAppRule(true, RelayPreset.LOW)),
            chats = mapOf("alice" to SavedChat(whatsapp, "Alice", preset = RelayPreset.HIGH),
                "muted" to SavedChat(whatsapp, "Muted", preset = RelayPreset.MUTE)),
            tone = RelayToneRules(true, urgent = RelayPreset.HIGH), keywords = rules)
        val expected = RelayMatch(RelayPreset.DOUBLE, 2, keyword = true)
        assertEquals(expected, config.match(whatsapp, setOf("alice"), RelayTone.URGENT, urgent))
        assertEquals("keyword", expected.category)
        assertNull(config.match(whatsapp, emptySet(), null, news))
        assertNull(config.match(whatsapp, setOf("muted"), null, urgent))
        assertNull(config.copy(onlySavedChats = setOf(whatsapp)).match(whatsapp, setOf("carol"), null, urgent))
        assertEquals(RelayMatch(RelayPreset.HIGH, 1), config.match(whatsapp, setOf("alice")))
    }
    @Test fun `rules round trip and older configs decode without keywords`() {
        val expected = RelayRuleConfig(keywords = rules)
        assertEquals(expected, RelayRuleStore.decode(JSONObject(RelayRuleStore.encode(expected).toString())))
        val legacy = RelayRuleStore.encode(RelayRuleConfig()).apply { remove("keywords") }
        assertTrue(RelayRuleStore.decode(legacy).keywords.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun `blank keywords are rejected`() {
        RelayKeywordRule(listOf(" "), RelayPreset.HIGH)
    }
    @Test(expected = IllegalArgumentException::class) fun `rule count is bounded`() {
        RelayRuleConfig(keywords = List(RelayKeywordRule.MAX_RULES + 1) { urgent })
    }
}
