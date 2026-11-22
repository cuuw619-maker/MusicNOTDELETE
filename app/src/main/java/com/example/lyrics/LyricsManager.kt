package com.example.lyrics

import android.util.Log
import androidx.compose.runtime.Immutable
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
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.abs

@Immutable
enum class LyricSpeaker {
    FIRST,
    SECOND,
    BOTH,
    BACKGROUND
}

@Immutable
data class LyricWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val trailingSpace: Boolean = true,
    val syllableGroupId: Int = -1
)

@Immutable
data class ParallelLyricLine(
    val text: String,
    val timestampMs: Long,
    val endTimeMs: Long,
    val words: List<LyricWord> = emptyList(),
    val speakerLabel: String? = null
)

@Immutable
data class DualLyricLine(
    val timestampMs: Long,
    val endTimeMs: Long = timestampMs + 3000L,
    val primaryText: String,
    val secondaryText: String? = null,
    val words: List<LyricWord> = emptyList(),
    val parallelLines: List<ParallelLyricLine> = emptyList(),
    val isSynced: Boolean = true,
    val hasWordTiming: Boolean = false,
    val isInstrumental: Boolean = false,
    val speaker: LyricSpeaker = LyricSpeaker.FIRST,
    val speakerLabel: String? = null,
    val isBackground: Boolean = false
)

