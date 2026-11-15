package com.example.online

import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs

@Immutable
enum class SourceAttemptStatus {
    SUCCESS,
    HTTP_ERROR,
    EMPTY_RESULT,
    RELEVANCE_REJECTED,
    TIMEOUT,
    NETWORK_ERROR,
    PLAYABILITY_BLOCKED
}

@Immutable
data class SourceAttemptLog(
    val sourceId: String,
    val displayName: String,
    val stage: String,
    val status: SourceAttemptStatus,
    val httpCode: Int? = null,
    val detail: String,
    val latencyMs: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis()
)

@Immutable
data class SourceHealthStatus(
    val sourceId: String,
    val displayName: String,
    val badge: String,
    val isHealthy: Boolean,
    val httpCode: Int? = null,
    val latencyMs: Long = 0L,
    val detail: String = ""
)

@Immutable
data class PlaybackDiagnosticReport(
    val trackTitle: String,
    val trackArtist: String,
    val errorCodeLabel: String,
    val summary: String,
    val attempts: List<SourceAttemptLog>,
    val suggestedAlternatives: List<OnlineTrack> = emptyList(),
    val canAutoRetryOtherSources: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
)

object SourceDiagnosticsLog {
    private const val MAX_ENTRIES = 64
    private val ringBuffer = ConcurrentLinkedDeque<SourceAttemptLog>()

    fun record(entry: SourceAttemptLog) {
        ringBuffer.addFirst(entry)
        while (ringBuffer.size > MAX_ENTRIES) {
            ringBuffer.pollLast()
        }
    }

    fun recent(limit: Int = 24): List<SourceAttemptLog> =
        ringBuffer.take(limit)

    fun clear() {
        ringBuffer.clear()
    }

    fun formatCopyableReport(report: PlaybackDiagnosticReport): String {
        val sb = StringBuilder()
        sb.appendLine("=== imux player • Отчёт источника ===")
        sb.appendLine("Трек: ${report.trackArtist} — ${report.trackTitle}")
        sb.appendLine("Код ошибки: ${report.errorCodeLabel}")
        sb.appendLine("Причина: ${report.summary}")
        sb.appendLine("--- Попытки источников ---")
        report.attempts.forEach { a ->
            val codePart = a.httpCode?.let { "HTTP $it" } ?: a.status.name
            sb.appendLine("[${a.displayName} • ${a.stage}] $codePart (${a.latencyMs} ms): ${a.detail}")
        }
        return sb.toString().trim()
    }
}

data class RelevanceAssessment(
    val score: Int,
    val isAcceptable: Boolean,
    val titleMatchRatio: Float,
    val artistMatched: Boolean,
    val reason: String
)

/**
 * Strict track relevance filter so multi-source search & cross-resolution NEVER
 * substitute completely unrelated songs (e.g. Hindi soundtrack hits or random Archive podcasts).
 */
