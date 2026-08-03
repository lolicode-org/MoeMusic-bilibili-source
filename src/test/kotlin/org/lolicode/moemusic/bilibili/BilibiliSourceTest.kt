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
        assertEquals(
            BilibiliVideoReference("BV18D3x6WEb2"),
            parseBilibiliIdentifier(
                "分享视频：TV动画「バンドリ！ ゆめ∞みた（BanG Dream! YUME∞MITA）」#7 插入曲「TearJerker」 " +
                    "https://www.bilibili.com/video/BV18D3x6WEb2/",
            ),
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

    @Test
    fun `favorite page contributes public videos using first cid`() {
        val tracks = BilibiliSource().autoplayTracksFromFavoritePage(
            Json.parseToJsonElement(
                """{"medias":[
                    {"type":2,"attr":0,"bvid":"BV18D3x6WEb2","title":"TearJerker","page":1,"duration":144,"cover":"https://i0.hdslb.com/a.jpg","upper":{"mid":1,"name":"Artist"},"ugc":{"first_cid":40429357668}},
                    {"type":2,"attr":0,"bvid":"BV1pm411f7JY","title":"Multi-part","page":2,"duration":999,"ugc":{"first_cid":2}},
                    {"type":12,"attr":0,"bvid":"BV18D3x6WEb2","ugc":{"first_cid":1}},
                    {"type":2,"attr":9,"bvid":"BV1xF3467Etw","ugc":{"first_cid":3}}
                ]}""",
            ).jsonObject,
        )

        assertEquals(2, tracks.size)
        assertEquals(144000L, tracks.first { it.id == "BV18D3x6WEb2:40429357668" }.durationMs)
        assertEquals(-1L, tracks.first { it.id == "BV1pm411f7JY:2" }.durationMs)
    }
}
