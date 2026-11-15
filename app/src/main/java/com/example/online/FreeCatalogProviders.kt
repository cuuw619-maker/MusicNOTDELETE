package com.example.online

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Independent, 100% free key-less online catalog & stream providers:
 * 1. SoundCloud v2 (dynamic JS client_id + progressive MP3 / HLS m3u8)
 * 2. JioSaavn Direct (official jiosaavn.com/api.php + DES-ECB 38346591 decrypt)
 * 3. Audius Discovery Pool (full node list with automatic failover)
 * 4. Bandcamp Public Search (direct track JSON -> MP3-128)
 * 5. Archive.org Music Audio (strict music/MP3/OGG filter + largest audio file pick)
 * 6. Deezer & iTunes Catalogs (metadata + real 30s preview URLs)
 */
object FreeCatalogProviders {

    private const val TAG = "FreeCatalogProviders"
    private val client = NetworkClient.fastProbeClient

    // ========================================================
    // 1. SOUNDCLOUD v2 (Dynamic client_id + Progressive/HLS)
    // ========================================================

    @Volatile
    private var cachedScClientId: String? = null
    @Volatile
    private var scClientIdFetchedAtMs: Long = 0L

    private data class ScTranscodingMeta(
        val transcodingUrl: String,
        val protocol: String,
        val trackAuth: String
    )

    private val scTranscodingCache = ConcurrentHashMap<String, ScTranscodingMeta>()

