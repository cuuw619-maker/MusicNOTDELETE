package com.example.online

import android.util.Log
import com.example.network.NetworkClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/**
 * Wraps NewPipeExtractor with OkHttp so YouTube `signatureCipher` and `n`-parameter
 * throttling are automatically deciphered in-process using Rhino JS without external proxies.
 */
object NewPipeYouTubeResolver {

    private const val TAG = "NewPipeYTResolver"

    @Volatile
    private var initialized = false

    private val okHttpDownloader = object : Downloader() {
        @Throws(IOException::class)
        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()

            val builder = okhttp3.Request.Builder().url(url)
            var contentType: String? = null
            headers.forEach { (key, values) ->
                if (key.equals("Content-Type", ignoreCase = true)) {
                    contentType = values.firstOrNull()
                }
                values.forEach { v ->
                    builder.addHeader(key, v)
                }
            }
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", NetworkClient.BROWSER_UA)
            }

            if (method.equals("POST", ignoreCase = true)) {
                val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                val bodyBytes = dataToSend ?: ByteArray(0)
                builder.post(bodyBytes.toRequestBody(mediaType))
            } else if (method.equals("HEAD", ignoreCase = true)) {
                builder.head()
            } else {
                builder.get()
            }

            NetworkClient.fastProbeClient.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val respHeaders = mutableMapOf<String, MutableList<String>>()
                for (i in 0 until resp.headers.size) {
                    val name = resp.headers.name(i)
                    val value = resp.headers.value(i)
                    respHeaders.getOrPut(name) { mutableListOf() }.add(value)
                }
                val finalUrl = resp.request.url.toString()
                return Response(
                    resp.code,
                    resp.message,
                    respHeaders,
                    respBody,
                    finalUrl
                )
            }
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(okHttpDownloader)
                initialized = true
            }
        }
    }

    private fun probeNewPipeStream(url: String, isHls: Boolean = false): Boolean {
        return try {
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.BROWSER_UA)
            if (isHls) {
                builder.head()
            } else {
                builder.header("Range", "bytes=0-63")
            }
            NetworkClient.fastProbeClient.newCall(builder.build()).execute().use { resp ->
                if (resp.code !in listOf(200, 206, 302, 303, 307)) return false
                val contentType = resp.header("Content-Type")?.lowercase()?.trim().orEmpty()
                if (contentType.startsWith("text/html") ||
                    contentType.startsWith("text/plain") ||
                    contentType.startsWith("application/json")
                ) {
                    return false
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun resolveAudioStream(videoId: String, expectedDurationMs: Long = 0L): ResolvedStream? {
        if (videoId.isBlank()) return null
        val t0 = System.currentTimeMillis()
        return try {
            ensureInitialized()
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)

            if (YouTubeStreamManager.isSuspiciousStubStream(
                    requestedVideoId = videoId,
                    returnedVideoId = info.id,
                    returnedTitle = info.name,
                    returnedDurationSec = info.duration,
                    expectedDurationMs = expectedDurationMs
                )
            ) {
                SourceDiagnosticsLog.record(
                    SourceAttemptLog(
                        sourceId = "yt_web_rhino",
                        displayName = "YT Web Rhino • NewPipe",
                        stage = "stub_filter",
                        status = SourceAttemptStatus.PLAYABILITY_BLOCKED,
                        httpCode = 200,
                        detail = "Отфильтрована заглушка YouTube (${info.name.take(48)}, ${info.duration}с)",
                        latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    )
                )
                return null
            }

            val audioCandidates = info.audioStreams
                .filter { it.content.startsWith("http") }
                .sortedByDescending { stream ->
                    val formatBonus = if (stream.format?.suffix?.equals("m4a", true) == true) 80_000 else 40_000
                    stream.averageBitrate + formatBonus
                }
                .take(3)

            for (bestAudio in audioCandidates) {
                if (probeNewPipeStream(bestAudio.content, isHls = false)) {
                    SourceDiagnosticsLog.record(
                        SourceAttemptLog(
                            sourceId = "yt_web_rhino",
                            displayName = "YT Web Rhino • NewPipe",
                            stage = "decipher",
                            status = SourceAttemptStatus.SUCCESS,
                            httpCode = 200,
                            detail = "Дешифрован и подтверждён (${bestAudio.format?.suffix ?: "audio"} ${bestAudio.averageBitrate}k)",
                            latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        )
                    )
                    return ResolvedStream(
                        url = bestAudio.content,
                        userAgent = NetworkClient.BROWSER_UA,
                        expiresAtMs = System.currentTimeMillis() + 4 * 3600_000L,
                        engine = "yt_web_rhino"
                    )
                }
            }

            val hls = info.hlsUrl
            if (!hls.isNullOrBlank() && probeNewPipeStream(hls, isHls = true)) {
                SourceDiagnosticsLog.record(
                    SourceAttemptLog(
                        sourceId = "yt_web_rhino",
                        displayName = "YT Web Rhino • NewPipe",
                        stage = "hls",
                        status = SourceAttemptStatus.SUCCESS,
                        httpCode = 200,
                        detail = "Подтверждён HLS-манифест",
                        latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    )
                )
                return ResolvedStream(
                    url = hls,
                    userAgent = NetworkClient.BROWSER_UA,
                    expiresAtMs = System.currentTimeMillis() + 4 * 3600_000L,
                    engine = "yt_web_rhino"
                )
            }

            val hadAny = audioCandidates.isNotEmpty() || !hls.isNullOrBlank()
            SourceDiagnosticsLog.record(
                SourceAttemptLog(
                    sourceId = "yt_web_rhino",
                    displayName = "YT Web Rhino • NewPipe",
                    stage = if (hadAny) "cdn_probe" else "decipher",
                    status = if (hadAny) SourceAttemptStatus.HTTP_ERROR else SourceAttemptStatus.EMPTY_RESULT,
                    httpCode = if (hadAny) 403 else null,
                    detail = if (hadAny) "CDN отклонил извлечённые потоки NewPipe" else "Список аудиопотоков пуст",
                    latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                )
            )
            null
        } catch (e: Throwable) {
            Log.w(TAG, "NewPipe resolve failed for $videoId: ${e.message}")
            val rawMsg = e.message ?: ""
            val isBlock = YouTubeStreamManager.isDeprecationOrAppBlockMessage(rawMsg) ||
                rawMsg.contains("Sign in", true) ||
                rawMsg.contains("bot", true) ||
                rawMsg.contains("unavailable", true)
            val status = when {
                isBlock -> SourceAttemptStatus.PLAYABILITY_BLOCKED
                rawMsg.contains("403") -> SourceAttemptStatus.HTTP_ERROR
                else -> SourceAttemptStatus.NETWORK_ERROR
            }
            val detail = if (isBlock) {
                YouTubeStreamManager.translatePlayabilityReason("UNPLAYABLE", rawMsg)
            } else {
                rawMsg.take(140).ifBlank { "Сбой дешифрации" }
            }
            SourceDiagnosticsLog.record(
                SourceAttemptLog(
                    sourceId = "yt_web_rhino",
                    displayName = "YT Web Rhino • NewPipe",
                    stage = "decipher",
                    status = status,
                    httpCode = if (rawMsg.contains("403")) 403 else null,
                    detail = detail,
                    latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                )
            )
            null
        }
    }
}