object RelevanceScorer {

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "feat", "ft", "vs", "official", "audio",
        "video", "lyrics", "lyric", "hd", "hq", "4k", "music", "original", "version",
        "explicit", "clean", "radio", "edit", "topic", "vevo"
    )

    private val UNWANTED_MODIFIERS = listOf(
        "slowed", "reverb", "nightcore", "8d audio", "karaoke",
        "cover", "sped up", "instrumental", "teaser", "reaction", "parody", "ringtone"
    )

    fun tokenize(raw: String): List<String> {
        val cleaned = raw.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()
    }

    fun normalizePlain(raw: String): String =
        raw.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Meld-inspired fuzzy score: title (45%), artist (35%), duration (20%).
     * Bigram similarity tolerates punctuation and small spelling differences
     * without allowing an artist-only match to pass.
     */
    fun assess(
        candidate: OnlineTrack,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long = 0L
    ): RelevanceAssessment {
        val normTargetTitle = normalizePlain(targetTitle)
        val normTargetArtist = normalizePlain(targetArtist)
        val normCandidateTitle = normalizePlain(candidate.title)
        val normCandidateArtist = normalizePlain(candidate.artist)
        val combinedCandidate = normalizePlain("${candidate.artist} ${candidate.title}")

        val titleTokens = tokenize(targetTitle).ifEmpty {
            normTargetTitle.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        val candidateTitleTokens = tokenize(candidate.title).toSet()
        val matchedTitleCount = titleTokens.count { token ->
            token in candidateTitleTokens || normCandidateTitle.contains(token)
        }
        val titleRatio = if (titleTokens.isEmpty()) 1f
            else matchedTitleCount.toFloat() / titleTokens.size.toFloat()

        val titleScore = textSimilarity(normTargetTitle, normCandidateTitle)
        val artistScore = if (normTargetArtist.isBlank()) 0.5f else maxOf(
            textSimilarity(normTargetArtist, normCandidateArtist),
            textSimilarity(normTargetArtist, combinedCandidate)
        )
        val artistMatched = normTargetArtist.isBlank() ||
            artistScore >= 0.30f || combinedCandidate.contains(normTargetArtist)

        var durationTooFar = false
        val durationScore = if (
            expectedDurationMs > 15_000L &&
            candidate.durationMs > 15_000L &&
            !candidate.isPreviewOnly
        ) {
            val diffSec = abs(candidate.durationMs - expectedDurationMs) / 1000L
            durationTooFar = diffSec > 45L
            when {
                diffSec <= 2L -> 1.0f
                diffSec <= 5L -> 0.8f
                diffSec <= 10L -> 0.5f
                diffSec <= 30L -> 0.2f
                else -> 0.0f
            }
        } else {
            0.5f
        }

        var score = (
            titleScore * 45f +
                artistScore * 35f +
                durationScore * 20f
            ).toInt()

        if (candidate.isOfficialAudio) score += 3
        if (normCandidateArtist.endsWith("topic")) score += 3
        if (candidate.title.contains("official audio", ignoreCase = true)) score += 2
        if (candidate.directStreamUrl != null && !candidate.isPreviewOnly) score += 2
        if (candidate.isPreviewOnly) score -= 18

        val targetLower = targetTitle.lowercase()
        val candidateLower = candidate.title.lowercase()
        for (modifier in UNWANTED_MODIFIERS) {
            if (candidateLower.contains(modifier) && !targetLower.contains(modifier)) score -= 24
        }
        if (candidateLower.contains("remix") && !targetLower.contains("remix")) score -= 16
        if (candidateLower.contains("live") && !targetLower.contains("live")) score -= 14
        score = score.coerceIn(0, 100)

        val exactTitleMatch = normTargetTitle.isNotBlank() &&
            (normCandidateTitle == normTargetTitle || normCandidateTitle.contains(normTargetTitle))
        val titleOk = exactTitleMatch || titleScore >= 0.48f || titleRatio >= 0.60f
        val artistOk = normTargetArtist.isBlank() || artistMatched
        val accepted = score >= 50 && titleOk && artistOk && !durationTooFar

        val reason = when {
            !titleOk -> "Название не совпадает (сходство ${(titleScore * 100).toInt()}%)"
            !artistOk -> "Другой исполнитель (сходство ${(artistScore * 100).toInt()}%)"
            durationTooFar -> "Сильное отличие длительности (${candidate.durationSeconds}с)"
            score < 50 -> "Низкий балл релевантности ($score)"
            else -> "Совпадение подтверждено ($score/100)"
        }

        return RelevanceAssessment(
            score = score,
            isAcceptable = accepted,
            titleMatchRatio = titleRatio,
            artistMatched = artistMatched,
            reason = reason
        )
    }

    private fun textSimilarity(first: String, second: String): Float {
        if (first.isBlank() || second.isBlank()) return 0f
        if (first == second) return 1f

        fun bigrams(value: String): Set<String> =
            if (value.length < 2) setOf(value) else value.windowed(2).toSet()

        val a = bigrams(first)
        val b = bigrams(second)
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.count { it in b }
        return (2f * intersection) / (a.size + b.size).toFloat()
    }

    /**
     * Filter for interactive search queries: accepts if query tokens cleanly overlap
     * with title or artist, eliminating irrelevant junk results.
     */
    fun matchesSearchQuery(track: OnlineTrack, query: String): Boolean {
        val tokens = tokenize(query).ifEmpty {
            normalizePlain(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        if (tokens.isEmpty()) return true
        val combined = normalizePlain("${track.artist} ${track.title} ${track.album}")
        val matched = tokens.count { combined.contains(it) }
        val minRequired = if (tokens.size <= 2) tokens.size else ((tokens.size * 0.55f).toInt().coerceAtLeast(2))
        return matched >= minRequired
    }
}