@Immutable
data class LyricsState(
    val title: String = "",
    val artist: String = "",
    val lines: List<DualLyricLine> = emptyList(),
    val plainLyrics: String = "",
    val source: String = "",
    val isSynced: Boolean = false,
    val hasDualLines: Boolean = false,
    val hasWordSync: Boolean = false,
    val hasDuet: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class LyricsManager {
    private val client = NetworkClient.okHttpClient
    private val lrcLineRegex = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)$")
    private val wordTagRegex = Regex("<(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?>([^<]*)")
    private val lyricsCache = ConcurrentHashMap<String, LyricsState>()

    companion object {
        val LYRICS_PLUS_ENDPOINTS = listOf(
            "https://lyricsplus.binimum.org/v2/lyrics/get",
            "https://lyricsplus.atomix.one/v2/lyrics/get",
            "https://lyricsplus-seven.vercel.app/v2/lyrics/get",
            "https://lyricsplus.prjktla.workers.dev/v2/lyrics/get",
            "https://lyrics-plus-backend.vercel.app/v2/lyrics/get"
        )
    }

    /**
     * Multi-source concurrent lyrics engine (All 5 LyricsPlus mirrors + Meld sources):
     * Queries BetterLyrics TTML, all 5 LyricsPlus mirrors, SimpMusic, KuGou KRC, YTM native,
     * LRCLIB, and NetEase concurrently, then selects the richest version (preferring native
     * per-word/syllable timing + parallel backing vocals + duet alignment).
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Long = 0L,
        youtubeVideoId: String? = null,
        ytStreamManager: com.example.online.YouTubeStreamManager? = null
    ): LyricsState = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(title)
        val cleanArtist = cleanArtistName(artist)
        val cacheKey = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}|$durationSeconds|${youtubeVideoId.orEmpty()}"
        lyricsCache[cacheKey]?.let { return@withContext it }

        try {
            val queryTitle = cleanTitle.ifBlank { title.trim() }
            val queryArtist = cleanArtist
            val searchQuery = if (queryArtist.isNotBlank()) "$queryArtist $queryTitle" else queryTitle

            val candidates = coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<LyricsState?>>()

                if (queryTitle.isNotBlank() && queryArtist.isNotBlank()) {
                    tasks += async {
                        withTimeoutOrNull(4600L) {
                            fetchBetterLyricsTtml(queryTitle, queryArtist, durationSeconds)
                        }
                    }
                    for (mirrorBase in LYRICS_PLUS_ENDPOINTS) {
                        tasks += async {
                            withTimeoutOrNull(4500L) {
                                fetchLyricsPlusMirror(mirrorBase, queryTitle, queryArtist, durationSeconds)
                            }
                        }
                    }
                    tasks += async {
                        withTimeoutOrNull(4500L) {
                            fetchFromLrclibDirect(queryTitle, queryArtist, durationSeconds)
                        }
                    }
                }

                if (!youtubeVideoId.isNullOrBlank()) {
                    tasks += async {
                        withTimeoutOrNull(4500L) {
                            fetchSimpMusicLyrics(youtubeVideoId, title, artist)
                        }
                    }
                    if (ytStreamManager != null) {
                        tasks += async {
                            withTimeoutOrNull(4500L) {
                                fetchYtmNativeLyricsState(youtubeVideoId, title, artist, ytStreamManager)
                            }
                        }
                    }
                }

                if (queryTitle.isNotBlank()) {
                    tasks += async {
                        withTimeoutOrNull(4800L) {
                            KuGouLyricsProvider.fetchLyrics(
                                client = client,
                                title = queryTitle,
                                artist = queryArtist,
                                durationSeconds = durationSeconds,
                                inlineSpeakerParser = { raw -> extractInlineSpeakerPrefix(raw) },
                                synthesizeWords = { txt, st, en -> synthesizeNaturalWords(txt, st, en) },
                                finalizeLines = { raw -> insertInstrumentalBreaks(preprocessParallelLines(raw)) },
                                fallbackLrcParser = { rawLrc -> parseLrc(rawLrc) }
                            )
                        }
                    }
                    tasks += async {
                        withTimeoutOrNull(4500L) {
                            fetchFromLrclibSearch(searchQuery, queryTitle, queryArtist, durationSeconds)
                        }
                    }
                    tasks += async {
                        withTimeoutOrNull(4800L) {
                            fetchFromNetEase(searchQuery, queryTitle, durationSeconds)
                        }
                    }
                }

                tasks.awaitAll().filterNotNull().filter { it.lines.isNotEmpty() || it.plainLyrics.isNotBlank() }
            }

            val bestCandidate = candidates.maxByOrNull { scoreLyricsCandidate(it, durationSeconds) }
            if (bestCandidate != null) {
                lyricsCache[cacheKey] = bestCandidate
                return@withContext bestCandidate
            }

            if (queryTitle.isNotBlank() && queryArtist.isNotBlank()) {
                val fallback = withTimeoutOrNull(4000L) {
                    fetchFromFallbackSources(queryTitle, queryArtist)
                }
                if (fallback != null && fallback.lines.isNotEmpty()) {
                    lyricsCache[cacheKey] = fallback
                    return@withContext fallback
                }
            }

            LyricsState(
                title = title,
                artist = artist,
                isSynced = false,
                errorMessage = "Текст не найден. Нажмите на лупу для ручного поиска или вставьте LRC / TTML."
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LyricsManager", "Error fetching lyrics", e)
            LyricsState(
                title = title,
                artist = artist,
                isSynced = false,
                errorMessage = "Ошибка загрузки текста: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Scores candidate lyrics strictly prioritizing:
     * 1) Native word/syllable sync + 2) Parallel backing sub-lines + 3) Duets & translations +
     * 4) Track duration consistency.
     */
    fun scoreLyricsCandidate(state: LyricsState?, expectedDurationSec: Long = 0L): Int {
        if (state == null) return Int.MIN_VALUE
        val nonInst = state.lines.filter { !it.isInstrumental }
        if (nonInst.isEmpty() && state.plainLyrics.isBlank()) return Int.MIN_VALUE

        val lineCount = nonInst.size
        val wordSyncedLines = nonInst.count { it.hasWordTiming && it.words.isNotEmpty() }
        val parallelCount = nonInst.sumOf { it.parallelLines.size }

        var score = 0
        if (state.isSynced) score += 85
        if (state.hasWordSync && wordSyncedLines > 0) {
            val densityBonus = (wordSyncedLines * 45) / lineCount.coerceAtLeast(1)
            score += 240 + densityBonus
        }
        if (parallelCount > 0) {
            score += 140 + (parallelCount * 6).coerceAtMost(80)
        }
        if (state.hasDuet) score += 90
        if (state.hasDualLines) score += 45
        score += lineCount.coerceAtMost(60)

        if (expectedDurationSec > 20L && state.isSynced && nonInst.isNotEmpty()) {
            val lastLineEndSec = (nonInst.maxOf { maxOf(it.timestampMs, it.endTimeMs) }) / 1000L
            if (lastLineEndSec > expectedDurationSec + 35L || lastLineEndSec < (expectedDurationSec * 0.25).toLong()) {
                score -= 95
            }
        }

        val src = state.source.lowercase()
        score += when {
            src.contains("betterlyrics") -> 12
            src.contains("lyricsplus") -> 11
            src.contains("kugou krc") -> 10
            src.contains("netease yrc") -> 9
            src.contains("simpmusic") -> 8
            src.contains("lrclib sync") -> 6
            src.contains("youtube") -> 4
            else -> 1
        }
        return score
    }

    private suspend fun fetchYtmNativeLyricsState(
        youtubeVideoId: String,
        title: String,
        artist: String,
        ytStreamManager: com.example.online.YouTubeStreamManager
    ): LyricsState? {
        val ytmNative = ytStreamManager.fetchYouTubeMusicNativeLyrics(youtubeVideoId) ?: return null
        if (ytmNative.isSynced && ytmNative.timedLines.isNotEmpty()) {
            val timed = ytmNative.timedLines.mapIndexed { idx, (startMs, lineText) ->
                val nextStart = ytmNative.timedLines.getOrNull(idx + 1)?.first ?: (startMs + 3800L)
                val endMs = (nextStart - 80L).coerceAtLeast(startMs + 450L)
                val (sp, lbl, cleanLine) = extractInlineSpeakerPrefix(lineText)
                DualLyricLine(
                    timestampMs = startMs,
                    endTimeMs = endMs,
                    primaryText = cleanLine,
                    words = synthesizeNaturalWords(cleanLine, startMs, endMs),
                    isSynced = true,
                    hasWordTiming = false,
                    speaker = sp,
                    speakerLabel = lbl,
                    isBackground = sp == LyricSpeaker.BACKGROUND
                )
            }
            val enriched = insertInstrumentalBreaks(preprocessParallelLines(timed))
            return LyricsState(
                title = title,
                artist = artist,
                lines = enriched,
                plainLyrics = ytmNative.plainText,
                source = ytmNative.sourceLabel,
                isSynced = true,
                hasDualLines = enriched.any { !it.secondaryText.isNullOrBlank() },
                hasWordSync = false,
                hasDuet = enriched.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
            )
        } else if (ytmNative.plainText.isNotBlank()) {
            val plainLines = preprocessParallelLines(
                ytmNative.plainText.lines().filter { it.isNotBlank() }.map { line ->
                    val (sp, lbl, cleanLine) = extractInlineSpeakerPrefix(line.trim())
                    DualLyricLine(
                        timestampMs = -1L,
                        primaryText = cleanLine,
                        isSynced = false,
                        speaker = sp,
                        speakerLabel = lbl,
                        isBackground = sp == LyricSpeaker.BACKGROUND
                    )
                }
            )
            return LyricsState(
                title = title,
                artist = artist,
                lines = plainLines,
                plainLyrics = ytmNative.plainText,
                source = ytmNative.sourceLabel,
                isSynced = false,
                hasDualLines = false,
                hasDuet = plainLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
            )
        }
        return null
    }

    private fun fetchSimpMusicLyrics(
        videoId: String,
        title: String,
        artist: String
    ): LyricsState? {
        return try {
            val url = "https://api-lyrics.simpmusic.org/v1/$videoId"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "SimpMusic/0.2.15 (imux-player)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim() ?: return null
                parseSimpMusicResponse(body, title, artist)
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "SimpMusic API failed for $videoId: ${e.message}")
            null
        }
    }

    fun parseSimpMusicResponse(
        body: String,
        fallbackTitle: String,
        fallbackArtist: String
    ): LyricsState? {
        if (body.isBlank()) return null
        return try {
            val root = JSONObject(body)
            val dataObj = when {
                root.optJSONArray("data")?.length() ?: 0 > 0 ->
                    root.optJSONArray("data")?.optJSONObject(0)
                root.optJSONObject("data") != null ->
                    root.optJSONObject("data")
                else -> root
            } ?: return null

            val rich = dataObj.optString("richSyncLyrics", "").trim()
            val synced = dataObj.optString("syncedLyrics", "").trim()
            val plain = dataObj.optString("plainLyrics", "").trim()

            val richLines = if (rich.isNotBlank()) parseLrc(rich) else emptyList()
            if (richLines.isNotEmpty()) {
                return LyricsState(
                    title = fallbackTitle,
                    artist = fallbackArtist,
                    lines = richLines,
                    plainLyrics = plain.ifBlank { richLines.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText } },
                    source = "SimpMusic Rich",
                    isSynced = richLines.any { it.isSynced },
                    hasDualLines = richLines.any { !it.secondaryText.isNullOrBlank() },
                    hasWordSync = richLines.any { it.hasWordTiming },
                    hasDuet = richLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                )
            }

            val syncedLines = if (synced.isNotBlank()) parseLrc(synced) else emptyList()
            if (syncedLines.isNotEmpty()) {
                return LyricsState(
                    title = fallbackTitle,
                    artist = fallbackArtist,
                    lines = syncedLines,
                    plainLyrics = plain.ifBlank { syncedLines.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText } },
                    source = "SimpMusic Sync",
                    isSynced = syncedLines.any { it.isSynced },
                    hasDualLines = syncedLines.any { !it.secondaryText.isNullOrBlank() },
                    hasWordSync = syncedLines.any { it.hasWordTiming },
                    hasDuet = syncedLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                )
            }

            if (plain.isNotBlank()) {
                val plainLines = preprocessParallelLines(
                    plain.lines().filter { it.isNotBlank() }.map { line ->
                        val (sp, lbl, clean) = extractInlineSpeakerPrefix(line.trim())
                        DualLyricLine(
                            timestampMs = -1L,
                            primaryText = clean,
                            isSynced = false,
                            speaker = sp,
                            speakerLabel = lbl,
                            isBackground = sp == LyricSpeaker.BACKGROUND
                        )
                    }
                )
                return LyricsState(
                    title = fallbackTitle,
                    artist = fallbackArtist,
                    lines = plainLines,
                    plainLyrics = plain,
                    source = "SimpMusic Text",
                    isSynced = false,
                    hasDuet = plainLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                )
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBetterLyricsTtml(
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsState? {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val durParam = if (durationSeconds > 0) "&d=$durationSeconds" else ""
        val endpoints = listOf(
            "https://lyrics-api.boidu.dev/getLyrics?a=$encArtist&s=$encTitle$durParam",
            "https://lyrics-api.boidu.dev/ttml/getLyrics?a=$encArtist&s=$encTitle$durParam"
        )

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, application/xml, text/xml")
                    .header("User-Agent", "imux-music-player/2.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string()?.trim() ?: return@use
                    val ttmlContent = when {
                        body.startsWith("{") -> {
                            val json = JSONObject(body)
                            json.optString("ttml", "")
                                .ifBlank { json.optString("lyrics", "") }
                        }
                        body.contains("<tt") -> body
                        else -> ""
                    }
                    if (!ttmlContent.contains("<tt") || !ttmlContent.contains("<p")) return@use

                    val parsed = insertInstrumentalBreaks(preprocessParallelLines(parseTtml(ttmlContent)))
                    if (parsed.isNotEmpty()) {
                        return LyricsState(
                            title = title,
                            artist = artist,
                            lines = parsed,
                            plainLyrics = parsed.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText },
                            source = "BetterLyrics TTML",
                            isSynced = parsed.any { it.isSynced },
                            hasDualLines = parsed.any { !it.secondaryText.isNullOrBlank() },
                            hasWordSync = parsed.any { it.hasWordTiming },
                            hasDuet = parsed.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("LyricsManager", "BetterLyrics endpoint failed ($url): ${e.message}")
            }
        }
        return null
    }

    private fun fetchLyricsPlusMirror(
        baseUrl: String,
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsState? {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val durParam = if (durationSeconds > 0) "&duration=$durationSeconds" else ""
        val url = "$baseUrl?title=$encTitle&artist=$encArtist$durParam"
        val hostLabel = runCatching { java.net.URI(baseUrl).host?.removePrefix("www.") }.getOrNull().orEmpty()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "imux-music-player/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseLyricsPlusJson(body, title, artist, hostLabel)
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "LyricsPlus mirror failed ($url): ${e.message}")
            null
        }
    }

    fun parseLyricsPlusJson(
        body: String,
        title: String,
        artist: String,
        mirrorHost: String = ""
    ): LyricsState? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            val lyricsArr = json.optJSONArray("lyrics") ?: return null
            if (lyricsArr.length() == 0) return null

            val type = json.optString("type", "")
            val parsedLines = mutableListOf<DualLyricLine>()
            val plainSb = StringBuilder()

            for (i in 0 until lyricsArr.length()) {
                val lineObj = lyricsArr.optJSONObject(i) ?: continue
                val startTime = lineObj.optLong("time", 0L)
                val dur = lineObj.optLong("duration", 3000L).coerceAtLeast(300L)
                val rawText = lineObj.optString("text", "").trim()
                if (rawText.isBlank()) continue

                // Extract duet/agent metadata from LyricsPlus element object
                val elemObj = lineObj.optJSONObject("element")
                val agentRaw = elemObj?.optString("singer", "")
                    ?.ifBlank { elemObj.optString("key", "") }
                    ?.ifBlank { lineObj.optString("agent", "") }
                    ?: ""
                val isBg = elemObj?.optBoolean("isBackground", false) == true ||
                    lineObj.optBoolean("isBackground", false)

                val (speakerFromAgent, labelFromAgent) = mapAgentToSpeaker(agentRaw, isBg)
                val (speaker, label, text) = extractInlineSpeakerPrefix(
                    raw = rawText,
                    fallbackSpeaker = speakerFromAgent,
                    fallbackLabel = labelFromAgent,
                    isBg = isBg
                )

                val syllabusArr = lineObj.optJSONArray("syllabus")
                val words = mutableListOf<LyricWord>()
                if (syllabusArr != null && syllabusArr.length() > 0) {
                    var groupId = 0
                    for (w in 0 until syllabusArr.length()) {
                        val sym = syllabusArr.optJSONObject(w) ?: continue
                        val rawSymText = sym.optString("text", "")
                        val wText = rawSymText.trim()
                        val wStart = sym.optLong("time", startTime)
                        val wDur = sym.optLong("duration", 250L).coerceAtLeast(60L)
                        if (wText.isNotEmpty()) {
                            val nextSym = if (w + 1 < syllabusArr.length()) syllabusArr.optJSONObject(w + 1) else null
                            val nextTrimmed = nextSym?.optString("text", "")?.trim().orEmpty()
                            val isExplicitPart = sym.optBoolean("isWordPart", false) ||
                                sym.optBoolean("isPartOfWord", false)
                            val fusedWithNext = nextTrimmed.isNotEmpty() &&
                                rawSymText.lastOrNull()?.isWhitespace() != true &&
                                text.contains("$wText$nextTrimmed", ignoreCase = true)
                            val hasSpaceAfter = !(isExplicitPart || fusedWithNext)
                            words.add(
                                LyricWord(
                                    text = wText,
                                    startTimeMs = wStart,
                                    endTimeMs = wStart + wDur,
                                    trailingSpace = hasSpaceAfter,
                                    syllableGroupId = groupId
                                )
                            )
                            if (hasSpaceAfter) groupId++
                        }
                    }
                }

                val translationObj = lineObj.optJSONObject("translation")
                val secondary = translationObj?.optString("text", null)?.takeIf { it.isNotBlank() }

                val finalWords = if (words.isNotEmpty()) words
                else synthesizeNaturalWords(text, startTime, startTime + dur)

                parsedLines.add(
                    DualLyricLine(
                        timestampMs = startTime,
                        endTimeMs = startTime + dur,
                        primaryText = text,
                        secondaryText = secondary,
                        words = finalWords,
                        isSynced = true,
                        hasWordTiming = words.isNotEmpty(),
                        speaker = speaker,
                        speakerLabel = label,
                        isBackground = isBg || speaker == LyricSpeaker.BACKGROUND
                    )
                )
                plainSb.appendLine(text)
            }

            if (parsedLines.isEmpty()) return null
            val enriched = insertInstrumentalBreaks(preprocessParallelLines(parsedLines))
            val tag = if (type.equals("Word", true)) "LyricsPlus Word-Sync" else "LyricsPlus Sync"
            val sourceName = if (mirrorHost.isNotBlank()) "$tag ($mirrorHost)" else tag
            LyricsState(
                title = title,
                artist = artist,
                lines = enriched,
                plainLyrics = plainSb.toString().trim(),
                source = sourceName,
                isSynced = enriched.any { it.isSynced },
                hasDualLines = enriched.any { !it.secondaryText.isNullOrBlank() },
                hasWordSync = enriched.any { it.hasWordTiming },
                hasDuet = enriched.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromLrclibDirect(
        title: String,
        artist: String,
        durationSeconds: Long
    ): LyricsState? {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val durParam = if (durationSeconds > 0) "&duration=$durationSeconds" else ""
            val url = "https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist$durParam"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Lrclib-Client", "imux-player/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    return parseLrclibJson(JSONObject(body), title, artist)
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "fetchFromLrclibDirect failed: ${e.message}")
        }
        return null
    }

    private fun fetchFromLrclibSearch(
        query: String,
        targetTitle: String,
        targetArtist: String,
        durationSeconds: Long
    ): LyricsState? {
        try {
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encQuery"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Lrclib-Client", "imux-player/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val arr = JSONArray(response.body?.string() ?: "[]")
                    if (arr.length() == 0) return null

                    var bestObj: JSONObject? = null
                    var bestScore = Int.MIN_VALUE

                    for (i in 0 until minOf(arr.length(), 10)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val candTitle = obj.optString("trackName", "").lowercase()
                        val candArtist = obj.optString("artistName", "").lowercase()
                        val candDur = obj.optDouble("duration", 0.0).toLong()
                        val hasSynced = obj.optString("syncedLyrics", "").isNotBlank()

                        var score = if (hasSynced) 80 else 10
                        if (candTitle == targetTitle.lowercase()) score += 45
                        else if (candTitle.contains(targetTitle.lowercase())) score += 25

                        if (targetArtist.isNotBlank() && candArtist.contains(targetArtist.lowercase())) {
                            score += 35
                        }

                        if (durationSeconds > 10 && candDur > 10) {
                            val diff = abs(candDur - durationSeconds)
                            score += when {
                                diff <= 2 -> 70
                                diff <= 5 -> 45
                                diff <= 12 -> 15
                                diff <= 25 -> -20
                                else -> -70
                            }
                        }

                        if (score > bestScore) {
                            bestScore = score
                            bestObj = obj
                        }
                    }

                    if (bestObj != null) {
                        return parseLrclibJson(bestObj, targetTitle, targetArtist)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "fetchFromLrclibSearch failed: ${e.message}")
        }
        return null
    }

    private fun parseLrclibJson(
        obj: JSONObject,
        fallbackTitle: String,
        fallbackArtist: String
    ): LyricsState? {
        val trackName = obj.optString("trackName", fallbackTitle)
        val artistName = obj.optString("artistName", fallbackArtist)
        val synced = obj.optString("syncedLyrics", "")
        val plain = obj.optString("plainLyrics", "")

        if (synced.isNotBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) {
                return LyricsState(
                    title = trackName,
                    artist = artistName,
                    lines = lines,
                    plainLyrics = plain.ifBlank { lines.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText } },
                    source = "LRCLIB Sync",
                    isSynced = true,
                    hasDualLines = lines.any { !it.secondaryText.isNullOrBlank() },
                    hasWordSync = lines.any { it.hasWordTiming },
                    hasDuet = lines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                )
            }
        }

        if (plain.isNotBlank()) {
            val plainLines = preprocessParallelLines(
                plain.lines().filter { it.isNotBlank() }.map { line ->
                    val (sp, lbl, clean) = extractInlineSpeakerPrefix(line.trim())
                    DualLyricLine(
                        timestampMs = -1L,
                        primaryText = clean,
                        isSynced = false,
                        speaker = sp,
                        speakerLabel = lbl,
                        isBackground = sp == LyricSpeaker.BACKGROUND
                    )
                }
            )
            return LyricsState(
                title = trackName,
                artist = artistName,
                lines = plainLines,
                plainLyrics = plain,
                source = "LRCLIB Text",
                isSynced = false,
                hasDualLines = false,
                hasDuet = plainLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
            )
        }
        return null
    }

    private fun fetchFromNetEase(
        query: String,
        targetTitle: String,
        durationSeconds: Long
    ): LyricsState? {
        try {
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$encQuery&type=1&offset=0&limit=5"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("Referer", "https://music.163.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .build()

            var bestSongId: Long = -1L
            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
                var bestDiff = Long.MAX_VALUE
                for (i in 0 until songs.length()) {
                    val s = songs.optJSONObject(i) ?: continue
                    val id = s.optLong("id", -1L)
                    if (id <= 0L) continue
                    val durSec = s.optLong("duration", 0L) / 1000L
                    val diff = if (durationSeconds > 0 && durSec > 0) abs(durSec - durationSeconds) else 0L
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestSongId = id
                    }
                }
                if (bestDiff > 20 && durationSeconds > 15) return null
            }

            if (bestSongId <= 0L) return null

            val lyricUrl = "https://music.163.com/api/song/lyric?id=$bestSongId&lv=1&tv=1&yv=1"
            val lyricReq = Request.Builder()
                .url(lyricUrl)
                .header("Referer", "https://music.163.com/")
                .build()

            client.newCall(lyricReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val yrcRaw = json.optJSONObject("yrc")?.optString("lyric", "") ?: ""
                val lrcRaw = json.optJSONObject("lrc")?.optString("lyric", "") ?: ""
                val tLrcRaw = json.optJSONObject("tlyric")?.optString("lyric", "") ?: ""

                val primaryLines = when {
                    yrcRaw.isNotBlank() -> parseNetEaseYrc(yrcRaw)
                    lrcRaw.isNotBlank() -> parseStandardOrEnhancedLrc(lrcRaw)
                    else -> emptyList()
                }
                if (primaryLines.isEmpty()) return null

                val mergedWithTrans = if (tLrcRaw.isNotBlank()) {
                    val transMap = parseTimestampToTextMap(tLrcRaw)
                    primaryLines.map { line ->
                        val trans = transMap[line.timestampMs]
                            ?: transMap.entries.minByOrNull { abs(it.key - line.timestampMs) }
                                ?.takeIf { abs(it.key - line.timestampMs) <= 450L }?.value
                        if (!trans.isNullOrBlank() && line.secondaryText.isNullOrBlank()) {
                            line.copy(secondaryText = trans)
                        } else line
                    }
                } else primaryLines

                val enriched = insertInstrumentalBreaks(preprocessParallelLines(mergedWithTrans))
                return LyricsState(
                    title = targetTitle,
                    artist = "",
                    lines = enriched,
                    plainLyrics = enriched.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText },
                    source = if (yrcRaw.isNotBlank()) "NetEase YRC + Перевод" else "NetEase Sync",
                    isSynced = true,
                    hasDualLines = enriched.any { !it.secondaryText.isNullOrBlank() },
                    hasWordSync = enriched.any { it.hasWordTiming },
                    hasDuet = enriched.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                )
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "NetEase lyrics failed: ${e.message}")
        }
        return null
    }

    private fun parseTimestampToTextMap(lrc: String): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        for (line in lrc.lines()) {
            val m = lrcLineRegex.matcher(line.trim())
            if (m.find()) {
                val ts = parseLrcTimestampMs(m.group(1), m.group(2), m.group(3))
                val txt = m.group(4)?.trim() ?: ""
                if (txt.isNotBlank()) map[ts] = txt
            }
        }
        return map
    }

    private fun parseNetEaseYrc(yrc: String): List<DualLyricLine> {
        val result = mutableListOf<DualLyricLine>()
        val linePattern = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
        val wordPattern = Regex("\\((\\d+),(\\d+),\\d+\\)([^\\(]*)")

        for (raw in yrc.lines()) {
            val lm = linePattern.matchEntire(raw.trim()) ?: continue
            val startMs = lm.groupValues[1].toLongOrNull() ?: continue
            val durMs = lm.groupValues[2].toLongOrNull()?.coerceAtLeast(300L) ?: 3000L
            val content = lm.groupValues[3]

            val words = mutableListOf<LyricWord>()
            val sb = StringBuilder()
            for (wm in wordPattern.findAll(content)) {
                val wStart = wm.groupValues[1].toLongOrNull() ?: startMs
                val wDur = wm.groupValues[2].toLongOrNull()?.coerceAtLeast(60L) ?: 200L
                val wText = wm.groupValues[3]
                if (wText.isNotBlank()) {
                    words.add(LyricWord(wText.trim(), wStart, wStart + wDur))
                }
                sb.append(wText)
            }
            val fullText = sb.toString().trim()
            if (fullText.isBlank()) continue
            val (sp, lbl, clean) = extractInlineSpeakerPrefix(fullText)
            result.add(
                DualLyricLine(
                    timestampMs = startMs,
                    endTimeMs = startMs + durMs,
                    primaryText = clean,
                    words = words.ifEmpty { synthesizeNaturalWords(clean, startMs, startMs + durMs) },
                    isSynced = true,
                    hasWordTiming = words.isNotEmpty(),
                    speaker = sp,
                    speakerLabel = lbl,
                    isBackground = sp == LyricSpeaker.BACKGROUND
                )
            )
        }
        return result
    }

    private fun fetchFromFallbackSources(title: String, artist: String): LyricsState? {
        try {
            val encArtist = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
            val encTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val url = "https://api.lyrics.ovh/v1/$encArtist/$encTitle"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val lyrics = json.optString("lyrics", "").trim()
                    if (lyrics.isNotBlank()) {
                        val plainLines = preprocessParallelLines(
                            lyrics.lines().filter { it.isNotBlank() }.map { line ->
                                val (sp, lbl, clean) = extractInlineSpeakerPrefix(line.trim())
                                DualLyricLine(
                                    timestampMs = -1L,
                                    primaryText = clean,
                                    isSynced = false,
                                    speaker = sp,
                                    speakerLabel = lbl,
                                    isBackground = sp == LyricSpeaker.BACKGROUND
                                )
                            }
                        )
                        return LyricsState(
                            title = title,
                            artist = artist,
                            lines = plainLines,
                            plainLyrics = lyrics,
                            source = "Lyrics.ovh",
                            isSynced = false,
                            hasDualLines = false,
                            hasDuet = plainLines.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsManager", "Fallback lyrics.ovh failed: ${e.message}")
        }
        return null
    }

    /**
     * Unified parser supporting:
     * - TTML (with ttm:agent, ttm:role="x-bg", ttm:role="x-translation", and <span> syllable timing)
     * - SRT (SubRip .srt blocks with timestamps and speaker prefixes)
     * - Enhanced LRC (<mm:ss.xx> per-word tags), Standard LRC, Duet tags (v1:/v2:/both:/bg:), and // translations
     */
    fun parseLrc(rawContent: String): List<DualLyricLine> {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rawLines = when {
            trimmed.contains("<tt") && trimmed.contains("<p") -> parseTtml(trimmed)
            trimmed.contains("-->") && Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{2,3}").containsMatchIn(trimmed) -> parseSrt(trimmed)
            else -> parseStandardOrEnhancedLrc(trimmed)
        }
        return insertInstrumentalBreaks(preprocessParallelLines(rawLines))
    }

    private fun parseStandardOrEnhancedLrc(rawLrc: String): List<DualLyricLine> {
        val rawEntries = mutableListOf<Triple<Long, String, LyricSpeakerMeta>>()
        val speakerNameMap = linkedMapOf<String, LyricSpeaker>()

        for (line in rawLrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val matcher = lrcLineRegex.matcher(trimmed)
            if (matcher.find()) {
                val timestampMs = parseLrcTimestampMs(matcher.group(1), matcher.group(2), matcher.group(3))
                val content = matcher.group(4)?.trim() ?: ""
                if (content.isNotBlank()) {
                    val meta = detectSpeakerFromLine(content, speakerNameMap)
                    rawEntries.add(Triple(timestampMs, meta.cleanedText, meta))
                }
            }
        }

        if (rawEntries.isEmpty()) return emptyList()

        // Sort chronologically preserving same-timestamp order
        val sorted = rawEntries.sortedBy { it.first }

        // Group entries carefully:
        // Merge consecutive identical timestamps ONLY when they are NOT distinct duet speakers (e.g. v1 & v2 overlap!)
        val merged = mutableListOf<Quadruple<Long, String, String?, LyricSpeakerMeta>>()
        var i = 0
        while (i < sorted.size) {
            val (ts, text, meta) = sorted[i]
            val inlineParts = text.split(" // ", limit = 2)
            val primary = inlineParts[0].trim()
            val inlineSecondary = if (inlineParts.size > 1) inlineParts[1].trim() else null

            if (inlineSecondary == null &&
                i + 1 < sorted.size &&
                sorted[i + 1].first == ts &&
                !meta.explicitDuetTag &&
                !sorted[i + 1].third.explicitDuetTag &&
                sorted[i + 1].third.speaker == meta.speaker
            ) {
                val nextText = sorted[i + 1].second.trim()
                merged.add(Quadruple(ts, primary, nextText, meta))
                i += 2
            } else {
                merged.add(Quadruple(ts, primary, inlineSecondary, meta))
                i += 1
            }
        }

        return merged.mapIndexed { idx, (timestamp, rawPrimary, secondary, meta) ->
            // Compute smart endTime: prefer next line from SAME speaker when overlapping duet exists, otherwise next line + overlap allowance
            val nextSameSpeakerTs = merged.drop(idx + 1).firstOrNull { it.fourth.speaker == meta.speaker }?.first
            val nextAnyTs = merged.getOrNull(idx + 1)?.first
            val lineEndMs = when {
                nextAnyTs == null -> timestamp + 4500L
                nextSameSpeakerTs != null && nextSameSpeakerTs > timestamp && meta.explicitDuetTag ->
                    (nextSameSpeakerTs - 50L).coerceIn(timestamp + 900L, timestamp + 7500L)
                else -> (nextAnyTs - 40L).coerceIn(timestamp + 900L, timestamp + 8000L)
            }

            val enhancedWords = parseEnhancedWords(rawPrimary, timestamp, lineEndMs)
            val cleanPrimary = stripWordTags(rawPrimary)
            val hasNativeWords = enhancedWords.isNotEmpty()
            val finalWords = if (hasNativeWords) enhancedWords
            else synthesizeNaturalWords(cleanPrimary, timestamp, lineEndMs)

            DualLyricLine(
                timestampMs = timestamp,
                endTimeMs = lineEndMs,
                primaryText = cleanPrimary,
                secondaryText = secondary,
                words = finalWords,
                isSynced = true,
                hasWordTiming = hasNativeWords,
                speaker = meta.speaker,
                speakerLabel = meta.label,
                isBackground = meta.isBackground
            )
        }
    }

    /**
     * Parses Apple Music / Musixmatch / YouTube TTML XML including ttm:agent duets,
     * ttm:role="x-bg" background vocals, ttm:role="x-translation", and <span> word timing.
     */
    fun parseTtml(ttml: String): List<DualLyricLine> {
        val agents = linkedMapOf<String, Pair<LyricSpeaker, String?>>()
        val agentRegex = Regex("""<ttm:agent[^>]*xml:id=["']([^"']+)["'][^>]*>(.*?)</ttm:agent>""", RegexOption.DOT_MATCHES_ALL)
        var agentCounter = 0
        for (am in agentRegex.findAll(ttml)) {
            val id = am.groupValues[1]
            val inner = am.groupValues[2]
            val nameMatch = Regex("""<ttm:name[^>]*>(.*?)</ttm:name>""").find(inner)
            val name = nameMatch?.groupValues?.get(1)?.let { decodeXmlEntities(it).trim() }?.takeIf { it.isNotBlank() }
            val lower = id.lowercase()
            val speaker = when {
                lower.contains("all") || lower.contains("both") || lower == "v3" -> LyricSpeaker.BOTH
                lower == "v2" || agentCounter == 1 -> LyricSpeaker.SECOND
                else -> LyricSpeaker.FIRST
            }
            if (!lower.contains("all") && !lower.contains("both")) agentCounter++
            agents[id] = speaker to name
        }

        val pRegex = Regex("""<p\s+([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val spanRegex = Regex("""<span\s+([^>]*)>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        val results = mutableListOf<DualLyricLine>()

        for (pm in pRegex.findAll(ttml)) {
            val attrs = pm.groupValues[1]
            val body = pm.groupValues[2]
            val beginStr = extractXmlAttr(attrs, "begin") ?: continue
            val endStr = extractXmlAttr(attrs, "end")
            val startMs = parseFlexibleTimeMs(beginStr) ?: continue
            val endMs = (endStr?.let { parseFlexibleTimeMs(it) } ?: (startMs + 3500L)).coerceAtLeast(startMs + 300L)

            val agentId = extractXmlAttr(attrs, "ttm:agent")
            val agentEntry = agentId?.let { agents[it] }
            val baseSpeaker = agentEntry?.first ?: LyricSpeaker.FIRST
            val label = agentEntry?.second

            var translationText: String? = null
            val mainWords = mutableListOf<LyricWord>()
            val bgWords = mutableListOf<LyricWord>()
            val mainTextSb = StringBuilder()
            val bgTextSb = StringBuilder()

            val spanMatches = spanRegex.findAll(body).toList()
            if (spanMatches.isNotEmpty()) {
                var mainGroupId = 0
                var bgGroupId = 0
                for (sIdx in spanMatches.indices) {
                    val sm = spanMatches[sIdx]
                    val sAttrs = sm.groupValues[1]
                    val rawInner = decodeXmlEntities(sm.groupValues[2].replace(Regex("<[^>]+>"), ""))
                    val sCleanText = rawInner.trim()
                    if (sCleanText.isBlank()) continue

                    val nextSpan = spanMatches.getOrNull(sIdx + 1)
                    val gapAfter = if (nextSpan != null && nextSpan.range.first > sm.range.last) {
                        body.substring(sm.range.last + 1, nextSpan.range.first)
                    } else {
                        " "
                    }
                    val hasSpaceAfter = (rawInner.lastOrNull()?.isWhitespace() == true) ||
                        gapAfter.any { it.isWhitespace() }

                    val role = extractXmlAttr(sAttrs, "ttm:role")?.lowercase() ?: ""
                    when {
                        role.contains("x-translation") -> {
                            translationText = if (translationText == null) sCleanText else "$translationText $sCleanText"
                        }
                        role.contains("x-bg") -> {
                            bgTextSb.append(sCleanText)
                            if (hasSpaceAfter) bgTextSb.append(' ')
                            val sbMs = extractXmlAttr(sAttrs, "begin")?.let { parseFlexibleTimeMs(it) } ?: startMs
                            val seMs = extractXmlAttr(sAttrs, "end")?.let { parseFlexibleTimeMs(it) } ?: endMs
                            bgWords.add(
                                LyricWord(
                                    text = sCleanText,
                                    startTimeMs = sbMs,
                                    endTimeMs = seMs.coerceAtLeast(sbMs + 60L),
                                    trailingSpace = hasSpaceAfter,
                                    syllableGroupId = bgGroupId
                                )
                            )
                            if (hasSpaceAfter) bgGroupId++
                        }
                        else -> {
                            mainTextSb.append(sCleanText)
                            if (hasSpaceAfter) mainTextSb.append(' ')
                            val sbMs = extractXmlAttr(sAttrs, "begin")?.let { parseFlexibleTimeMs(it) }
                            val seMs = extractXmlAttr(sAttrs, "end")?.let { parseFlexibleTimeMs(it) }
                            if (sbMs != null) {
                                mainWords.add(
                                    LyricWord(
                                        text = sCleanText,
                                        startTimeMs = sbMs,
                                        endTimeMs = (seMs ?: (sbMs + 280L)).coerceAtLeast(sbMs + 60L),
                                        trailingSpace = hasSpaceAfter,
                                        syllableGroupId = mainGroupId
                                    )
                                )
                                if (hasSpaceAfter) mainGroupId++
                            }
                        }
                    }
                }
            }

            val fallbackClean = decodeXmlEntities(body.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
            val primaryText = mainTextSb.toString().replace(Regex("\\s+"), " ").trim().ifBlank { fallbackClean }

            val bgCleanRaw = bgTextSb.toString().replace(Regex("\\s+"), " ").trim()
            val bgClean = bgCleanRaw.removeSurrounding("(", ")").trim()
            val bgStart = bgWords.firstOrNull()?.startTimeMs ?: startMs
            val bgEnd = bgWords.lastOrNull()?.endTimeMs ?: endMs

            if (primaryText.isNotBlank()) {
                val hasWordTiming = mainWords.isNotEmpty()
                val attachedParallel = if (bgClean.isNotBlank()) {
                    val pWords = if (bgWords.isNotEmpty()) {
                        bgWords.map { w ->
                            w.copy(text = w.text.removePrefix("(").removeSuffix(")").trim().ifBlank { w.text })
                        }
                    } else {
                        synthesizeNaturalWords(bgClean, bgStart, bgEnd)
                    }
                    listOf(
                        ParallelLyricLine(
                            text = bgClean,
                            timestampMs = bgStart,
                            endTimeMs = bgEnd,
                            words = pWords,
                            speakerLabel = "Бэк"
                        )
                    )
                } else {
                    emptyList()
                }
                results.add(
                    DualLyricLine(
                        timestampMs = startMs,
                        endTimeMs = maxOf(endMs, if (attachedParallel.isNotEmpty()) bgEnd else endMs),
                        primaryText = primaryText,
                        secondaryText = translationText,
                        words = if (hasWordTiming) mainWords else synthesizeNaturalWords(primaryText, startMs, endMs),
                        parallelLines = attachedParallel,
                        isSynced = true,
                        hasWordTiming = hasWordTiming,
                        speaker = baseSpeaker,
                        speakerLabel = label,
                        isBackground = false
                    )
                )
            } else if (bgCleanRaw.isNotBlank()) {
                results.add(
                    DualLyricLine(
                        timestampMs = bgStart,
                        endTimeMs = bgEnd,
                        primaryText = bgCleanRaw,
                        words = if (bgWords.isNotEmpty()) bgWords else synthesizeNaturalWords(bgCleanRaw, bgStart, bgEnd),
                        isSynced = true,
                        hasWordTiming = bgWords.isNotEmpty(),
                        speaker = LyricSpeaker.BACKGROUND,
                        speakerLabel = "Бэк",
                        isBackground = true
                    )
                )
            }
        }
        return results.sortedBy { it.timestampMs }
    }

    /**
     * Parses SubRip (.srt) files supporting overlapping cues, duet speaker labels, and italics background tags.
     */
    fun parseSrt(srt: String): List<DualLyricLine> {
        val blocks = srt.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        val speakerNameMap = linkedMapOf<String, LyricSpeaker>()
        val timeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,.]\d{2,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{2,3})""")
        val results = mutableListOf<DualLyricLine>()

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            val timeIdx = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (timeIdx < 0) continue

            val tm = timeRegex.find(lines[timeIdx]) ?: continue
            val startMs = parseFlexibleTimeMs(tm.groupValues[1].replace(',', '.')) ?: continue
            val endMs = (parseFlexibleTimeMs(tm.groupValues[2].replace(',', '.')) ?: (startMs + 3000L))
                .coerceAtLeast(startMs + 300L)

            val textLines = lines.drop(timeIdx + 1)
            if (textLines.isEmpty()) continue

            // Handle SubRip simultaneous two-speaker cues (e.g. "- Line A\n- Line B")
            val isDialoguePair = textLines.size >= 2 && textLines.all { it.startsWith("-") }
            if (isDialoguePair) {
                textLines.take(2).forEachIndexed { idx, rawLine ->
                    val stripped = rawLine.removePrefix("-").trim().replace(Regex("<[^>]+>"), "")
                    val sp = if (idx == 0) LyricSpeaker.FIRST else LyricSpeaker.SECOND
                    if (stripped.isNotBlank()) {
                        results.add(
                            DualLyricLine(
                                timestampMs = startMs,
                                endTimeMs = endMs,
                                primaryText = stripped,
                                words = synthesizeNaturalWords(stripped, startMs, endMs),
                                isSynced = true,
                                speaker = sp,
                                speakerLabel = if (sp == LyricSpeaker.SECOND) "Дуэт" else null
                            )
                        )
                    }
                }
            } else {
                val joinedRaw = textLines.joinToString(" ").trim()
                val isItalicBg = joinedRaw.startsWith("<i>") && joinedRaw.endsWith("</i>")
                val stripped = joinedRaw.replace(Regex("<[^>]+>"), "").trim()
                val parts = stripped.split(" // ", limit = 2)
                val meta = detectSpeakerFromLine(parts[0], speakerNameMap)
                val finalSp = if (isItalicBg) LyricSpeaker.BACKGROUND else meta.speaker

                results.add(
                    DualLyricLine(
                        timestampMs = startMs,
                        endTimeMs = endMs,
                        primaryText = meta.cleanedText,
                        secondaryText = parts.getOrNull(1)?.trim(),
                        words = synthesizeNaturalWords(meta.cleanedText, startMs, endMs),
                        isSynced = true,
                        speaker = finalSp,
                        speakerLabel = if (isItalicBg) "Бэк" else meta.label,
                        isBackground = isItalicBg || meta.isBackground
                    )
                )
            }
        }
        return results.sortedBy { it.timestampMs }
    }

    private data class LyricSpeakerMeta(
        val speaker: LyricSpeaker,
        val label: String?,
        val cleanedText: String,
        val isBackground: Boolean,
        val explicitDuetTag: Boolean
    )

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun mapAgentToSpeaker(agent: String, isBg: Boolean): Pair<LyricSpeaker, String?> {
        if (isBg) return LyricSpeaker.BACKGROUND to "Бэк"
        val lower = agent.lowercase().trim()
        return when {
            lower == "v2" || lower == "2" || lower.contains("right") || lower.contains("duet") -> LyricSpeaker.SECOND to "Вокал 2"
            lower == "v_all" || lower == "both" || lower == "all" || lower == "v3" -> LyricSpeaker.BOTH to "Вместе"
            lower == "v1" || lower == "1" -> LyricSpeaker.FIRST to null
            else -> LyricSpeaker.FIRST to null
        }
    }

    private fun extractInlineSpeakerPrefix(
        raw: String,
        fallbackSpeaker: LyricSpeaker = LyricSpeaker.FIRST,
        fallbackLabel: String? = null,
        isBg: Boolean = false
    ): Triple<LyricSpeaker, String?, String> {
        val meta = detectSpeakerFromLine(raw, linkedMapOf())
        return if (meta.explicitDuetTag) {
            Triple(meta.speaker, meta.label, meta.cleanedText)
        } else {
            Triple(if (isBg) LyricSpeaker.BACKGROUND else fallbackSpeaker, fallbackLabel, meta.cleanedText)
        }
    }

    private fun detectSpeakerFromLine(
        raw: String,
        speakerNameMap: MutableMap<String, LyricSpeaker>
    ): LyricSpeakerMeta {
        val text = raw.trim()
        val lower = text.lowercase()

        // Apple/LRCX style tags: v1:, v2:, v_all:, bg:, {v1}, {v2}
        return when {
            lower.startsWith("v1:") || lower.startsWith("{v1}") -> {
                val clean = text.replaceFirst(Regex("^(?i)(v1:|\\{v1\\})\\s*"), "").trim()
                LyricSpeakerMeta(LyricSpeaker.FIRST, null, clean, false, true)
            }
            lower.startsWith("v2:") || lower.startsWith("{v2}") -> {
                val clean = text.replaceFirst(Regex("^(?i)(v2:|\\{v2\\})\\s*"), "").trim()
                LyricSpeakerMeta(LyricSpeaker.SECOND, "Вокал 2", clean, false, true)
            }
            lower.startsWith("v_all:") || lower.startsWith("v3:") || lower.startsWith("both:") || lower.startsWith("all:") -> {
                val clean = text.replaceFirst(Regex("^(?i)(v_all:|v3:|both:|all:)\\s*"), "").trim()
                LyricSpeakerMeta(LyricSpeaker.BOTH, "Вместе", clean, false, true)
            }
            lower.startsWith("bg:") || lower.startsWith("{bg}") -> {
                val clean = text.replaceFirst(Regex("^(?i)(bg:|\\{bg\\})\\s*"), "").trim()
                LyricSpeakerMeta(LyricSpeaker.BACKGROUND, "Бэк", clean, true, true)
            }
            // Bracket/Colon singer tags e.g. "[Исполнитель 2]: ..." or "Дуэт: ..."
            Regex("^\\[(Дуэт|Все|Both|All)\\]:?\\s*", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                val clean = text.replaceFirst(Regex("^\\[(Дуэт|Все|Both|All)\\]:?\\s*", RegexOption.IGNORE_CASE), "").trim()
                LyricSpeakerMeta(LyricSpeaker.BOTH, "Вместе", clean, false, true)
            }
            // Background lines completely wrapped in parentheses e.g. "(Yeah, ooh)"
            text.startsWith("(") && text.endsWith(")") && text.length in 3..45 -> {
                LyricSpeakerMeta(LyricSpeaker.BACKGROUND, "Бэк", text.removeSurrounding("(", ")").trim(), true, false)
            }
            // Explicit [Singer]: prefix
            Regex("^\\[([^\\]]{1,20})\\]:\\s+.+").matches(text) -> {
                val m = Regex("^\\[([^\\]]{1,20})\\]:\\s+(.+)$").find(text)
                if (m == null) {
                    LyricSpeakerMeta(LyricSpeaker.FIRST, null, text, false, false)
                } else {
                    val singer = m.groupValues[1].trim()
                    val body = m.groupValues[2].trim()
                    val sp = speakerNameMap.getOrPut(singer.lowercase()) {
                        when (speakerNameMap.size) {
                            0 -> LyricSpeaker.FIRST
                            1 -> LyricSpeaker.SECOND
                            else -> LyricSpeaker.BOTH
                        }
                    }
                    LyricSpeakerMeta(sp, if (sp == LyricSpeaker.FIRST) null else singer, body, false, true)
                }
            }
            else -> LyricSpeakerMeta(LyricSpeaker.FIRST, null, text, false, false)
        }
    }

    private fun parseLrcTimestampMs(minStr: String?, secStr: String?, msGroup: String?): Long {
        val min = minStr?.toLongOrNull() ?: 0L
        val sec = secStr?.toLongOrNull() ?: 0L
        val msStr = msGroup ?: "0"
        val ms = when (msStr.length) {
            1 -> msStr.toLong() * 100L
            2 -> msStr.toLong() * 10L
            3 -> msStr.toLong()
            else -> 0L
        }
        return (min * 60 + sec) * 1000 + ms
    }

    private fun parseFlexibleTimeMs(token: String): Long? {
        val t = token.trim()
        if (t.endsWith("ms")) return t.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (t.endsWith("s")) return (t.removeSuffix("s").toDoubleOrNull()?.times(1000.0))?.toLong()

        val parts = t.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                ((h * 3600 + m * 60) * 1000.0 + s * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                (m * 60 * 1000.0 + s * 1000.0).toLong()
            }
            else -> t.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
    }

    private fun extractXmlAttr(attrs: String, name: String): String? {
        val m = Regex("""$name=["']([^"']+)["']""").find(attrs)
        return m?.groupValues?.get(1)
    }

    private fun decodeXmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parseEnhancedWords(raw: String, lineStartMs: Long, lineEndMs: Long): List<LyricWord> {
        val matches = wordTagRegex.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList()

        val result = mutableListOf<LyricWord>()
        for (i in matches.indices) {
            val m = matches[i]
            val startMs = parseLrcTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue

            val endMs = if (i + 1 < matches.size) {
                val nm = matches[i + 1]
                parseLrcTimestampMs(nm.groupValues[1], nm.groupValues[2], nm.groupValues[3])
                    .coerceAtLeast(startMs + 80L)
            } else {
                lineEndMs.coerceAtLeast(startMs + 250L)
            }

            result.add(LyricWord(text, startMs, endMs))
        }
        return result
    }

    private fun stripWordTags(text: String): String =
        text.replace(Regex("<\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?>"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Synthesizes natural-paced per-word timing across line-level timestamps
     * weighted by phonetic character length and punctuation pauses.
     */
    fun synthesizeNaturalWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        val rawWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return emptyList()

        val activeSpan = ((endMs - startMs) * 0.88f).toLong().coerceIn(450L, 4800L)
        val weights = rawWords.map { word ->
            val charWeight = word.length.coerceAtLeast(2).toFloat()
            val pauseBonus = if (word.endsWith(",") || word.endsWith(";") || word.endsWith("—")) 1.5f
            else if (word.endsWith(".") || word.endsWith("!") || word.endsWith("?")) 2.2f
            else 0f
            charWeight + pauseBonus
        }
        val totalWeight = weights.sum().coerceAtLeast(1f)

        var cursor = startMs
        return rawWords.mapIndexed { idx, w ->
            val slice = ((weights[idx] / totalWeight) * activeSpan).toLong().coerceAtLeast(90L)
            val wStart = cursor
            val wEnd = (wStart + slice).coerceAtMost(startMs + activeSpan)
            cursor = wEnd
            LyricWord(text = w, startTimeMs = wStart, endTimeMs = wEnd)
        }
    }

    /**
     * Pre-display parallel vocal analysis:
     * 1. Separates inline parenthesized backing/echo segments (e.g. "Hold on (yeah, ooh)") out of the main
     *    text line into dedicated [ParallelLyricLine] entries rendered underneath in smaller typography.
     * 2. Merges standalone overlapping backing/ad-lib lines directly underneath their active parent phrase
     *    while leaving explicit standalone duet voices (v1/v2/both) and non-overlapping `bg:` lines intact.
     */
    fun preprocessParallelLines(lines: List<DualLyricLine>): List<DualLyricLine> {
        if (lines.isEmpty()) return lines
        val parenRegex = Regex("\\(([^()]{2,64})\\)")

        // Step 1: Extract inline parenthesized backing cues from non-background lines
        val splitInline = lines.map { line ->
            if (line.isInstrumental || line.isBackground || line.speaker == LyricSpeaker.BACKGROUND) {
                return@map line
            }
            val matches = parenRegex.findAll(line.primaryText).toList()
            if (matches.isEmpty()) return@map line

            val strippedMain = parenRegex.replace(line.primaryText, " ")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.;!?])"), "$1")
                .trim()

            // Only split if meaningful main lyrics remain outside the parentheses
            if (strippedMain.length < 2) return@map line

            val existingParallel = line.parallelLines.toMutableList()
            val span = (line.endTimeMs - line.timestampMs).coerceAtLeast(800L)
            val totalLen = line.primaryText.length.coerceAtLeast(1)

            for (m in matches) {
                val cueText = m.groupValues[1].trim()
                if (cueText.isBlank()) continue
                val startFrac = (m.range.first.toFloat() / totalLen.toFloat()).coerceIn(0f, 0.85f)
                val endFrac = ((m.range.last + 1).toFloat() / totalLen.toFloat()).coerceIn(startFrac + 0.15f, 1.0f)
                val pStart = if (line.isSynced && line.timestampMs >= 0L) {
                    line.timestampMs + (span * startFrac).toLong()
                } else -1L
                val pEnd = if (line.isSynced && line.timestampMs >= 0L) {
                    (line.timestampMs + (span * endFrac).toLong()).coerceAtLeast(pStart + 350L)
                } else -1L
                val pWords = if (line.isSynced && pStart >= 0L) {
                    synthesizeNaturalWords(cueText, pStart, pEnd)
                } else emptyList()
                existingParallel.add(
                    ParallelLyricLine(
                        text = cueText,
                        timestampMs = pStart,
                        endTimeMs = pEnd,
                        words = pWords,
                        speakerLabel = "Бэк"
                    )
                )
            }

            // Recompute main words without the extracted parenthesized tokens
            val mainWords = if (!line.isSynced || line.timestampMs < 0L) {
                emptyList()
            } else if (line.hasWordTiming && line.words.isNotEmpty()) {
                val filteredWords = line.words.filterNot { w ->
                    val wt = w.text.trim()
                    (wt.startsWith("(") && wt.endsWith(")")) ||
                        matches.any { m -> m.groupValues[1].contains(wt.removePrefix("(").removeSuffix(")"), ignoreCase = true) && wt.contains('(') }
                }
                filteredWords.ifEmpty { synthesizeNaturalWords(strippedMain, line.timestampMs, line.endTimeMs) }
            } else {
                synthesizeNaturalWords(strippedMain, line.timestampMs, line.endTimeMs)
            }

            line.copy(
                primaryText = strippedMain,
                words = mainWords,
                parallelLines = existingParallel
            )
        }

        // Step 2: Fold standalone overlapping backing cues onto the active parent main line
        val consolidated = mutableListOf<DualLyricLine>()
        for (line in splitInline) {
            val prev = consolidated.lastOrNull()
            val isImplicitOverlapBg = prev != null &&
                !prev.isInstrumental &&
                !prev.isBackground &&
                line.isBackground &&
                line.isSynced &&
                prev.isSynced &&
                line.timestampMs >= prev.timestampMs &&
                line.timestampMs < (prev.endTimeMs - 180L)

            val isExactSameTimestampBg = prev != null &&
                !prev.isInstrumental &&
                !prev.isBackground &&
                line.isBackground &&
                line.isSynced &&
                prev.isSynced &&
                abs(line.timestampMs - prev.timestampMs) <= 120L

            if (prev != null && (isImplicitOverlapBg || isExactSameTimestampBg)) {
                val pWords = if (line.words.isNotEmpty()) line.words
                else synthesizeNaturalWords(line.primaryText, line.timestampMs, line.endTimeMs)
                val updatedParallel = prev.parallelLines + ParallelLyricLine(
                    text = line.primaryText,
                    timestampMs = line.timestampMs,
                    endTimeMs = line.endTimeMs,
                    words = pWords,
                    speakerLabel = line.speakerLabel ?: "Бэк"
                )
                consolidated[consolidated.lastIndex] = prev.copy(
                    endTimeMs = maxOf(prev.endTimeMs, line.endTimeMs),
                    parallelLines = updatedParallel
                )
            } else {
                consolidated.add(line)
            }
        }
        return consolidated
    }

    private fun insertInstrumentalBreaks(lines: List<DualLyricLine>): List<DualLyricLine> {
        if (lines.isEmpty()) return lines
        val result = mutableListOf<DualLyricLine>()

        if (lines.first().timestampMs > 4500L) {
            result.add(
                DualLyricLine(
                    timestampMs = 0L,
                    endTimeMs = lines.first().timestampMs,
                    primaryText = "♪",
                    isSynced = true,
                    isInstrumental = true
                )
            )
        }

        for (i in lines.indices) {
            val current = lines[i]
            result.add(current)

            if (i + 1 < lines.size) {
                val next = lines[i + 1]
                val gap = next.timestampMs - current.timestampMs
                if (gap >= 8500L) {
                    val interludeStart = current.timestampMs + 4200L
                    result.add(
                        DualLyricLine(
                            timestampMs = interludeStart,
                            endTimeMs = next.timestampMs,
                            primaryText = "♪",
                            isSynced = true,
                            isInstrumental = true
                        )
                    )
                }
            }
        }
        return result
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\[(official|video|audio|lyrics|lyric|hd|4k|music video|visualizer|remaster|remastered).*?\\]"), "")
            .replace(Regex("(?i)\\((official|video|audio|lyrics|lyric|hd|4k|music video|visualizer|remaster|remastered).*?\\)"), "")
            .replace(Regex("(?i)\\s+-\\s+(remaster|remastered|live|explicit|clean|version).*$"), "")
            .replace(Regex("(?i)\\s+(ft|feat)\\..*"), "")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex("(?i)\\s*-\\s*topic"), "")
            .replace(Regex("(?i)vevo"), "")
            .substringBefore(",")
            .substringBefore("&")
            .trim()
    }
}
