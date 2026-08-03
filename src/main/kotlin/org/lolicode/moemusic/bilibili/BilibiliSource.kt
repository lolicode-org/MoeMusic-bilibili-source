package org.lolicode.moemusic.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.IdentifierResolutionResult
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.SourceException
import org.lolicode.moemusic.api.SourceFormatException
import org.lolicode.moemusic.api.SourceNetworkException
import org.lolicode.moemusic.api.SourceRateLimitException
import org.lolicode.moemusic.api.SourceTimeoutException
import org.lolicode.moemusic.api.TrackUnavailableException
import org.lolicode.moemusic.api.UserFacingException
import org.lolicode.moemusic.api.UserResult
import org.lolicode.moemusic.api.model.ArtistInfo
import org.lolicode.moemusic.api.model.LoudnessInfo
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackResolution
import org.lolicode.moemusic.api.model.ResolvedTrackPatch
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SearchResult
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.SelectionEntryKind
import org.lolicode.moemusic.api.model.SelectionResolveResult
import org.lolicode.moemusic.api.model.TrackInfo
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale

private const val API_BASE = "https://api.bilibili.com"
private const val BILIBILI_WEB_BASE = "https://www.bilibili.com"
private const val SEARCH_PAGE_SIZE = 20
private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
private const val TRACK_SEPARATOR = ":"
private const val SELECTION_PREFIX = "video:"
private const val BILIBILI_IDENTIFIER_PREFIX = "bilibili:"

private val JSON = Json { isLenient = true }
private val BVID_PATTERN = Regex("BV[0-9A-Za-z]{10}")
private val TRACK_ID_PATTERN = Regex("(BV[0-9A-Za-z]{10}):([1-9][0-9]*)")
private val AV_ID_PATTERN = Regex("av([1-9][0-9]*)", RegexOption.IGNORE_CASE)
private val AV_PATH_PATTERN = Regex("(?:^|/)av([1-9][0-9]*)(?:/|$)", RegexOption.IGNORE_CASE)
private val HTML_TAG_PATTERN = Regex("<[^>]*>")
private val HTML_ENTITY_PATTERN = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);")

internal data class BilibiliTrackKey(val bvid: String, val cid: Long)

internal data class BilibiliVideoReference(
    val bvid: String? = null,
    val aid: Long? = null,
    val page: Int? = null,
    val cid: Long? = null,
    val shortUrl: URI? = null,
)

internal fun canonicalTrackId(bvid: String, cid: Long): String = "$bvid$TRACK_SEPARATOR$cid"

internal fun parseTrackId(value: String): BilibiliTrackKey? {
    val match = TRACK_ID_PATTERN.matchEntire(value.trim()) ?: return null
    return match.groupValues[2].toLongOrNull()?.let { cid -> BilibiliTrackKey(match.groupValues[1], cid) }
}

internal fun parseDurationMillis(value: String?): Long {
    val parts = value.orEmpty().trim().split(':')
    val values = parts.map { it.toLongOrNull() ?: return -1L }
    if (values.any { it < 0 }) return -1
    return runCatching {
        val seconds = values.fold(0L) { total, part ->
            Math.addExact(Math.multiplyExact(total, 60L), part)
        }
        Math.multiplyExact(seconds, 1000L)
    }.getOrDefault(-1L)
}

internal fun cleanBilibiliText(value: String): String = HTML_ENTITY_PATTERN.replace(
    HTML_TAG_PATTERN.replace(value, ""),
) { match ->
    when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        "nbsp" -> " "
        else -> when {
            entity.startsWith("#x") -> entity.removePrefix("#x").toIntOrNull(16)?.let { it.toChar().toString() }
            entity.startsWith("#") -> entity.removePrefix("#").toIntOrNull()?.let { it.toChar().toString() }
            else -> null
        } ?: match.value
    }
}

