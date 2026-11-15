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
    val qualityBadge: String = defaultQualityBadgeForSource(sourceEngine),
    val isPreviewOnly: Boolean = false
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
        val safeHost = if (sourceEngine in setOf(
                "soundcloud", "audius", "saavn", "bandcamp", "archive",
                "jamendo", "bilibili", "deezer", "itunes", "spotify"
            )
        ) {
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
    "soundcloud" -> "SoundCloud"
    "audius" -> "Audius"
    "saavn" -> "JioSaavn"
    "jamendo" -> "Jamendo"
    "bilibili" -> "Bilibili"
    "bandcamp" -> "Bandcamp"
    "archive" -> "Archive"
    "deezer" -> "Deezer"
    "itunes" -> "iTunes"
    "spotify" -> "Spotify"
    else -> "Онлайн"
}

fun defaultQualityBadgeForSource(sourceEngine: String): String = when (sourceEngine.lowercase()) {
    "youtube" -> "AAC/Opus"
    "soundcloud" -> "MP3/HLS"
    "audius" -> "MP3 320k"
    "saavn" -> "M4A 320k"
    "jamendo" -> "CC MP3"
    "bilibili" -> "DASH M4A"
    "bandcamp" -> "MP3 128k"
    "archive" -> "MP3/OGG"
    "deezer" -> "30с / Cross"
    "itunes" -> "30с AAC"
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

    @Volatile
    private var cachedVisitorData: String? = null
    @Volatile
    private var visitorDataFetchedAtMs: Long = 0L

    fun init(context: android.content.Context) {
        WebViewYouTubeHelper.init(context)
    }

    companion object {
        private const val TAG = "YouTubeStreamManager"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MOBILE_UA = NetworkClient.BROWSER_UA
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        val SOURCE_FILTERS = listOf(
            OnlineSourceOption("all", "Все (полные)", "MELD"),
            OnlineSourceOption("youtube", "YouTube Music", "YTM"),
            OnlineSourceOption("soundcloud", "SoundCloud", "HLS/MP3"),
            OnlineSourceOption("jamendo", "Jamendo", "CC MP3"),
            OnlineSourceOption("bilibili", "Bilibili", "M4A"),
            OnlineSourceOption("audius", "Audius", "320k"),
            OnlineSourceOption("saavn", "JioSaavn", "320k"),
            OnlineSourceOption("bandcamp", "Bandcamp", "128k"),
            OnlineSourceOption("archive", "Archive.org", "OGG/MP3"),
            OnlineSourceOption("deezer", "Deezer (30с)", "30с"),
            OnlineSourceOption("itunes", "iTunes (30с)", "30с")
        )

        val DELIVERY_ENGINES = listOf(
            DeliveryEngineOption("auto", "Авто (Meld X)"),
            DeliveryEngineOption("innertube_android_vr", "YT VR"),
            DeliveryEngineOption("innertube_ios", "YT iOS"),
            DeliveryEngineOption("innertube_android_music", "YT Music"),
            DeliveryEngineOption("webview_decipher", "YT WebView JS"),
            DeliveryEngineOption("newpipe", "YT NewPipe"),
            DeliveryEngineOption("innertube_web_embedded", "YT Embed"),
            DeliveryEngineOption("innertube_tv_embedded", "YT TV"),
            DeliveryEngineOption("innertube_mweb", "YT MWeb"),
            DeliveryEngineOption("piped", "Piped"),
            DeliveryEngineOption("invidious", "Invidious"),
            DeliveryEngineOption("cobalt", "Cobalt")
        )

        private val COBALT_INSTANCES = listOf(
            "https://cobalt-api.kwiatekmiki.com",
            "https://co.eepy.today",
            "https://cobalt-api.meowing.de"
        )

        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.adminforge.de",
            "https://pipedapi.r4fo.com",
            "https://pipedapi.darkness.services",
            "https://pipedapi.nosebs.ru"
        )

        private val INVIDIOUS_INSTANCES = listOf(
            "https://invidious.privacyredirect.com",
            "https://invidious.jing.rocks",
            "https://inv.nadeko.net",
            "https://iv.datura.network"
        )

        fun formatEngineDisplay(engineId: String): String = when (engineId.lowercase()) {
            "newpipe" -> "YouTube • NewPipe"
            "webview_decipher" -> "YouTube • WebView JS"
            "innertube_web_embedded" -> "YouTube • Embed"
            "innertube_mweb" -> "YouTube • MWeb"
            "innertube_android_vr" -> "YouTube • VR"
            "innertube_ios" -> "YouTube • iOS"
            "innertube_tv_embedded" -> "YouTube • TV"
            "innertube_android_music" -> "YouTube • Music"
            "innertube_android_testsuite" -> "YouTube • Suite"
            "piped" -> "Piped Mirror"
            "invidious" -> "Invidious Mirror"
            "cobalt" -> "Cobalt Tunnel"
            "yt_embed" -> "YouTube • Web"
            "soundcloud" -> "SoundCloud • MP3/HLS"
            "jamendo" -> "Jamendo • CC MP3"
            "bilibili" -> "Bilibili • DASH M4A"
            "audius" -> "Audius • MP3"
            "saavn" -> "JioSaavn • 320k"
            "bandcamp" -> "Bandcamp • 128k"
            "archive" -> "Archive.org"
            "deezer" -> "Deezer"
            "itunes" -> "iTunes • 30с"
            "local" -> "Локальный"
            else -> engineId
        }
    }

    private fun isMirrorHealthy(mirror: String): Boolean {
        val blockedUntil = unhealthyMirrorsUntil[mirror] ?: return true
        return System.currentTimeMillis() >= blockedUntil
    }

    private fun markMirrorUnhealthy(mirror: String, cooldownMs: Long = 120_000L) {
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
     * Sources: YouTube, SoundCloud, Audius, JioSaavn Direct, Bandcamp, Archive.org, Deezer, iTunes.
     */
    suspend fun search(query: String, sourceFilter: String = "all"): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val normalizedFilter = sourceFilter.lowercase().trim()
        withTimeoutOrNull(14_500L) {
            when (normalizedFilter) {
                "youtube" -> searchYouTubeAggregated(trimmed)
                "soundcloud" -> FreeCatalogProviders.searchSoundCloud(trimmed)
                "jamendo" -> FreeCatalogProviders.searchJamendo(trimmed)
                "bilibili" -> FreeCatalogProviders.searchBilibili(trimmed)
                "audius" -> FreeCatalogProviders.searchAudiusPool(trimmed)
                "saavn" -> FreeCatalogProviders.searchJioSaavnDirect(trimmed)
                "bandcamp" -> FreeCatalogProviders.searchBandcamp(trimmed)
                "archive" -> FreeCatalogProviders.searchArchiveMusic(trimmed)
                "deezer" -> FreeCatalogProviders.searchDeezer(trimmed)
                "itunes" -> FreeCatalogProviders.searchItunes(trimmed)
                else -> {
                    coroutineScope {
                        val ytDeferred = async { searchYouTubeAggregated(trimmed) }
                        val scDeferred = async { FreeCatalogProviders.searchSoundCloud(trimmed) }
                        val jamendoDeferred = async { FreeCatalogProviders.searchJamendo(trimmed) }
                        val bilibiliDeferred = async { FreeCatalogProviders.searchBilibili(trimmed) }
                        val audiusDeferred = async { FreeCatalogProviders.searchAudiusPool(trimmed) }
                        val saavnDeferred = async { FreeCatalogProviders.searchJioSaavnDirect(trimmed) }
                        val bandcampDeferred = async { FreeCatalogProviders.searchBandcamp(trimmed) }
                        val archiveDeferred = async { FreeCatalogProviders.searchArchiveMusic(trimmed) }

                        val buckets = awaitAll(
                            ytDeferred,
                            scDeferred,
                            jamendoDeferred,
                            bilibiliDeferred,
                            audiusDeferred,
                            saavnDeferred,
                            bandcampDeferred,
                            archiveDeferred
                        )
                        interleaveTracks(buckets)
                    }
                }
            }
        } ?: emptyList()
    }

    private suspend fun searchYouTubeAggregated(query: String): List<OnlineTrack> = coroutineScope {
        // Do not discard web results just because YouTube Music returned something:
        // the exact recording is often ranked below the first YTM shelf.
        val ytMusic = async(Dispatchers.IO) { searchViaYtMusicInnerTube(query) }
        val webResults = async(Dispatchers.IO) { searchViaYouTubeWebHtml(query) }
        val primary = (ytMusic.await() + webResults.await())
            .distinctBy { "${it.sourceEngine}:${it.id}" }

        if (primary.size >= 8) return@coroutineScope primary

        // Mirrors are a secondary catalog, not a replacement for the native results.
        val piped = async(Dispatchers.IO) { searchViaPipedParallel(query) }
        val invidious = async(Dispatchers.IO) { searchViaInvidiousParallel(query) }
        (primary + piped.await() + invidious.await())
            .distinctBy { "${it.sourceEngine}:${it.id}" }
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
     * Finds strictly relevant matches scored by title, artist, and duration proximity.
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
            val scDeferred = async { FreeCatalogProviders.searchSoundCloud(query) }
            val jamendoDeferred = async { FreeCatalogProviders.searchJamendo(query) }
            val bilibiliDeferred = async { FreeCatalogProviders.searchBilibili(query) }
            val saavnDeferred = async { FreeCatalogProviders.searchJioSaavnDirect(query) }
            val audiusDeferred = async { FreeCatalogProviders.searchAudiusPool(query) }
            val bandcampDeferred = async { FreeCatalogProviders.searchBandcamp(query) }
            val archiveDeferred = async { FreeCatalogProviders.searchArchiveMusic(query) }
            (ytDeferred.await() + ytPlainDeferred.await() + scDeferred.await() +
                jamendoDeferred.await() + bilibiliDeferred.await() +
                saavnDeferred.await() + audiusDeferred.await() +
                bandcampDeferred.await() + archiveDeferred.await())
                .distinctBy { "${it.sourceEngine}:${it.id}" }
        }

        val accepted = candidates
            .map { it to RelevanceScorer.assess(it, cleanTitle, cleanArtist, expectedDurationMs) }
            .filter { it.second.isAcceptable }
            .sortedByDescending { it.second.score }

        val best = accepted.firstOrNull()?.first
        if (best != null) {
            matchCache[cacheKey] = best
        }
        best
    }

    /**
     * Resolves a direct playable audio stream URL AND matched duration + active engine for track metadata,
     * automatically iterating through 2-3 similar YouTube videos if the top match fails HTTP 403.
     */
    suspend fun resolveTrackStreamForMetadata(
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long = 0L,
        excludedEngines: Set<String> = emptySet(),
        preferredEngine: String? = null,
        allowPreviewFallback: Boolean = false,
        excludedVideoIds: Set<String> = emptySet()
    ): Triple<String, Long, String>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000L) {
            val cleanTitle = cleanQueryPart(targetTitle)
            val cleanArtist = cleanQueryPart(targetArtist)
            val query = "$cleanArtist $cleanTitle".trim().ifBlank { targetTitle }

            val candidates = coroutineScope {
                // Meld-style multi-query matching: search artist/title in both orders and
                // quote the title so short Russian titles are not buried by broad results.
                val youtubeQueries = linkedSetOf(
                    query,
                    "$cleanTitle $cleanArtist".trim(),
                    "\"$cleanTitle\" $cleanArtist".trim(),
                    "$query audio"
                ).filter { it.isNotBlank() }.distinct()
                val youtubeSearches = youtubeQueries.map { q ->
                    async(Dispatchers.IO) { searchYouTubeAggregated(q) }
                }
                val scD = async { FreeCatalogProviders.searchSoundCloud(query) }
                val jamendoD = async { FreeCatalogProviders.searchJamendo(query) }
                val bilibiliD = async { FreeCatalogProviders.searchBilibili(query) }
                val saavnD = async { FreeCatalogProviders.searchJioSaavnDirect(query) }
                val audiusD = async { FreeCatalogProviders.searchAudiusPool(query) }
                val bandcampD = async { FreeCatalogProviders.searchBandcamp(query) }
                val archiveD = async { FreeCatalogProviders.searchArchiveMusic(query) }
                val deezerD = async {
                    if (allowPreviewFallback) FreeCatalogProviders.searchDeezer(query) else emptyList()
                }
                val itunesD = async {
                    if (allowPreviewFallback) FreeCatalogProviders.searchItunes(query) else emptyList()
                }
                (youtubeSearches.awaitAll().flatten() + scD.await() + jamendoD.await() + bilibiliD.await() +
                    saavnD.await() + audiusD.await() + bandcampD.await() + archiveD.await() +
                    deezerD.await() + itunesD.await())
                    .distinctBy { "${it.sourceEngine}:${it.id}" }
            }

            val scored = candidates.mapNotNull { cand ->
                if (cand.sourceEngine == "youtube" && cand.id in excludedVideoIds) {
                    return@mapNotNull null
                }
                val assessment = RelevanceScorer.assess(cand, cleanTitle, cleanArtist, expectedDurationMs)
                if (!assessment.isAcceptable) {
                    SourceDiagnosticsLog.record(
                        SourceAttemptLog(
                            sourceId = cand.sourceEngine,
                            displayName = displayNameForSource(cand.sourceEngine),
                            stage = "relevance",
                            status = SourceAttemptStatus.RELEVANCE_REJECTED,
                            detail = "${cand.artist} — ${cand.title}: ${assessment.reason}"
                        )
                    )
                    null
                } else {
                    cand to assessment.score
                }
            }.sortedByDescending { it.second }.map { it.first }

            var ytVideosTried = 0
            for (cand in scored) {
                if (cand.sourceEngine in excludedEngines) continue
                if (cand.isPreviewOnly && !allowPreviewFallback) continue

                when (cand.sourceEngine) {
                    "youtube" -> {
                        if (ytVideosTried >= 3) continue
                        ytVideosTried++
                        val resolved = resolveStreamResult(cand.id, excludedEngines, preferredEngine)
                        if (resolved != null) {
                            return@withTimeoutOrNull Triple(
                                resolved.url,
                                if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                resolved.engine
                            )
                        }
                    }
                    "soundcloud" -> {
                        val scResolved = FreeCatalogProviders.resolveSoundCloudStream(cand.id)
                        if (scResolved != null) {
                            cacheStream("sc:${cand.id}", scResolved)
                            return@withTimeoutOrNull Triple(
                                scResolved.url,
                                if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                "soundcloud"
                            )
                        }
                    }
                    "bilibili" -> {
                        val biliResolved = FreeCatalogProviders.resolveBilibiliStream(cand.id)
                        if (biliResolved != null) {
                            cacheStream("bilibili:${cand.id}", biliResolved)
                            return@withTimeoutOrNull Triple(
                                biliResolved.url,
                                if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                "bilibili"
                            )
                        }
                    }
                    else -> {
                        val resolvedUrl = resolveDirectTrackPlayableUrl(cand) ?: continue
                        val resolved = ResolvedStream(
                            url = resolvedUrl,
                            userAgent = MOBILE_UA,
                            expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                            engine = cand.sourceEngine
                        )
                        cacheStream("${cand.sourceEngine}:${cand.id}", resolved)
                        return@withTimeoutOrNull Triple(
                            resolvedUrl,
                            if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                            cand.sourceEngine
                        )
                    }
                }
            }
            null
        }
    }

    /**
     * Collects working alternatives across sources for the diagnostics/switcher UI.
     */
    suspend fun findRelevantAlternatives(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L
    ): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val cleanTitle = cleanQueryPart(title)
        val cleanArtist = cleanQueryPart(artist)
        val query = "$cleanArtist $cleanTitle".trim().ifBlank { title }
        val all = search(query, "all")
        all.map { track ->
            track to RelevanceScorer.assess(track, cleanTitle, cleanArtist, expectedDurationMs)
        }
            .filter { it.second.isAcceptable }
            .sortedByDescending { it.second.score }
            .map { it.first }
            .distinctBy { "${it.sourceEngine}:${it.id}" }
            .take(10)
    }

    suspend fun resolveDirectTrackPlayableUrl(track: OnlineTrack): String? = withContext(Dispatchers.IO) {
        when (track.sourceEngine) {
            "soundcloud" -> {
                val resolved = FreeCatalogProviders.resolveSoundCloudStream(track.id)
                if (resolved != null) {
                    cacheStream("sc:${track.id}", resolved)
                    resolved.url
                } else null
            }
            "bilibili" -> {
                val resolved = FreeCatalogProviders.resolveBilibiliStream(track.id)
                if (resolved != null) {
                    cacheStream("bilibili:${track.id}", resolved)
                    resolved.url
                } else null
            }
            "archive" -> FreeCatalogProviders.resolveArchiveStreamUrl(track.id) ?: track.directStreamUrl
            "audius" -> {
                val resolved = FreeCatalogProviders.resolveAudiusStreamWithFailover(track.id)
                if (resolved != null) {
                    cacheStream("audius:${track.id}", resolved)
                    resolved.url
                } else track.directStreamUrl
            }
            "deezer" -> {
                // First try cross-resolving full track via YouTube/SoundCloud/Jamendo/Bilibili/Audius/Saavn; fallback cleanly to 30s preview
                val fullMatch = resolveTrackStreamForMetadata(
                    targetTitle = track.title,
                    targetArtist = track.artist,
                    expectedDurationMs = track.durationMs,
                    excludedEngines = setOf("deezer", "itunes"),
                    allowPreviewFallback = false
                )
                fullMatch?.first ?: track.directStreamUrl
            }
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
            if (host.contains("bilivideo.", ignoreCase = true)) {
                hostHeadersCache["bilivideo.com"]?.let { return it.userAgent to it.headers }
            }
        }
        return null
    }

    suspend fun resolveAlternateStream(
        failedUrl: String,
        videoId: String?,
        title: String,
        artist: String,
        expectedDurationMs: Long,
        excludedVideoIds: Set<String> = emptySet()
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        val failedMeta = urlHeadersCache.remove(failedUrl)
        val excludedEnginesForSameVideo = if (failedMeta != null) setOf(failedMeta.engine) else emptySet()

        if (!videoId.isNullOrBlank() && videoId !in excludedVideoIds) {
            streamCache.remove(videoId)

            // On a playback-time 403, prefer a fresh URL intercepted from YouTube's
            // own embed player. This supplies a current CDN signature and PoToken,
            // rather than immediately retrying another possibly stale extracted URL.
            if ("webview_decipher" !in excludedEnginesForSameVideo) {
                val intercepted = WebViewYouTubeHelper.resolveViaWebViewEmbedIntercept(videoId)
                if (intercepted != null && intercepted.url != failedUrl) {
                    cacheStream(videoId, intercepted)
                    return@withContext intercepted
                }
            }

            val direct = resolveStreamResult(videoId, excludedEnginesForSameVideo, null)
            if (direct != null && direct.url != failedUrl) return@withContext direct
        }

        // Video ID was blocked/403 or exhausted: try up to 3 other similar YouTube videos + free sources
        val nextExcludedIds = buildSet {
            addAll(excludedVideoIds)
            if (!videoId.isNullOrBlank()) add(videoId)
        }
        val matched = resolveTrackStreamForMetadata(
            targetTitle = title,
            targetArtist = artist,
            expectedDurationMs = expectedDurationMs,
            excludedEngines = emptySet(),
            allowPreviewFallback = false,
            excludedVideoIds = nextExcludedIds
        )
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

        // Explicit engine override
        if (!preferredEngine.isNullOrBlank() && preferredEngine != "auto") {
            val forced = when {
                preferredEngine == "webview_decipher" -> WebViewYouTubeHelper.resolveViaWebViewEmbedIntercept(videoId)
                preferredEngine == "newpipe" -> NewPipeYouTubeResolver.resolveAudioStream(videoId)
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

        withTimeoutOrNull(18_000L) {
            // 1. Race verified NewPipeExtractor + modern InnerTubeX clients (with signatureCipher & n-param JS decipher)
            val primaryResult = coroutineScope {
                val racers = buildList {
                    if ("newpipe" !in excludedEngines) {
                        add(async(Dispatchers.IO) { NewPipeYouTubeResolver.resolveAudioStream(videoId) })
                    }
                    add(async(Dispatchers.IO) { resolveViaInnerTubeParallel(videoId, excludedEngines) })
                }
                raceFirstNonNull(racers)
            }
            if (primaryResult != null) {
                cacheStream(videoId, primaryResult)
                return@withTimeoutOrNull primaryResult
            }

            // 2. Fallback race: Headless WebView embed intercept (PoToken + ciphered googlevideo) + Watch HTML + Mirrors
            val webViewFallback = if ("webview_decipher" !in excludedEngines) {
                WebViewYouTubeHelper.resolveViaWebViewEmbedIntercept(videoId)
            } else null
            if (webViewFallback != null) {
                cacheStream(videoId, webViewFallback)
                return@withTimeoutOrNull webViewFallback
            }

            val racedFallback = coroutineScope {
                val racers = buildList {
                    if ("yt_embed" !in excludedEngines) add(async { resolveViaWatchPageExtract(videoId) })
                    if ("piped" !in excludedEngines) add(async { resolveViaPipedParallel(videoId) })
                    if ("invidious" !in excludedEngines) add(async { resolveViaInvidiousParallel(videoId) })
                    if ("cobalt" !in excludedEngines) add(async { resolveViaCobaltParallel(videoId) })
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

    /**
     * Live health probe across all free online sources so the user sees exact HTTP status & ping latency.
     */
    suspend fun testAllSourcesHealth(): List<SourceHealthStatus> = withContext(Dispatchers.IO) {
        coroutineScope {
            val checks = listOf(
                async {
                    pingEndpoint(
                        sourceId = "youtube",
                        displayName = "YouTube",
                        badge = "InnerTubeX / WV",
                        url = "https://www.youtube.com/generate_204",
                        headers = mapOf("User-Agent" to MOBILE_UA)
                    )
                },
                async {
                    val t0 = System.currentTimeMillis()
                    val cid = FreeCatalogProviders.getOrFetchSoundCloudClientId()
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    if (!cid.isNullOrBlank()) {
                        SourceHealthStatus("soundcloud", "SoundCloud", "MP3/HLS", true, 200, dt, "client_id активен")
                    } else {
                        SourceHealthStatus("soundcloud", "SoundCloud", "MP3/HLS", false, null, dt, "Не получен client_id")
                    }
                },
                async {
                    pingEndpoint(
                        sourceId = "jamendo",
                        displayName = "Jamendo",
                        badge = "CC MP3",
                        url = "https://api.jamendo.com/v3.0/tracks/?client_id=709fa152&format=json&limit=1"
                    )
                },
                async {
                    pingEndpoint(
                        sourceId = "bilibili",
                        displayName = "Bilibili",
                        badge = "DASH M4A",
                        url = "https://api.bilibili.com/x/web-interface/nav",
                        headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to MOBILE_UA)
                    )
                },
                async {
                    val pool = FreeCatalogProviders.getAudiusDiscoveryPool()
                    val node = pool.firstOrNull() ?: "https://discoveryprovider.audius.co"
                    pingEndpoint("audius", "Audius", "${pool.size} нод", "$node/health_check")
                },
                async {
                    pingEndpoint(
                        sourceId = "saavn",
                        displayName = "JioSaavn",
                        badge = "320k Direct",
                        url = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&query=test",
                        headers = mapOf("Referer" to "https://www.jiosaavn.com/")
                    )
                },
                async {
                    pingEndpoint("bandcamp", "Bandcamp", "MP3 128k", "https://bandcamp.com/search?q=music&item_type=t")
                },
                async {
                    pingEndpoint("archive", "Archive.org", "OGG/MP3", "https://archive.org/advancedsearch.php?q=mediatype:audio&rows=1&output=json")
                },
                async {
                    pingEndpoint("deezer", "Deezer", "30с API", "https://api.deezer.com/search?q=music&limit=1")
                },
                async {
                    pingEndpoint("itunes", "Apple iTunes", "30с AAC", "https://itunes.apple.com/search?term=music&limit=1")
                }
            )
            checks.awaitAll()
        }
    }

    private fun pingEndpoint(
        sourceId: String,
        displayName: String,
        badge: String,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): SourceHealthStatus {
        val t0 = System.currentTimeMillis()
        return try {
            val reqB = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqB.header(k, v) }
            probeClient.newCall(reqB.build()).execute().use { resp ->
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                SourceHealthStatus(
                    sourceId = sourceId,
                    displayName = displayName,
                    badge = badge,
                    isHealthy = resp.code in 200..399,
                    httpCode = resp.code,
                    latencyMs = dt,
                    detail = if (resp.code in 200..399) "Доступен (${dt}мс)" else "HTTP ${resp.code} ${resp.message}"
                )
            }
        } catch (e: Exception) {
            val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            SourceHealthStatus(
                sourceId = sourceId,
                displayName = displayName,
                badge = badge,
                isHealthy = false,
                httpCode = null,
                latencyMs = dt,
                detail = e.localizedMessage?.take(64) ?: "Сбой сети"
            )
        }
    }

    /**
     * Googlevideo URLs carry their own Unix expiry ("expire" query parameter).
     * Do not keep them in the in-memory cache longer than the CDN signature is valid.
     * A safety margin avoids handing ExoPlayer a URL that expires while it is opening.
     */
    private fun cacheSafeExpiry(stream: ResolvedStream): Long {
        val host = runCatching { Uri.parse(stream.url).host.orEmpty() }.getOrDefault("")
        if (!host.contains("googlevideo.com", ignoreCase = true)) return stream.expiresAtMs

        val urlExpirySeconds = runCatching {
            Uri.parse(stream.url).getQueryParameter("expire")?.toLongOrNull()
        }.getOrNull() ?: return stream.expiresAtMs

        val cdnExpiryMs = urlExpirySeconds * 1000L - 90_000L
        return minOf(stream.expiresAtMs, cdnExpiryMs)
    }

    private fun cacheStream(key: String, stream: ResolvedStream) {
        val safeExpiry = cacheSafeExpiry(stream)
        if (safeExpiry <= System.currentTimeMillis()) {
            // The URL is already expired (or too close to expiry); never cache it.
            streamCache.remove(key)
            urlHeadersCache.remove(stream.url)
            return
        }

        val safeStream = stream.copy(expiresAtMs = safeExpiry)
        streamCache[key] = safeStream
        urlHeadersCache[stream.url] = safeStream
        val host = runCatching { Uri.parse(stream.url).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            hostHeadersCache[host] = safeStream
            if (host.contains("googlevideo.com", ignoreCase = true)) {
                hostHeadersCache["googlevideo.com"] = safeStream
            }
            if (host.contains("bilivideo.", ignoreCase = true)) {
                hostHeadersCache["bilivideo.com"] = safeStream
            }
        }
    }

    private fun verifyStreamReachable(
        sourceId: String,
        url: String,
        userAgent: String,
        headers: Map<String, String> = emptyMap(),
        useHeadOnly: Boolean = false
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val parsedUri = runCatching { Uri.parse(url) }.getOrNull()
        val host = parsedUri?.host.orEmpty()
        if (host.contains("googlevideo.com", ignoreCase = true)) {
            val expireSeconds = runCatching {
                parsedUri?.getQueryParameter("expire")?.toLongOrNull()
            }.getOrNull()
            if (expireSeconds != null && expireSeconds * 1000L <= System.currentTimeMillis() + 90_000L) {
                SourceDiagnosticsLog.record(
                    SourceAttemptLog(
                        sourceId = sourceId,
                        displayName = formatEngineDisplay(sourceId),
                        stage = "cdn_probe",
                        status = SourceAttemptStatus.HTTP_ERROR,
                        httpCode = 403,
                        detail = "Подписанная ссылка Googlevideo истекла или истекает менее чем через 90 секунд",
                        latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    )
                )
                return false
            }
        }
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
            if (useHeadOnly) {
                reqBuilder.head()
            } else {
                reqBuilder.header("Range", "bytes=0-63")
            }
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                val ok = resp.code in listOf(200, 206, 302, 303, 307)
                if (!ok) {
                    SourceDiagnosticsLog.record(
                        SourceAttemptLog(
                            sourceId = sourceId,
                            displayName = formatEngineDisplay(sourceId),
                            stage = "cdn_probe",
                            status = SourceAttemptStatus.HTTP_ERROR,
                            httpCode = resp.code,
                            detail = "Поток отклонил запрос (HTTP ${resp.code})",
                            latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        )
                    )
                }
                ok
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
            clientVersion = "1.62.27",
            clientIdHeader = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            isEmbeddedWeb = false,
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3"
        ),
        InnerTubeProfile(
            engineId = "innertube_ios",
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientIdHeader = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            isEmbeddedWeb = false,
            osName = "iOS",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2"
        ),
        InnerTubeProfile(
            engineId = "innertube_android_testsuite",
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientIdHeader = "30",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            isEmbeddedWeb = false,
            osName = "Android",
            osVersion = "11"
        ),
        InnerTubeProfile(
            engineId = "innertube_android_music",
            clientName = "ANDROID_MUSIC",
            clientVersion = "8.12.53",
            clientIdHeader = "21",
            userAgent = "com.google.android.apps.youtube.music/8.12.53 (Linux; U; Android 14; en_US) gzip",
            isEmbeddedWeb = false,
            osName = "Android",
            osVersion = "14"
        ),
        InnerTubeProfile(
            engineId = "innertube_web_embedded",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20250501.01.00",
            clientIdHeader = "56",
            userAgent = DESKTOP_UA,
            isEmbeddedWeb = true
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
            engineId = "innertube_mweb",
            clientName = "MWEB",
            clientVersion = "2.20250501.01.00",
            clientIdHeader = "2",
            userAgent = MOBILE_UA,
            isEmbeddedWeb = false
        )
    )

    private fun generateCpn(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString(16) {
            repeat(16) { append(chars[java.util.concurrent.ThreadLocalRandom.current().nextInt(chars.length)]) }
        }
    }

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

    private suspend fun resolveSingleInnerTubeProfile(
        videoId: String,
        profile: InnerTubeProfile,
        visitorData: String?
    ): ResolvedStream? {
        val t0 = System.currentTimeMillis()
        return try {
            val (_, sts) = if (profile.isEmbeddedWeb || profile.clientName == "MWEB") {
                WebViewYouTubeHelper.ensurePlayerJsMetadata(videoId)
            } else null to null
            val cachedPot = WebViewYouTubeHelper.getCachedPoToken()

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
                put("cpn", generateCpn())
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                if (sts != null) {
                    put("playbackContext", JSONObject().apply {
                        put("contentPlaybackContext", JSONObject().apply {
                            put("signatureTimestamp", sts)
                            put("html5Preference", "HTML5_PREF_WANTS")
                        })
                    })
                }
                if (!cachedPot.isNullOrBlank() && (profile.isEmbeddedWeb || profile.clientName == "MWEB" || profile.clientName == "IOS")) {
                    put("serviceIntegrityDimensions", JSONObject().apply {
                        put("poToken", cachedPot)
                    })
                }
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.clientIdHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
            if (!visitorData.isNullOrBlank()) {
                reqBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
            if (profile.isEmbeddedWeb || profile.clientName == "MWEB") {
                reqBuilder.header("Origin", "https://www.youtube.com")
                reqBuilder.header("Referer", "https://www.youtube.com/")
            }

            probeClient.newCall(reqBuilder.build()).execute().use { response ->
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    SourceDiagnosticsLog.record(
                        SourceAttemptLog(profile.engineId, formatEngineDisplay(profile.engineId), "player", SourceAttemptStatus.HTTP_ERROR, response.code, "HTTP ${response.code}", dt)
                    )
                    return null
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status", "") ?: ""
                if (status != "OK" && status.isNotBlank()) {
                    val reason = playability?.optString("reason", status) ?: status
                    SourceDiagnosticsLog.record(
                        SourceAttemptLog(profile.engineId, formatEngineDisplay(profile.engineId), "player", SourceAttemptStatus.PLAYABILITY_BLOCKED, 200, "$status: $reason", dt)
                    )
                    return null
                }

                val streamingData = json.optJSONObject("streamingData") ?: return null
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                val legacy = streamingData.optJSONArray("formats")
                val hlsManifest = streamingData.optString("hlsManifestUrl", "")

                data class RawCandidate(val plainUrl: String, val cipher: String, val score: Int, val isHls: Boolean = false)
                val rawCandidates = mutableListOf<RawCandidate>()
                if (adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val fmt = adaptive.optJSONObject(i) ?: continue
                        val mime = fmt.optString("mimeType", "")
                        if (!mime.startsWith("audio/")) continue
                        val url = fmt.optString("url", "")
                        val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                        if (url.isBlank() && cipher.isBlank()) continue
                        val bitrate = fmt.optInt("bitrate", 0)
                        val codecBonus = when {
                            mime.contains("mp4a", ignoreCase = true) -> 60_000
                            mime.contains("opus", ignoreCase = true) -> 45_000
                            else -> 10_000
                        }
                        rawCandidates.add(RawCandidate(url, cipher, bitrate + codecBonus))
                    }
                }
                if (rawCandidates.isEmpty() && legacy != null) {
                    for (i in 0 until legacy.length()) {
                        val fmt = legacy.optJSONObject(i) ?: continue
                        val url = fmt.optString("url", "")
                        val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                        if (url.isNotBlank() || cipher.isNotBlank()) {
                            rawCandidates.add(RawCandidate(url, cipher, fmt.optInt("bitrate", 128_000)))
                        }
                    }
                }
                if (rawCandidates.isEmpty() && hlsManifest.isNotBlank()) {
                    rawCandidates.add(RawCandidate(hlsManifest, "", 100_000, isHls = true))
                }

                val cdnHeaders = if (profile.isEmbeddedWeb || profile.clientName == "MWEB") {
                    mapOf("Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
                } else {
                    emptyMap()
                }

                for (cand in rawCandidates.sortedByDescending { it.score }.take(4)) {
                    var candidateUrl = if (cand.plainUrl.isNotBlank()) {
                        cand.plainUrl
                    } else {
                        WebViewYouTubeHelper.decipherSignatureCipherUrl(cand.cipher, videoId) ?: continue
                    }
                    if (!cand.isHls && (candidateUrl.contains("&n=") || candidateUrl.contains("?n="))) {
                        candidateUrl = WebViewYouTubeHelper.unthrottleUrlIfNeeded(candidateUrl, videoId)
                    }
                    if (!cachedPot.isNullOrBlank() && !candidateUrl.contains("pot=")) {
                        val sep = if (candidateUrl.contains("?")) "&" else "?"
                        candidateUrl = "${candidateUrl}${sep}pot=${Uri.encode(cachedPot)}"
                    }
                    if (verifyStreamReachable(profile.engineId, candidateUrl, profile.userAgent, cdnHeaders, useHeadOnly = cand.isHls)) {
                        SourceDiagnosticsLog.record(
                            SourceAttemptLog(profile.engineId, formatEngineDisplay(profile.engineId), "player", SourceAttemptStatus.SUCCESS, 200, "Поток подтверждён", dt)
                        )
                        return ResolvedStream(
                            url = candidateUrl,
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
            SourceDiagnosticsLog.record(
                SourceAttemptLog(profile.engineId, formatEngineDisplay(profile.engineId), "player", SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(100) ?: "Ошибка", (System.currentTimeMillis() - t0).coerceAtLeast(1L))
            )
            null
        }
    }

    private suspend fun resolveViaWatchPageExtract(videoId: String): ResolvedStream? {
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (!resp.isSuccessful) {
                    SourceDiagnosticsLog.record(SourceAttemptLog("yt_embed", "YouTube • Web", "watch", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", dt))
                    return null
                }
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
                        if (!mime.startsWith("audio/")) continue
                        val plainUrl = fmt.optString("url", "")
                        val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                        val resolvedUrl = when {
                            plainUrl.isNotBlank() -> WebViewYouTubeHelper.unthrottleUrlIfNeeded(plainUrl, videoId)
                            cipher.isNotBlank() -> WebViewYouTubeHelper.decipherSignatureCipherUrl(cipher, videoId)
                            else -> null
                        } ?: continue

                        if (verifyStreamReachable("yt_embed", resolvedUrl, DESKTOP_UA)) {
                            SourceDiagnosticsLog.record(SourceAttemptLog("yt_embed", "YouTube • Web", "watch", SourceAttemptStatus.SUCCESS, 200, "Аудио расшифровано из HTML", dt))
                            return ResolvedStream(
                                url = resolvedUrl,
                                userAgent = DESKTOP_UA,
                                expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                engine = "yt_embed"
                            )
                        }
                    }
                }
                if (hls.isNotBlank() && verifyStreamReachable("yt_embed", hls, DESKTOP_UA, useHeadOnly = true)) {
                    SourceDiagnosticsLog.record(SourceAttemptLog("yt_embed", "YouTube • Web", "watch", SourceAttemptStatus.SUCCESS, 200, "HLS найден в HTML", dt))
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
            SourceDiagnosticsLog.record(SourceAttemptLog("yt_embed", "YouTube • Web", "watch", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
            null
        }
    }

    private suspend fun resolveViaCobaltParallel(videoId: String): ResolvedStream? = coroutineScope {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val healthy = COBALT_INSTANCES.filter { isMirrorHealthy(it) }.ifEmpty { COBALT_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
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
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            SourceDiagnosticsLog.record(SourceAttemptLog("cobalt", "Cobalt", instance, SourceAttemptStatus.HTTP_ERROR, response.code, "HTTP ${response.code}", dt))
                            return@use null
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val status = json.optString("status", "")
                        if (status == "tunnel" || status == "redirect" || status == "stream") {
                            val streamUrl = json.optString("url", "")
                            if (streamUrl.isNotBlank()) {
                                SourceDiagnosticsLog.record(SourceAttemptLog("cobalt", "Cobalt", instance, SourceAttemptStatus.SUCCESS, 200, "Туннель активен", dt))
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
                } catch (e: Exception) {
                    markMirrorUnhealthy(instance)
                    SourceDiagnosticsLog.record(SourceAttemptLog("cobalt", "Cobalt", instance, SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(80) ?: "Сбой", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
                    null
                }
            }
        }

        raceFirstNonNull(deferreds)
    }

    private suspend fun resolveViaPipedParallel(videoId: String): ResolvedStream? = coroutineScope {
        val healthy = PIPED_INSTANCES.filter { isMirrorHealthy(it) }.ifEmpty { PIPED_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    val request = Request.Builder()
                        .url("$instance/streams/$videoId")
                        .header("Accept", "application/json")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            SourceDiagnosticsLog.record(SourceAttemptLog("piped", "Piped", instance, SourceAttemptStatus.HTTP_ERROR, response.code, "HTTP ${response.code}", dt))
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
                            if (verifyStreamReachable("piped", candidateUrl, MOBILE_UA)) {
                                SourceDiagnosticsLog.record(SourceAttemptLog("piped", "Piped", instance, SourceAttemptStatus.SUCCESS, 200, "Поток готов", dt))
                                return@use ResolvedStream(
                                    url = candidateUrl,
                                    userAgent = MOBILE_UA,
                                    expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                    engine = "piped"
                                )
                            }
                        }
                        val hlsUrl = json.optString("hls", "")
                        if (hlsUrl.isNotBlank() && verifyStreamReachable("piped", hlsUrl, MOBILE_UA, useHeadOnly = true)) {
                            SourceDiagnosticsLog.record(SourceAttemptLog("piped", "Piped", instance, SourceAttemptStatus.SUCCESS, 200, "HLS поток готов", dt))
                            return@use ResolvedStream(
                                url = hlsUrl,
                                userAgent = MOBILE_UA,
                                expiresAtMs = System.currentTimeMillis() + (2 * 3600 * 1000L),
                                engine = "piped"
                            )
                        }
                        null
                    }
                } catch (e: Exception) {
                    markMirrorUnhealthy(instance)
                    SourceDiagnosticsLog.record(SourceAttemptLog("piped", "Piped", instance, SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(80) ?: "Сбой", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
                    null
                }
            }
        }

        raceFirstNonNull(deferreds)
    }

    private suspend fun resolveViaInvidiousParallel(videoId: String): ResolvedStream? = coroutineScope {
        val healthy = INVIDIOUS_INSTANCES.filter { isMirrorHealthy(it) }.ifEmpty { INVIDIOUS_INSTANCES }

        val deferreds = healthy.map { instance ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    val request = Request.Builder()
                        .url("$instance/api/v1/videos/$videoId")
                        .header("Accept", "application/json")
                        .header("User-Agent", MOBILE_UA)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        if (!response.isSuccessful) {
                            markMirrorUnhealthy(instance)
                            SourceDiagnosticsLog.record(SourceAttemptLog("invidious", "Invidious", instance, SourceAttemptStatus.HTTP_ERROR, response.code, "HTTP ${response.code}", dt))
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
                            if (verifyStreamReachable("invidious", candidateUrl, MOBILE_UA)) {
                                SourceDiagnosticsLog.record(SourceAttemptLog("invidious", "Invidious", instance, SourceAttemptStatus.SUCCESS, 200, "Поток готов", dt))
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
                } catch (e: Exception) {
                    markMirrorUnhealthy(instance)
                    SourceDiagnosticsLog.record(SourceAttemptLog("invidious", "Invidious", instance, SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(80) ?: "Сбой", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
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

    /**
     * Native YouTube Music lyrics pipeline (Meld-style):
     * Resolves `browseId` (MPLYt...) via `music.youtube.com/youtubei/v1/next`
     * and fetches synced timed lyrics or plain YouTube Music lyrics via `youtubei/v1/browse`.
     */
    data class YtmNativeLyricsResult(
        val isSynced: Boolean,
        val timedLines: List<Pair<Long, String>>,
        val plainText: String,
        val sourceLabel: String
    )

    fun fetchYouTubeMusicNativeLyrics(videoId: String): YtmNativeLyricsResult? {
        if (videoId.isBlank()) return null
        return try {
            val contextJson = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250212.01.00")
                    put("hl", "ru")
                    put("gl", "UA")
                })
            }
            val nextPayload = JSONObject().apply {
                put("context", contextJson)
                put("videoId", videoId)
                put("isAudioOnly", true)
            }
            val nextReq = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
                .post(nextPayload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", MOBILE_UA)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            var lyricsBrowseId: String? = null
            probeClient.newCall(nextReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string() ?: return null
                val m = Regex("\"browseId\"\\s*:\\s*\"(MPLYt[^\"]+)\"").find(raw)
                lyricsBrowseId = m?.groupValues?.getOrNull(1)
            }

            val browseId = lyricsBrowseId ?: return null
            val browsePayload = JSONObject().apply {
                put("context", contextJson)
                put("browseId", browseId)
            }
            val browseReq = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(browsePayload.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", MOBILE_UA)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()

            probeClient.newCall(browseReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string() ?: "{}")
                val contents = root.optJSONObject("contents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (contents != null) {
                    for (i in 0 until contents.length()) {
                        val timedData = contents.optJSONObject(i)?.optJSONObject("elementRenderer")
                            ?.optJSONObject("newElement")?.optJSONObject("type")
                            ?.optJSONObject("componentType")?.optJSONObject("model")
                            ?.optJSONObject("timedLyricsModel")?.optJSONObject("lyricsData")
                        val timedArr = timedData?.optJSONArray("timedLyricsData")
                        if (timedArr != null && timedArr.length() > 0) {
                            val pairs = mutableListOf<Pair<Long, String>>()
                            for (j in 0 until timedArr.length()) {
                                val item = timedArr.optJSONObject(j) ?: continue
                                val lyricText = item.optString("lyricLine", "").trim()
                                val cueStart = item.optJSONObject("cueRange")
                                    ?.optLong("startTimeMilliseconds", -1L) ?: -1L
                                if (lyricText.isNotBlank() && cueStart >= 0L) {
                                    pairs.add(cueStart to lyricText)
                                }
                            }
                            if (pairs.isNotEmpty()) {
                                return YtmNativeLyricsResult(
                                    isSynced = true,
                                    timedLines = pairs,
                                    plainText = pairs.joinToString("\n") { it.second },
                                    sourceLabel = "YouTube Music Sync"
                                )
                            }
                        }

                        val descShelf = contents.optJSONObject(i)?.optJSONObject("musicDescriptionShelfRenderer")
                        val runs = descShelf?.optJSONObject("description")?.optJSONArray("runs")
                        if (runs != null && runs.length() > 0) {
                            val sb = StringBuilder()
                            for (r in 0 until runs.length()) {
                                sb.append(runs.optJSONObject(r)?.optString("text", "") ?: "")
                            }
                            val plain = sb.toString().trim()
                            if (plain.isNotBlank()) {
                                return YtmNativeLyricsResult(
                                    isSynced = false,
                                    timedLines = emptyList(),
                                    plainText = plain,
                                    sourceLabel = "YouTube Music"
                                )
                            }
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "YTM native lyrics fetch failed: ${e.message}")
            null
        }
    }

    // ========================================================
    // YOUTUBE SEARCH ADAPTERS
    // ========================================================

    private fun searchViaYtMusicInnerTube(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
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
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (!response.isSuccessful) {
                    SourceDiagnosticsLog.record(SourceAttemptLog("youtube", "YouTube Music", "search", SourceAttemptStatus.HTTP_ERROR, response.code, "HTTP ${response.code}", dt))
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val tracks = parseYtMusicSearchJson(JSONObject(body))
                SourceDiagnosticsLog.record(
                    SourceAttemptLog("youtube", "YouTube Music", "search", if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT, 200, "Найдено ${tracks.size}", dt)
                )
                return tracks
            }
        } catch (e: Exception) {
            SourceDiagnosticsLog.record(SourceAttemptLog("youtube", "YouTube Music", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(80) ?: "Сбой", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
        }
        return emptyList()
    }

    private fun searchViaYouTubeWebHtml(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (!resp.isSuccessful) {
                    SourceDiagnosticsLog.record(SourceAttemptLog("youtube", "YouTube Web", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", dt))
                    return emptyList()
                }
                val html = resp.body?.string() ?: return emptyList()
                val jsonText = extractBalancedJson(html, "var ytInitialData = ")
                    ?: extractBalancedJson(html, "ytInitialData = ")
                    ?: return emptyList()
                val list = parseYouTubeWebSearchJson(JSONObject(jsonText))
                SourceDiagnosticsLog.record(SourceAttemptLog("youtube", "YouTube Web", "search", if (list.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT, 200, "Найдено ${list.size}", dt))
                list
            }
        } catch (e: Exception) {
            SourceDiagnosticsLog.record(SourceAttemptLog("youtube", "YouTube Web", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message?.take(80) ?: "Сбой", (System.currentTimeMillis() - t0).coerceAtLeast(1L)))
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
    ): Int = RelevanceScorer.assess(candidate, targetTitle, targetArtist, expectedDurationMs).score

    private fun cleanQueryPart(input: String): String {
        return input
            .replace(Regex("\\s*\\((feat|ft|with)\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[(feat|ft|with)\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
