package org.lolicode.moemusic.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BilibiliSourceTest {
    @Test
    fun `track ids retain bvid and cid`() {
        val key = parseTrackId("BV16JKGegE8t:28346943818")

        assertEquals(BilibiliTrackKey("BV16JKGegE8t", 28346943818L), key)
        assertEquals("BV16JKGegE8t:28346943818", canonicalTrackId(key!!.bvid, key.cid))
    }

    @Test
    fun `identifiers carry the selected part`() {
        assertEquals(
            BilibiliVideoReference("BV16JKGegE8t", page = 2),
            parseBilibiliIdentifier("https://www.bilibili.com/video/BV16JKGegE8t?p=2"),
        )
        assertEquals(
            BilibiliVideoReference(aid = 12345),
            parseBilibiliIdentifier("bilibili:av12345"),
        )
    }

    @Test
    fun `text and durations are normalized`() {
        assertEquals("A & B", cleanBilibiliText("<em class=\"keyword\">A</em> &amp; B"))
        assertEquals(3723000L, parseDurationMillis("1:02:03"))
        assertEquals(-1L, parseDurationMillis("unknown"))
        assertNull(parseTrackId("BV16JKGegE8t"))
    }

    @Test
    fun `voice balance lufs is retained`() {
        val result = BilibiliSource().loudnessFrom(
            Json.parseToJsonElement("{\"volume\":{\"measured_i\":-8.9,\"measured_tp\":6.3,\"target_i\":-14}}").jsonObject,
        )

        assertEquals(-8.9, result?.integratedLufs)
    }
}
