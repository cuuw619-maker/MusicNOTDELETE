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
    PLAYABILITY_BLOCKED,
    SABR_ONLY,
    CIPHER_FAILED,
    NO_AUDIO_FORMATS
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
    val timestampMs: Long = System.currentTimeMillis(),
    val seqId: Long = SourceDiagnosticsLog.nextSeqId()
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
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun nextSeqId(): Long = seqCounter.incrementAndGet()

    fun latestSeqId(): Long = seqCounter.get()

    fun record(entry: SourceAttemptLog) {
        ringBuffer.addFirst(entry)
        while (ringBuffer.size > MAX_ENTRIES) {
            ringBuffer.pollLast()
        }
    }

    fun recent(limit: Int = 24): List<SourceAttemptLog> =
        ringBuffer.take(limit)

    fun recentSince(sinceSeqId: Long, limit: Int = 20): List<SourceAttemptLog> =
        ringBuffer.asSequence()
            .filter { it.seqId > sinceSeqId }
            .take(limit)
            .toList()

    /**
     * Resolves an accurate failure label from current-attempt diagnostics without EVER falsely blaming "HTTP 200"
     * (e.g. from search calls or HTTP 200 player JSON payloads that carried a block/SABR/cipher failure).
     */
    fun resolveDiagnosticErrorCode(attempts: List<SourceAttemptLog>): String {
        val failures = attempts.filter { it.status != SourceAttemptStatus.SUCCESS }
        if (failures.isEmpty()) return "NO_WORKING_STREAM"

        // Prefer explicit structural/CDN failure reasons in priority order
        val realHttpError = failures.firstOrNull {
            it.status == SourceAttemptStatus.HTTP_ERROR && it.httpCode != null && it.httpCode != 200
        }
        val sabrOnly = failures.firstOrNull { it.status == SourceAttemptStatus.SABR_ONLY }
        val cipherFailed = failures.firstOrNull { it.status == SourceAttemptStatus.CIPHER_FAILED }
        val blocked = failures.firstOrNull { it.status == SourceAttemptStatus.PLAYABILITY_BLOCKED }
        val noAudio = failures.firstOrNull { it.status == SourceAttemptStatus.NO_AUDIO_FORMATS }
        val timeout = failures.firstOrNull { it.status == SourceAttemptStatus.TIMEOUT }
        val networkErr = failures.firstOrNull { it.status == SourceAttemptStatus.NETWORK_ERROR }

        return when {
            realHttpError != null -> "HTTP ${realHttpError.httpCode}"
            sabrOnly != null -> "SABR_ONLY"
            cipherFailed != null -> "CIPHER_FAILED"
            blocked != null -> "PLAYABILITY_BLOCKED"
            noAudio != null -> "NO_AUDIO_FORMATS"
            timeout != null -> "TIMEOUT"
            networkErr != null -> "NETWORK_ERROR"
            else -> failures.first().status.name
        }
    }

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
            val codePart = a.httpCode?.takeIf { it != 200 || a.status == SourceAttemptStatus.SUCCESS }
                ?.let { "HTTP $it" } ?: a.status.name
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

    fun assess(
        candidate: OnlineTrack,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long = 0L
    ): RelevanceAssessment {
        val normTargetTitle = normalizePlain(targetTitle)
        val normTargetArtist = normalizePlain(targetArtist)
        val normCandTitle = normalizePlain(candidate.title)
        val normCandArtist = normalizePlain(candidate.artist)
        val combinedCand = "$normCandArtist $normCandTitle"

        val titleTokens = tokenize(targetTitle).ifEmpty {
            normalizePlain(targetTitle).split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        val artistTokens = tokenize(targetArtist).ifEmpty {
            normalizePlain(targetArtist).split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        val candTitleTokens = tokenize(candidate.title).toSet()
        val matchedTitleCount = if (titleTokens.isEmpty()) 0 else {
            titleTokens.count { token ->
                token in candTitleTokens || normCandTitle.contains(token)
            }
        }
        val titleRatio = if (titleTokens.isEmpty()) 1f else matchedTitleCount.toFloat() / titleTokens.size.toFloat()

        val exactTitleMatch = normTargetTitle.isNotBlank() &&
            (normCandTitle == normTargetTitle || normCandTitle.contains(normTargetTitle))

        val artistMatch = when {
            artistTokens.isEmpty() -> true
            normTargetArtist.isNotBlank() && combinedCand.contains(normTargetArtist) -> true
            else -> artistTokens.any { token ->
                combinedCand.contains(token)
            }
        }

        var score = 0
        if (normCandTitle == normTargetTitle && normTargetTitle.isNotBlank()) {
            score += 70
        } else if (exactTitleMatch) {
            score += 52
        } else {
            score += (titleRatio * 48f).toInt()
        }

        if (artistTokens.isNotEmpty()) {
            if (normCandArtist.contains(normTargetArtist) && normTargetArtist.isNotBlank()) {
                score += 48
            } else if (artistMatch) {
                score += 28
            } else {
                score -= 35
            }
        }

        if (candidate.isOfficialAudio) score += 18
        if (normCandArtist.endsWith("topic")) score += 16
        if (candidate.title.contains("official audio", ignoreCase = true)) score += 10
        if (candidate.directStreamUrl != null && !candidate.isPreviewOnly) score += 8
        if (candidate.isPreviewOnly) score -= 24 // Prefer full tracks unless only preview exists

        var durationTooFar = false
        if (expectedDurationMs > 15_000L && candidate.durationMs > 15_000L && !candidate.isPreviewOnly) {
            val diffSec = abs(candidate.durationMs - expectedDurationMs) / 1000L
            score += when {
                diffSec <= 4L -> 50
                diffSec <= 10L -> 30
                diffSec <= 20L -> 10
                diffSec <= 35L -> -15
                else -> {
                    durationTooFar = true
                    -75
                }
            }
        }

        val targetLower = targetTitle.lowercase()
        val candLower = candidate.title.lowercase()
        for (mod in UNWANTED_MODIFIERS) {
            if (candLower.contains(mod) && !targetLower.contains(mod)) {
                score -= 48
            }
        }
        if (candLower.contains("remix") && !targetLower.contains("remix")) {
            score -= 32
        }
        if (candLower.contains("live") && !targetLower.contains("live")) {
            score -= 28
        }

        // Hard acceptance gate
        val titleOk = exactTitleMatch || titleRatio >= 0.5f || (titleTokens.size == 1 && matchedTitleCount == 1)
        val artistOk = artistTokens.isEmpty() || artistMatch || (exactTitleMatch && score >= 62)
        val accepted = score >= 45 && titleOk && artistOk && !durationTooFar

        val reason = when {
            !titleOk -> "Название не совпадает (${candidate.title})"
            !artistOk -> "Другой исполнитель (${candidate.artist})"
            durationTooFar -> "Сильное отличие длительности (${candidate.durationSeconds}с)"
            score < 45 -> "Низкий балл релевантности ($score)"
            else -> "Совпадение подтверждено ($score)"
        }

        return RelevanceAssessment(
            score = score,
            isAcceptable = accepted,
            titleMatchRatio = titleRatio,
            artistMatched = artistMatch,
            reason = reason
        )
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
