package com.example.online

import android.net.Uri
import android.util.Log
import com.example.model.Song
import com.example.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class OnlineSourceOption(
    val id: String,
    val label: String,
    val badge: String
)

data class DeliveryEngineOption(
    val id: String,
    val label: String
)

data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Онлайн",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val isOfficialAudio: Boolean = false,
    val sourceEngine: String = "youtube",
    val directStreamUrl: String? = null,
    val qualityBadge: String = defaultQualityBadgeForSource(sourceEngine)
) {
    val durationMs: Long
        get() = durationSeconds * 1000L

    val sourceDisplayName: String
        get() = displayNameForSource(sourceEngine)

    fun toSong(streamUrl: String? = null): Song {
        val uniqueId = -((("$sourceEngine:$id").hashCode().toLong() and 0x7FFFFFFFL) + 1000L)
        val uriStr = streamUrl ?: directStreamUrl ?: buildOnlineUri()
        return Song(
            id = uniqueId,
            title = title,
            artist = artist,
            album = album,
            albumId = -(album.hashCode().toLong() and 0x7FFFFFFFL) - 1000L,
            durationMs = durationMs,
            contentUriString = uriStr,
            albumArtUriString = thumbnailUrl,
            genre = sourceDisplayName,
            mimeType = "audio/mp4",
            dateAdded = System.currentTimeMillis()
        )
    }

    fun buildOnlineUri(): String {
        val safeHost = if (sourceEngine in setOf("audius", "saavn", "archive", "deezer", "spotify")) {
            sourceEngine
        } else {
            "youtube"
        }
        val builder = Uri.Builder()
            .scheme("online")
            .authority(safeHost)
            .appendPath(id)
            .appendQueryParameter("title", title)
            .appendQueryParameter("artist", artist)
            .appendQueryParameter("dur", durationMs.toString())
        if (!directStreamUrl.isNullOrBlank()) {
            builder.appendQueryParameter("stream", directStreamUrl)
        }
        return builder.build().toString()
    }
}

fun displayNameForSource(sourceEngine: String): String = when (sourceEngine.lowercase()) {
    "youtube" -> "YouTube"
    "audius" -> "Audius"
    "saavn" -> "JioSaavn"
    "archive" -> "Archive"
    "deezer" -> "Deezer"
    "spotify" -> "Spotify"
    else -> "Онлайн"
}

fun defaultQualityBadgeForSource(sourceEngine: String): String = when (sourceEngine.lowercase()) {
    "youtube" -> "AAC/Opus"
    "audius" -> "MP3 320k"
    "saavn" -> "M4A 320k"
    "archive" -> "MP3/OGG"
    "deezer" -> "Cross/HiFi"
    else -> "Stream"
}

data class ResolvedStream(
    val url: String,
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtMs: Long,
    val engine: String
)

class YouTubeStreamManager {
    private val probeClient = NetworkClient.fastProbeClient
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()
    private val urlHeadersCache = ConcurrentHashMap<String, ResolvedStream>()
    private val hostHeadersCache = ConcurrentHashMap<String, ResolvedStream>()
    private val matchCache = ConcurrentHashMap<String, OnlineTrack>()
    private val unhealthyMirrorsUntil = ConcurrentHashMap<String, Long>()
    private val archiveFilesCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedVisitorData: String? = null
    @Volatile
    private var visitorDataFetchedAtMs: Long = 0L
    @Volatile
    private var cachedAudiusHost: String? = null
    @Volatile
    private var audiusHostFetchedAtMs: Long = 0L

    companion object {
        private const val TAG = "YouTubeStreamManager"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        val SOURCE_FILTERS = listOf(
            OnlineSourceOption("all", "Все источники", "MULTI"),
            OnlineSourceOption("youtube", "YouTube", "YT"),
            OnlineSourceOption("audius", "Audius", "MP3"),
            OnlineSourceOption("saavn", "JioSaavn", "320k"),
            OnlineSourceOption("archive", "Archive.org", "OGG/MP3"),
            OnlineSourceOption("deezer", "Deezer", "HiFi")
        )

        val DELIVERY_ENGINES = listOf(
            DeliveryEngineOption("auto", "Авто"),
            DeliveryEngineOption("innertube_android_vr", "YT VR"),
            DeliveryEngineOption("innertube_ios", "YT iOS"),
            DeliveryEngineOption("innertube_tv_embedded", "YT TV"),
            DeliveryEngineOption("innertube_android_music", "YT Music"),
            DeliveryEngineOption("piped", "Piped"),
            DeliveryEngineOption("invidious", "Invidious"),
            DeliveryEngineOption("cobalt", "Cobalt")
        )

        private val COBALT_INSTANCES = listOf(
            "https://api.cobalt.tools",
            "https://cobalt-api.kwiatekmiki.com",
            "https://cobalt.canine.tools",
            "https://co.eepy.today"
        )

        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.r4fo.com",
            "https://api.piped.privacydev.net",
            "https://pipedapi.ducks.party",
            "https://pipedapi.darkness.services",
            "https://pipedapi.nosebs.ru"
        )

