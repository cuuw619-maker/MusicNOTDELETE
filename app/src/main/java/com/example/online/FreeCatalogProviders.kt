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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
 * 6. Jamendo Public Catalog (full CC-licensed MP3 streams)
 * 7. Bilibili Music (WBI signed search + DASH M4A audio with Referer headers)
 * 8. Deezer & iTunes Catalogs (metadata + real 30s preview URLs)
 */
object FreeCatalogProviders {

    private const val TAG = "FreeCatalogProviders"
    private val client = NetworkClient.fastProbeClient
    private val unhealthyHostsUntil = ConcurrentHashMap<String, Long>()

    private fun isHostHealthy(host: String): Boolean {
        val until = unhealthyHostsUntil[host] ?: return true
        return System.currentTimeMillis() >= until
    }

    private fun markHostUnhealthy(host: String, cooldownMs: Long = 90_000L) {
        unhealthyHostsUntil[host] = System.currentTimeMillis() + cooldownMs
    }

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

    internal fun extractSoundCloudClientIdFromText(text: String): String? {
        if (text.isBlank()) return null
        val patterns = listOf(
            Regex("""client_id\s*[:=]\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""["']client_id["']\s*:\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""clientId\s*[:=]\s*["']([a-zA-Z0-9]{32})["']"""),
            Regex("""\bclient_id=([a-zA-Z0-9]{32})\b"""),
            Regex("""api_key\s*[:=]\s*["']([a-zA-Z0-9]{32})["']""")
        )
        for (p in patterns) {
            val found = p.find(text)?.groupValues?.getOrNull(1)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    fun getOrFetchSoundCloudClientId(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedScClientId
        if (!cached.isNullOrBlank() && now - scClientIdFetchedAtMs < 6 * 3600_000L) {
            return cached
        }
        val t0 = System.currentTimeMillis()
        val pagesToScan = listOf(
            "https://soundcloud.com",
            "https://m.soundcloud.com",
            "https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/293"
        )
        val scriptUrls = LinkedHashSet<String>()
        var lastHttpCode: Int? = null
        var hadNetworkError: String? = null

        for (pageUrl in pagesToScan) {
            try {
                val pageReq = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                client.newCall(pageReq).execute().use { resp ->
                    lastHttpCode = resp.code
                    if (!resp.isSuccessful) return@use
                    val html = resp.body?.string() ?: return@use

                    // Check inline hydration scripts first
                    extractSoundCloudClientIdFromText(html)?.let { inlineId ->
                        cachedScClientId = inlineId
                        scClientIdFetchedAtMs = now
                        recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.SUCCESS, 200, "Получен client_id из HTML", t0)
                        return inlineId
                    }

                    // Collect all sndcdn JS bundle URLs across assets / scripts
                    Regex("""(https://[^"'\s<>]+sndcdn\.com/[^"'\s<>]+\.js(?:\?[^"'\s<>]*)?)""").findAll(html).forEach {
                        scriptUrls.add(it.groupValues[1])
                    }
                    Regex("""src=["'](/[^"'\s<>]+\.js(?:\?[^"'\s<>]*)?)["']""").findAll(html).forEach {
                        val rel = it.groupValues[1]
                        val origin = Uri.parse(pageUrl).let { u -> "${u.scheme}://${u.host}" }
                        scriptUrls.add("$origin$rel")
                    }
                }
            } catch (e: Exception) {
                hadNetworkError = e.message
            }
        }

        val orderedScripts = scriptUrls.toList().asReversed().take(16)
        for (jsUrl in orderedScripts) {
            try {
                val jsReq = Request.Builder()
                    .url(jsUrl)
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .build()
                client.newCall(jsReq).execute().use { jsResp ->
                    if (!jsResp.isSuccessful) return@use
                    val body = jsResp.body?.string() ?: return@use
                    val foundId = extractSoundCloudClientIdFromText(body)
                    if (!foundId.isNullOrBlank()) {
                        cachedScClientId = foundId
                        scClientIdFetchedAtMs = now
                        recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.SUCCESS, 200, "Получен публичный client_id", t0)
                        return foundId
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (hadNetworkError != null && scriptUrls.isEmpty()) {
            recordAttempt("soundcloud", "SoundCloud", "client_id", SourceAttemptStatus.NETWORK_ERROR, null, hadNetworkError, t0)
        } else {
            recordAttempt(
                "soundcloud",
                "SoundCloud",
                "client_id",
                SourceAttemptStatus.EMPTY_RESULT,
                lastHttpCode,
                "client_id не найден (${orderedScripts.size} JS проверено)",
                t0
            )
        }
        return cached
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

        // Secondary live mirror if official endpoint didn't yield results (dead saavn.dev removed)
        val mirrors = getJioSaavnMirrorHosts()
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

    internal fun getJioSaavnMirrorHosts(): List<String> = listOf("https://saavn.sumit.co")

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
        val ordered = pool.filter { isHostHealthy(it) }.ifEmpty { pool }.take(3)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        for (host in ordered) {
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url("$host/v1/tracks/search?query=$encoded&app_name=imux")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        markHostUnhealthy(host)
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
                markHostUnhealthy(host)
                recordAttempt("audius", "Audius", "search", SourceAttemptStatus.NETWORK_ERROR, null, "$host: ${e.message}", t0)
            }
        }
        return emptyList()
    }

    fun resolveAudiusStreamWithFailover(trackId: String): ResolvedStream? {
        val pool = getAudiusDiscoveryPool()
        val ordered = pool.filter { isHostHealthy(it) }.ifEmpty { pool }.take(3)
        for (host in ordered) {
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
                        markHostUnhealthy(host)
                        recordAttempt("audius", "Audius", "stream", SourceAttemptStatus.HTTP_ERROR, resp.code, "$host HTTP ${resp.code}", t0)
                    }
                }
            } catch (e: Exception) {
                markHostUnhealthy(host)
                recordAttempt("audius", "Audius", "stream", SourceAttemptStatus.NETWORK_ERROR, null, "$host: ${e.message}", t0)
            }
        }
        return ResolvedStream(
            url = "${ordered.firstOrNull() ?: pool.firstOrNull() ?: "https://discoveryprovider.audius.co"}/v1/tracks/$trackId/stream?app_name=imux",
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
                    async { withTimeoutOrNull(2_400L) { parseBandcampTrackPage(url) } }
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

    // ========================================================
    // 7. JAMENDO PUBLIC CATALOG (Full CC MP3 Streams)
    // ========================================================

    private const val JAMENDO_PUBLIC_CLIENT_ID = "709fa152"

    fun searchJamendo(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.jamendo.com/v3.0/tracks/?client_id=$JAMENDO_PUBLIC_CLIENT_ID&format=json&limit=12&audioformat=mp32&search=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("jamendo", "Jamendo", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return emptyList()
                }
                val root = JSONObject(resp.body?.string() ?: "{}")
                val results = root.optJSONArray("results") ?: return emptyList()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    val title = item.optString("name", "").trim()
                    val streamUrl = item.optString("audio", "").trim()
                    if (id.isBlank() || title.isBlank() || !streamUrl.startsWith("http")) continue

                    val artist = item.optString("artist_name", "Jamendo Artist").trim().ifBlank { "Jamendo Artist" }
                    val album = item.optString("album_name", "Jamendo").trim().ifBlank { "Jamendo" }
                    val durSec = item.optLong("duration", 0L)
                    val thumb = item.optString("album_image", item.optString("image", ""))
                        .takeIf { it.startsWith("http") }

                    val cand = OnlineTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = durSec,
                        thumbnailUrl = thumb,
                        isOfficialAudio = true,
                        sourceEngine = "jamendo",
                        directStreamUrl = streamUrl,
                        qualityBadge = "CC MP3",
                        isPreviewOnly = false
                    )
                    if (RelevanceScorer.matchesSearchQuery(cand, query)) {
                        tracks.add(cand)
                    }
                }
                recordAttempt(
                    "jamendo",
                    "Jamendo",
                    "search",
                    if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                    200,
                    "Найдено ${tracks.size} полных треков",
                    t0
                )
                tracks
            }
        } catch (e: Exception) {
            recordAttempt("jamendo", "Jamendo", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    // ========================================================
    // 8. BILIBILI MUSIC (WBI Signed Search + DASH Audio .m4a)
    // ========================================================

    private val BILIBILI_MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    @Volatile
    private var cachedBilibiliBuvid3: String? = null

    @Volatile
    private var cachedBilibiliMixinKey: String? = null

    @Volatile
    private var bilibiliSessionFetchedAtMs: Long = 0L

    internal fun computeBilibiliMixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder()
        for (idx in BILIBILI_MIXIN_TAB) {
            if (idx in raw.indices) {
                sb.append(raw[idx])
            }
        }
        return sb.toString().take(32)
    }

    internal fun signBilibiliWbiQuery(
        params: Map<String, String>,
        mixinKey: String,
        wtsSeconds: Long = System.currentTimeMillis() / 1000L
    ): String {
        val allParams = sortedMapOf<String, String>()
        params.forEach { (k, v) ->
            allParams[k] = v.replace(Regex("[!'()*]"), "")
        }
        allParams["wts"] = wtsSeconds.toString()

        val encodedQuery = allParams.entries.joinToString("&") { (k, v) ->
            val ek = URLEncoder.encode(k, "UTF-8").replace("+", "%20")
            val ev = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            "$ek=$ev"
        }
        val md5 = MessageDigest.getInstance("MD5")
            .digest((encodedQuery + mixinKey).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$encodedQuery&w_rid=$md5"
    }

    private fun ensureBilibiliSession(): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        if (!cachedBilibiliBuvid3.isNullOrBlank() &&
            !cachedBilibiliMixinKey.isNullOrBlank() &&
            now - bilibiliSessionFetchedAtMs < 45 * 60_000L
        ) {
            return cachedBilibiliBuvid3 to cachedBilibiliMixinKey
        }
        try {
            if (cachedBilibiliBuvid3.isNullOrBlank()) {
                val spiReq = Request.Builder()
                    .url("https://api.bilibili.com/x/frontend/finger/spi")
                    .header("User-Agent", NetworkClient.BROWSER_UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                client.newCall(spiReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val b3 = JSONObject(resp.body?.string() ?: "{}")
                            .optJSONObject("data")
                            ?.optString("b_3", "")
                            ?.takeIf { it.isNotBlank() }
                        if (b3 != null) cachedBilibiliBuvid3 = b3
                    }
                }
            }
            val navReq = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Referer", "https://www.bilibili.com/")
                .apply {
                    cachedBilibiliBuvid3?.let { header("Cookie", "buvid3=$it") }
                }
                .build()
            client.newCall(navReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val wbiImg = JSONObject(resp.body?.string() ?: "{}")
                        .optJSONObject("data")
                        ?.optJSONObject("wbi_img")
                    val imgUrl = wbiImg?.optString("img_url", "") ?: ""
                    val subUrl = wbiImg?.optString("sub_url", "") ?: ""
                    val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
                    val subKey = subUrl.substringAfterLast('/').substringBefore('.')
                    if (imgKey.isNotBlank() && subKey.isNotBlank()) {
                        cachedBilibiliMixinKey = computeBilibiliMixinKey(imgKey, subKey)
                        bilibiliSessionFetchedAtMs = now
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bilibili session init failed: ${e.message}")
        }
        return cachedBilibiliBuvid3 to cachedBilibiliMixinKey
    }

    fun searchBilibili(query: String): List<OnlineTrack> {
        val t0 = System.currentTimeMillis()
        return try {
            val (buvid3, mixinKey) = ensureBilibiliSession()
            val queryParams = mapOf(
                "search_type" to "video",
                "keyword" to query.trim(),
                "page" to "1",
                "page_size" to "12"
            )
            val fullUrl = if (!mixinKey.isNullOrBlank()) {
                val signedQuery = signBilibiliWbiQuery(queryParams, mixinKey)
                "https://api.bilibili.com/x/web-interface/wbi/search/type?$signedQuery"
            } else {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encoded&page=1&page_size=12"
            }

            val req = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Accept", "application/json")
                .header("Origin", "https://search.bilibili.com")
                .header("Referer", "https://search.bilibili.com/")
                .apply {
                    if (!buvid3.isNullOrBlank()) header("Cookie", "buvid3=$buvid3")
                }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("bilibili", "Bilibili", "search", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return emptyList()
                }
                val root = JSONObject(resp.body?.string() ?: "{}")
                if (root.optInt("code", -1) != 0) {
                    val code = root.optInt("code", -1)
                    val msg = root.optString("message", "Bilibili code $code")
                    recordAttempt("bilibili", "Bilibili", "search", SourceAttemptStatus.HTTP_ERROR, code, msg, t0)
                    return emptyList()
                }
                val arr = root.optJSONObject("data")?.optJSONArray("result") ?: JSONArray()
                val tracks = mutableListOf<OnlineTrack>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val bvid = item.optString("bvid", "").trim()
                    val rawTitle = item.optString("title", "")
                        .replace(Regex("<[^>]+>"), "")
                    val title = decodeHtmlBasic(rawTitle)
                    if (bvid.isBlank() || title.isBlank()) continue

                    val durSec = parseBilibiliDuration(item.optString("duration", "0:00"))
                    if (durSec > 18 * 60L) continue // Skip ultra-long compilations/podcasts

                    val artist = item.optString("author", "Bilibili").trim().ifBlank { "Bilibili" }
                    val rawPic = item.optString("pic", "").trim()
                    val thumb = when {
                        rawPic.startsWith("//") -> "https:$rawPic"
                        rawPic.startsWith("http") -> rawPic
                        else -> null
                    }
                    val cand = OnlineTrack(
                        id = bvid,
                        title = title,
                        artist = artist,
                        album = "Bilibili Music",
                        durationSeconds = durSec,
                        thumbnailUrl = thumb,
                        isOfficialAudio = false,
                        sourceEngine = "bilibili",
                        directStreamUrl = null,
                        qualityBadge = "DASH M4A",
                        isPreviewOnly = false
                    )
                    if (RelevanceScorer.matchesSearchQuery(cand, query)) {
                        tracks.add(cand)
                    }
                }
                recordAttempt(
                    "bilibili",
                    "Bilibili",
                    "search",
                    if (tracks.isNotEmpty()) SourceAttemptStatus.SUCCESS else SourceAttemptStatus.EMPTY_RESULT,
                    200,
                    "Найдено ${tracks.size} треков",
                    t0
                )
                tracks
            }
        } catch (e: Exception) {
            recordAttempt("bilibili", "Bilibili", "search", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            emptyList()
        }
    }

    fun resolveBilibiliStream(bvid: String): ResolvedStream? {
        if (bvid.isBlank()) return null
        val t0 = System.currentTimeMillis()
        return try {
            val (buvid3, _) = ensureBilibiliSession()
            val referer = "https://www.bilibili.com/video/$bvid"
            val viewReq = Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Referer", referer)
                .apply {
                    if (!buvid3.isNullOrBlank()) header("Cookie", "buvid3=$buvid3")
                }
                .build()

            val cid = client.newCall(viewReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data")
                data?.optLong("cid", 0L)?.takeIf { it > 0L }
                    ?: data?.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid", 0L)?.takeIf { it > 0L }
            } ?: run {
                recordAttempt("bilibili", "Bilibili", "view", SourceAttemptStatus.EMPTY_RESULT, null, "Не найден cid для $bvid", t0)
                return null
            }

            val playReq = Request.Builder()
                .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=16&fourk=0")
                .header("User-Agent", NetworkClient.BROWSER_UA)
                .header("Referer", referer)
                .header("Origin", "https://www.bilibili.com")
                .apply {
                    if (!buvid3.isNullOrBlank()) header("Cookie", "buvid3=$buvid3")
                }
                .build()

            client.newCall(playReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    recordAttempt("bilibili", "Bilibili", "playurl", SourceAttemptStatus.HTTP_ERROR, resp.code, "HTTP ${resp.code}", t0)
                    return null
                }
                val data = JSONObject(resp.body?.string() ?: "{}").optJSONObject("data") ?: return null
                val dashAudios = data.optJSONObject("dash")?.optJSONArray("audio")
                val candidateUrls = mutableListOf<Pair<String, Int>>()

                if (dashAudios != null) {
                    for (i in 0 until dashAudios.length()) {
                        val item = dashAudios.optJSONObject(i) ?: continue
                        val bw = item.optInt("bandwidth", 0)
                        val base = item.optString("baseUrl", item.optString("base_url", ""))
                        if (base.startsWith("http")) candidateUrls.add(base to bw)
                        val backups = item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url")
                        if (backups != null) {
                            for (j in 0 until backups.length()) {
                                val bk = backups.optString(j, "")
                                if (bk.startsWith("http")) candidateUrls.add(bk to (bw - 1000))
                            }
                        }
                    }
                }
                if (candidateUrls.isEmpty()) {
                    val durl = data.optJSONArray("durl")
                    if (durl != null && durl.length() > 0) {
                        val u = durl.optJSONObject(0)?.optString("url", "") ?: ""
                        if (u.startsWith("http")) candidateUrls.add(u to 64_000)
                    }
                }

                val streamHeaders = buildMap {
                    put("Referer", referer)
                    put("Origin", "https://www.bilibili.com")
                    if (!buvid3.isNullOrBlank()) put("Cookie", "buvid3=$buvid3")
                }

                for ((streamUrl, _) in candidateUrls.sortedByDescending { it.second }.take(4)) {
                    val probeReq = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", NetworkClient.BROWSER_UA)
                        .header("Range", "bytes=0-63")
                        .apply { streamHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()
                    val ok = runCatching {
                        client.newCall(probeReq).execute().use { pr -> pr.code in listOf(200, 206, 302, 307) }
                    }.getOrDefault(false)
                    if (ok) {
                        recordAttempt("bilibili", "Bilibili", "playurl", SourceAttemptStatus.SUCCESS, 200, "DASH M4A поток готов", t0)
                        return ResolvedStream(
                            url = streamUrl,
                            userAgent = NetworkClient.BROWSER_UA,
                            headers = streamHeaders,
                            expiresAtMs = System.currentTimeMillis() + 90 * 60_000L,
                            engine = "bilibili"
                        )
                    }
                }

                recordAttempt("bilibili", "Bilibili", "playurl", SourceAttemptStatus.HTTP_ERROR, 403, "CDN Bilibili отклонил поток", t0)
                null
            }
        } catch (e: Exception) {
            recordAttempt("bilibili", "Bilibili", "playurl", SourceAttemptStatus.NETWORK_ERROR, null, e.message ?: "Ошибка", t0)
            null
        }
    }

    private fun parseBilibiliDuration(raw: String): Long {
        val parts = raw.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60L + parts[1]
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            else -> 0L
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