internal fun parseBilibiliIdentifier(raw: String): BilibiliVideoReference? {
    var value = raw.trim()
    if (value.startsWith(BILIBILI_IDENTIFIER_PREFIX, ignoreCase = true)) {
        value = value.substring(BILIBILI_IDENTIFIER_PREFIX.length).trim()
    }

    BVID_PATTERN.matchEntire(value)?.let { return BilibiliVideoReference(bvid = it.value) }
    AV_ID_PATTERN.matchEntire(value)?.let {
        it.groupValues[1].toLongOrNull()?.let { aid ->
            return BilibiliVideoReference(aid = aid)
        }
    }
    parseTrackId(value)?.let { return BilibiliVideoReference(bvid = it.bvid, cid = it.cid) }

    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (!isBilibiliHost(host) && host != "b23.tv") return null
    val query = parseQuery(uri.rawQuery)
    BVID_PATTERN.find(uri.path.orEmpty())?.let {
        return BilibiliVideoReference(
            bvid = it.value,
            page = query["p"]?.toIntOrNull()?.takeIf { page -> page > 0 },
            cid = query["cid"]?.toLongOrNull()?.takeIf { cid -> cid > 0 },
        )
    }
    AV_PATH_PATTERN.find(uri.path.orEmpty())?.let {
        it.groupValues[1].toLongOrNull()?.let { aid ->
            return BilibiliVideoReference(
                aid = aid,
                page = query["p"]?.toIntOrNull()?.takeIf { page -> page > 0 },
                cid = query["cid"]?.toLongOrNull()?.takeIf { cid -> cid > 0 },
            )
        }
    }
    if (host == "b23.tv") return BilibiliVideoReference(shortUrl = uri)
    return null
}

private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
    .split('&')
    .mapNotNull { item ->
        val separator = item.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = runCatching { URLDecoder.decode(item.substring(0, separator), StandardCharsets.UTF_8) }.getOrNull()
        val value = runCatching { URLDecoder.decode(item.substring(separator + 1), StandardCharsets.UTF_8) }.getOrNull()
        if (key.isNullOrBlank() || value == null) null else key.lowercase(Locale.ROOT) to value
    }
    .toMap()

private fun isBilibiliHost(host: String): Boolean =
    host == "bilibili.com" || host.endsWith(".bilibili.com")

private data class BilibiliPage(
    val cid: Long,
    val page: Int,
    val part: String,
    val durationSeconds: Long,
)

private data class BilibiliVideo(
    val bvid: String,
    val title: String,
    val coverUrl: String?,
    val ownerId: Long?,
    val ownerName: String,
    val paid: Boolean,
    val pages: List<BilibiliPage>,
)

private class BilibiliApiException(
    val code: Int,
    val detail: String,
) : RuntimeException("Bilibili API error $code: $detail")

private class BilibiliBadResponseException(cause: Throwable) : RuntimeException(cause)