        private val INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://invidious.privacyredirect.com",
            "https://iv.datura.network",
            "https://invidious.jing.rocks"
        )

        private val SAAVN_MIRRORS = listOf(
            "https://saavn.dev",
            "https://jiosaavn-api-privatecvc2.vercel.app",
            "https://saavn.sumit.co"
        )

        private val UNWANTED_MODIFIERS = listOf(
            "slowed", "reverb", "nightcore", "8d", "karaoke",
            "cover", "sped up", "instrumental", "remix", "teaser", "reaction"
        )

        fun formatEngineDisplay(engineId: String): String = when (engineId.lowercase()) {
            "innertube_android_vr" -> "YouTube • VR"
            "innertube_ios" -> "YouTube • iOS"
            "innertube_tv_embedded" -> "YouTube • TV"
            "innertube_android_music" -> "YouTube • Music"
            "piped" -> "Piped Mirror"
            "invidious" -> "Invidious Mirror"
            "cobalt" -> "Cobalt Tunnel"
            "yt_embed" -> "YouTube • Web"
            "audius" -> "Audius • MP3"
            "saavn" -> "JioSaavn • 320k"
            "archive" -> "Archive.org"
            "deezer" -> "Deezer Preview"
            "local" -> "Локальный"
            else -> engineId
        }
    }

    private fun isMirrorHealthy(mirror: String): Boolean {
        val blockedUntil = unhealthyMirrorsUntil[mirror] ?: return true
        return System.currentTimeMillis() >= blockedUntil
    }

    private fun markMirrorUnhealthy(mirror: String, cooldownMs: Long = 180_000L) {
        unhealthyMirrorsUntil[mirror] = System.currentTimeMillis() + cooldownMs
    }

    fun clearStreamCacheForId(videoId: String) {
        streamCache.remove(videoId)
    }

    fun getLastResolvedEngine(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return streamCache[videoId]?.engine
    }

    /**
     * Multi-source online search supporting both aggregated ("all") mode and single-source targeting:
     * Sources: youtube (InnerTube + Web HTML + Piped + Invidious), audius, saavn, archive, deezer.
     */
    suspend fun search(query: String, sourceFilter: String = "all"): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val normalizedFilter = sourceFilter.lowercase().trim()
        withTimeoutOrNull(11_500L) {
            when (normalizedFilter) {
                "youtube" -> searchYouTubeAggregated(trimmed)
                "audius" -> searchViaAudius(trimmed)
                "saavn" -> searchViaSaavn(trimmed)
                "archive" -> searchViaInternetArchive(trimmed)
                "deezer" -> searchViaDeezer(trimmed)
                else -> {
                    coroutineScope {
                        val ytDeferred = async { searchYouTubeAggregated(trimmed) }
                        val audiusDeferred = async { searchViaAudius(trimmed) }
                        val saavnDeferred = async { searchViaSaavn(trimmed) }
                        val archiveDeferred = async { searchViaInternetArchive(trimmed) }
                        val deezerDeferred = async { searchViaDeezer(trimmed) }

                        val buckets = awaitAll(ytDeferred, audiusDeferred, saavnDeferred, archiveDeferred, deezerDeferred)
                        interleaveTracks(buckets)
                    }
                }
            }
        } ?: emptyList()
    }

    private suspend fun searchYouTubeAggregated(query: String): List<OnlineTrack> = coroutineScope {
        val ytMusic = searchViaYtMusicInnerTube(query)
        if (ytMusic.isNotEmpty()) return@coroutineScope ytMusic

        val webResults = async { searchViaYouTubeWebHtml(query) }
        val piped = async { searchViaPipedParallel(query) }
        val invidious = async { searchViaInvidiousParallel(query) }
        val merged = (webResults.await() + piped.await() + invidious.await())
            .distinctBy { it.id }
        merged
    }

    private fun interleaveTracks(buckets: List<List<OnlineTrack>>): List<OnlineTrack> {
        val maxLen = buckets.maxOfOrNull { it.size } ?: 0
        if (maxLen == 0) return emptyList()
        val out = mutableListOf<OnlineTrack>()
        val seen = HashSet<String>()
        for (i in 0 until maxLen) {
            for (bucket in buckets) {
                val track = bucket.getOrNull(i) ?: continue
                val key = "${track.sourceEngine}:${track.id}"
                if (seen.add(key)) {
                    out.add(track)
                }
            }
        }
        return out
    }

    /**
     * Finds the exact matching official audio track scored by title/artist similarity and duration proximity.
     */
    suspend fun findBestTrackMatch(
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long = 0L
    ): OnlineTrack? = withContext(Dispatchers.IO) {
        val cacheKey = "${targetArtist.lowercase().trim()}|${targetTitle.lowercase().trim()}"
        matchCache[cacheKey]?.let { return@withContext it }

        val cleanTitle = cleanQueryPart(targetTitle)
        val cleanArtist = cleanQueryPart(targetArtist)
        val query = "$cleanArtist $cleanTitle".trim().ifBlank { targetTitle }

        val candidates = coroutineScope {
            val ytDeferred = async { searchViaYtMusicInnerTube("$query audio") }
            val ytPlainDeferred = async { searchYouTubeAggregated(query) }
            val saavnDeferred = async { searchViaSaavn(query) }
            val audiusDeferred = async { searchViaAudius(query) }
            val archiveDeferred = async { searchViaInternetArchive(query) }
            (ytDeferred.await() + ytPlainDeferred.await() + saavnDeferred.await() + audiusDeferred.await() + archiveDeferred.await())
                .distinctBy { "${it.sourceEngine}:${it.id}" }
        }

        if (candidates.isEmpty()) return@withContext null

        val best = candidates.maxByOrNull { candidate ->
            scoreCandidate(candidate, cleanTitle, cleanArtist, expectedDurationMs)
        }
        if (best != null) {
            matchCache[cacheKey] = best
        }
        best
    }

    /**
     * Resolves a direct playable audio stream URL AND matched duration + active engine for track metadata.
     */
    suspend fun resolveTrackStreamForMetadata(
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long = 0L,
        excludedEngines: Set<String> = emptySet(),
        preferredEngine: String? = null
    ): Triple<String, Long, String>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(14_000L) {
            val cleanTitle = cleanQueryPart(targetTitle)
            val cleanArtist = cleanQueryPart(targetArtist)
            val query = "$cleanArtist $cleanTitle".trim().ifBlank { targetTitle }

            val candidates = coroutineScope {
                val ytD = async { searchYouTubeAggregated(query) }
                val saavnD = async { searchViaSaavn(query) }
                val audiusD = async { searchViaAudius(query) }
                val archiveD = async { searchViaInternetArchive(query) }
                (ytD.await() + saavnD.await() + audiusD.await() + archiveD.await())
                    .distinctBy { "${it.sourceEngine}:${it.id}" }
            }.sortedByDescending { scoreCandidate(it, cleanTitle, cleanArtist, expectedDurationMs) }

            for (cand in candidates.take(6)) {
                if (cand.sourceEngine == "deezer" && cand.directStreamUrl == null) continue

                if (cand.directStreamUrl != null && cand.sourceEngine !in excludedEngines) {
                    val resolvedUrl = resolveDirectTrackPlayableUrl(cand) ?: continue
                    val resolved = ResolvedStream(
                        url = resolvedUrl,
                        userAgent = MOBILE_UA,
                        expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                        engine = cand.sourceEngine
                    )
                    cacheStream(cand.id, resolved)
                    return@withTimeoutOrNull Triple(
                        resolvedUrl,
                        if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                        cand.sourceEngine
                    )
                }

                if (cand.sourceEngine == "youtube") {
                    val resolved = resolveStreamResult(cand.id, excludedEngines, preferredEngine)
                    if (resolved != null) {
                        return@withTimeoutOrNull Triple(
                            resolved.url,
                            if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                            resolved.engine
                        )
                    }
                }
            }
            null
        }
    }

    suspend fun resolveDirectTrackPlayableUrl(track: OnlineTrack): String? = withContext(Dispatchers.IO) {
        when (track.sourceEngine) {
            "archive" -> resolveArchiveStreamUrl(track.id) ?: track.directStreamUrl
            "audius" -> track.directStreamUrl ?: buildAudiusStreamUrl(track.id)
            else -> track.directStreamUrl
        }
    }

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        resolveStreamResult(videoId, emptySet(), null)?.url
    }

    fun getHeadersForStreamUrl(streamUrl: String): Pair<String, Map<String, String>>? {
        urlHeadersCache[streamUrl]?.let { return it.userAgent to it.headers }
        val host = runCatching { Uri.parse(streamUrl).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            hostHeadersCache[host]?.let { return it.userAgent to it.headers }
            if (host.contains("googlevideo.com", ignoreCase = true)) {
                hostHeadersCache["googlevideo.com"]?.let { return it.userAgent to it.headers }
            }
        }
        return null
    }

    suspend fun resolveAlternateStream(
        failedUrl: String,
        videoId: String?,
        title: String,
        artist: String,
        expectedDurationMs: Long
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        val failedMeta = urlHeadersCache.remove(failedUrl)
        val excluded = if (failedMeta != null) setOf(failedMeta.engine) else emptySet()
        if (!videoId.isNullOrBlank()) {
            streamCache.remove(videoId)
            val direct = resolveStreamResult(videoId, excluded, null)
            if (direct != null) return@withContext direct
        }
        val matched = resolveTrackStreamForMetadata(title, artist, expectedDurationMs, excluded)
        matched?.let { (url, _, engine) ->
            urlHeadersCache[url] ?: ResolvedStream(
                url = url,
                userAgent = MOBILE_UA,
                expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                engine = engine
            )
        }
    }

    suspend fun resolveStreamResult(
        videoId: String,
        excludedEngines: Set<String> = emptySet(),
        preferredEngine: String? = null
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        if (preferredEngine.isNullOrBlank() || preferredEngine == "auto") {
            val cached = streamCache[videoId]
            if (cached != null && System.currentTimeMillis() < cached.expiresAtMs && cached.engine !in excludedEngines) {
                return@withContext cached
            }
        }

        // Handle explicit user-selected delivery engine first
        if (!preferredEngine.isNullOrBlank() && preferredEngine != "auto") {
            val forced = when {
                preferredEngine.startsWith("innertube_") -> {
                    val vd = fetchOrGetVisitorData()
                    val profile = buildInnerTubeProfiles().firstOrNull { it.engineId == preferredEngine }
                    profile?.let { resolveSingleInnerTubeProfile(videoId, it, vd) }
                }
                preferredEngine == "piped" -> resolveViaPipedParallel(videoId)
                preferredEngine == "invidious" -> resolveViaInvidiousParallel(videoId)
                preferredEngine == "cobalt" -> resolveViaCobaltParallel(videoId)
                else -> null
            }
            if (forced != null) {
                cacheStream(videoId, forced)
                return@withContext forced
            }
        }

        withTimeoutOrNull(13_500L) {
            val innerTubeResult = resolveViaInnerTubeParallel(videoId, excludedEngines)
            if (innerTubeResult != null) {
                cacheStream(videoId, innerTubeResult)
                return@withTimeoutOrNull innerTubeResult
            }

            val racedFallback = coroutineScope {
                val racers = buildList {
                    if ("piped" !in excludedEngines) add(async { resolveViaPipedParallel(videoId) })
                    if ("invidious" !in excludedEngines) add(async { resolveViaInvidiousParallel(videoId) })
                    if ("cobalt" !in excludedEngines) add(async { resolveViaCobaltParallel(videoId) })
                    if ("yt_embed" !in excludedEngines) add(async { resolveViaWatchPageExtract(videoId) })
                }
                if (racers.isEmpty()) return@coroutineScope null
                raceFirstNonNull(racers)
            }

            if (racedFallback != null) {
                cacheStream(videoId, racedFallback)
            }
            racedFallback
        }
    }

    private fun cacheStream(key: String, stream: ResolvedStream) {
        streamCache[key] = stream
        urlHeadersCache[stream.url] = stream
        val host = runCatching { Uri.parse(stream.url).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            hostHeadersCache[host] = stream
            if (host.contains("googlevideo.com", ignoreCase = true)) {
                hostHeadersCache["googlevideo.com"] = stream
            }
        }
    }

    private fun verifyStreamReachable(
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
        useHeadOnly: Boolean = false
    ): Boolean {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            if (useHeadOnly) {
                reqBuilder.head()
            } else {
                reqBuilder.header("Range", "bytes=0-1")
            }
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.code in listOf(200, 206, 302, 303, 307)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream probe failed: ${e.message}")
            false
        }
    }

    private fun fetchOrGetVisitorData(): String? {
        val now = System.currentTimeMillis()
        val existing = cachedVisitorData
        if (!existing.isNullOrBlank() && now - visitorDataFetchedAtMs < 20 * 60_000L) {
            return existing
        }
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20250212.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", MOBILE_UA)
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return existing
                val vd = JSONObject(resp.body?.string() ?: "{}")
                    .optJSONObject("responseContext")
                    ?.optString("visitorData", "")
                    ?.takeIf { it.isNotBlank() }
                if (vd != null) {
                    cachedVisitorData = vd
                    visitorDataFetchedAtMs = now
                }
                vd ?: existing
            }
        } catch (_: Exception) {
            existing
        }
    }

    private data class InnerTubeProfile(
        val engineId: String,
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val isEmbeddedWeb: Boolean = false,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null
    )

    private fun buildInnerTubeProfiles(): List<InnerTubeProfile> = listOf(
        InnerTubeProfile(
            engineId = "innertube_android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientIdHeader = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            isEmbeddedWeb = false,
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3"
        ),
        InnerTubeProfile(
            engineId = "innertube_tv_embedded",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientIdHeader = "85",
            userAgent = DESKTOP_UA,
            isEmbeddedWeb = true
        ),
        InnerTubeProfile(
            engineId = "innertube_ios",
            clientName = "IOS",
            clientVersion = "20.03.2",
            clientIdHeader = "5",
            userAgent = "com.google.ios.youtube/20.03.2 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X)",
            isEmbeddedWeb = false,
            osName = "iOS",
            osVersion = "18.2",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2"
        ),
        InnerTubeProfile(
            engineId = "innertube_android_music",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.29.52",
            clientIdHeader = "21",
            userAgent = "com.google.android.apps.youtube.music/7.29.52 (Linux; U; Android 14; Pixel 8) gzip",
            isEmbeddedWeb = false,
            osName = "Android",
            osVersion = "14"
        )
    )

    private suspend fun resolveViaInnerTubeParallel(
        videoId: String,
        excludedEngines: Set<String>
    ): ResolvedStream? = coroutineScope {
        val visitorData = fetchOrGetVisitorData()
        val profiles = buildInnerTubeProfiles().filter { it.engineId !in excludedEngines }
        if (profiles.isEmpty()) return@coroutineScope null

        val deferreds = profiles.map { profile ->
            async(Dispatchers.IO) {
                resolveSingleInnerTubeProfile(videoId, profile, visitorData)
            }
        }
        raceFirstNonNull(deferreds)
    }

    private fun resolveSingleInnerTubeProfile(
        videoId: String,
        profile: InnerTubeProfile,
        visitorData: String?
    ): ResolvedStream? {
        return try {
            val clientObj = JSONObject().apply {
                put("clientName", profile.clientName)
                put("clientVersion", profile.clientVersion)
                put("hl", "en")
                put("gl", "US")
                visitorData?.let { put("visitorData", it) }
                profile.osName?.let { put("osName", it) }
                profile.osVersion?.let { put("osVersion", it) }
                profile.deviceMake?.let { put("deviceMake", it) }
                profile.deviceModel?.let { put("deviceModel", it) }
            }

            val contextObj = JSONObject().apply {
                put("client", clientObj)
                if (profile.isEmbeddedWeb) {
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", "https://www.youtube.com/")
                    })
                }
            }

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextObj)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val endpointHost = if (profile.clientName == "ANDROID_MUSIC") {
                "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
            } else {
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            }

            val reqBuilder = Request.Builder()
                .url(endpointHost)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
            if (!visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
            if (profile.isEmbeddedWeb) {
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/")
            }

            probeClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: "{}")
                val status = json.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
                if (status != "OK" && status.isNotBlank()) {
                    Log.w(TAG, "InnerTube ${profile.clientName} status=$status")
                    return null
                }

                val streamingData = json.optJSONObject("streamingData") ?: return null
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                val legacy = streamingData.optJSONArray("formats")
                val hlsManifest = streamingData.optString("hlsManifestUrl", "")

                val candidates = mutableListOf<Pair<String, Int>>()
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val mime = fmt.optString("mimeType", "")
                        if (!mime.startsWith("audio/")) continue
                        val url = fmt.optString("url", "")
                        if (url.isBlank()) continue
                        val bitrate = fmt.optInt("bitrate", 0)
                        val codecBonus = when {
                            mime.contains("mp4a", ignoreCase = true) -> 60_000
                            mime.contains("opus", ignoreCase = true) -> 45_000
                            else -> 10_000
                        }
                        candidates.add(url to (bitrate + codecBonus))
                    }
                }
                if (candidates.isEmpty() && legacy != null) {
                    for (i in 0 until legacy.length()) {
                        val fmt = legacy.optJSONObject(i) ?: continue
                        val url = fmt.optString("url", "")
                        if (url.isNotBlank()) {
                            candidates.add(url to fmt.optInt("bitrate", 128_000))
                        }
                    }
                }
                if (candidates.isEmpty() && hlsManifest.isNotBlank()) {
                    candidates.add(hlsManifest to 100_000)
                }

                val cdnHeaders = if (profile.isEmbeddedWeb) {
                    mapOf("Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
                } else {
                    emptyMap()
                }

                for ((url, _) in candidates.sortedByDescending { it.second }.take(3)) {
                    if (verifyStreamReachable(url, profile.userAgent, cdnHeaders)) {
                        return ResolvedStream(
                            url = url,
                            userAgent = profile.userAgent,
                            headers = cdnHeaders,
                            expiresAtMs = System.currentTimeMillis() + (3 * 3600 * 1000L),
                            engine = profile.engineId
                        )
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube (${profile.clientName}) failed: ${e.message}")
            null
        }
    }

    private fun resolveViaWatchPageExtract(videoId: String): ResolvedStream? {
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                val jsonText = extractBalancedJson(html, "ytInitialPlayerResponse = ") ?: return null
                val json = JSONObject(jsonText)
                val streamingData = json.optJSONObject("streamingData") ?: return null
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                val hls = streamingData.optString("hlsManifestUrl", "")

                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val mime = fmt.optString("mimeType", "")
                        val url = fmt.optString("url", "")
                        if (mime.startsWith("audio/") && url.isNotBlank() && verifyStreamReachable(url, DESKTOP_UA)) {
                            return ResolvedStream(
                                url = url,
                                userAgent = DESKTOP_UA,
                                expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                engine = "yt_embed"
                            )
                        }
                    }
                }
                if (hls.isNotBlank() && verifyStreamReachable(hls, DESKTOP_UA, useHeadOnly = true)) {
                    return ResolvedStream(
                        url = hls,
                        userAgent = DESKTOP_UA,
                        expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                        engine = "yt_embed"
                    )
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Watch HTML extractor failed: ${e.message}")
            null
        }
    }

    private suspend fun resolveViaCobaltParallel(videoId: String): ResolvedStream? = coroutineScope {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val healthy = COBALT_INSTANCES.filter { isMirrorHealthy(it) }
            .ifEmpty { COBALT_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                try {
                    val payload = JSONObject().apply {
                        put("url", watchUrl)
                        put("downloadMode", "audio")
                        put("audioFormat", "best")
                        put("audioBitrate", "128")
                        put("disableMetadata", true)
                    }
                    val request = Request.Builder()
                        .url(instance)
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", MOBILE_UA)
                        .build()

                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            return@use null
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val status = json.optString("status", "")
                        if (status == "tunnel" || status == "redirect" || status == "stream") {
                            val streamUrl = json.optString("url", "")
                            if (streamUrl.isNotBlank()) {
                                return@use ResolvedStream(
                                    url = streamUrl,
                                    userAgent = MOBILE_UA,
                                    expiresAtMs = System.currentTimeMillis() + (1800 * 1000L),
                                    engine = "cobalt"
                                )
                            }
                        }
                        null
                    }
                } catch (_: Exception) {
                    markMirrorUnhealthy(instance)
                    null
                }
            }
        }

        raceFirstNonNull(deferreds)
    }

    private suspend fun resolveViaPipedParallel(videoId: String): ResolvedStream? = coroutineScope {
        val healthy = PIPED_INSTANCES.filter { isMirrorHealthy(it) }
            .ifEmpty { PIPED_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("$instance/streams/$videoId")
                        .header("Accept", "application/json")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            return@use null
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val audioStreams = json.optJSONArray("audioStreams") ?: JSONArray()
                        val candidates = mutableListOf<Pair<String, Int>>()
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url", "")
                            if (streamUrl.isBlank()) continue
                            val bitrate = stream.optInt("bitrate", 0)
                            val mime = stream.optString("mimeType", "")
                            val score = bitrate + if (mime.contains("mp4", true) || mime.contains("m4a", true)) 45_000 else 25_000
                            candidates.add(streamUrl to score)
                        }
                        for ((candidateUrl, _) in candidates.sortedByDescending { it.second }.take(2)) {
                            if (verifyStreamReachable(candidateUrl, MOBILE_UA)) {
                                return@use ResolvedStream(
                                    url = candidateUrl,
                                    userAgent = MOBILE_UA,
                                    expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                    engine = "piped"
                                )
                            }
                        }
                        val hlsUrl = json.optString("hls", "")
                        if (hlsUrl.isNotBlank() && verifyStreamReachable(hlsUrl, MOBILE_UA, useHeadOnly = true)) {
                            return@use ResolvedStream(
                                url = hlsUrl,
                                userAgent = MOBILE_UA,
                                expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                engine = "piped"
                            )
                        }
                        null
                    }
                } catch (_: Exception) {
                    markMirrorUnhealthy(instance)
                    null
                }
            }
        }

        raceFirstNonNull(deferreds)
    }

    private suspend fun resolveViaInvidiousParallel(videoId: String): ResolvedStream? = coroutineScope {
        val healthy = INVIDIOUS_INSTANCES.filter { isMirrorHealthy(it) }
            .ifEmpty { INVIDIOUS_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("$instance/api/v1/videos/$videoId")
                        .header("Accept", "application/json")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            return@use null
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val formats = json.optJSONArray("adaptiveFormats") ?: JSONArray()
                        val candidates = mutableListOf<Pair<String, Int>>()
                        for (i in 0 until formats.length()) {
                            val fmt = formats.optJSONObject(i) ?: continue
                            val type = fmt.optString("type", "")
                            if (!type.startsWith("audio/")) continue
                            val rawUrl = fmt.optString("url", "")
                            if (rawUrl.isBlank()) continue
                            val fullUrl = if (rawUrl.startsWith("/")) "$instance$rawUrl" else rawUrl
                            val bitrate = fmt.optInt("bitrate", 0)
                            candidates.add(fullUrl to bitrate)
                        }
                        for ((candidateUrl, _) in candidates.sortedByDescending { it.second }.take(2)) {
                            if (verifyStreamReachable(candidateUrl, MOBILE_UA)) {
                                return@use ResolvedStream(
                                    url = candidateUrl,
                                    userAgent = MOBILE_UA,
                                    expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                    engine = "invidious"
                                )
                            }
                        }
                        null
                    }
                } catch (_: Exception) {
                    markMirrorUnhealthy(instance)
                    null
                }
            }
        }

        raceFirstNonNull(deferreds)
    }

    private suspend fun <T> raceFirstNonNull(
        deferreds: List<kotlinx.coroutines.Deferred<T?>>
    ): T? {
        if (deferreds.isEmpty()) return null
        val pending = deferreds.toMutableList()
        var winner: T? = null
        while (pending.isNotEmpty() && winner == null) {
            val finished = select {
                pending.forEach { d -> d.onAwait { res -> d to res } }
            }
            pending.remove(finished.first)
            if (finished.second != null) winner = finished.second
        }
        pending.forEach { it.cancel() }
        return winner
    }

    // ========================================================
    // SEARCH SOURCES: InnerTube, YT HTML, Piped, Invidious, JioSaavn, Audius, Archive.org, Deezer
    // ========================================================

    private fun searchViaYtMusicInnerTube(query: String): List<OnlineTrack> {
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20250212.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "EgWKAQIIAWoMEAMQBBAJEAoQBRAV")
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", MOBILE_UA)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                return parseYtMusicSearchJson(JSONObject(body))
            }
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube search failed: ${e.message}")
        }
        return emptyList()
    }

    private fun searchViaYouTubeWebHtml(query: String): List<OnlineTrack> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                val jsonText = extractBalancedJson(html, "var ytInitialData = ")
                    ?: extractBalancedJson(html, "ytInitialData = ")
                    ?: return emptyList()
                parseYouTubeWebSearchJson(JSONObject(jsonText))
            }
        } catch (e: Exception) {
            Log.w(TAG, "YT Web HTML search failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseYouTubeWebSearchJson(root: JSONObject): List<OnlineTrack> {
        val out = mutableListOf<OnlineTrack>()
        try {
            val sections = root.optJSONObject("contents")
                ?.optJSONObject("twoColumnSearchResultsRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (s in 0 until sections.length()) {
                val items = sections.optJSONObject(s)
                    ?.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val v = items.optJSONObject(i)?.optJSONObject("videoRenderer") ?: continue
                    val videoId = v.optString("videoId", "")
                    if (videoId.isBlank()) continue
                    val title = v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                        ?: continue
                    val artist = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "YouTube")
                        ?: "YouTube"
                    val durText = v.optJSONObject("lengthText")?.optString("simpleText", "") ?: ""
                    val durSec = parseDurationStringToSeconds(durText)
                    val thumbs = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val thumb = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(thumbs.length() - 1).optString("url") else null

                    out.add(
                        OnlineTrack(
                            id = videoId,
                            title = title,
                            artist = artist,
                            durationSeconds = durSec,
                            thumbnailUrl = thumb,
                            isOfficialAudio = false,
                            sourceEngine = "youtube"
                        )
                    )
                    if (out.size >= 12) return out
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseYtMusicSearchJson(root: JSONObject): List<OnlineTrack> {
        val tracks = mutableListOf<OnlineTrack>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return emptyList()

            val sectionContents = tabs.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            for (s in 0 until sectionContents.length()) {
                val shelf = sectionContents.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue

                for (i in 0 until items.length()) {
                    val renderer = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                        ?.takeIf { it.isNotBlank() } ?: continue

                    val flexColumns = renderer.optJSONArray("flexColumns") ?: continue
                    val title = extractFlexColumnText(flexColumns.optJSONObject(0)) ?: continue
                    val subRuns = flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")

                    var artist = "Исполнитель"
                    var album = "Онлайн"
                    var durationSec = 0L

                    if (subRuns != null) {
                        val parts = mutableListOf<String>()
                        for (r in 0 until subRuns.length()) {
                            val txt = subRuns.optJSONObject(r)?.optString("text", "")?.trim() ?: ""
                            if (txt.isNotEmpty() && txt != "•" && txt != "&" && txt != ",") {
                                parts.add(txt)
                            }
                        }
                        if (parts.isNotEmpty()) artist = parts[0]
                        val durCandidate = parts.lastOrNull { it.contains(":") }
                        if (durCandidate != null) {
                            durationSec = parseDurationStringToSeconds(durCandidate)
                        }
                        if (parts.size >= 3 && !parts[1].contains(":")) {
                            album = parts[1]
                        }
                    }

                    val thumbnails = renderer.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                    val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
                        thumbnails.getJSONObject(thumbnails.length() - 1).optString("url")
                    } else null

                    tracks.add(
                        OnlineTrack(
                            id = videoId,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSeconds = durationSec,
                            thumbnailUrl = thumbUrl,
                            isOfficialAudio = true,
                            sourceEngine = "youtube"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing InnerTube search JSON: ${e.message}")
        }
        return tracks
    }

    private suspend fun searchViaPipedParallel(query: String): List<OnlineTrack> = coroutineScope {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val healthy = PIPED_INSTANCES.filter { isMirrorHealthy(it) }.ifEmpty { PIPED_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("$instance/search?q=$encodedQuery&filter=music_songs")
                        .header("Accept", "application/json")
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            return@use null
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val items = json.optJSONArray("items") ?: JSONArray()
                        val result = mutableListOf<OnlineTrack>()
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            if (item.optString("type", "") != "stream") continue
                            val rawUrl = item.optString("url", "")
                            val videoId = rawUrl.substringAfter("watch?v=", "").substringBefore("&")
                            if (videoId.isEmpty()) continue
                            result.add(
                                OnlineTrack(
                                    id = videoId,
                                    title = item.optString("title", "Неизвестный трек"),
                                    artist = item.optString("uploaderName", "Неизвестный исполнитель"),
                                    durationSeconds = item.optLong("duration", 0L),
                                    thumbnailUrl = item.optString("thumbnail", null),
                                    isOfficialAudio = true,
                                    sourceEngine = "youtube"
                                )
                            )
                        }
                        result.takeIf { it.isNotEmpty() }
                    }
                } catch (_: Exception) {
                    markMirrorUnhealthy(instance)
                    null
                }
            }
        }
        raceFirstNonNull(deferreds) ?: emptyList()
    }

    private suspend fun searchViaInvidiousParallel(query: String): List<OnlineTrack> = coroutineScope {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val healthy = INVIDIOUS_INSTANCES.filter { isMirrorHealthy(it) }.ifEmpty { INVIDIOUS_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("$instance/api/v1/search?q=$encodedQuery&type=video")
                        .header("Accept", "application/json")
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            return@use null
                        }
                        val array = JSONArray(response.body?.string() ?: "[]")
                        val result = mutableListOf<OnlineTrack>()
                        for (i in 0 until minOf(array.length(), 15)) {
                            val item = array.optJSONObject(i) ?: continue
                            val videoId = item.optString("videoId", "")
                            if (videoId.isEmpty()) continue
                            val thumbs = item.optJSONArray("videoThumbnails")
                            val thumbUrl = if (thumbs != null && thumbs.length() > 0) thumbs.getJSONObject(0).optString("url") else null
                            result.add(
                                OnlineTrack(
                                    id = videoId,
                                    title = item.optString("title", "Трек"),
                                    artist = item.optString("author", "Исполнитель"),
                                    durationSeconds = item.optLong("lengthSeconds", 0L),
                                    thumbnailUrl = thumbUrl,
                                    sourceEngine = "youtube"
                                )
                            )
                        }
                        result.takeIf { it.isNotEmpty() }
                    }
                } catch (_: Exception) {
                    markMirrorUnhealthy(instance)
                    null
                }
            }
        }
        raceFirstNonNull(deferreds) ?: emptyList()
    }

    private fun searchViaSaavn(query: String): List<OnlineTrack> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        for (mirror in SAAVN_MIRRORS) {
            if (!isMirrorHealthy(mirror)) continue
            try {
                val req = Request.Builder()
                    .url("$mirror/api/search/songs?query=$encoded&limit=10")
                    .header("Accept", "application/json")
                    .header("User-Agent", MOBILE_UA)
                    .build()
                probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        markMirrorUnhealthy(mirror)
                        return@use
                    }
                    val root = JSONObject(resp.body?.string() ?: "{}")
                    val results = root.optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
                    val tracks = mutableListOf<OnlineTrack>()
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        val title = item.optString("name", "").replace("&quot;", "\"").replace("&amp;", "&")
                        if (id.isBlank() || title.isBlank()) continue
                        val durationSec = item.optLong("duration", 0L)
                        val artistNames = item.optJSONObject("artists")
                            ?.optJSONArray("primary")
                            ?.let { arr ->
                                (0 until arr.length()).mapNotNull { idx ->
                                    arr.optJSONObject(idx)?.optString("name")?.takeIf { it.isNotBlank() }
                                }.joinToString(", ")
                            }?.takeIf { it.isNotBlank() } ?: "Исполнитель"

                        val images = item.optJSONArray("image")
                        val thumb = if (images != null && images.length() > 0) {
                            images.getJSONObject(images.length() - 1).optString("url")
                        } else null

                        val downloads = item.optJSONArray("downloadUrl")
                        val streamUrl = if (downloads != null && downloads.length() > 0) {
                            downloads.getJSONObject(downloads.length() - 1).optString("url")
                        } else null

                        if (!streamUrl.isNullOrBlank()) {
                            tracks.add(
                                OnlineTrack(
                                    id = id,
                                    title = title,
                                    artist = artistNames,
                                    album = item.optJSONObject("album")?.optString("name", "JioSaavn") ?: "JioSaavn",
                                    durationSeconds = durationSec,
                                    thumbnailUrl = thumb,
                                    isOfficialAudio = true,
                                    sourceEngine = "saavn",
                                    directStreamUrl = streamUrl
                                )
                            )
                        }
                    }
                    if (tracks.isNotEmpty()) return tracks
                }
            } catch (_: Exception) {
                markMirrorUnhealthy(mirror)
            }
        }
        return emptyList()
    }

    private fun resolveHealthyAudiusHost(): String {
        val now = System.currentTimeMillis()
        val cached = cachedAudiusHost
        if (!cached.isNullOrBlank() && now - audiusHostFetchedAtMs < 30 * 60_000L) {
            return cached
        }
        return try {
            val req = Request.Builder()
                .url("https://api.audius.co")
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "https://discoveryprovider.audius.co"
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data")
                val picked = if (data != null && data.length() > 0) {
                    data.optString(0, "").trimEnd('/')
                } else ""
                val host = picked.ifBlank { "https://discoveryprovider.audius.co" }
                cachedAudiusHost = host
                audiusHostFetchedAtMs = now
                host
            }
        } catch (_: Exception) {
            cached ?: "https://discoveryprovider.audius.co"
        }
    }

    private fun buildAudiusStreamUrl(id: String): String {
        val host = resolveHealthyAudiusHost()
        return "$host/v1/tracks/$id/stream?app_name=imux"
    }

    private fun searchViaAudius(query: String): List<OnlineTrack> {
        return try {
            val host = resolveHealthyAudiusHost()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("$host/v1/tracks/search?query=$encoded&app_name=imux")
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = JSONObject(resp.body?.string() ?: "{}")
                val data = root.optJSONArray("data") ?: return emptyList()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until minOf(data.length(), 10)) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val title = item.optString("title", "")
                    if (id.isBlank() || title.isBlank()) continue
                    val artist = item.optJSONObject("user")?.optString("name", "Audius Artist") ?: "Audius Artist"
                    val thumb = item.optJSONObject("artwork")?.optString("480x480")
                        ?: item.optJSONObject("artwork")?.optString("150x150")
                    val dur = item.optLong("duration", 0L)
                    val streamUrl = "$host/v1/tracks/$id/stream?app_name=imux"
                    tracks.add(
                        OnlineTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = "Audius",
                            durationSeconds = dur,
                            thumbnailUrl = thumb,
                            isOfficialAudio = true,
                            sourceEngine = "audius",
                            directStreamUrl = streamUrl
                        )
                    )
                }
                tracks
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audius search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Independent open streaming source #3: Internet Archive Audio catalog (key-free, open MP3/OGG streams).
     */
    private fun searchViaInternetArchive(query: String): List<OnlineTrack> {
        return try {
            val sanitized = query.trim().replace("\"", "")
            val rawQuery = "mediatype:audio AND ($sanitized)"
            val encoded = URLEncoder.encode(rawQuery, "UTF-8")
            val req = Request.Builder()
                .url("https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,creator&rows=8&output=json")
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = JSONObject(resp.body?.string() ?: "{}")
                val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val id = doc.optString("identifier", "")
                    val title = doc.optString("title", "")
                    if (id.isBlank() || title.isBlank()) continue
                    val creatorVal = doc.opt("creator")
                    val artist = when (creatorVal) {
                        is JSONArray -> creatorVal.optString(0, "Internet Archive")
                        is String -> creatorVal
                        else -> "Internet Archive"
                    }.ifBlank { "Internet Archive" }

                    tracks.add(
                        OnlineTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = "Archive.org",
                            durationSeconds = 0L,
                            thumbnailUrl = "https://archive.org/services/img/$id",
                            isOfficialAudio = false,
                            sourceEngine = "archive",
                            directStreamUrl = null
                        )
                    )
                }
                tracks
            }
        } catch (e: Exception) {
            Log.w(TAG, "Archive.org search failed: ${e.message}")
            emptyList()
        }
    }

    private fun resolveArchiveStreamUrl(identifier: String): String? {
        archiveFilesCache[identifier]?.let { return it }
        return try {
            val req = Request.Builder()
                .url("https://archive.org/metadata/$identifier/files")
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val result = JSONObject(resp.body?.string() ?: "{}").optJSONArray("result") ?: return null
                var mp3Candidate: String? = null
                var oggCandidate: String? = null
                for (i in 0 until result.length()) {
                    val fileObj = result.optJSONObject(i) ?: continue
                    val name = fileObj.optString("name", "")
                    val lower = name.lowercase()
                    if (lower.endsWith(".mp3") && mp3Candidate == null) {
                        mp3Candidate = name
                    } else if ((lower.endsWith(".ogg") || lower.endsWith(".m4a")) && oggCandidate == null) {
                        oggCandidate = name
                    }
                }
                val chosen = mp3Candidate ?: oggCandidate ?: return null
                val encodedFile = Uri.encode(chosen, "/")
                val url = "https://archive.org/download/$identifier/$encodedFile"
                archiveFilesCache[identifier] = url
                url
            }
        } catch (e: Exception) {
            Log.w(TAG, "Archive file lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Independent open catalog source #4: Deezer public API (cross-resolved to full-length audio upon playback,
     * with HQ 30s preview fallback if cross-resolution fails).
     */
    private fun searchViaDeezer(query: String): List<OnlineTrack> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=8")
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data") ?: return emptyList()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: continue
                    val title = item.optString("title", "")
                    if (title.isBlank()) continue
                    val artist = item.optJSONObject("artist")?.optString("name", "Исполнитель") ?: "Исполнитель"
                    val albumObj = item.optJSONObject("album")
                    val album = albumObj?.optString("title", "Deezer") ?: "Deezer"
                    val thumb = albumObj?.optString("cover_big") ?: albumObj?.optString("cover_medium")
                    val dur = item.optLong("duration", 0L)
                    tracks.add(
                        OnlineTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSeconds = dur,
                            thumbnailUrl = thumb,
                            isOfficialAudio = true,
                            sourceEngine = "deezer",
                            directStreamUrl = null
                        )
                    )
                }
                tracks
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deezer search failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractBalancedJson(html: String, marker: String): String? {
        val startIdx = html.indexOf(marker)
        if (startIdx < 0) return null
        val jsonStart = html.indexOf('{', startIdx + marker.length)
        if (jsonStart < 0) return null

        var depth = 0
        var inStr = false
        var escape = false
        for (i in jsonStart until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inStr = !inStr
                continue
            }
            if (!inStr) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return html.substring(jsonStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractFlexColumnText(columnObj: JSONObject?): String? {
        val runs = columnObj?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseDurationStringToSeconds(durStr: String): Long {
        val parts = durStr.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> 0L
        }
    }

    internal fun scoreCandidate(
        candidate: OnlineTrack,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long
    ): Int {
        var score = 0
        val candTitleLower = candidate.title.lowercase()
        val candArtistLower = candidate.artist.lowercase()
        val targetTitleLower = targetTitle.lowercase()
        val targetArtistLower = targetArtist.lowercase()

        val titleTokens = targetTitleLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (candTitleLower == targetTitleLower) {
            score += 60
        } else if (candTitleLower.contains(targetTitleLower)) {
            score += 45
        } else {
            val matchedTokens = titleTokens.count { candTitleLower.contains(it) }
            score += matchedTokens * 15
        }

        val artistTokens = targetArtistLower.split(Regex("[,&\\s]+")).filter { it.length >= 2 }
        val combinedMeta = "$candArtistLower $candTitleLower"
        if (candArtistLower.contains(targetArtistLower)) {
            score += 45
        } else {
            val matchedArtist = artistTokens.count { combinedMeta.contains(it) }
            score += matchedArtist * 18
        }

        if (candidate.isOfficialAudio) score += 25
        if (candArtistLower.endsWith("- topic")) score += 20
        if (candTitleLower.contains("official audio")) score += 10
        if (candidate.directStreamUrl != null) score += 10

        if (expectedDurationMs > 10_000L && candidate.durationMs > 10_000L) {
            val diffSec = abs(candidate.durationMs - expectedDurationMs) / 1000L
            score += when {
                diffSec <= 3L -> 65
                diffSec <= 8L -> 40
                diffSec <= 15L -> 15
                diffSec <= 25L -> -10
                else -> -55
            }
        }

        for (mod in UNWANTED_MODIFIERS) {
            if (candTitleLower.contains(mod) && !targetTitleLower.contains(mod)) {
                score -= 50
            }
        }
        if (candTitleLower.contains("live") && !targetTitleLower.contains("live")) {
            score -= 35
        }

        return score
    }

    private fun cleanQueryPart(input: String): String {
        return input
            .replace(Regex("\\s*\\((feat|ft|with)\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(feat|ft|with)\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