    private fun recordAttempt(
        sourceId: String,
        displayName: String,
        stage: String,
        status: SourceAttemptStatus,
        httpCode: Int? = null,
        detail: String,
        startMs: Long
    ) {
        SourceDiagnosticsLog.record(
            SourceAttemptLog(
                sourceId = sourceId,
                displayName = displayName,
                stage = stage,
                status = status,
                httpCode = httpCode,
                detail = detail,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        )
    }

    fun getOrFetchSoundCloudClientId(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedScClientId
        if (!cached.isNullOrBlank() && now - scClientIdFetchedAtMs < 6 * 3600_000L) {
            return cached
        }
        val t0 = System.currentTimeMillis()
        return try {
            val pageReq = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Accept", "text/html")
                .build()
            val scriptUrls = mutableListOf<String>()
            client.newCall(pageReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.HTTP_ERROR, resp.code, "Home page HTTP ${resp.code}", t0)
                    return cached
                }
                val html = resp.body?.string() ?: ""
                Regex("""https://a-v2\.sndcdn\.com/assets/[a-zA-Z0-9._-]+\.js""").findAll(html).forEach {
                    scriptUrls.add(it.value)
                }
            }
            // Scan from the last scripts first (main app bundles contain client_id)
            val idPattern = Regex("""client_id\s*[:=]\s*["']([a-zA-Z0-9]{32})["']""")
            for (jsUrl in scriptUrls.asReversed().take(8)) {
                try {
                    val jsReq = Request.Builder()
                        .url(jsUrl)
                        .header("User-Agent", NetworkClient.BROWSER_UA)
                        .build()
                    client.newCall(jsReq).execute().use { jsResp ->
                        if (!jsResp.isSuccessful) return@use
                        val body = jsResp.body?.string() ?: return@use
                        val match = idPattern.find(body)
                        if (match != null) {
                            val foundId = match.groupValues[1]
                            cachedScClientId = foundId
                            scClientIdFetchedAtMs = now
                            recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.SUCCESS, 200, "Получен публичный client_id", t0)
                            return foundId
                        }
                    }
                } catch (_: Exception) {
                }
            }
            recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.EMPTY_RESULT, null, "client_id не найден в JS-бандлах", t0)
            cached
        } catch (e: Exception) {
            recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка сети", t0)
            cached
        }
    }

    fun searchSoundCloud(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        val clientId = getOrFetchSoundCloudClientId() ?: return emptyList()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api-v2.soundcloud.com/search/tracks?q=$encoded&client_id=$clientId&limit=12&app_locale=en"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Origin", "https://soundcloud.com")
                .header("Referer", "https://soundcloud.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        cachedScClientId = null // Force refresh next time
                    }
                    recordAttempt("soundcloud", "SoundCloud", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "Поиск вернул HTTP ${resp.code}", t0)
                    return emptyList()
                }
                val root = JSONObject(resp.body?.string() ?: "{}")
                val coll = root.optJSONArray("collection") ?: return emptyList()
                val out = mutableListOf<OnlineTrack>()
                for (i in 0 until coll.length()) {
                    val item = coll.optJSONObject(i) ?: continue
                    val id = item.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: continue
                    val title = item.optString("title", "").trim()
                    if (title.isBlank()) continue
                    if (item.optString("policy", "") == "BLOCK") continue
                    val transcodings = item.optJSONObject("media")?.optJSONArray("transcodings") ?: continue
                    val bestTranscoding = pickBestScTranscoding(transcodings) ?: continue

                    val trackAuth = item.optString("track_authorization", "")
                    scTranscodingCache[id] = ScTranscodingMeta(bestTranscoding.first, bestTranscoding.second, trackAuth)

                    val artist = item.optJSONObject("user")?.optString("username", "SoundCloud")
                        ?.takeIf { it.isNotBlank() } ?: "SoundCloud"
                    val durSec = item.optLong("duration", 0L) / 1000L
                    val artwork = item.optString("artwork_url", "")
                        .replace("-large.", "-t500x500.")
                        .takeIf { it.startsWith("http") }

                    val isSnipped = item.optString("policy", "").equals("SNIP", ignoreCase = true)
                    out.add(
                        OnlineTrack(
                            id = id,
                            title = title,
                            artist = artist,
                            album = "SoundCloud",
                            durationSeconds = durSec,
                            thumbnailUrl = artwork,
                            isOfficialAudio = true,
                            sourceEngine = "soundcloud",
                            directStreamUrl = null,
                            qualityBadge = if (isSnipped) "30с превью" else if (bestTranscoding.second == "hls") "HLS AAC" else "MP3",
                            isPreviewOnly = isSnipped
                        )
                    )
                }
                val filtered = out.filter { RelevanceScorer.matchesSearchQuery(it, query) }.ifEmpty { out.take(6) }
                recordAttempt(
                    "soundcloud",
                    "SoundCloud",
                    "search",
                    if (filtered.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                    200,
                    "Найдено ${filtered.size} треков",
                    t0
                )
                filtered
            }
        } catch (e: Exception) {
            recordAttempt("soundcloud", "SoundCloud", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка поиска", t0)
            emptyList()
        }
    }

    private fun pickBestScTranscoding(transcodings: JSONArray): Pair<String, String>? {
        var progressiveUrl: String? = null
        var hlsAudioUrl: String? = null
        for (i in 0 until transcodings.length()) {
            val t = transcodings.optJSONObject(i) ?: continue
            val url = t.optString("url", "")
            if (url.isBlank()) continue
            val format = t.optJSONObject("format")
            val protocol = format?.optString("protocol", "") ?: ""
            val mime = format?.optString("mime_type", "") ?: ""
            val preset = t.optString("preset", "")
            if (t.optBoolean("snipped", false) && progressiveUrl != null) continue
            if (protocol == "progressive" && mime.contains("audio", true)) {
                progressiveUrl = url
            } else if (protocol == "hls" && (preset.contains("aac", true) || preset.contains("mp3", true) || mime.contains("audio", true))) {
                if (hlsAudioUrl == null || preset.contains("aac_160k")) {
                    hlsAudioUrl = url
                }
            }
        }
        return when {
            progressiveUrl != null -> progressiveUrl to "progressive"
            hlsAudioUrl != null -> hlsAudioUrl to "hls"
            else -> null
        }
    }

    fun resolveSoundCloudStream(trackId: String): ResolvedStream? {
        val t0 = System.currentTimeMillis()
        val clientId = getOrFetchSoundCloudClientId() ?: return null
        return try {
            val meta = scTranscodingCache[trackId] ?: run {
                val trackReq = Request.Builder()
                    .url("https://api-v2.soundcloud.com/tracks/$trackId?client_id=$clientId")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(trackReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        recordAttempt("soundcloud", "SoundCloud", "track_meta", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                        return null
                    }
                    val item = JSONObject(resp.body?.string() ?: "{}")
                    val transcodings = item.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
                    val picked = pickBestScTranscoding(transcodings) ?: return null
                    ScTranscodingMeta(picked.first, picked.second, item.optString("track_authorization", "")).also {
                        scTranscodingCache[trackId] = it
                    }
                }
            }

            val sep = if (meta.transcodingUrl.contains("?")) "&" else "?"
            val authParam = if (meta.trackAuth.isNotBlank()) "&track_authorization=${URLEncoder.encode(meta.trackAuth, "UTF-8")}" else ""
            val resolveUrl = "${meta.transcodingUrl}${sep}client_id=$clientId$authParam"
            val req = Request.Builder()
                .url(resolveUrl)
                .header("Accept", "application/json")
                .header("Origin", "https://soundcloud.com")
                .header("Referer", "https://soundcloud.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) cachedScClientId = null
                    recordAttempt("soundcloud", "SoundCloud", "stream", SourceAttemptStatus.HTTP_ERROR, resp.code, "Транскодинг HTTP ${resp.code}", t0)
                    return null
                }
                val streamUrl = JSONObject(resp.body?.string() ?: "{}").optString("url", "")
                if (streamUrl.isBlank()) {
                    recordAttempt("soundcloud", "SoundCloud", "stream", SourceAttemptStatus.EMPTY_RESULT, 200, "Пустой URL потока", t0)
                    return null
                }
                recordAttempt("soundcloud", "SoundCloud", "stream", SourceAttemptStatus.SUCCESS, 200, "Поток готов (${meta.protocol})", t0)
                ResolvedStream(
                    url = streamUrl,
                    userAgent = NetworkClient.BROWSER_UA,
                    headers = mapOf("Origin" to "https://soundcloud.com", "Referer" to "https://soundcloud.com/"),
                    expiresAtMs = System.currentTimeMillis() + 30 * 60_000L,
                    engine = "soundcloud"
                )
            }
        } catch (e: Exception) {
            recordAttempt("soundcloud", "SoundCloud", "stream", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            null
        }
    }

    // ========================================================
    // 2. JIOSAAVN DIRECT (First-party API + DES-ECB 38346591)
    // ========================================================

    fun decryptJioSaavnUrl(encryptedMediaUrl: String, prefer320kbps: Boolean = true): String? {
        if (encryptedMediaUrl.isBlank()) return null
        return try {
            val decoded = Base64.decode(encryptedMediaUrl.trim(), Base64.DEFAULT)
            val keySpec = SecretKeySpec("38346591".toByteArray(StandardCharsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val plain = String(cipher.doFinal(decoded), StandardCharsets.UTF_8).trim()
            if (!plain.startsWith("http")) return null
            if (prefer320kbps) {
                plain.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
            } else {
                plain.replace("_96.mp4", "_160.mp4")
            }
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn DES decrypt error: ${e.message}")
            null
        }
    }

    fun searchJioSaavnDirect(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=10&p=1&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Referer", "https://www.jiosaavn.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("saavn", "JioSaavn", "search_direct", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                } else {
                    val root = JSONObject(resp.body?.string() ?: "{}")
                    val results = root.optJSONArray("results") ?: JSONArray()
                    val tracks = mutableListOf<OnlineTrack>()
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        val rawTitle = item.optString("title", item.optString("song", ""))
                        val title = decodeHtmlBasic(rawTitle)
                        if (id.isBlank() || title.isBlank()) continue

                        val more = item.optJSONObject("more_info")
                        val encrypted = more?.optString("encrypted_media_url", "")
                            ?.takeIf { it.isNotBlank() }
                            ?: item.optString("encrypted_media_url", "")
                        val has320 = more?.optString("320kbps", "false").equals("true", true) ||
                            item.optString("320kbps", "false").equals("true", true)
                        val streamUrl = decryptJioSaavnUrl(encrypted, prefer320kbps = has320) ?: continue

                        val durSec = more?.optString("duration", "0")?.toLongOrNull()
                            ?: item.optLong("duration", 0L)
                        val artistNames = extractSaavnArtists(more ?: item)
                        val album = decodeHtmlBasic(more?.optString("album", "JioSaavn") ?: "JioSaavn")
                        val thumb = item.optString("image", "")
                            .replace("150x150", "500x500")
                            .takeIf { it.startsWith("http") }

                        tracks.add(
                            OnlineTrack(
                                id = id,
                                title = title,
                                artist = artistNames,
                                album = album,
                                durationSeconds = durSec,
                                thumbnailUrl = thumb,
                                isOfficialAudio = true,
                                sourceEngine = "saavn",
                                directStreamUrl = streamUrl,
                                qualityBadge = if (has320) "M4A 320k" else "M4A 160k"
                            )
                        )
                    }
                    val relevant = tracks.filter { RelevanceScorer.matchesSearchQuery(it, query) }
                    if (relevant.isNotEmpty()) {
                        recordAttempt("saavn", "JioSaavn", "search_direct", SourceAttemptStatus.SUCCESS, 200, "Найдено ${relevant.size} треков", t0)
                        return relevant
                    } else if (tracks.isNotEmpty()) {
                        recordAttempt("saavn", "JioSaavn", "search_direct", SourceAttemptStatus.RELEVANCE_REJECTED, 200, "Отклонено ${tracks.size} нерелевантных треков", t0)
                        return emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            recordAttempt("saavn", "JioSaavn", "search_direct", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
        }

        // Secondary saavn.sumit / saavn.dev mirror if official endpoint didn't yield results
        val mirrors = listOf("https://saavn.dev", "https://saavn.sumit.co")
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        for (mirror in mirrors) {
            val tm = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url("$mirror/api/search/songs?query=$encoded&limit=8")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        recordAttempt("saavn", "JioSaavn", "mirror", SourceAttemptStatus.HTTP_ERROR, resp.code, "$mirror HTTP ${resp.code}", tm)
                        return@use
                    }
                    val root = JSONObject(resp.body?.string() ?: "{}")
                    val results = root.optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
                    val tracks = mutableListOf<OnlineTrack>()
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val id = item.optString("id", "")
                        val title = decodeHtmlBasic(item.optString("name", ""))
                        if (id.isBlank() || title.isBlank()) continue
                        val downloads = item.optJSONArray("downloadUrl") ?: continue
                        if (downloads.length() == 0) continue
                        val streamUrl = downloads.getJSONObject(downloads.length() - 1).optString("url", "")
                        if (streamUrl.isBlank()) continue
                        val artistNames = item.optJSONObject("artists")?.optJSONArray("primary")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                                .joinToString(", ")
                        }?.takeIf { it.isNotBlank() } ?: "Исполнитель"
                        val images = item.optJSONArray("image")
                        val thumb = if (images != null && images.length() > 0) images.getJSONObject(images.length() - 1).optString("url") else null
                        tracks.add(
                            OnlineTrack(
                                id = id,
                                title = title,
                                artist = artistNames,
                                album = decodeHtmlBasic(item.optJSONObject("album")?.optString("name", "JioSaavn") ?: "JioSaavn"),
                                durationSeconds = item.optLong("duration", 0L),
                                thumbnailUrl = thumb,
                                isOfficialAudio = true,
                                sourceEngine = "saavn",
                                directStreamUrl = streamUrl
                            )
                        )
                    }
                    val relevant = tracks.filter { RelevanceScorer.matchesSearchQuery(it, query) }
                    if (relevant.isNotEmpty()) {
                        recordAttempt("saavn", "JioSaavn", "mirror", SourceAttemptStatus.SUCCESS, 200, "Зеркало $mirror: ${relevant.size}", tm)
                        return relevant
                    }
                }
            } catch (e: Exception) {
                recordAttempt("saavn", "JioSaavn", "mirror", SourceAttemptStatus.NETWORK_ERROR, null, "$mirror: ${e.message}", tm)
            }
        }
        return emptyList()
    }

    private fun extractSaavnArtists(obj: JSONObject): String {
        val artistMap = obj.optJSONObject("artistMap")
        val primary = artistMap?.optJSONArray("primary_artists")
        if (primary != null && primary.length() > 0) {
            val names = (0 until primary.length()).mapNotNull {
                primary.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() }
            }
            if (names.isNotEmpty()) return decodeHtmlBasic(names.joinToString(", "))
        }
        val subtitle = decodeHtmlBasic(obj.optString("subtitle", obj.optString("primary_artists", "")))
        return subtitle.substringBefore(" - ").ifBlank { "JioSaavn Artist" }
    }

    // ========================================================
    // 3. AUDIUS MULTI-NODE DISCOVERY POOL WITH FAILOVER
    // ========================================================

    private val fallbackAudiusHosts = listOf(
        "https://discoveryprovider.audius.co",
        "https://discoveryprovider2.audius.co",
        "https://discoveryprovider3.audius.co",
        "https://audius-discovery-1.cultur3stake.com",
        "https://dn1.monophonic.digital"
    )

    @Volatile
    private var cachedAudiusHosts: List<String> = emptyList()
    @Volatile
    private var audiusPoolFetchedAtMs: Long = 0L

    fun getAudiusDiscoveryPool(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedAudiusHosts.isNotEmpty() && now - audiusPoolFetchedAtMs < 20 * 60_000L) {
            return cachedAudiusHosts
        }
        return try {
            val req = Request.Builder()
                .url("https://api.audius.co")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return fallbackAudiusHosts
                val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data") ?: return fallbackAudiusHosts
                val parsed = (0 until arr.length())
                    .mapNotNull { arr.optString(it, "").trim().trimEnd('/').takeIf { h -> h.startsWith("https://") } }
                    .distinct()
                val merged = (parsed + fallbackAudiusHosts).distinct()
                cachedAudiusHosts = merged
                audiusPoolFetchedAtMs = now
                merged
            }
        } catch (_: Exception) {
            fallbackAudiusHosts
        }
    }

    fun searchAudiusPool(query: String): List<OnlineTrack> {
        val pool = getAudiusDiscoveryPool()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        for (host in pool.take(4)) {
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url("$host/v1/tracks/search?query=$encoded&app_name=imux")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        recordAttempt("audius", "Audius", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "$host HTTP ${resp.code}", t0)
                        return@use
                    }
                    val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data") ?: JSONArray()
                    val tracks = mutableListOf<OnlineTrack>()
                    for (i in 0 until minOf(data.length(), 12)) {
                        val item = data.optJSONObject(i) ?: continue
                        if (!item.optBoolean("is_streamable", true)) continue
                        val id = item.optString("id", "")
                        val title = item.optString("title", "").trim()
                        if (id.isBlank() || title.isBlank()) continue
                        val artist = item.optJSONObject("user")?.optString("name", "Audius Artist") ?: "Audius Artist"
                        val thumb = item.optJSONObject("artwork")?.optString("480x480")
                            ?: item.optJSONObject("artwork")?.optString("150x150")
                        val dur = item.optLong("duration", 0L)
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
                                directStreamUrl = "$host/v1/tracks/$id/stream?app_name=imux",
                                qualityBadge = "MP3 320k"
                            )
                        )
                    }
                    val relevant = tracks.filter { RelevanceScorer.matchesSearchQuery(it, query) }
                        .ifEmpty { tracks.take(4) }
                    recordAttempt(
                        "audius",
                        "Audius",
                        "search",
                        if (relevant.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                        200,
                        "$host: найдено ${relevant.size}",
                        t0
                    )
                    if (relevant.isNotEmpty()) return relevant
                }
            } catch (e: Exception) {
                recordAttempt("audius", "Audius", "search", SourceAttemptStatus.NETWORK_ERROR, null, "$host: ${e.message}", t0)
            }
        }
        return emptyList()
    }

    fun resolveAudiusStreamWithFailover(trackId: String): ResolvedStream? {
        val pool = getAudiusDiscoveryPool()
        for (host in pool.take(4)) {
            val streamUrl = "$host/v1/tracks/$trackId/stream?app_name=imux"
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(streamUrl)
                    .head()
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code in listOf(200, 206, 302, 303, 307)) {
                        val finalUrl = resp.request.url.toString()
                        recordAttempt("audius", "Audius", "stream", SourceAttemptStatus.SUCCESS, resp.code, "Нода $host активна", t0)
                        return ResolvedStream(
                            url = finalUrl,
                            userAgent = NetworkClient.BROWSER_UA,
                            expiresAtMs = System.currentTimeMillis() + 3600_000L,
                            engine = "audius"
                        )
                    } else {
                        recordAttempt("audius", "Audius", "stream", SourceAttemptStatus.HTTP_ERROR, resp.code, "$host HTTP ${resp.code}", t0)
                    }
                }
            } catch (e: Exception) {
                recordAttempt("audius", "Audius", "stream", SourceAttemptStatus.NETWORK_ERROR, null, "$host: ${e.message}", t0)
            }
        }
        return ResolvedStream(
            url = "${pool.firstOrNull() ?: "https://discoveryprovider.audius.co"}/v1/tracks/$trackId/stream?app_name=imux",
            userAgent = NetworkClient.BROWSER_UA,
            expiresAtMs = System.currentTimeMillis() + 1800_000L,
            engine = "audius"
        )
    }

    // ========================================================
    // 4. BANDCAMP PUBLIC TRACKS (MP3-128 Direct)
    // ========================================================

    suspend fun searchBandcamp(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "https://bandcamp.com/search?q=$encoded&item_type=t"
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Accept", "text/html")
                .build()
            val pageUrls = mutableListOf<String>()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("bandcamp", "Bandcamp", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return@withContext emptyList()
                }
                val html = resp.body?.string() ?: ""
                Regex("""href="(https://[a-zA-Z0-9-]+\.bandcamp\.com/track/[^"?]+)""").findAll(html).forEach { m ->
                    val u = m.groupValues[1]
                    if (u !in pageUrls) pageUrls.add(u)
                }
            }
            if (pageUrls.isEmpty()) {
                recordAttempt("bandcamp", "Bandcamp", "search", SourceAttemptStatus.EMPTY_RESULT, 200, "Треки не найдены", t0)
                return@withContext emptyList()
            }

            val parsedTracks = coroutineScope {
                pageUrls.take(4).map { url ->
                    async { parseBandcampTrackPage(url) }
                }.awaitAll().filterNotNull()
            }
            val relevant = parsedTracks.filter { RelevanceScorer.matchesSearchQuery(it, query) }
                .ifEmpty { parsedTracks }
            recordAttempt(
                "bandcamp",
                "Bandcamp",
                "search",
                if (relevant.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                200,
                "Загружено ${relevant.size} MP3-128 треков",
                t0
            )
            relevant
        } catch (e: Exception) {
            recordAttempt("bandcamp", "Bandcamp", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    private fun parseBandcampTrackPage(trackUrl: String): OnlineTrack? {
        return try {
            val req = Request.Builder()
                .url(trackUrl)
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                val tralbumMatch = Regex("""data-tralbum="([^"]+)"""").find(html) ?: return null
                val decodedJson = decodeHtmlBasic(tralbumMatch.groupValues[1])
                val json = JSONObject(decodedJson)
                val artist = json.optString("artist", "Bandcamp Artist")
                val trackArr = json.optJSONArray("trackinfo") ?: return null
                if (trackArr.length() == 0) return null
                val tObj = trackArr.optJSONObject(0) ?: return null
                val title = tObj.optString("title", "")
                val streamUrl = tObj.optJSONObject("file")?.optString("mp3-128", "") ?: ""
                if (title.isBlank() || !streamUrl.startsWith("http")) return null
                val dur = tObj.optDouble("duration", 0.0).toLong()
                val artId = json.optLong("art_id", 0L)
                val thumb = if (artId > 0L) "https://f4.bcbits.com/img/a${artId}_10.jpg" else null
                val id = trackUrl.substringAfter("/track/").take(64).ifBlank { title.hashCode().toString() }

                OnlineTrack(
                    id = id,
                    title = title,
                    artist = artist,
                    album = "Bandcamp",
                    durationSeconds = dur,
                    thumbnailUrl = thumb,
                    isOfficialAudio = true,
                    sourceEngine = "bandcamp",
                    directStreamUrl = streamUrl,
                    qualityBadge = "MP3 128k"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    // ========================================================
    // 5. ARCHIVE.ORG STRICT MUSIC FILTER
    // ========================================================

    private val archiveFilesCache = ConcurrentHashMap<String, String>()

    fun searchArchiveMusic(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val tokens = RelevanceScorer.tokenize(query).ifEmpty {
                query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            }
            if (tokens.isEmpty()) return emptyList()
            val termsExpr = tokens.joinToString(" AND ") { "\"$it\"" }
            val rawQuery = "mediatype:(audio OR etree) AND format:(MP3 OR \"VBR MP3\" OR Ogg Vorbis) AND ($termsExpr)"
            val encoded = URLEncoder.encode(rawQuery, "UTF-8")
            val req = Request.Builder()
                .url("https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,creator&rows=10&output=json")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("archive", "Archive.org", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return emptyList()
                }
                val docs = JSONObject(resp.body?.string() ?: "{}")
                    .optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
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

                    val candidate = OnlineTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = "Archive.org",
                        durationSeconds = 0L,
                        thumbnailUrl = "https://archive.org/services/img/$id",
                        isOfficialAudio = false,
                        sourceEngine = "archive",
                        directStreamUrl = null,
                        qualityBadge = "MP3/OGG"
                    )
                    if (RelevanceScorer.matchesSearchQuery(candidate, query)) {
                        tracks.add(candidate)
                    }
                }
                recordAttempt(
                    "archive",
                    "Archive.org",
                    "search",
                    if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.RELEVANCE_REJECTED,
                    200,
                    if (tracks.isNotEmpty()) "Найдено ${tracks.size}" else "Нет точных совпадений музыки",
                    t0
                )
                tracks
            }
        } catch (e: Exception) {
            recordAttempt("archive", "Archive.org", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    fun resolveArchiveStreamUrl(identifier: String): String? {
        archiveFilesCache[identifier]?.let { return it }
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url("https://archive.org/metadata/$identifier/files")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("archive", "Archive.org", "files", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return null
                }
                val result = JSONObject(resp.body?.string() ?: "{}").optJSONArray("result") ?: return null
                val mp3s = mutableListOf<Pair<String, Long>>()
                val others = mutableListOf<Pair<String, Long>>()
                for (i in 0 until result.length()) {
                    val fileObj = result.optJSONObject(i) ?: continue
                    val name = fileObj.optString("name", "")
                    val size = fileObj.optString("size", "0").toLongOrNull() ?: 0L
                    val lower = name.lowercase()
                    if (lower.endsWith("_64kb.mp3") || lower.contains("spectrogram")) continue
                    when {
                        lower.endsWith(".mp3") -> mp3s.add(name to size)
                        lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".flac") -> others.add(name to size)
                    }
                }
                val chosen = (mp3s.maxByOrNull { it.second } ?: others.maxByOrNull { it.second })?.first
                    ?: return null
                val encodedFile = Uri.encode(chosen, "/")
                val url = "https://archive.org/download/$identifier/$encodedFile"
                archiveFilesCache[identifier] = url
                recordAttempt("archive", "Archive.org", "files", SourceAttemptStatus.SUCCESS, 200, "Выбран $chosen", t0)
                url
            }
        } catch (e: Exception) {
            recordAttempt("archive", "Archive.org", "files", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            null
        }
    }

    // ========================================================
    // 6. DEEZER & ITUNES (Direct Preview URL + Cross-Resolve)
    // ========================================================

    fun searchDeezer(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=10")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("deezer", "Deezer", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return emptyList()
                }
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
                    val previewUrl = item.optString("preview", "").takeIf { it.startsWith("http") }

                    val cand = OnlineTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = dur,
                        thumbnailUrl = thumb,
                        isOfficialAudio = true,
                        sourceEngine = "deezer",
                        directStreamUrl = previewUrl,
                        qualityBadge = if (previewUrl != null) "Cross/30с" else "Cross",
                        isPreviewOnly = previewUrl != null
                    )
                    if (RelevanceScorer.matchesSearchQuery(cand, query)) {
                        tracks.add(cand)
                    }
                }
                recordAttempt(
                    "deezer",
                    "Deezer",
                    "search",
                    if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                    200,
                    "Найдено ${tracks.size}",
                    t0
                )
                tracks
            }
        } catch (e: Exception) {
            recordAttempt("deezer", "Deezer", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    fun searchItunes(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=10")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("itunes", "Apple iTunes", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return emptyList()
                }
                val results = JSONObject(resp.body?.string() ?: "{}").optJSONArray("results") ?: return emptyList()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optLong("trackId", 0L).takeIf { it > 0L }?.toString() ?: continue
                    val title = item.optString("trackName", "").trim()
                    if (title.isBlank()) continue
                    val artist = item.optString("artistName", "Artist").trim()
                    val album = item.optString("collectionName", "iTunes").trim()
                    val thumb = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                        .takeIf { it.startsWith("http") }
                    val durSec = item.optLong("trackTimeMillis", 0L) / 1000L
                    val previewUrl = item.optString("previewUrl", "").takeIf { it.startsWith("http") } ?: continue

                    val cand = OnlineTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = durSec,
                        thumbnailUrl = thumb,
                        isOfficialAudio = true,
                        sourceEngine = "itunes",
                        directStreamUrl = previewUrl,
                        qualityBadge = "AAC 30с превью",
                        isPreviewOnly = true
                    )
                    if (RelevanceScorer.matchesSearchQuery(cand, query)) {
                        tracks.add(cand)
                    }
                }
                recordAttempt(
                    "itunes",
                    "Apple iTunes",
                    "search",
                    if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                    200,
                    "Найдено ${tracks.size}",
                    t0
                )
                tracks
            }
        } catch (e: Exception) {
            recordAttempt("itunes", "Apple iTunes", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    private fun decodeHtmlBasic(raw: String): String =
        raw.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
}
