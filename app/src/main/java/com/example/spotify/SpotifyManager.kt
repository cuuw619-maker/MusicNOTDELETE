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
    val authMethod: String = "",
    val user: SpotifyUser? = null,
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val importProgressLoaded: Int = 0,
    val importProgressTotal: Int = 0
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
        get() = prefs.getString(KEY_CUSTOM_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_CLIENT_ID, value.trim()).apply()

    fun hasValidCustomClientId(): Boolean =
        customClientId.trim().matches(Regex("^[a-fA-F0-9]{20,40}$"))

    init {
        loadSavedSession()
    }

    private fun loadSavedSession() {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val spDc = prefs.getString(KEY_SP_DC_COOKIE, null)
        val userId = prefs.getString(KEY_USER_ID, null)
        val userName = prefs.getString(KEY_USER_NAME, null)
        val userAvatar = prefs.getString(KEY_USER_AVATAR, null)
        val method = prefs.getString(KEY_AUTH_METHOD, "OAuth") ?: "OAuth"

        if ((!token.isNullOrEmpty() || !spDc.isNullOrEmpty()) && !userId.isNullOrEmpty()) {
            _authState.value = SpotifyAuthState(
                isConnected = true,
                authMethod = method,
                user = SpotifyUser(id = userId, displayName = userName ?: "Spotify User", avatarUrl = userAvatar)
            )
        }
    }

    /**
     * Generates a validated PKCE Authorization URL ONLY if user supplied a valid Spotify Developer Client ID.
     * Prevents Spotify's "invalid argument / INVALID_CLIENT" error caused by unregistered Client IDs.
     */
    fun createAuthorizationUri(): Uri? {
        val clientId = customClientId.trim()
        if (!hasValidCustomClientId()) {
            _authState.value = _authState.value.copy(
                errorMessage = "Укажите корректный Spotify Client ID (32 символа из developer.spotify.com) или используйте вход через Web-токен sp_dc."
            )
            return null
        }

        val verifier = generateCodeVerifier()
        currentCodeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "playlist-read-private playlist-read-collaborative user-library-read user-read-private")
            .appendQueryParameter("show_dialog", "true")
            .build()
    }

    /**
     * Connects user's Spotify account directly from the in-app WebView session capture
     * (`accessToken`, `client-token`, and `sp_dc` cookie), avoiding TOTP/HTTP 400 issues.
     */
    suspend fun connectWithCapturedWebSession(
        accessToken: String?,
        clientToken: String?,
        spDcCookie: String?
    ): Boolean = withContext(Dispatchers.IO) {
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
        try {
            val cleanSpDc = spDcCookie?.trim()?.takeIf { it.isNotBlank() }
            if (!cleanSpDc.isNullOrBlank()) {
                prefs.edit().putString(KEY_SP_DC_COOKIE, cleanSpDc).apply()
            }
            val cleanClientToken = clientToken?.trim()?.takeIf { it.isNotBlank() }
            if (!cleanClientToken.isNullOrBlank()) {
                prefs.edit().putString(KEY_CLIENT_TOKEN, cleanClientToken).apply()
            }

            val resolvedToken = accessToken
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: cleanSpDc?.let { exchangeSpDcCookieForToken(it) }

            if (resolvedToken.isNullOrBlank()) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось получить accessToken из Web-сессии Spotify. Попробуйте ещё раз."
                )
                return@withContext false
            }

            saveTokens(resolvedToken, null, 3500L, method = "WebView Вход")
            val profileOk = fetchUserProfile(resolvedToken)
            if (!profileOk) {
                prefs.edit()
                    .putString(KEY_USER_ID, "spotify_web")
                    .putString(KEY_USER_NAME, "Spotify Аккаунт")
                    .apply()
                _authState.value = _authState.value.copy(
                    isConnected = true,
                    authMethod = "WebView Вход",
                    user = SpotifyUser("spotify_web", "Spotify Аккаунт"),
                    isLoading = false
                )
            }
            fetchUserPlaylists()
            true
        } catch (e: Exception) {
            Log.e("SpotifyManager", "connectWithCapturedWebSession error", e)
            _authState.value = _authState.value.copy(
                isLoading = false,
                errorMessage = "Ошибка Web-входа Spotify: ${e.localizedMessage}"
            )
            false
        }
    }

    /**
     * Connects user's full Spotify account using a Web Player session cookie (`sp_dc=...`) OR Bearer access token.
     */
    suspend fun connectWithWebTokenOrCookie(rawInput: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            _authState.value = _authState.value.copy(errorMessage = "Введите cookie sp_dc или токен доступа")
            return@withContext false
        }

        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)

        try {
            val accessToken: String? = if (trimmed.startsWith("Bearer ", ignoreCase = true) || trimmed.startsWith("BQ")) {
                trimmed.removePrefix("Bearer ").removePrefix("bearer ").trim()
            } else {
                val cookieValue = trimmed.removePrefix("sp_dc=").substringBefore(";").trim()
                prefs.edit().putString(KEY_SP_DC_COOKIE, cookieValue).apply()
                exchangeSpDcCookieForToken(cookieValue)
            }

            if (accessToken.isNullOrBlank()) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось получить токен Spotify из sp_dc. Используйте «Войти в аккаунт Spotify (Web)»."
                )
                return@withContext false
            }

            saveTokens(accessToken, null, 3500L, method = "Web Session")
            val profileOk = fetchUserProfile(accessToken)
            if (!profileOk) {
                prefs.edit()
                    .putString(KEY_USER_ID, "spotify_web")
                    .putString(KEY_USER_NAME, "Spotify Web")
                    .apply()
                _authState.value = _authState.value.copy(
                    isConnected = true,
                    authMethod = "Web Session",
                    user = SpotifyUser("spotify_web", "Spotify Web"),
                    isLoading = false
                )
            }
            fetchUserPlaylists()
            true
        } catch (e: Exception) {
            Log.e("SpotifyManager", "Web cookie connect error", e)
            _authState.value = _authState.value.copy(
                isLoading = false,
                errorMessage = "Ошибка подключения: ${e.localizedMessage}"
            )
            false
        }
    }

    private fun exchangeSpDcCookieForToken(spDc: String? = null): String? {
        try {
            val builder = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("App-Platform", "WebPlayer")

            if (!spDc.isNullOrBlank()) {
                builder.header("Cookie", "sp_dc=$spDc")
            }

            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val token = json.optString("accessToken", "")
                    val isAnonymous = json.optBoolean("isAnonymous", true)
                    if (token.isNotBlank() && (spDc.isNullOrBlank() || !isAnonymous)) {
                        return token
                    }
                    if (token.isNotBlank() && spDc.isNullOrBlank()) return token
                }
            }
        } catch (e: Exception) {
            Log.w("SpotifyManager", "get_access_token error: ${e.message}")
        }
        return null
    }

    /**
     * Handles redirect from browser custom tab (imux://spotify-callback?code=...).
     */
    suspend fun handleRedirectUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            _authState.value = _authState.value.copy(
                errorMessage = "Spotify отклонил вход: $error. Проверьте Redirect URI (imux://spotify-callback) и Client ID.",
                isLoading = false
            )
            return@withContext false
        }

        if (code.isNullOrEmpty()) return@withContext false

        val verifier = currentCodeVerifier
        if (verifier.isNullOrEmpty()) {
            _authState.value = _authState.value.copy(errorMessage = "Сессия OAuth истекла, попробуйте снова.", isLoading = false)
            return@withContext false
        }

        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)

        try {
            val body = FormBody.Builder()
                .add("client_id", customClientId.trim())
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
                        errorMessage = "Ошибка обмена токена (${response.code}). Убедитесь, что Client ID корректный."
                    )
                    return@withContext false
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val accessToken = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", null)
                val expiresIn = json.optLong("expires_in", 3600L)

                saveTokens(accessToken, refreshToken, expiresIn, method = "OAuth PKCE")
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

    private fun Request.Builder.withSpotifyApiHeaders(token: String): Request.Builder {
        header("Authorization", "Bearer $token")
        header("Accept", "application/json")
        header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
        val clientToken = prefs.getString(KEY_CLIENT_TOKEN, null)
        if (!clientToken.isNullOrBlank()) {
            header("client-token", clientToken)
            header("App-Platform", "WebPlayer")
        }
        return this
    }

    private suspend fun fetchUserProfile(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .withSpotifyApiHeaders(accessToken)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val id = json.optString("id", "spotify_user")
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
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchUserProfile error", e)
        }
        false
    }

    /**
     * Fetches ALL playlists of the authorized user with full pagination + Liked Songs entry.
     */
    suspend fun fetchUserPlaylists() = withContext(Dispatchers.IO) {
        val token = getValidAccessToken() ?: run {
            _authState.value = _authState.value.copy(isLoading = false)
            return@withContext
        }
        _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)

        try {
            var likedTotal = 0
            try {
                val likedCountReq = Request.Builder()
                    .url("https://api.spotify.com/v1/me/tracks?limit=1")
                    .withSpotifyApiHeaders(token)
                    .build()
                client.newCall(likedCountReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        likedTotal = JSONObject(resp.body?.string() ?: "{}").optInt("total", 0)
                    }
                }
            } catch (_: Exception) {
            }

            val playlists = mutableListOf<SpotifyPlaylist>()
            playlists.add(
                SpotifyPlaylist(
                    id = LIKED_SONGS_ID,
                    name = "Любимые треки Spotify",
                    description = "Вся ваша коллекция понравившихся треков",
                    trackCount = likedTotal,
                    ownerName = _authState.value.user?.displayName ?: "Вы"
                )
            )

            var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
            var firstErrorCode: Int? = null

            while (nextUrl != null) {
                val request = Request.Builder()
                    .url(nextUrl)
                    .withSpotifyApiHeaders(token)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        firstErrorCode = response.code
                        nextUrl = null
                        return@use
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONArray("items") ?: JSONArray()

                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isBlank()) continue
                        val name = item.optString("name", "Плейлист")
                        val desc = item.optString("description", "")
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
                    nextUrl = if (json.isNull("next")) null else json.optString("next").takeIf { it.isNotBlank() }
                }
            }

            if (firstErrorCode != null && playlists.size <= 1) {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Не удалось загрузить плейлисты (HTTP $firstErrorCode). Войдите заново через Web-аккаунт."
                )
            } else {
                _authState.value = _authState.value.copy(
                    playlists = playlists,
                    isLoading = false,
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchUserPlaylists error", e)
            _authState.value = _authState.value.copy(isLoading = false, errorMessage = e.localizedMessage)
        }
    }

    /**
     * Fetches ALL tracks of a playlist without arbitrary count caps via paginated Web API or Embed JSON fallback.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_SONGS_ID) {
            return@withContext fetchLikedTracks()
        }

        val token = getValidAccessToken() ?: exchangeSpDcCookieForToken(null)
        val tracksList = mutableListOf<SpotifyTrack>()

        if (!token.isNullOrBlank()) {
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100"
            try {
                while (nextUrl != null) {
                    val request = Request.Builder()
                        .url(nextUrl)
                        .withSpotifyApiHeaders(token)
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            nextUrl = null
                            return@use
                        }
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val total = json.optInt("total", 0)
                        val items = json.optJSONArray("items") ?: JSONArray()

                        for (i in 0 until items.length()) {
                            val trackObj = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                            val parsed = parseSpotifyTrackObject(trackObj)
                            if (parsed != null) tracksList.add(parsed)
                        }

                        _authState.value = _authState.value.copy(
                            importProgressLoaded = tracksList.size,
                            importProgressTotal = maxOf(total, tracksList.size)
                        )
                        nextUrl = if (json.isNull("next")) null else json.optString("next").takeIf { it.isNotBlank() }
                    }
                }
            } catch (e: Exception) {
                Log.w("SpotifyManager", "Web API track fetch error: ${e.message}")
            }
        }

        if (tracksList.isEmpty()) {
            val embedData = fetchPublicPlaylistFromEmbed(playlistId)
            if (embedData != null) {
                tracksList.addAll(embedData.second)
            }
        }

        tracksList
    }

    private suspend fun fetchLikedTracks(): List<SpotifyTrack> {
        val token = getValidAccessToken() ?: return emptyList()
        val result = mutableListOf<SpotifyTrack>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        try {
            while (nextUrl != null) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .withSpotifyApiHeaders(token)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        nextUrl = null
                        return@use
                    }
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val total = json.optInt("total", 0)
                    val items = json.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val trackObj = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                        parseSpotifyTrackObject(trackObj)?.let { result.add(it) }
                    }
                    _authState.value = _authState.value.copy(
                        importProgressLoaded = result.size,
                        importProgressTotal = maxOf(total, result.size)
                    )
                    nextUrl = if (json.isNull("next")) null else json.optString("next").takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "fetchLikedTracks error", e)
        }
        return result
    }

    private fun parseSpotifyTrackObject(trackObj: JSONObject): SpotifyTrack? {
        val id = trackObj.optString("id", "")
        val title = trackObj.optString("name", "")
        if (title.isBlank()) return null

        val artistsArray = trackObj.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        if (artistsArray != null) {
            for (j in 0 until artistsArray.length()) {
                artistNames.add(artistsArray.getJSONObject(j).optString("name", ""))
            }
        }
        val artist = artistNames.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Spotify Artist" }

        val albumObj = trackObj.optJSONObject("album")
        val albumName = albumObj?.optString("name", "Spotify") ?: "Spotify"
        val images = albumObj?.optJSONArray("images")
        val coverUrl = if (images != null && images.length() > 0) {
            images.getJSONObject(0).optString("url")
        } else null

        val durationMs = trackObj.optLong("duration_ms", 0L)
        val previewUrl = if (trackObj.isNull("preview_url")) null else trackObj.optString("preview_url")

        return SpotifyTrack(
            id = id.ifBlank { "${artist}_$title".hashCode().toString() },
            title = title,
            artist = artist,
            album = albumName,
            durationMs = durationMs,
            albumArtUrl = coverUrl,
            previewUrl = previewUrl
        )
    }

    /**
     * Imports a Spotify playlist AND persists every track into Room (`online_songs`) + `playlist_songs`!
     */
    suspend fun importPlaylist(
        spotifyPlaylist: SpotifyPlaylist,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Playlist? = withContext(Dispatchers.IO) {
        try {
            val tracks = fetchPlaylistTracks(spotifyPlaylist.id)
            if (tracks.isEmpty()) return@withContext null
            persistPlaylistAndTracks(spotifyPlaylist.name, spotifyPlaylist.coverUrl, tracks) { loaded, total ->
                _authState.value = _authState.value.copy(
                    importProgressLoaded = loaded,
                    importProgressTotal = total
                )
                onProgress(loaded, total)
            }
        } finally {
            _authState.value = _authState.value.copy(importProgressLoaded = 0, importProgressTotal = 0)
        }
    }

    /**
     * Zero-config import by link/ID: works for ANY public playlist via Spotify Web API or Embed JSON fallback.
     */
    suspend fun importPublicPlaylistByUrl(urlOrId: String): Playlist? = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(urlOrId) ?: return@withContext null

        // 1. Try authenticated or anonymous Web Token first for full metadata & pagination
        val token = getValidAccessToken() ?: exchangeSpDcCookieForToken(null)
        if (!token.isNullOrBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://api.spotify.com/v1/playlists/$playlistId")
                    .header("Authorization", "Bearer $token")
                    .build()

                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val name = json.optString("name", "Импортированный плейлист")
                        val images = json.optJSONArray("images")
                        val cover = if (images != null && images.length() > 0) images.getJSONObject(0).optString("url") else null

                        val tracks = fetchPlaylistTracks(playlistId)
                        if (tracks.isNotEmpty()) {
                            return@withContext persistPlaylistAndTracks(name, cover, tracks)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SpotifyManager", "Web API public playlist fetch failed, trying Embed fallback", e)
            }
        }

        // 2. Fallback: Zero-Auth Spotify Embed Page (`__NEXT_DATA__`)
        val embedResult = fetchPublicPlaylistFromEmbed(playlistId)
        if (embedResult != null && embedResult.second.isNotEmpty()) {
            return@withContext persistPlaylistAndTracks(embedResult.first.name, embedResult.first.coverUrl, embedResult.second)
        }

        null
    }

    /**
     * Scrapes clean structured JSON (`__NEXT_DATA__`) from `open.spotify.com/embed/playlist/{id}`.
     * Requires zero API keys or Client IDs!
     */
    private fun fetchPublicPlaylistFromEmbed(playlistId: String): Pair<SpotifyPlaylist, List<SpotifyTrack>>? {
        try {
            val req = Request.Builder()
                .url("https://open.spotify.com/embed/playlist/$playlistId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                val startTag = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                val startIdx = html.indexOf(startTag)
                if (startIdx == -1) return null
                val jsonStart = startIdx + startTag.length
                val jsonEnd = html.indexOf("</script>", jsonStart)
                if (jsonEnd == -1) return null

                val nextJson = JSONObject(html.substring(jsonStart, jsonEnd))
                val entity = nextJson.optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity") ?: return null

                val playlistName = entity.optString("name", entity.optString("title", "Spotify Плейлист"))
                val ownerName = entity.optString("subtitle", "Spotify")
                val coverImages = entity.optJSONObject("coverArt")?.optJSONArray("sources")
                val playlistCover = if (coverImages != null && coverImages.length() > 0) {
                    coverImages.getJSONObject(0).optString("url")
                } else null

                val trackList = entity.optJSONArray("trackList") ?: JSONArray()
                val tracks = mutableListOf<SpotifyTrack>()

                for (i in 0 until trackList.length()) {
                    val item = trackList.optJSONObject(i) ?: continue
                    val title = item.optString("title", "")
                    if (title.isBlank()) continue
                    val artist = item.optString("subtitle", "Spotify Artist")
                    val durationMs = item.optLong("duration", 0L)
                    val uri = item.optString("uri", "")
                    val trackId = uri.substringAfterLast(":").ifBlank { "${artist}_$title".hashCode().toString() }
                    val audioPreview = item.optJSONObject("audioPreview")?.optString("url", null)

                    tracks.add(
                        SpotifyTrack(
                            id = trackId,
                            title = title,
                            artist = artist,
                            album = playlistName,
                            durationMs = durationMs,
                            albumArtUrl = playlistCover,
                            previewUrl = audioPreview
                        )
                    )
                }

                val pl = SpotifyPlaylist(
                    id = playlistId,
                    name = playlistName,
                    coverUrl = playlistCover,
                    trackCount = tracks.size,
                    ownerName = ownerName
                )
                return Pair(pl, tracks)
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "Embed playlist parse error", e)
        }
        return null
    }

    private suspend fun persistPlaylistAndTracks(
        name: String,
        coverUrl: String?,
        tracks: List<SpotifyTrack>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Playlist {
        val playlistId = musicRepository.createPlaylist(name)
        val songPairs = ArrayList<Pair<Song, String>>(tracks.size)
        val songIds = ArrayList<Long>(tracks.size)

        tracks.forEachIndexed { index, st ->
            // Deterministic negative ID outside local MediaStore positive IDs
            val uniqueKey = "${st.id}|${st.artist}|${st.title}"
            val songId = -((uniqueKey.hashCode().toLong() and 0x7FFFFFFFL) + 500_000L)
            val onlineUri = "online://spotify/${st.id}?title=${Uri.encode(st.title)}&artist=${Uri.encode(st.artist)}&dur=${st.durationMs}"

            val song = Song(
                id = songId,
                title = st.title,
                artist = st.artist,
                album = st.album,
                albumId = -(st.album.hashCode().toLong() and 0x7FFFFFFFL) - 500_000L,
                durationMs = st.durationMs,
                contentUriString = onlineUri,
                albumArtUriString = st.albumArtUrl ?: coverUrl,
                genre = "Spotify Import",
                mimeType = "audio/mp4",
                dateAdded = System.currentTimeMillis()
            )
            songPairs.add(Pair(song, st.id))
            songIds.add(songId)
            onProgress(index + 1, tracks.size)
        }

        // Batch persist online tracks into Room FIRST so playlist queries resolve them immediately!
        musicRepository.saveOnlineSongs(songPairs)
        musicRepository.addSongsToPlaylistBatch(playlistId, songIds)

        return Playlist(
            id = playlistId,
            name = name,
            createdAt = System.currentTimeMillis(),
            songCount = songIds.size,
            firstSongAlbumArt = coverUrl ?: tracks.firstNotNullOfOrNull { it.albumArtUrl }
        )
    }

    private fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("spotify:playlist:")) {
            return trimmed.substringAfter("spotify:playlist:").substringBefore("?")
        }
        if (!trimmed.contains("/")) return trimmed
        val uri = Uri.parse(trimmed)
        val path = uri.path ?: return null
        return path.substringAfterLast("/").takeIf { it.isNotBlank() }
    }

    fun disconnect() {
        val savedClientId = customClientId
        prefs.edit().clear().apply()
        if (savedClientId.isNotBlank()) {
            customClientId = savedClientId
        }
        _authState.value = SpotifyAuthState()
    }

    private suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val spDc = prefs.getString(KEY_SP_DC_COOKIE, null)

        if (!token.isNullOrEmpty() && System.currentTimeMillis() < expiresAt) {
            return@withContext token
        }

        if (!spDc.isNullOrBlank()) {
            val freshWebToken = exchangeSpDcCookieForToken(spDc)
            if (!freshWebToken.isNullOrBlank()) {
                saveTokens(freshWebToken, null, 3500L, method = "Web Session")
                return@withContext freshWebToken
            }
        }

        if (refreshToken.isNullOrEmpty() || !hasValidCustomClientId()) {
            return@withContext token
        }

        try {
            val body = FormBody.Builder()
                .add("client_id", customClientId.trim())
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
                    saveTokens(newAccessToken, newRefresh, expiresIn, method = "OAuth PKCE")
                    return@withContext newAccessToken
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyManager", "Token refresh error", e)
        }

        token
    }

    private fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        method: String
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60000L
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_AUTH_METHOD, method)
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
        private const val LIKED_SONGS_ID = "__spotify_liked_songs__"

        private const val KEY_ACCESS_TOKEN = "key_spotify_access_token"
        private const val KEY_REFRESH_TOKEN = "key_spotify_refresh_token"
        private const val KEY_EXPIRES_AT = "key_spotify_expires_at"
        private const val KEY_CODE_VERIFIER = "key_spotify_code_verifier"
        private const val KEY_CUSTOM_CLIENT_ID = "key_spotify_custom_client_id"
        private const val KEY_SP_DC_COOKIE = "key_spotify_sp_dc"
        private const val KEY_CLIENT_TOKEN = "key_spotify_client_token"
        private const val KEY_AUTH_METHOD = "key_spotify_auth_method"
        private const val KEY_USER_ID = "key_spotify_user_id"
        private const val KEY_USER_NAME = "key_spotify_user_name"
        private const val KEY_USER_AVATAR = "key_spotify_user_avatar"
    }
}
