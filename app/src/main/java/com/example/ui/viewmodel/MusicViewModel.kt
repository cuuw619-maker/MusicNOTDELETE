package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MusicApplication
import com.example.data.preferences.PreferencesManager
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Folder
import com.example.model.Genre
import com.example.model.PlaybackState
import com.example.model.Playlist
import com.example.model.SearchCategory
import com.example.model.SearchResult
import com.example.model.SleepTimerOption
import com.example.model.SleepTimerState
import com.example.model.Song
import com.example.model.SortOrder
import android.net.Uri
import com.example.lyrics.LyricsState
import com.example.online.OnlineTrack
import com.example.spotify.SpotifyPlaylist
import com.example.playback.SleepTimerManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MusicApplication
    val repository = app.musicRepository
    val controllerManager = app.musicControllerManager
    val equalizerManager = app.equalizerManager
    val preferencesManager = app.preferencesManager

    val playbackState: StateFlow<PlaybackState> = controllerManager.playbackState
    val playbackPositionMs: StateFlow<Long> = controllerManager.playbackPositionMs
    val activeStreamEngine: StateFlow<String> = controllerManager.activeStreamEngine
    val diagnosticReport = controllerManager.diagnosticReport
    val equalizerState = equalizerManager.equalizerState

    // Sleep Timer
    val sleepTimerManager = SleepTimerManager(
        onTimeExpired = {
            controllerManager.playPause()
        }
    )
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerManager.state

    // Repository flows
    val allSongs = repository.allSongs
    val sortedSongs = repository.sortedSongs
    val albums = repository.albums
    val artists = repository.artists
    val genres = repository.genres
    val folders = repository.folders
    val recentlyAdded = repository.recentlyAddedSongs
    val recentlyPlayed = repository.recentlyPlayedSongs
    val mostPlayed = repository.mostPlayedSongs
    val favoriteSongs = repository.favoriteSongs
    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isScanning = repository.isScanning
    val sortOrder = repository.sortOrder

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMode = MutableStateFlow(0) // 0: Local, 1: Online
    val searchMode: StateFlow<Int> = _searchMode.asStateFlow()

    private val _searchCategory = MutableStateFlow(SearchCategory.ALL)
    val searchCategory: StateFlow<SearchCategory> = _searchCategory.asStateFlow()

    private val _searchResult = MutableStateFlow(SearchResult())
    val searchResult: StateFlow<SearchResult> = _searchResult.asStateFlow()

    val searchHistory: StateFlow<List<String>> = repository.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var onlineSearchJob: kotlinx.coroutines.Job? = null
    private var historyCommitJob: kotlinx.coroutines.Job? = null
    private var lastQueryEditAtMs: Long = 0L
    private var lastSavedHistoryQuery: String? = null

    // Filtered songs for Library Screen
    val filteredSongs: StateFlow<List<Song>> = combine(sortedSongs, _searchQuery) { songs, query ->
        if (query.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Preferences
    val themeMode = preferencesManager.themeMode
    val dynamicColor = preferencesManager.dynamicColor
    val resumePlayback = preferencesManager.resumePlayback
    val amoledDark = preferencesManager.amoledDark
    val playbackSpeed = preferencesManager.playbackSpeed
    val pitchSemitones = preferencesManager.pitchSemitones
    val pitchCents = preferencesManager.pitchCents
    val audioEngineMode = preferencesManager.audioEngineMode
    val antiCrackleBuffer = preferencesManager.antiCrackleBuffer
    val playerSwipeDirection = preferencesManager.playerSwipeDirection
    val doubleTapSeekSeconds = preferencesManager.doubleTapSeekSeconds
    val volumeGestureEnabled = preferencesManager.volumeGestureEnabled
    val filterShortAudio = preferencesManager.filterShortAudio
    val preservePitch = preferencesManager.preservePitch
    val crossfadeEnabled = preferencesManager.crossfadeEnabled
    val crossfadeDuration = preferencesManager.crossfadeDuration
    val trackEndFadeOutEnabled = preferencesManager.trackEndFadeOutEnabled
    val trackEndFadeOutDuration = preferencesManager.trackEndFadeOutDuration
    val fadeOutOnPause = preferencesManager.fadeOutOnPause
    val fadeInOnStart = preferencesManager.fadeInOnStart
    val vinylModeEnabled = preferencesManager.vinylModeEnabled
    val spatialReverbPreset = preferencesManager.spatialReverbPreset
    val stereoWidener = preferencesManager.stereoWidener
    val audioBalance = preferencesManager.audioBalance
    val volumeNormalize = preferencesManager.volumeNormalize
    val gaplessPlayback = preferencesManager.gaplessPlayback
    val abPointA = controllerManager.abPointA
    val abPointB = controllerManager.abPointB
    val reducedMotion = preferencesManager.reducedMotion
    val hapticEnabled = preferencesManager.hapticEnabled
    val animationScale = preferencesManager.animationScale
    val performanceMode = preferencesManager.performanceMode
    val liquidGlassEnabled = preferencesManager.liquidGlassEnabled
    val liquidGlassPreset = preferencesManager.liquidGlassPreset
    val liquidGlassBlurDp = preferencesManager.liquidGlassBlurDp
    val liquidGlassRefraction = preferencesManager.liquidGlassRefraction
    val liquidGlassFrostAlpha = preferencesManager.liquidGlassFrostAlpha
    val liquidGlassHighlight = preferencesManager.liquidGlassHighlight
    val audioReactiveShadersEnabled = preferencesManager.audioReactiveShadersEnabled
    val autoDjEnabled = preferencesManager.autoDjEnabled
    val miniSpectrumMode = preferencesManager.miniSpectrumMode

    // In-memory song bookmarks (timestamp pins): songId -> list of timestamps
    private val _bookmarksState = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    val bookmarksState: StateFlow<Map<Long, List<Long>>> = _bookmarksState.asStateFlow()

    private val _isBookmarksSheetVisible = MutableStateFlow(false)
    val isBookmarksSheetVisible: StateFlow<Boolean> = _isBookmarksSheetVisible.asStateFlow()

    // Plugins
    val pluginManager = app.pluginManager
    val plugins = pluginManager.pluginsState
    val userPlugins = pluginManager.userProjectsState

    // UI state for sheets and dialogs
    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    private val _isQueueSheetVisible = MutableStateFlow(false)
    val isQueueSheetVisible: StateFlow<Boolean> = _isQueueSheetVisible.asStateFlow()

    private val _isEqualizerSheetVisible = MutableStateFlow(false)
    val isEqualizerSheetVisible: StateFlow<Boolean> = _isEqualizerSheetVisible.asStateFlow()

    private val _isSleepTimerSheetVisible = MutableStateFlow(false)
    val isSleepTimerSheetVisible: StateFlow<Boolean> = _isSleepTimerSheetVisible.asStateFlow()

    private val _isLyricsSheetVisible = MutableStateFlow(false)
    val isLyricsSheetVisible: StateFlow<Boolean> = _isLyricsSheetVisible.asStateFlow()

    private val _isAudioInspectorVisible = MutableStateFlow(false)
    val isAudioInspectorVisible: StateFlow<Boolean> = _isAudioInspectorVisible.asStateFlow()

    private val _isCarModeActive = MutableStateFlow(false)
    val isCarModeActive: StateFlow<Boolean> = _isCarModeActive.asStateFlow()

    private val _isCreatePlaylistDialogVisible = MutableStateFlow(false)
    val isCreatePlaylistDialogVisible: StateFlow<Boolean> = _isCreatePlaylistDialogVisible.asStateFlow()

    private val _songToAddToPlaylist = MutableStateFlow<Song?>(null)
    val songToAddToPlaylist: StateFlow<Song?> = _songToAddToPlaylist.asStateFlow()

    private val _isSpotifySheetVisible = MutableStateFlow(false)
    val isSpotifySheetVisible: StateFlow<Boolean> = _isSpotifySheetVisible.asStateFlow()

    // Spotify
    val spotifyManager = app.spotifyManager
    val spotifyAuthState = spotifyManager.authState

    // Multi-Source Online Streaming & Track/Source Switcher
    val youTubeStreamManager = app.youTubeStreamManager
    val onlineSourceFilter: StateFlow<String> = preferencesManager.onlineSourceFilter

    private val _onlineSearchResults = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val onlineSearchResults: StateFlow<List<OnlineTrack>> = _onlineSearchResults.asStateFlow()

    private val _isOnlineSearchLoading = MutableStateFlow(false)
    val isOnlineSearchLoading: StateFlow<Boolean> = _isOnlineSearchLoading.asStateFlow()

    private val _isSourceSwitcherVisible = MutableStateFlow(false)
    val isSourceSwitcherVisible: StateFlow<Boolean> = _isSourceSwitcherVisible.asStateFlow()

    private val _sourceSwitcherTitle = MutableStateFlow("")
    val sourceSwitcherTitle: StateFlow<String> = _sourceSwitcherTitle.asStateFlow()

    private val _sourceSwitcherQuery = MutableStateFlow("")
    val sourceSwitcherQuery: StateFlow<String> = _sourceSwitcherQuery.asStateFlow()

    private val _alternativeSourceFilter = MutableStateFlow("all")
    val alternativeSourceFilter: StateFlow<String> = _alternativeSourceFilter.asStateFlow()

    private val _alternativeTracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val alternativeTracks: StateFlow<List<OnlineTrack>> = _alternativeTracks.asStateFlow()

    private val _isAlternativeLoading = MutableStateFlow(false)
    val isAlternativeLoading: StateFlow<Boolean> = _isAlternativeLoading.asStateFlow()

    val selectedDeliveryEngine: StateFlow<String> = preferencesManager.preferredDeliveryEngine

    private val _sourceHealthStatuses = MutableStateFlow<List<com.example.online.SourceHealthStatus>>(emptyList())
    val sourceHealthStatuses: StateFlow<List<com.example.online.SourceHealthStatus>> = _sourceHealthStatuses.asStateFlow()

    private val _isCheckingSourceHealth = MutableStateFlow(false)
    val isCheckingSourceHealth: StateFlow<Boolean> = _isCheckingSourceHealth.asStateFlow()

    private var sourceSwitcherReplaceCurrent = true
    private var alternativeSearchJob: kotlinx.coroutines.Job? = null

    // Lyrics + per-song timing calibration offset
    val lyricsManager = app.lyricsManager
    private val _lyricsState = MutableStateFlow(LyricsState())
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private val _lyricsOffsetMs = MutableStateFlow(0L)
    val lyricsOffsetMs: StateFlow<Long> = _lyricsOffsetMs.asStateFlow()

    // Navigation drill down (detail screens inside Library/Playlists)
    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _selectedFolder = MutableStateFlow<Folder?>(null)
    val selectedFolder: StateFlow<Folder?> = _selectedFolder.asStateFlow()

    private val _selectedGenre = MutableStateFlow<Genre?>(null)
    val selectedGenre: StateFlow<Genre?> = _selectedGenre.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    private val _playlistSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistSongs: StateFlow<List<Song>> = _playlistSongs.asStateFlow()

    init {
        // Instant local search filtering WITHOUT polluting search history on every keystroke
        viewModelScope.launch {
            combine(_searchQuery.debounce(250).distinctUntilChanged(), _searchCategory) { query, category ->
                query to category
            }.collect { (query, category) ->
                if (query.isNotBlank()) {
                    _searchResult.value = repository.search(query, category)
                } else {
                    _searchResult.value = SearchResult()
                }
            }
        }

        // Debounced online search when in Online mode (min 2 chars to avoid spamming mirrors)
        viewModelScope.launch {
            combine(_searchQuery.debounce(420).distinctUntilChanged(), _searchMode) { query, mode ->
                query to mode
            }.collect { (query, mode) ->
                if (mode == 1) {
                    if (query.trim().length >= 2) {
                        searchOnline(query, immediate = true)
                    } else if (query.isBlank()) {
                        onlineSearchJob?.cancel()
                        _onlineSearchResults.value = emptyList()
                        _isOnlineSearchLoading.value = false
                    }
                }
            }
        }

        // Restore last played song into playback state on launch so Mini Player is immediately visible
        viewModelScope.launch {
            allSongs.collect { songs ->
                if (songs.isNotEmpty() && controllerManager.playbackState.value.currentSong == null) {
                    val lastId = preferencesManager.getLastSongId()
                    val lastPos = preferencesManager.getLastPositionMs()
                    val targetSong = songs.find { it.id == lastId } ?: songs.firstOrNull()
                    if (targetSong != null) {
                        controllerManager.restoreLastPlayedSong(targetSong, songs, lastPos)
                    }
                }
            }
        }
    }

    private fun scheduleHistoryCommitCheck() {
        historyCommitJob?.cancel()
        val capturedQuery = _searchQuery.value.trim()
        if (capturedQuery.length < 2) return

        historyCommitJob = viewModelScope.launch {
            val elapsed = System.currentTimeMillis() - lastQueryEditAtMs
            val remaining = (2000L - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) {
                kotlinx.coroutines.delay(remaining)
            }
            // Verify query didn't change while waiting 2 seconds and search is not mid-flight
            if (_searchQuery.value.trim() != capturedQuery) return@launch
            if (_searchMode.value == 1 && _isOnlineSearchLoading.value) {
                // Wait briefly until loading settles (up to 4s) before checking results
                var waited = 0L
                while (_isOnlineSearchLoading.value && waited < 4000L) {
                    kotlinx.coroutines.delay(150L)
                    waited += 150L
                    if (_searchQuery.value.trim() != capturedQuery) return@launch
                }
            }
            commitSearchHistoryIfValid(capturedQuery)
        }
    }

    private suspend fun commitSearchHistoryIfValid(trimmedQuery: String) {
        if (trimmedQuery.length < 2) return
        if (trimmedQuery == lastSavedHistoryQuery) return
        val hasResults = if (_searchMode.value == 1) {
            _onlineSearchResults.value.isNotEmpty()
        } else {
            !_searchResult.value.isEmpty
        }
        if (hasResults) {
            lastSavedHistoryQuery = trimmedQuery
            repository.addSearchHistory(trimmedQuery)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        lastQueryEditAtMs = System.currentTimeMillis()
        if (query.isBlank()) {
            historyCommitJob?.cancel()
            onlineSearchJob?.cancel()
            _onlineSearchResults.value = emptyList()
            _isOnlineSearchLoading.value = false
        } else {
            scheduleHistoryCommitCheck()
        }
    }

    fun setSearchQuery(query: String) {
        onSearchQueryChanged(query)
    }

    fun setSearchMode(mode: Int) {
        _searchMode.value = mode
        val q = _searchQuery.value.trim()
        if (mode == 1 && q.length >= 2) {
            searchOnline(q, immediate = true)
        }
        if (q.length >= 2) {
            scheduleHistoryCommitCheck()
        }
    }

    fun submitSearchQuery() {
        val q = _searchQuery.value.trim()
        if (q.isBlank()) return
        if (_searchMode.value == 1) {
            searchOnline(q, immediate = true)
        }
        viewModelScope.launch {
            commitSearchHistoryIfValid(q)
        }
    }

    fun commitHistoryOnResultSelected() {
        val q = _searchQuery.value.trim()
        if (q.length < 2) return
        viewModelScope.launch {
            if (q != lastSavedHistoryQuery) {
                lastSavedHistoryQuery = q
                repository.addSearchHistory(q)
            }
        }
    }

    fun setSearchCategory(category: SearchCategory) {
        _searchCategory.value = category
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch {
            repository.deleteSearchHistory(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _isNowPlayingExpanded.value = expanded
    }

    fun setQueueSheetVisible(visible: Boolean) {
        _isQueueSheetVisible.value = visible
    }

    fun setEqualizerSheetVisible(visible: Boolean) {
        _isEqualizerSheetVisible.value = visible
    }

    fun setSleepTimerSheetVisible(visible: Boolean) {
        _isSleepTimerSheetVisible.value = visible
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerManager.setTimer(option)
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancel()
    }

    fun setCreatePlaylistDialogVisible(visible: Boolean) {
        _isCreatePlaylistDialogVisible.value = visible
    }

    fun setSongToAddToPlaylist(song: Song?) {
        _songToAddToPlaylist.value = song
    }

    fun selectAlbum(album: Album?) {
        _selectedAlbum.value = album
    }

    fun selectArtist(artist: Artist?) {
        _selectedArtist.value = artist
    }

    fun selectFolder(folder: Folder?) {
        _selectedFolder.value = folder
    }

    fun selectGenre(genre: Genre?) {
        _selectedGenre.value = genre
    }

    fun selectPlaylist(playlist: Playlist?) {
        _selectedPlaylist.value = playlist
        if (playlist != null) {
            viewModelScope.launch {
                repository.getSongsForPlaylist(playlist.id).collect { songs ->
                    _playlistSongs.value = songs
                }
            }
        } else {
            _playlistSongs.value = emptyList()
        }
    }

    fun setSortOrder(order: SortOrder) = repository.setSortOrder(order)
    fun scanLibrary() = repository.scanLibrary()

    fun deleteSong(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
        }
    }

    // Playback actions
    fun playSong(song: Song, queue: List<Song> = allSongs.value) {
        controllerManager.playSong(song, queue)
    }

    fun playQueue(queue: List<Song>, startIndex: Int = 0) {
        controllerManager.playQueue(queue, startIndex)
    }

    fun playPause() = controllerManager.playPause()
    fun seekTo(posMs: Long) = controllerManager.seekTo(posMs)
    fun skipToNext() = controllerManager.skipToNext()
    fun skipToPrevious() = controllerManager.skipToPrevious()
    fun rewind10() = controllerManager.rewind10()
    fun forward10() = controllerManager.forward10()
    fun toggleShuffle() = controllerManager.toggleShuffle()
    fun cycleRepeatMode() = controllerManager.cycleRepeatMode()

    fun playQueueIndex(index: Int) = controllerManager.playQueueIndex(index)
    fun removeFromQueue(index: Int) = controllerManager.removeFromQueue(index)
    fun reorderQueue(fromIndex: Int, toIndex: Int) = controllerManager.reorderQueue(fromIndex, toIndex)
    fun clearQueue() = controllerManager.clearQueue()
    fun addToQueue(song: Song) = controllerManager.addToQueue(song)

    // Favorites & Playlists
    fun toggleFavorite(songId: Long) {
        viewModelScope.launch {
            repository.toggleFavorite(songId)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            repository.createPlaylist(name)
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
            if (_selectedPlaylist.value?.id == playlistId) {
                _selectedPlaylist.value = _selectedPlaylist.value?.copy(name = newName)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_selectedPlaylist.value?.id == playlistId) {
                _selectedPlaylist.value = null
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            // If song came from YouTube Music Search / online queue, persist in online_songs first
            val candidate = _songToAddToPlaylist.value?.takeIf { it.id == songId }
                ?: playbackState.value.queue.find { it.id == songId }
                ?: playbackState.value.currentSong?.takeIf { it.id == songId }
            if (candidate != null && (candidate.id < 0 || candidate.contentUriString.startsWith("http") || candidate.contentUriString.startsWith("online://"))) {
                repository.saveOnlineSong(candidate)
            }
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    // Preferences & Equalizer
    fun setThemeMode(mode: PreferencesManager.ThemeMode) = preferencesManager.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = preferencesManager.setDynamicColor(enabled)
    fun setResumePlayback(enabled: Boolean) = preferencesManager.setResumePlayback(enabled)
    fun setAmoledDark(enabled: Boolean) = preferencesManager.setAmoledDark(enabled)
    fun setReducedMotion(enabled: Boolean) = preferencesManager.setReducedMotion(enabled)
    fun setHapticEnabled(enabled: Boolean) = preferencesManager.setHapticEnabled(enabled)
    fun setAnimationScale(scale: Float) = preferencesManager.setAnimationScale(scale)
    fun setPerformanceMode(mode: PreferencesManager.PerformanceMode) = preferencesManager.setPerformanceMode(mode)
    fun setLiquidGlassEnabled(enabled: Boolean) = preferencesManager.setLiquidGlassEnabled(enabled)
    fun setLiquidGlassPreset(preset: String) = preferencesManager.setLiquidGlassPreset(preset)
    fun applyLiquidGlassPreset(preset: String) = preferencesManager.applyLiquidGlassPreset(preset)
    fun setLiquidGlassBlurDp(blurDp: Float) = preferencesManager.setLiquidGlassBlurDp(blurDp)
    fun setLiquidGlassRefraction(refraction: Float) = preferencesManager.setLiquidGlassRefraction(refraction)
    fun setLiquidGlassFrostAlpha(alpha: Float) = preferencesManager.setLiquidGlassFrostAlpha(alpha)
    fun setLiquidGlassHighlight(highlight: Float) = preferencesManager.setLiquidGlassHighlight(highlight)
    fun setAudioReactiveShadersEnabled(enabled: Boolean) = preferencesManager.setAudioReactiveShadersEnabled(enabled)
    fun setAutoDjEnabled(enabled: Boolean) = preferencesManager.setAutoDjEnabled(enabled)
    fun setMiniSpectrumMode(mode: String) = preferencesManager.setMiniSpectrumMode(mode)
    fun cycleMiniSpectrumMode() {
        val next = when (miniSpectrumMode.value) {
            "BARS" -> "WAVE"
            "WAVE" -> "OFF"
            else -> "BARS"
        }
        preferencesManager.setMiniSpectrumMode(next)
    }

    fun setPlaybackSpeed(speed: Float) = controllerManager.setPlaybackSpeed(speed)
    fun setPitchSemitones(semitones: Int) = controllerManager.setPitchSemitones(semitones)
    fun setPitchCents(cents: Int) = controllerManager.setPitchCents(cents)
    fun setAudioEngineMode(mode: String) = equalizerManager.setAudioEngineMode(mode)
    fun setAntiCrackleBuffer(enabled: Boolean) = preferencesManager.setAntiCrackleBuffer(enabled)
    fun setPlayerSwipeDirection(dir: String) = preferencesManager.setPlayerSwipeDirection(dir)
    fun setDoubleTapSeekSeconds(seconds: Int) = preferencesManager.setDoubleTapSeekSeconds(seconds)
    fun setVolumeGestureEnabled(enabled: Boolean) = preferencesManager.setVolumeGestureEnabled(enabled)
    fun setFilterShortAudio(enabled: Boolean) = preferencesManager.setFilterShortAudio(enabled)
    fun setPreservePitch(preserve: Boolean) = controllerManager.setPreservePitch(preserve)
    fun setGaplessPlayback(enabled: Boolean) = controllerManager.setGaplessPlayback(enabled)
    fun setVolumeNormalize(enabled: Boolean) = equalizerManager.setVolumeNormalize(enabled)
    fun setCrossfadeEnabled(enabled: Boolean) = preferencesManager.setCrossfadeEnabled(enabled)
    fun setCrossfadeDuration(seconds: Float) = preferencesManager.setCrossfadeDuration(seconds)
    fun setTrackEndFadeOutEnabled(enabled: Boolean) = preferencesManager.setTrackEndFadeOutEnabled(enabled)
    fun setTrackEndFadeOutDuration(seconds: Float) = preferencesManager.setTrackEndFadeOutDuration(seconds)
    fun setFadeOutOnPause(enabled: Boolean) = preferencesManager.setFadeOutOnPause(enabled)
    fun setFadeInOnStart(enabled: Boolean) = preferencesManager.setFadeInOnStart(enabled)
    fun setVinylModeEnabled(enabled: Boolean) = preferencesManager.setVinylModeEnabled(enabled)

    fun setABPointA(ms: Long?) = controllerManager.setABPointA(ms)
    fun setABPointB(ms: Long?) = controllerManager.setABPointB(ms)
    fun clearABRepeat() = controllerManager.clearABRepeat()

    fun setLyricsSheetVisible(visible: Boolean) { _isLyricsSheetVisible.value = visible }
    fun setAudioInspectorVisible(visible: Boolean) { _isAudioInspectorVisible.value = visible }
    fun setCarModeActive(active: Boolean) { _isCarModeActive.value = active }

    fun setBookmarksSheetVisible(visible: Boolean) { _isBookmarksSheetVisible.value = visible }

    fun addBookmark(songId: Long, positionMs: Long) {
        val current = _bookmarksState.value.toMutableMap()
        val list = (current[songId] ?: emptyList()).toMutableList()
        if (!list.contains(positionMs)) {
            list.add(positionMs)
            list.sort()
            current[songId] = list
            _bookmarksState.value = current
        }
    }

    fun removeBookmark(songId: Long, positionMs: Long) {
        val current = _bookmarksState.value.toMutableMap()
        val list = (current[songId] ?: emptyList()).toMutableList()
        list.remove(positionMs)
        current[songId] = list
        _bookmarksState.value = current
    }

    fun setSpatialReverbPreset(preset: String) = equalizerManager.setSpatialReverbPreset(preset)
    fun setStereoWidener(percent: Int) = equalizerManager.setStereoWidener(percent)
    fun setAudioBalance(balance: Float) = equalizerManager.setAudioBalance(balance)

    fun setPluginEnabled(pluginId: String, enabled: Boolean) = pluginManager.setPluginEnabled(pluginId, enabled)
    fun saveUserPlugin(project: com.example.plugin.UserPluginProject) = pluginManager.saveUserPlugin(project)
    fun deleteUserPlugin(pluginId: String) = pluginManager.deleteUserPlugin(pluginId)

    fun setEqualizerEnabled(enabled: Boolean) = equalizerManager.setEnabled(enabled)
    fun setEqualizerBandLevel(band: Short, level: Short) = equalizerManager.setBandLevel(band, level)
    fun setBassBoost(level: Short) = equalizerManager.setBassBoost(level)
    fun setVirtualizer(strength: Short) = equalizerManager.setVirtualizer(strength)
    fun setLoudnessEnhancer(targetGainmB: Int) = equalizerManager.setLoudnessEnhancer(targetGainmB)
    fun setEqualizerPreAmpDb(db: Float) = equalizerManager.setPreAmpDb(db)
    fun setEqualizerLimiterEnabled(enabled: Boolean) = equalizerManager.setLimiterEnabled(enabled)
    fun resetEqualizerBands() = equalizerManager.resetAllBands()
    fun useEqualizerPreset(presetIndex: Short) = equalizerManager.usePreset(presetIndex)
    fun applyEqualizerPreset(presetName: String) = equalizerManager.applyPreset(presetName)

    // ==========================================
    // SPOTIFY CONNECT & PLAYLIST SYNC
    // ==========================================
    fun setSpotifySheetVisible(visible: Boolean) {
        _isSpotifySheetVisible.value = visible
        if (visible && spotifyAuthState.value.isConnected) {
            fetchSpotifyPlaylists()
        }
    }

    fun handleSpotifyRedirect(uri: Uri) {
        viewModelScope.launch {
            val success = spotifyManager.handleRedirectUri(uri)
            if (success) {
                fetchSpotifyPlaylists()
            }
        }
    }

    fun connectSpotifyWithWebToken(rawInput: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = spotifyManager.connectWithWebTokenOrCookie(rawInput)
            onDone(ok)
        }
    }

    fun connectSpotifyWithWebSession(
        accessToken: String?,
        clientToken: String?,
        spDcCookie: String?,
        onDone: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val ok = spotifyManager.connectWithCapturedWebSession(accessToken, clientToken, spDcCookie)
            onDone(ok)
        }
    }

    fun fetchSpotifyPlaylists() {
        viewModelScope.launch {
            spotifyManager.fetchUserPlaylists()
        }
    }

    fun importSpotifyPlaylist(playlist: SpotifyPlaylist, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = spotifyManager.importPlaylist(playlist)
            onDone(res != null)
        }
    }

    fun importSpotifyPlaylistByUrl(urlOrId: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = spotifyManager.importPublicPlaylistByUrl(urlOrId)
            onDone(res != null)
        }
    }

    fun disconnectSpotify() {
        spotifyManager.disconnect()
    }

    // ==========================================
    // ONLINE STREAMING SEARCH, MULTI-SOURCE & VERSION SWITCHER
    // ==========================================
    fun setOnlineSourceFilter(sourceId: String) {
        if (onlineSourceFilter.value == sourceId) return
        preferencesManager.setOnlineSourceFilter(sourceId)
        val q = _searchQuery.value.trim()
        if (_searchMode.value == 1 && q.length >= 2) {
            searchOnline(q, immediate = true)
        }
    }

    fun searchOnline(query: String, immediate: Boolean = false) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            onlineSearchJob?.cancel()
            _onlineSearchResults.value = emptyList()
            _isOnlineSearchLoading.value = false
            return
        }
        val activeFilter = onlineSourceFilter.value
        onlineSearchJob?.cancel()
        onlineSearchJob = viewModelScope.launch {
            if (!immediate) {
                kotlinx.coroutines.delay(280L)
            }
            _isOnlineSearchLoading.value = true
            try {
                val results = youTubeStreamManager.search(trimmed, activeFilter)
                if (_searchQuery.value.trim() == trimmed && onlineSourceFilter.value == activeFilter) {
                    _onlineSearchResults.value = results
                    _isOnlineSearchLoading.value = false
                    if (results.isNotEmpty()) {
                        val imageLoader = coil.Coil.imageLoader(app)
                        results.take(6).forEach { track ->
                            val thumb = track.thumbnailUrl
                            if (!thumb.isNullOrBlank()) {
                                runCatching {
                                    imageLoader.enqueue(
                                        coil.request.ImageRequest.Builder(app)
                                            .data(thumb)
                                            .size(200)
                                            .build()
                                    )
                                }
                            }
                        }
                        scheduleHistoryCommitCheck()
                    }
                } else {
                    _isOnlineSearchLoading.value = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("MusicViewModel", "Online search failed", t)
                _isOnlineSearchLoading.value = false
            }
        }
    }

    fun playOnlineTrack(track: OnlineTrack) {
        commitHistoryOnResultSelected()
        controllerManager.dismissDiagnosticReport()
        val queue = _onlineSearchResults.value.map { it.toSong() }
        val targetSong = track.toSong()
        val target = queue.find { it.id == targetSong.id } ?: targetSong
        controllerManager.playSong(target, if (queue.isNotEmpty()) queue else listOf(target))
    }

    fun openSourceSwitcherForCurrent() {
        val song = playbackState.value.currentSong ?: return
        sourceSwitcherReplaceCurrent = true
        val initialQ = buildString {
            if (song.artist.isNotBlank() && !song.artist.equals("Unknown", true)) {
                append(song.artist).append(' ')
            }
            append(song.title)
        }.trim()
        _sourceSwitcherTitle.value = song.title
        _sourceSwitcherQuery.value = initialQ
        _alternativeSourceFilter.value = "all"
        _isSourceSwitcherVisible.value = true
        loadAlternativeCandidates()
    }

    fun openSourceSwitcherForOnlineTrack(track: OnlineTrack) {
        sourceSwitcherReplaceCurrent = false
        val initialQ = "${track.artist} ${track.title}".trim()
        _sourceSwitcherTitle.value = track.title
        _sourceSwitcherQuery.value = initialQ
        _alternativeSourceFilter.value = "all"
        _isSourceSwitcherVisible.value = true
        loadAlternativeCandidates()
    }

    fun closeSourceSwitcher() {
        _isSourceSwitcherVisible.value = false
    }

    fun setAlternativeQuery(query: String) {
        _sourceSwitcherQuery.value = query
    }

    fun setAlternativeSourceFilter(sourceId: String) {
        _alternativeSourceFilter.value = sourceId
        loadAlternativeCandidates()
    }

    fun loadAlternativeCandidates() {
        val q = _sourceSwitcherQuery.value.trim()
        val filter = _alternativeSourceFilter.value
        if (q.isBlank()) {
            _alternativeTracks.value = emptyList()
            _isAlternativeLoading.value = false
            return
        }
        alternativeSearchJob?.cancel()
        alternativeSearchJob = viewModelScope.launch {
            _isAlternativeLoading.value = true
            try {
                val tracks = youTubeStreamManager.search(q, filter)
                _alternativeTracks.value = tracks
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("MusicViewModel", "Alternative search failed", t)
                _alternativeTracks.value = emptyList()
            } finally {
                _isAlternativeLoading.value = false
            }
        }
    }

    fun selectAlternativeTrack(track: OnlineTrack) {
        _isSourceSwitcherVisible.value = false
        if (sourceSwitcherReplaceCurrent && playbackState.value.currentSong != null) {
            controllerManager.replaceCurrentTrackWithOnlineCandidate(track)
        } else {
            playOnlineTrack(track)
        }
    }

    fun switchDeliveryEngine(engineId: String) {
        preferencesManager.setPreferredDeliveryEngine(engineId)
        val current = playbackState.value.currentSong
        val isOnlineCurrent = current != null && (
            current.contentUriString.startsWith("online://") ||
                current.contentUriString.startsWith("http") ||
                controllerManager.getYouTubeVideoIdForSong(current.id) != null
            )
        if (isOnlineCurrent) {
            controllerManager.switchDeliveryEngineForCurrent(engineId)
        }
    }

    fun retryPlayback() {
        controllerManager.dismissDiagnosticReport()
        controllerManager.retryCurrentTrack()
    }

    fun clearPlaybackError() {
        controllerManager.clearError()
    }

    fun dismissDiagnosticReport() {
        controllerManager.dismissDiagnosticReport()
    }

    fun runSourceHealthCheck() {
        if (_isCheckingSourceHealth.value) return
        viewModelScope.launch {
            _isCheckingSourceHealth.value = true
            _sourceHealthStatuses.value = youTubeStreamManager.testAllSourcesHealth()
            _isCheckingSourceHealth.value = false
        }
    }

    // ==========================================
    // DYNAMIC MULTI-SOURCE & BETTERLYRICS ENGINE
    // ==========================================
    fun loadLyrics(song: Song) {
        _lyricsOffsetMs.value = preferencesManager.getLyricsOffset(song.id)
        val ytVideoId = controllerManager.getYouTubeVideoIdForSong(song.id)
        viewModelScope.launch {
            _lyricsState.value = com.example.lyrics.LyricsState(
                title = song.title,
                artist = song.artist,
                isLoading = true
            )
            val state = lyricsManager.getLyrics(
                title = song.title,
                artist = song.artist,
                durationSeconds = song.durationMs / 1000L,
                youtubeVideoId = ytVideoId,
                ytStreamManager = youTubeStreamManager
            )
            _lyricsState.value = state
        }
    }

    fun nudgeLyricsOffset(songId: Long, deltaMs: Long) {
        val next = (_lyricsOffsetMs.value + deltaMs).coerceIn(-5000L, 5000L)
        _lyricsOffsetMs.value = next
        preferencesManager.setLyricsOffset(songId, next)
    }

    fun resetLyricsOffset(songId: Long) {
        _lyricsOffsetMs.value = 0L
        preferencesManager.setLyricsOffset(songId, 0L)
    }

    fun searchLyricsManually(query: String) {
        viewModelScope.launch {
            _lyricsState.value = com.example.lyrics.LyricsState(
                title = query,
                artist = "",
                isLoading = true
            )
            val state = lyricsManager.getLyrics(
                title = query,
                artist = "",
                durationSeconds = 0L,
                youtubeVideoId = null,
                ytStreamManager = youTubeStreamManager
            )
            _lyricsState.value = state
        }
    }
}
