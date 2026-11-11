package com.example.lyrics

import android.util.Log
import com.example.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

data class DualLyricLine(
    val timestampMs: Long,
    val primaryText: String,
    val secondaryText: String? = null,
    val isSynced: Boolean = true
)

data class LyricsState(
    val title: String = "",
    val artist: String = "",
    val lines: List<DualLyricLine> = emptyList(),
    val plainLyrics: String = "",
    val source: String = "",
    val hasDualLines: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class LyricsManager {
    private val client = NetworkClient.okHttpClient
    private val lrcRegex = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?\\](.*)")

    /**
     * Fetches synchronized and parallel lyrics from LRCLIB and multi-source fallbacks.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L
    ): LyricsState = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(title)
        val cleanArtist = cleanArtistName(artist)

        try {
            // 1. Direct match on LRCLIB
            val directLrc = fetchFromLrclibDirect(cleanTitle, cleanArtist, durationSeconds)
            if (directLrc != null) {
                return@withContext directLrc
            }

            // 2. Search match on LRCLIB
            val searchLrc = fetchFromLrclibSearch("$cleanArtist $cleanTitle")
            if (searchLrc != null) {
                return@withContext searchLrc
            }

            // 3. Fallback lyrics search
            val fallback = fetchFromFallbackSources(cleanTitle, cleanArtist)
            if (fallback != null) {
                return@withContext fallback
            }

            LyricsState(
                title = title,
                artist = artist,
                errorMessage = "Текст не найден. Попробуйте ручной поиск в строке выше."
            )
        } catch (e: Exception) {
            Log.e("LyricsManager", "Error fetching lyrics", e)
            LyricsState(
                title = title,
                artist = artist,
                errorMessage = "Ошибка подключения к сервису текстов: ${e.localizedMessage}"
            )
        }
    }

    private suspend fun fetchFromLrclibDirect(
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsState? = withContext(Dispatchers.IO) {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val durParam = if (durationSeconds > 0) "&duration=$durationSeconds" else ""
            val url = "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist$durParam"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("L-User-Agent", "imux-music-player/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    return@withContext parseLrclibJson(json, title, artist)
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "fetchFromLrclibDirect failed", e)
        }
        null
    }

    private suspend fun fetchFromLrclibSearch(query: String): LyricsState? = withContext(Dispatchers.IO) {
        try {
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encQuery"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("L-User-Agent", "imux-music-player/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        val firstMatch = array.getJSONObject(0)
                        return@withContext parseLrclibJson(firstMatch, query, "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "fetchFromLrclibSearch failed", e)
        }
        null
    }

    private suspend fun fetchFromFallbackSources(title: String, artist: String): LyricsState? = withContext(Dispatchers.IO) {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://api.lyrics.ovh/v1/$encArtist/$encTitle"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val json = JSONObject(body)
                    val rawLyrics = json.optString("lyrics", "")
                    if (rawLyrics.isNotBlank()) {
                        val plainLines = rawLyrics.lines().mapIndexed { index, line ->
                            DualLyricLine(
                                timestampMs = index * 3000L,
                                primaryText = line,
                                isSynced = false
                            )
                        }
                        return@withContext LyricsState(
                            title = title,
                            artist = artist,
                            lines = plainLines,
                            plainLyrics = rawLyrics,
                            source = "Lyrics.ovh"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // ignore fallback error
        }
        null
    }

    private fun parseLrclibJson(json: JSONObject, title: String, artist: String): LyricsState {
        val syncedLrc = json.optString("syncedLyrics", "")
        val plainLyrics = json.optString("plainLyrics", "")
        val trackName = json.optString("trackName", title)
        val artistName = json.optString("artistName", artist)

        if (syncedLrc.isNotBlank()) {
            val parsedLines = parseLrc(syncedLrc)
            val hasDual = parsedLines.any { it.secondaryText != null }
            return LyricsState(
                title = trackName,
                artist = artistName,
                lines = parsedLines,
                plainLyrics = plainLyrics.ifBlank { parsedLines.joinToString("\n") { it.primaryText } },
                source = "LRCLIB (Синхронизировано)",
                hasDualLines = hasDual
            )
        } else if (plainLyrics.isNotBlank()) {
            val lines = plainLyrics.lines().mapIndexed { index, line ->
                DualLyricLine(
                    timestampMs = index * 3000L,
                    primaryText = line,
                    isSynced = false
                )
            }
            return LyricsState(
                title = trackName,
                artist = artistName,
                lines = lines,
                plainLyrics = plainLyrics,
                source = "LRCLIB (Текст)",
                hasDualLines = false
            )
        }

        return LyricsState(title = title, artist = artist, errorMessage = "Текст пуст")
    }

    /**
     * Parses standard and dual/parallel LRC format.
     * Supports:
     * - Inline dual line: [00:15.30] Original Verse // Translation Verse
     * - Inline pipe dual line: [00:15.30] Original | Translation
     * - Multi-timestamp twin lines with identical timestamp: [00:15.30] Verse 1, [00:15.30] Translation
     */
    fun parseLrc(lrcText: String): List<DualLyricLine> {
        val rawLines = lrcText.lines()
        val tempMap = mutableMapOf<Long, MutableList<String>>()

        for (line in rawLines) {
            val matcher = lrcRegex.matcher(line.trim())
            if (matcher.matches()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3) ?: "0"
                val ms = when (msStr.length) {
                    2 -> msStr.toLong() * 10L
                    3 -> msStr.toLong()
                    else -> 0L
                }
                val totalMs = (min * 60 + sec) * 1000L + ms
                val text = matcher.group(4)?.trim() ?: ""

                if (text.isNotBlank()) {
                    val list = tempMap.getOrPut(totalMs) { mutableListOf() }
                    list.add(text)
                }
            }
        }

        val result = mutableListOf<DualLyricLine>()

        tempMap.toSortedMap().forEach { (timestamp, texts) ->
            if (texts.size >= 2) {
                // Dual lines paired by identical timestamp
                result.add(
                    DualLyricLine(
                        timestampMs = timestamp,
                        primaryText = texts[0],
                        secondaryText = texts[1],
                        isSynced = true
                    )
                )
            } else {
                val singleText = texts[0]
                // Check for inline delimiters: "//" or "|"
                val (primary, secondary) = when {
                    singleText.contains(" // ") -> {
                        val parts = singleText.split(" // ", limit = 2)
                        Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                    }
                    singleText.contains(" | ") -> {
                        val parts = singleText.split(" | ", limit = 2)
                        Pair(parts[0].trim(), parts.getOrNull(1)?.trim())
                    }
                    else -> Pair(singleText, null)
                }

                result.add(
                    DualLyricLine(
                        timestampMs = timestamp,
                        primaryText = primary,
                        secondaryText = secondary,
                        isSynced = true
                    )
                )
            }
        }

        return result
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\[(official|video|audio|lyrics|hd|4k|music video|visualizer|remastered).*?\\]"), "")
            .replace(Regex("(?i)\\((official|video|audio|lyrics|hd|4k|music video|visualizer|remastered).*?\\)"), "")
            .replace(Regex("(?i)ft\\..*"), "")
            .replace(Regex("(?i)feat\\..*"), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex("(?i) - topic"), "")
            .replace(Regex("(?i)vevo"), "")
            .trim()
    }
}
