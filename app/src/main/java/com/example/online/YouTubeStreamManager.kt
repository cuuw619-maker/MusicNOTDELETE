package com.example.online

import android.net.Uri
import android.util.Log
import com.example.model.Song
import com.example.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class OnlineTrack(
    val id: String, // videoId
    val title: String,
    val artist: String,
    val album: String = "YouTube Music",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val uploaderAvatar: String? = null
) {
    val durationMs: Long
        get() = durationSeconds * 1000L

    fun toSong(streamUrl: String? = null): Song {
        val uniqueId = (id.hashCode().toLong() and 0xFFFFFFFFL) or 0x4000000000000000L
        return Song(
            id = uniqueId,
            title = title,
            artist = artist,
            album = album,
            albumId = 0L,
            durationMs = durationMs,
            contentUriString = streamUrl ?: "online://youtube/$id?title=${Uri.encode(title)}&artist=${Uri.encode(artist)}",
            albumArtUriString = thumbnailUrl,
            genre = "Online Stream",
            mimeType = "audio/opus"
        )
    }
}

class YouTubeStreamManager {
    private val client = NetworkClient.okHttpClient
    private val streamCache = ConcurrentHashMap<String, Pair<String, Long>>() // videoId -> (url, expiresAt)

    companion object {
        // Fast, privacy-friendly Piped and Invidious public audio streaming API mirrors
        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.privacydev.net",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.leptons.xyz",
            "https://api.piped.projectsegfau.lt"
        )
    }

    /**
     * Searches YouTube Music for songs and artists.
     */
    suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        for (instance in PIPED_INSTANCES) {
            try {
                val url = "$instance/search?q=$encodedQuery&filter=music_songs"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items") ?: JSONArray()
                        val result = mutableListOf<OnlineTrack>()

                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val type = item.optString("type", "")
                            if (type != "stream") continue

                            val rawUrl = item.optString("url", "")
                            val videoId = rawUrl.substringAfter("watch?v=", "").substringBefore("&")
                            if (videoId.isEmpty()) continue

                            val title = item.optString("title", "Неизвестный трек")
                            val uploader = item.optString("uploaderName", "Неизвестный исполнитель")
                            val duration = item.optLong("duration", 0L)
                            val thumbnail = item.optString("thumbnail", null)
                            val uploaderAvatar = item.optString("uploaderAvatar", null)

                            result.add(
                                OnlineTrack(
                                    id = videoId,
                                    title = title,
                                    artist = uploader,
                                    durationSeconds = duration,
                                    thumbnailUrl = thumbnail,
                                    uploaderAvatar = uploaderAvatar
                                )
                            )
                        }

                        if (result.isNotEmpty()) {
                            return@withContext result
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("YouTubeStreamManager", "Search failed on $instance, trying next mirror", e)
            }
        }

        emptyList()
    }

    /**
     * Resolves the direct playable audio stream URL (Opus or AAC/M4A) for a given YouTube video ID.
     */
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val cached = streamCache[videoId]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            return@withContext cached.first
        }

        for (instance in PIPED_INSTANCES) {
            try {
                val url = "$instance/streams/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)
                        val audioStreams = json.optJSONArray("audioStreams") ?: JSONArray()

                        var bestStreamUrl: String? = null
                        var highestBitrate = 0

                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.getJSONObject(i)
                            val streamUrl = stream.optString("url", "")
                            val bitrate = stream.optInt("bitrate", 0)
                            val mime = stream.optString("mimeType", "")

                            // Prefer audio/opus or highest bitrate audio stream
                            if (streamUrl.isNotEmpty() && (bitrate > highestBitrate || mime.contains("opus"))) {
                                highestBitrate = bitrate
                                bestStreamUrl = streamUrl
                            }
                        }

                        if (bestStreamUrl != null) {
                            // Cache valid stream URL for 4 hours
                            val expiresAt = System.currentTimeMillis() + (4 * 3600 * 1000L)
                            streamCache[videoId] = Pair(bestStreamUrl, expiresAt)
                            return@withContext bestStreamUrl
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("YouTubeStreamManager", "Stream resolve failed on $instance, trying next", e)
            }
        }

        null
    }
}
