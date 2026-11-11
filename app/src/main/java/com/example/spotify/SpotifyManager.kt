package com.example.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.model.Playlist
import com.example.model.Song
import com.example.network.NetworkClient
import com.example.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class SpotifyUser(
    val id: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val ownerName: String = ""
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumArtUrl: String? = null,
    val previewUrl: String? = null
)

data class SpotifyAuthState(
    val isConnected: Boolean = false,
    val user: SpotifyUser? = null,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SpotifyManager(
    private val context: Context,
    private val musicRepository: MusicRepository
) {
    private val prefs = context.getSharedPreferences("imux_spotify_prefs", Context.MODE_PRIVATE)
    private val client = NetworkClient.okHttpClient

    private val _authState = MutableStateFlow(SpotifyAuthState())
    val authState: StateFlow<SpotifyAuthState> = _authState.asStateFlow()

    private var currentCodeVerifier: String?
        get() = prefs.getString(KEY_CODE_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_CODE_VERIFIER, value).apply()

    var customClientId: String
        get() = prefs.getString(KEY_CUSTOM_CLIENT_ID, DEFAULT_CLIENT_ID) ?: DEFAULT_CLIENT_ID
        set(value) = prefs.edit().putString(KEY_CUSTOM_CLIENT_ID, value.trim()).apply()

    init {
        loadSavedSession()
    }

    private fun loadSavedSession() {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val userName = prefs.getString(KEY_USER_NAME, null)
        val userAvatar = prefs.getString(KEY_USER_AVATAR, null)

        if (!token.isNullOrEmpty() && !userId.isNullOrEmpty()) {
            _authState.value = SpotifyAuthState(
                isConnected = true,
                user = SpotifyUser(id = userId, displayName = userName ?: "Spotify User", avatarUrl = userAvatar)
            )
        }
    }

    /**
     * Generates a secure PKCE Authorization URL for Spotify Web API.
     */
    fun createAuthorizationUri(): Uri {
        val verifier = generateCodeVerifier()
        currentCodeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", customClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "playlist-read-private playlist-read-collaborative user-read-private user-read-email")
            .build()
    }

    /**
     * Handles redirect from browser custom tab (imux://spotify-callback?code=...).
     */
    suspend fun handleRedirectUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            _authState.value = _authState.value.copy(errorMessage = "Ошибка входа: $error", isLoading = false)
            return@withContext false
        }

        if (code.isNullOrEmpty()) {
            return@withContext false
        }

        val verifier = currentCodeVerifier
        if (verifier.isNullOrEmpty()) {
            _authState.value = _authState.value.copy(errorMessage = "Сессия OAuth истекла, попробуйте снова.", isLoading = false)
            return@withContext false
        }

        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)

        try {
            val body = FormBody.Builder()
                .add("client_id", customClientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e("SpotifyManager", "Token exchange failed: $errBody")
                    _authState.value = _authState.value.copy(
                        isLoading = false,
                        errorMessage = "Ошибка получения токена: ${response.code}"
                    )
                    return@withContext false
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", null)
                val expiresIn = json.optLong("expires_in", 3600L)

                saveTokens(accessToken, refreshToken, expiresIn)
                fetchUserProfile(accessToken)
                fetchUserPlaylists()
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "OAuth redirect error", e)
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = e.localizedMessage)
            return@withContext false
        }
    }

    private suspend fun fetchUserProfile(accessToken: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val id = json.optString("id", "")
                    val name = json.optString("display_name", "Spotify User")
                    val email = json.optString("email", null)
                    val images = json.optJSONArray("images")
                    val avatarUrl = if (images != null && images.length() > 0) {
                        images.getJSONObject(0).optString("url")
                    } else null

                    prefs.edit()
                        .putString(KEY_USER_ID, id)
                        .putString(KEY_USER_NAME, name)
                        .putString(KEY_USER_AVATAR, avatarUrl)
                        .apply()

                    _authState.value = _authState.value.copy(
                        isConnected = true,
                        user = SpotifyUser(id, name, email, avatarUrl),
                        isLoading = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchUserProfile error", e)
        }
    }

    /**
     * Fetches current authorized user's playlists.
     */
    suspend fun fetchUserPlaylists() = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext
        _authState.value = _authState.value.copy(isLoading = true)

        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONArray("items") ?: JSONArray()
                    val playlists = mutableListOf<SpotifyPlaylist>()

                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val id = item.optString("id")
                        val name = item.optString("name")
                        val desc = item.optString("description")
                        val tracksObj = item.optJSONObject("tracks")
                        val totalTracks = tracksObj?.optInt("total", 0) ?: 0
                        val ownerObj = item.optJSONObject("owner")
                        val ownerName = ownerObj?.optString("display_name", "") ?: ""
                        val images = item.optJSONArray("images")
                        val coverUrl = if (images != null && images.length() > 0) {
                            images.getJSONObject(0).optString("url")
                        } else null

                        playlists.add(
                            SpotifyPlaylist(
                                id = id,
                                name = name,
                                description = desc,
                                coverUrl = coverUrl,
                                trackCount = totalTracks,
                                ownerName = ownerName
                            )
                        )
                    }

                    _authState.value = _authState.value.copy(playlists = playlists, isLoading = false)
                } else {
                    _authState.value = _authState.value.copy(isLoading = false, errorMessage = "Не удалось загрузить плейлисты")
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchUserPlaylists error", e)
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = e.localizedMessage)
        }
    }

    /**
     * Fetches tracks inside a given Spotify playlist ID.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: return@withContext emptyList()
        val tracksList = mutableListOf<SpotifyTrack>()

        try {
            val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONArray("items") ?: JSONArray()

                    for (i in 0 until items.length()) {
                        val trackObj = items.getJSONObject(i).optJSONObject("track") ?: continue
                        val id = trackObj.optString("id", "")
                        val title = trackObj.optString("name", "Неизвестный трек")
                        val duration = trackObj.optLong("duration_ms", 0L)
                        val preview = trackObj.optString("preview_url", null)

                        val artistsArr = trackObj.optJSONArray("artists")
                        val artistName = if (artistsArr != null && artistsArr.length() > 0) {
                            val names = mutableListOf<String>()
                            for (a in 0 until artistsArr.length()) {
                                names.add(artistsArr.getJSONObject(a).optString("name"))
                            }
                            names.joinToString(", ")
                        } else "Неизвестный исполнитель"

                        val albumObj = trackObj.optJSONObject("album")
                        val albumName = albumObj?.optString("name", "") ?: ""
                        val albumImages = albumObj?.optJSONArray("images")
                        val albumArt = if (albumImages != null && albumImages.length() > 0) {
                            albumImages.getJSONObject(0).optString("url")
                        } else null

                        tracksList.add(
                            SpotifyTrack(
                                id = id,
                                title = title,
                                artist = artistName,
                                album = albumName,
                                durationMs = duration,
                                albumArtUrl = albumArt,
                                previewUrl = preview
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchPlaylistTracks error", e)
        }

        tracksList
    }

    /**
     * Imports a Spotify playlist into the local application database.
     */
    suspend fun importPlaylist(
        spotifyPlaylist: SpotifyPlaylist,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Playlist? = withContext(Dispatchers.IO) {
        val tracks = fetchPlaylistTracks(spotifyPlaylist.id)
        if (tracks.isEmpty()) return@withContext null

        val playlistId = musicRepository.createPlaylist(spotifyPlaylist.name)

        tracks.forEachIndexed { index, st ->
            val songId = System.currentTimeMillis() + index
            musicRepository.addSongToPlaylist(playlistId, songId)
            onProgress(index + 1, tracks.size)
        }

        Playlist(
            id = playlistId,
            name = spotifyPlaylist.name,
            createdAt = System.currentTimeMillis(),
            songCount = tracks.size,
            firstSongAlbumArt = spotifyPlaylist.coverUrl
        )
    }

    /**
     * Import a public Spotify playlist directly by web link or ID (e.g. https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M).
     */
    suspend fun importPublicPlaylistByUrl(urlOrId: String): Playlist? = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(urlOrId) ?: return@withContext null
        val token = getValidAccessToken() ?: return@withContext null

        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/playlists/$playlistId")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val name = json.optString("name", "Импортированный плейлист")
                    val desc = json.optString("description", "")
                    val images = json.optJSONArray("images")
                    val cover = if (images != null && images.length() > 0) images.getJSONObject(0).optString("url") else null

                    val spotifyPl = SpotifyPlaylist(
                        id = playlistId,
                        name = name,
                        description = desc,
                        coverUrl = cover
                    )
                    return@withContext importPlaylist(spotifyPl)
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "importPublicPlaylistByUrl error", e)
        }
        null
    }

    private fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (!trimmed.contains("/")) return trimmed
        val uri = Uri.parse(trimmed)
        val path = uri.path ?: return null
        return path.substringAfterLast("/")
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        _authState.value = SpotifyAuthState()
    }

    private suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@withContext null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)

        if (System.currentTimeMillis() < expiresAt) {
            return@withContext token
        }

        if (refreshToken.isNullOrEmpty()) {
            return@withContext token
        }

        // Refresh token
        try {
            val body = FormBody.Builder()
                .add("client_id", customClientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val newAccessToken = json.getString("access_token")
                    val newRefresh = json.optString("refresh_token", refreshToken)
                    val expiresIn = json.optLong("expires_in", 3600L)
                    saveTokens(newAccessToken, newRefresh, expiresIn)
                    return@withContext newAccessToken
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "Token refresh error", e)
        }

        token
    }

    private fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60000L
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply {
                if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
                putLong(KEY_EXPIRES_AT, expiresAt)
            }
            .apply()
    }

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val code = ByteArray(64)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(StandardCharsets.US_ASCII)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, 0, bytes.size)
        val digest = md.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT_URI = "imux://spotify-callback"
        // Default Spotify developer Client ID for public music and playlist browsing
        const val DEFAULT_CLIENT_ID = "006d691f175440c9ad32e1858a2f4da0"

        private const val KEY_ACCESS_TOKEN = "key_spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "key_spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "key_spotify_expires_at"
        private const val KEY_CODE_VERIFIER = "key_spotify_code_verifier"
        private const val KEY_CUSTOM_CLIENT_ID = "key_spotify_custom_client_id"
        private const val KEY_USER_ID = "key_spotify_user_id"
        private const val KEY_USER_NAME = "key_spotify_user_name"
        private const val KEY_USER_AVATAR = "key_spotify_user_avatar"
    }
}
