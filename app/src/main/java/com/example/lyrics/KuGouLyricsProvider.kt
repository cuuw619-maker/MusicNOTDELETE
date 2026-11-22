package com.example.lyrics

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlin.math.abs

object KuGouLyricsProvider {
    private const val TAG = "KuGouLyricsProvider"

    private val KRC_KEY = byteArrayOf(
        64, 71, 97, 119,
        94, 50, 116, 71,
        81, 54, 49, 45,
        (-50).toByte(), (-46).toByte(), 110, 105
    )

    private val KRC_HEADER = byteArrayOf(
        'k'.code.toByte(),
        'r'.code.toByte(),
        'c'.code.toByte(),
        '1'.code.toByte()
    )

    private val krcLineRegex = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
    private val krcWordRegex = Regex("<(\\d+),(\\d+),\\d+>([^<]*)")
    private val krcOffsetRegex = Regex("^\\[offset:(-?\\d+)\\]$", RegexOption.IGNORE_CASE)
    private val krcLangRegex = Regex("^\\[language:([^\\]]+)\\]$", RegexOption.IGNORE_CASE)

    fun decodeKrcBase64(base64Content: String): String? {
        val trimmed = base64Content.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val raw = Base64.getDecoder().decode(trimmed)
            if (raw.size <= 4) return null
            for (i in 0 until 4) {
                if (raw[i] != KRC_HEADER[i]) return null
            }
            val payload = ByteArray(raw.size - 4)
            for (i in 4 until raw.size) {
                payload[i - 4] = (raw[i].toInt() xor KRC_KEY[(i - 4) % KRC_KEY.size].toInt()).toByte()
            }
            InflaterInputStream(ByteArrayInputStream(payload)).use { inflater ->
                inflater.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "KRC decode failed: ${e.message}")
            null
        }
    }

    fun parseKrcLines(
        rawKrc: String,
        inlineSpeakerParser: (String) -> Triple<LyricSpeaker, String?, String>,
        synthesizeWords: (String, Long, Long) -> List<LyricWord>
    ): List<DualLyricLine> {
        if (rawKrc.isBlank()) return emptyList()
        var offsetMs = 0L
        var embeddedTranslations: List<String>? = null
        val parsed = mutableListOf<DualLyricLine>()

        for (rawLine in rawKrc.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            krcOffsetRegex.matchEntire(line)?.let { om ->
                offsetMs = om.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            krcLangRegex.matchEntire(line)?.let { lm ->
                embeddedTranslations = extractKrcTranslations(lm.groupValues[1])
                continue
            }

            val m = krcLineRegex.matchEntire(line) ?: continue
            val rawStartMs = m.groupValues[1].toLongOrNull() ?: continue
            val lineDurMs = (m.groupValues[2].toLongOrNull() ?: 3000L).coerceAtLeast(300L)
            val lineStartMs = (rawStartMs + offsetMs).coerceAtLeast(0L)
            val body = m.groupValues[3]

            val wordMatches = krcWordRegex.findAll(body).toList()
            val words = mutableListOf<LyricWord>()
            val textSb = StringBuilder()

            if (wordMatches.isNotEmpty()) {
                var groupId = 0
                for (wIdx in wordMatches.indices) {
                    val wm = wordMatches[wIdx]
                    val wOffset = wm.groupValues[1].toLongOrNull() ?: 0L
                    val wDur = (wm.groupValues[2].toLongOrNull() ?: 180L).coerceAtLeast(50L)
                    val rawToken = wm.groupValues[3]
                    val cleanToken = rawToken.trim()
                    val wStart = (lineStartMs + wOffset).coerceAtLeast(0L)
                    val wEnd = (wStart + wDur).coerceAtLeast(wStart + 50L)

                    textSb.append(rawToken)
                    if (cleanToken.isNotEmpty()) {
                        val hasSpaceAfter = rawToken.lastOrNull()?.isWhitespace() == true ||
                            wIdx == wordMatches.lastIndex
                        words.add(
                            LyricWord(
                                text = cleanToken,
                                startTimeMs = wStart,
                                endTimeMs = wEnd,
                                trailingSpace = hasSpaceAfter,
                                syllableGroupId = groupId
                            )
                        )
                        if (hasSpaceAfter) groupId++
                    }
                }
            }

            val fullRawText = if (wordMatches.isNotEmpty()) {
                textSb.toString().replace(Regex("\\s+"), " ").trim()
            } else {
                body.replace(Regex("<[^>]+>"), "").trim()
            }
            if (fullRawText.isBlank()) continue

            val (speaker, label, cleanLine) = inlineSpeakerParser(fullRawText)
            val finalWords = if (words.isNotEmpty()) words
            else synthesizeWords(cleanLine, lineStartMs, lineStartMs + lineDurMs)

            parsed.add(
                DualLyricLine(
                    timestampMs = lineStartMs,
                    endTimeMs = lineStartMs + lineDurMs,
                    primaryText = cleanLine,
                    words = finalWords,
                    isSynced = true,
                    hasWordTiming = words.isNotEmpty(),
                    speaker = speaker,
                    speakerLabel = label,
                    isBackground = speaker == LyricSpeaker.BACKGROUND
                )
            )
        }

        val translations = embeddedTranslations
        if (translations != null && parsed.isNotEmpty()) {
            return parsed.mapIndexed { idx, line ->
                val tr = translations.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() }
                if (tr != null && line.secondaryText.isNullOrBlank()) {
                    line.copy(secondaryText = tr)
                } else {
                    line
                }
            }
        }
        return parsed
    }

    private fun extractKrcTranslations(langB64: String): List<String>? {
        return try {
            val jsonStr = Base64.getDecoder().decode(langB64.trim()).toString(Charsets.UTF_8)
            val root = JSONObject(jsonStr)
            val contentArr = root.optJSONArray("content") ?: return null
            for (i in 0 until contentArr.length()) {
                val entry = contentArr.optJSONObject(i) ?: continue
                if (entry.optInt("type", -1) == 1) {
                    val lyricContent = entry.optJSONArray("lyricContent") ?: continue
                    val lines = ArrayList<String>(lyricContent.length())
                    for (j in 0 until lyricContent.length()) {
                        val subArr = lyricContent.optJSONArray(j)
                        val joined = if (subArr != null) {
                            (0 until subArr.length()).joinToString(" ") { k -> subArr.optString(k, "") }.trim()
                        } else {
                            lyricContent.optString(j, "").trim()
                        }
                        lines.add(joined)
                    }
                    return lines
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun fetchLyrics(
        client: OkHttpClient,
        title: String,
        artist: String,
        durationSeconds: Long,
        inlineSpeakerParser: (String) -> Triple<LyricSpeaker, String?, String>,
        synthesizeWords: (String, Long, Long) -> List<LyricWord>,
        finalizeLines: (List<DualLyricLine>) -> List<DualLyricLine>,
        fallbackLrcParser: (String) -> List<DualLyricLine>
    ): LyricsState? {
        val rawQuery = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (rawQuery.isBlank()) return null

        return try {
            val encQuery = URLEncoder.encode(rawQuery, "UTF-8")
            val durParam = if (durationSeconds > 0L) "&duration=${durationSeconds * 1000L}" else ""
            val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$encQuery$durParam"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
                .build()

            var bestId = ""
            var bestAccessKey = ""

            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val candidates = json.optJSONArray("candidates") ?: return null
                var bestScore = Int.MIN_VALUE

                for (i in 0 until minOf(candidates.length(), 8)) {
                    val item = candidates.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    val key = item.optString("accesskey", "").trim()
                    if (id.isEmpty() || key.isEmpty()) continue

                    val candSong = item.optString("song", "").lowercase()
                    val candSinger = item.optString("singer", "").lowercase()
                    val candDurSec = item.optLong("duration", 0L) / 1000L
                    var score = item.optInt("score", 40)

                    if (candSong.contains(title.lowercase())) score += 35
                    if (artist.isNotBlank() && candSinger.contains(artist.lowercase())) score += 30
                    if (durationSeconds > 10 && candDurSec > 10) {
                        val diff = abs(candDurSec - durationSeconds)
                        score += when {
                            diff <= 3 -> 55
                            diff <= 8 -> 30
                            diff <= 16 -> 0
                            else -> -80
                        }
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                        bestAccessKey = key
                    }
                }
                if (bestScore < -20) return null
            }

            if (bestId.isEmpty() || bestAccessKey.isEmpty()) return null

            // 1. Prefer KRC (word-by-word timing + embedded translations)
            val krcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$bestId&accesskey=$bestAccessKey&fmt=krc&charset=utf8"
            val krcReq = Request.Builder()
                .url(krcUrl)
                .header("Accept", "application/json")
                .build()

            client.newCall(krcReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val encoded = json.optString("content", "")
                    val decoded = decodeKrcBase64(encoded)
                    if (!decoded.isNullOrBlank()) {
                        val rawLines = parseKrcLines(decoded, inlineSpeakerParser, synthesizeWords)
                        if (rawLines.isNotEmpty()) {
                            val enriched = finalizeLines(rawLines)
                            return LyricsState(
                                title = title,
                                artist = artist,
                                lines = enriched,
                                plainLyrics = enriched.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText },
                                source = "KuGou KRC Word-Sync",
                                isSynced = true,
                                hasDualLines = enriched.any { !it.secondaryText.isNullOrBlank() },
                                hasWordSync = enriched.any { it.hasWordTiming },
                                hasDuet = enriched.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                            )
                        }
                    }
                }
            }

            // 2. Fallback to KuGou standard LRC
            val lrcUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$bestId&accesskey=$bestAccessKey&fmt=lrc&charset=utf8"
            val lrcReq = Request.Builder().url(lrcUrl).header("Accept", "application/json").build()
            client.newCall(lrcReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "{}")
                val encoded = json.optString("content", "").trim()
                if (encoded.isEmpty()) return null
                val rawLrc = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                val parsed = fallbackLrcParser(rawLrc)
                if (parsed.isNotEmpty()) {
                    return LyricsState(
                        title = title,
                        artist = artist,
                        lines = parsed,
                        plainLyrics = parsed.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText },
                        source = "KuGou LRC",
                        isSynced = parsed.any { it.isSynced },
                        hasDualLines = parsed.any { !it.secondaryText.isNullOrBlank() },
                        hasWordSync = parsed.any { it.hasWordTiming },
                        hasDuet = parsed.any { it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH }
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "KuGou lyrics fetch failed: ${e.message}")
            null
        }
    }
}