/** Anonymous Bilibili source. It intentionally has no cookie, login, paid-video, or DRM path. */
class BilibiliSource(
    private val http: HttpClient = defaultHttpClient(),
) : SearchableMusicSource, IdentifierResolvableMusicSource {

    override val id: String = BilibiliPlugin.SOURCE_ID
    override val displayName: LocalizedText = LocalizedText.key("source.moemusic.bilibili")

    override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> {
        val text = query.query.trim().take(200)
        if (text.isBlank()) {
            return UserResult.Success(SearchResult(emptyList(), id, 0))
        }
        val limit = (query.limit.takeIf { it > 0 } ?: SEARCH_PAGE_SIZE).coerceIn(1, SEARCH_PAGE_SIZE)
        val offset = query.offset.coerceAtLeast(0)
        val page = offset.toLong() / limit + 1
        val pageOffset = offset % limit
        return try {
            val data = requestApi(
                "/x/web-interface/wbi/search/type",
                mapOf(
                    "search_type" to "video",
                    "keyword" to text,
                    "page" to page.toString(),
                    "page_size" to limit.toString(),
                ),
                BILIBILI_WEB_BASE,
            )
            val rows = (data["result"] as? JsonArray).orEmpty().mapNotNull(::searchEntry)
                .drop(pageOffset)
                .take(limit)
            val total = data.long("numResults")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: rows.size
            UserResult.Success(
                SearchResult(rows, id, total) {
                    hasMore = offset.toLong() + rows.size < total
                }
            )
        } catch (failure: Exception) {
            UserResult.Error(userMessage(failure, "error.moemusic.bilibili.search_failed"))
        }
    }

    override suspend fun resolveIdentifier(
        identifier: String,
        submitter: MoeMusicUser?,
    ): IdentifierResolutionResult {
        val input = identifier.trim()
        val owned = input.startsWith(BILIBILI_IDENTIFIER_PREFIX, ignoreCase = true)
        parseTrackId(input.removePrefixIgnoreCase(BILIBILI_IDENTIFIER_PREFIX))?.let { key ->
            return try {
                val video = fetchVideoByBvid(key.bvid)
                video.page(key.cid)?.let { page ->
                    IdentifierResolutionResult.Resolved(toTrack(video, page))
                } ?: IdentifierResolutionResult.Blocked(trackNotFoundMessage())
            } catch (failure: Exception) {
                IdentifierResolutionResult.Blocked(userMessage(failure, "error.moemusic.bilibili.track_not_found"))
            }
        }

        var reference = parseBilibiliIdentifier(input) ?: return if (owned) {
            IdentifierResolutionResult.Blocked(invalidIdentifierMessage())
        } else {
            IdentifierResolutionResult.Pass
        }
        if (reference.shortUrl != null) {
            reference = followShortUrl(reference.shortUrl) ?: return IdentifierResolutionResult.Blocked(invalidIdentifierMessage())
        }
        return try {
            val video = fetchVideo(reference)
            selectVideo(video, reference.page, reference.cid)
        } catch (failure: Exception) {
            IdentifierResolutionResult.Blocked(userMessage(failure, "error.moemusic.bilibili.track_not_found"))
        }
    }

    override suspend fun resolveSelection(
        selectionId: String,
        submitter: MoeMusicUser?,
    ): UserResult<SelectionResolveResult?> {
        parseTrackId(selectionId)?.let { key ->
            return when (val result = getTrackInfo(canonicalTrackId(key.bvid, key.cid), submitter)) {
                is UserResult.Success -> UserResult.Success(result.value?.let(SelectionResolveResult::Track))
                is UserResult.Error -> result
            }
        }
        val bvid = selectionId.removePrefix(SELECTION_PREFIX).takeIf { it != selectionId }
            ?.takeIf { BVID_PATTERN.matches(it) }
            ?: return UserResult.Error(invalidIdentifierMessage())
        return try {
            val video = fetchVideoByBvid(bvid)
            when (val selected = selectVideo(video, null, null)) {
                is IdentifierResolutionResult.Resolved -> UserResult.Success(SelectionResolveResult.Track(selected.track))
                is IdentifierResolutionResult.Choices -> UserResult.Success(SelectionResolveResult.Choices(selected.entries))
                is IdentifierResolutionResult.Blocked -> UserResult.Error(selected.message)
                IdentifierResolutionResult.Pass -> UserResult.Success(null)
            }
        } catch (failure: Exception) {
            UserResult.Error(userMessage(failure, "error.moemusic.bilibili.track_not_found"))
        }
    }

    override suspend fun getTrackInfo(trackId: String, submitter: MoeMusicUser?): UserResult<TrackInfo?> {
        val key = parseTrackId(trackId) ?: return UserResult.Error(invalidIdentifierMessage())
        return try {
            val video = fetchVideoByBvid(key.bvid)
            UserResult.Success(video.page(key.cid)?.let { toTrack(video, it) })
        } catch (failure: Exception) {
            UserResult.Error(userMessage(failure, "error.moemusic.bilibili.track_not_found"))
        }
    }

    override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResolution {
        val key = parseTrackId(track.id) ?: throw SourceFormatException()
        val video = try {
            fetchVideoByBvid(key.bvid)
        } catch (failure: Exception) {
            throw playbackException(failure)
        }
        val page = video.page(key.cid) ?: throw TrackUnavailableException(trackNotFoundMessage())
        if (video.paid) throw TrackUnavailableException(paidMessage())

        val playData = try {
            requestApi(
                "/x/player/playurl",
                mapOf(
                    "bvid" to key.bvid,
                    "cid" to key.cid.toString(),
                    "qn" to "127",
                    "fnval" to "4048",
                    "fnver" to "0",
                    "fourk" to "1",
                    "try_look" to "1",
                    "voice_balance" to "1",
                ),
                pageUrl(key.bvid, page.page),
            )
        } catch (failure: Exception) {
            throw playbackException(failure)
        }
        val audio = selectAudio(playData) ?: throw TrackUnavailableException(noStreamMessage())
        val audioUrl = mediaUrl(audio)
            ?: throw TrackUnavailableException(noStreamMessage())
        val loudness = loudnessFrom(playData)
        return PlaybackResolution(
            PlaybackResource(audioUrl) {
                headers = playbackHeaders(pageUrl(key.bvid, page.page))
            }
        ) {
            loudness?.let { value ->
                this.trackPatch = ResolvedTrackPatch { this.loudness = value }
            }
        }
    }

    private fun searchEntry(element: JsonElement): SelectionEntry? {
        val item = element as? JsonObject ?: return null
        val bvid = item.string("bvid")?.takeIf { BVID_PATTERN.matches(it) } ?: return null
        val title = cleanBilibiliText(item.string("title").orEmpty()).ifBlank { bvid }
        val author = item.string("author").orEmpty().ifBlank { "Bilibili" }
        val artistId = item.long("mid")?.toString() ?: author
        return SelectionEntry(
            selectionId = "$SELECTION_PREFIX$bvid",
            title = title,
            artists = listOf(ArtistInfo(artistId, author)),
            durationMs = parseDurationMillis(item.string("duration")),
        ) {
            sourceId = this@BilibiliSource.id
            kind = SelectionEntryKind.CONTAINER
            unavailableReason = if (item.flag("is_pay", "badgepay")) paidMessage() else null
        }
    }

    private fun selectVideo(
        video: BilibiliVideo,
        requestedPage: Int?,
        requestedCid: Long?,
    ): IdentifierResolutionResult {
        val page = requestedCid?.let { video.page(it) } ?: requestedPage?.let { video.pageNumber(it) }
        if (requestedCid != null || requestedPage != null) {
            return page?.let { IdentifierResolutionResult.Resolved(toTrack(video, it)) }
                ?: IdentifierResolutionResult.Blocked(trackNotFoundMessage())
        }
        if (video.pages.size == 1) return IdentifierResolutionResult.Resolved(toTrack(video, video.pages.single()))
        val entries = video.pages.map { pageInfo ->
            SelectionEntry(
                selectionId = canonicalTrackId(video.bvid, pageInfo.cid),
                title = pageTitle(video, pageInfo),
                artists = listOf(ArtistInfo(video.ownerId?.toString() ?: video.ownerName, video.ownerName)),
                durationMs = pageInfo.durationMillis(),
            ) {
                sourceId = this@BilibiliSource.id
                kind = SelectionEntryKind.TRACK
                unavailableReason = if (video.paid) paidMessage() else null
            }
        }
        return if (entries.isEmpty()) IdentifierResolutionResult.Blocked(trackNotFoundMessage())
        else IdentifierResolutionResult.Choices(entries)
    }

    private fun toTrack(video: BilibiliVideo, page: BilibiliPage): TrackInfo = TrackInfo(
        id = canonicalTrackId(video.bvid, page.cid),
        title = pageTitle(video, page),
        artists = listOf(ArtistInfo(video.ownerId?.toString() ?: video.ownerName, video.ownerName)),
        durationMs = page.durationMillis(),
    ) {
        sourceId = this@BilibiliSource.id
        coverUrl = video.coverUrl
        unavailableReason = if (video.paid) paidMessage() else null
    }

    private fun pageTitle(video: BilibiliVideo, page: BilibiliPage): String {
        val part = cleanBilibiliText(page.part).trim()
        return if (video.pages.size > 1 && part.isNotBlank() && !part.equals(video.title, ignoreCase = true)) {
            "${video.title} - $part"
        } else {
            video.title
        }
    }

    private fun fetchVideo(reference: BilibiliVideoReference): BilibiliVideo = when {
        reference.bvid != null -> fetchVideoByBvid(reference.bvid)
        reference.aid != null -> fetchVideoByAid(reference.aid)
        else -> throw BilibiliBadResponseException(IllegalArgumentException("missing video id"))
    }

    private fun fetchVideoByBvid(bvid: String): BilibiliVideo = parseVideo(
        requestApi("/x/web-interface/view", mapOf("bvid" to bvid), BILIBILI_WEB_BASE)
    )

    private fun fetchVideoByAid(aid: Long): BilibiliVideo = parseVideo(
        requestApi("/x/web-interface/view", mapOf("aid" to aid.toString()), BILIBILI_WEB_BASE)
    )

    private fun parseVideo(data: JsonObject): BilibiliVideo {
        val bvid = data.string("bvid")?.takeIf { BVID_PATTERN.matches(it) }
            ?: throw BilibiliBadResponseException(IllegalArgumentException("missing bvid"))
        val title = cleanBilibiliText(data.string("title").orEmpty()).ifBlank { bvid }
        val owner = data["owner"] as? JsonObject
        val pages = (data["pages"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val page = element as? JsonObject ?: return@mapIndexedNotNull null
            val cid = page.long("cid")?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            BilibiliPage(
                cid = cid,
                page = page.int("page")?.takeIf { it > 0 } ?: index + 1,
                part = cleanBilibiliText(page.string("part").orEmpty()),
                durationSeconds = page.long("duration")?.takeIf { it >= 0 } ?: 0,
            )
        }.ifEmpty {
            data.long("cid")?.takeIf { it > 0 }?.let {
                listOf(BilibiliPage(it, 1, title, data.long("duration")?.takeIf { duration -> duration >= 0 } ?: 0))
            }
                ?: emptyList()
        }
        val rights = data["rights"] as? JsonObject
        val paid = rights?.flag("pay", "arc_pay", "vip") == true ||
            data.flag("is_upower_exclusive", "is_chargeable_season", "need_login", "is_login_required")
        return BilibiliVideo(
            bvid = bvid,
            title = title,
            coverUrl = normalizeCoverUrl(data.string("pic")),
            ownerId = owner?.long("mid"),
            ownerName = cleanBilibiliText(owner?.string("name").orEmpty()).ifBlank { "Bilibili" },
            paid = paid,
            pages = pages,
        )
    }

    private fun requestApi(path: String, params: Map<String, String>, referer: String): JsonObject {
        val uri = URI.create("$API_BASE$path?${params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }}")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (timeout: HttpTimeoutException) {
            throw SourceTimeoutException(timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SourceTimeoutException(interrupted)
        } catch (network: IOException) {
            throw SourceNetworkException(network)
        }
        if (response.statusCode() !in 200..299) {
            runCatching { response.body().close() }
            if (response.statusCode() == 429) throw SourceRateLimitException()
            throw SourceNetworkException()
        }
        val body = try {
            response.body().use { it.readNBytes(MAX_RESPONSE_BYTES + 1) }
        } catch (read: IOException) {
            throw SourceNetworkException(read)
        }
        if (body.size > MAX_RESPONSE_BYTES) throw SourceException(badResponseMessage())
        val root = try {
            JSON.parseToJsonElement(body.toString(StandardCharsets.UTF_8)).jsonObject
        } catch (parse: Exception) {
            throw BilibiliBadResponseException(parse)
        }
        val code = root.int("code") ?: throw SourceException(badResponseMessage())
        if (code != 0) throw BilibiliApiException(code, root.string("message").orEmpty())
        return root["data"] as? JsonObject ?: throw SourceException(badResponseMessage())
    }

    private fun followShortUrl(uri: URI): BilibiliVideoReference? {
        var current = uri
        repeat(5) {
            val request = runCatching {
                HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
            }.getOrNull() ?: return null
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: IOException) {
                return null
            }
            if (response.statusCode() !in 300..399) {
                return parseBilibiliIdentifier(current.toString())?.takeUnless { it.shortUrl == current }
            }
            val location = response.headers().firstValue("location").orElse(null) ?: return null
            val next = runCatching { current.resolve(location) }.getOrNull() ?: return null
            val host = next.host?.lowercase(Locale.ROOT) ?: return null
            if (next.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
            if (host != "b23.tv" && !isBilibiliHost(host)) return null
            current = next
        }
        return null
    }

    private fun selectAudio(playData: JsonObject): JsonObject? {
        val dash = playData["dash"] as? JsonObject ?: return null
        val candidates = mutableListOf<Pair<JsonObject, Int>>()
        fun add(value: JsonElement?, tier: Int) {
            when (value) {
                is JsonArray -> value.mapNotNull { it as? JsonObject }.forEach { candidates += it to tier }
                is JsonObject -> candidates += value to tier
                else -> Unit
            }
        }
        add(dash["audio"], 1)
        add((dash["dolby"] as? JsonObject)?.get("audio"), 2)
        add((dash["flac"] as? JsonObject)?.get("audio"), 3)
        return candidates.asSequence()
            .filter { mediaUrl(it.first) != null }
            .maxWithOrNull(compareBy<Pair<JsonObject, Int>> { it.second }
                .thenBy { it.first.long("bandwidth") ?: 0L }
                .thenBy { it.first.long("id") ?: 0L })?.first
    }

    internal fun loudnessFrom(playData: JsonObject): LoudnessInfo? {
        val volume = playData["volume"] as? JsonObject ?: return null
        val lufs = (volume["measured_i"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in -70.0..0.0 }
            ?: return null
        return LoudnessInfo {
            integratedLufs = lufs
        }
    }

    private fun playbackHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Origin" to BILIBILI_WEB_BASE,
        "Referer" to referer,
        "User-Agent" to USER_AGENT,
    )

    private fun pageUrl(bvid: String, page: Int): String = "$BILIBILI_WEB_BASE/video/$bvid?p=$page"

    private fun isAllowedMediaUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == "bilivideo.com" || host.endsWith(".bilivideo.com") ||
            host == "akamaized.net" || host.endsWith(".akamaized.net")
    }

    private fun mediaUrl(audio: JsonObject): String? {
        audio.string("baseUrl", "base_url", "url")?.takeIf(::isAllowedMediaUrl)?.let { return it }
        val backups = audio["backupUrl"] ?: audio["backup_url"] ?: return null
        return when (backups) {
            is JsonArray -> backups.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .firstOrNull(::isAllowedMediaUrl)
            is JsonPrimitive -> backups.contentOrNull?.takeIf(::isAllowedMediaUrl)
            else -> null
        }
    }

    private fun normalizeCoverUrl(value: String?): String? {
        val candidate = value?.trim()?.let { if (it.startsWith("//")) "https:$it" else it } ?: return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return candidate.takeIf {
            host == "hdslb.com" || host.endsWith(".hdslb.com") || host == "biliimg.com" || host.endsWith(".biliimg.com")
        }
    }

    private fun playbackException(failure: Throwable): UserFacingException = when (failure) {
        is UserFacingException -> failure
        is BilibiliApiException -> when {
            failure.code == -412 -> SourceRateLimitException(failure)
            failure.code in RESTRICTED_API_CODES -> TrackUnavailableException(paidMessage(), failure)
            else -> SourceException(userMessage(failure, "error.moemusic.bilibili.request_failed"), failure)
        }
        is BilibiliBadResponseException -> SourceException(badResponseMessage(), failure)
        else -> SourceException(userMessage(failure, "error.moemusic.bilibili.request_failed"), failure)
    }

    private fun userMessage(failure: Throwable, fallbackKey: String): LocalizedText = when (failure) {
        is BilibiliApiException -> when (failure.code) {
            -404 -> trackNotFoundMessage()
            -412 -> LocalizedText.key("error.moemusic.source.rate_limit")
            in RESTRICTED_API_CODES -> paidMessage()
            else -> LocalizedText.key(fallbackKey, failure.code)
        }
        is BilibiliBadResponseException -> badResponseMessage()
        is SourceTimeoutException -> LocalizedText.key("error.moemusic.source.timeout")
        is SourceRateLimitException -> LocalizedText.key("error.moemusic.source.rate_limit")
        is SourceNetworkException -> LocalizedText.key("error.moemusic.source.network")
        is SourceException -> failure.userMessage
        else -> LocalizedText.key(fallbackKey)
    }

    private fun invalidIdentifierMessage(): LocalizedText = LocalizedText.key("error.moemusic.bilibili.invalid_identifier")
    private fun trackNotFoundMessage(): LocalizedText = LocalizedText.key("error.moemusic.bilibili.track_not_found")
    private fun paidMessage(): LocalizedText = LocalizedText.key("error.moemusic.bilibili.paid_video")
    private fun noStreamMessage(): LocalizedText = LocalizedText.key("error.moemusic.bilibili.no_stream")
    private fun badResponseMessage(): LocalizedText = LocalizedText.key("error.moemusic.bilibili.bad_response")

    private fun BilibiliVideo.page(cid: Long): BilibiliPage? = pages.firstOrNull { it.cid == cid }
    private fun BilibiliVideo.pageNumber(number: Int): BilibiliPage? = pages.firstOrNull { it.page == number }
    private fun BilibiliPage.durationMillis(): Long = runCatching {
        Math.multiplyExact(durationSeconds, 1000L)
    }.getOrDefault(-1L)

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.longOrNull }
    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.intOrNull }
    private fun JsonObject.flag(vararg keys: String): Boolean = keys.any { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.let { value ->
            value == "1" || value.equals("true", ignoreCase = true)
        } == true
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private companion object {
        val RESTRICTED_API_CODES = setOf(-101, -102, -403, -10403, -6000, -62001)
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
