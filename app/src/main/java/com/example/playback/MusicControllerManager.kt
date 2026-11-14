package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.preferences.PreferencesManager
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.Song
import com.example.plugin.PluginManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.random.Random

class MusicControllerManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    val equalizerManager: EqualizerManager,
    private val onSongPlayed: (Long) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    var pluginManager: PluginManager? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _abPointA = MutableStateFlow<Long?>(null)
    val abPointA: StateFlow<Long?> = _abPointA.asStateFlow()

    private val _abPointB = MutableStateFlow<Long?>(null)
    val abPointB: StateFlow<Long?> = _abPointB.asStateFlow()

    private var originalQueue = mutableListOf<Song>()
    private var currentQueueSongs = mutableListOf<Song>()
    private var positionUpdateJob: Job? = null
    private var volumeFadeJob: Job? = null
    private var isCrossfading = false
    @Volatile
    private var isResolvingOnline = false
    private val onlineSourceBySongId = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private var pendingRestore: Triple<Song, List<Song>, Long>? = null
    var playbackService: MusicPlaybackService? = null
        private set

    fun attachService(service: MusicPlaybackService) {
        playbackService = service
    }

    fun detachService() {
        playbackService = null
    }

    private fun getService(): MusicPlaybackService? =
        playbackService ?: MusicPlaybackService.instance

    init {
        initController()
    }

    private fun initController() {
        try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, MusicPlaybackService::class.java)
            )
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    setupPlayerListener()
                    applyPlaybackParameters()
                    val pending = pendingRestore
                    if (pending != null) {
                        pendingRestore = null
                        restoreLastPlayedSong(pending.first, pending.second, pending.third)
                    }
                    updatePlaybackState()
                } catch (e: Exception) {
                    Log.e("MusicControllerManager", "Failed to connect to MediaController", e)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Throwable) {
            Log.e("MusicControllerManager", "Failed to initialize MediaController", e)
        }
    }

    private fun setupPlayerListener() {
        val controller = mediaController ?: return
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
                if (isPlaying) {
                    startPositionUpdates()
                    pluginManager?.dispatchPlaybackStarted()
                } else {
                    stopPositionUpdates()
                    pluginManager?.dispatchPlaybackPaused()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                updatePlaybackState()
                if (state == Player.STATE_ENDED) {
                    stopPositionUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackState()
                val currentSong = _playbackState.value.currentSong
                if (currentSong != null) {
                    onSongPlayed(currentSong.id)
                    preferencesManager.setLastPlayedSong(currentSong.id, 0L)
                    pluginManager?.dispatchTrackChanged(currentSong)
                    val idx = controller.currentMediaItemIndex
                    if (currentSong.contentUriString.startsWith("online://")) {
                        resolveAndPlayIndex(idx, currentSong)
                    } else {
                        preresolveUpcomingQueue(idx)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updatePlaybackState()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePlaybackState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("MusicControllerManager", "Player error: ${error.message}", error)
                val cur = _playbackState.value.currentSong
                val idx = controller.currentMediaItemIndex
                if (cur != null && (cur.contentUriString.startsWith("online://") || cur.id < 0L || onlineSourceBySongId.containsKey(cur.id))) {
                    recoverOnlineStreamError(idx, cur)
                    return
                }
                isResolvingOnline = false
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Ошибка воспроизведения: ${error.localizedMessage ?: "Не удалось воспроизвести трек"}"
                )
            }

            @OptIn(UnstableApi::class)
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    equalizerManager.bindAudioSession(audioSessionId)
                }
            }
        })

        val savedShuffle = preferencesManager.shuffleEnabled.value
        controller.shuffleModeEnabled = savedShuffle

        val savedRepeat = when (preferencesManager.repeatMode.value) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = savedRepeat
        controller.volume = preferencesManager.playerVolume.value
    }

    fun applyPlaybackParameters() {
        val controller = mediaController ?: return
        val speed = preferencesManager.playbackSpeed.value.coerceIn(0.5f, 2.0f)
        val semitones = preferencesManager.pitchSemitones.value.coerceIn(-12, 12)
        val cents = preferencesManager.pitchCents.value.coerceIn(-50, 50)

        // Studio DSP pitch calculation: 2^((semitones + cents/100) / 12)
        // With semitones = 0 and cents = 0, pitchMultiplier is exactly 1.0f:
        // High quality time-stretching with studio fidelity and zero robotic crackling
        val totalSemitones = semitones + (cents / 100.0f)
        val pitchMultiplier = 2.0f.pow(totalSemitones / 12.0f)

        try {
            controller.playbackParameters = PlaybackParameters(speed, pitchMultiplier)
        } catch (e: Exception) {
            Log.e("MusicControllerManager", "Failed to apply playback parameters", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        preferencesManager.setPlaybackSpeed(clamped)
        applyPlaybackParameters()
    }

    fun setPitchSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(-12, 12)
        preferencesManager.setPitchSemitones(clamped)
        applyPlaybackParameters()
    }

    fun setPitchCents(cents: Int) {
        val clamped = cents.coerceIn(-50, 50)
        preferencesManager.setPitchCents(clamped)
        applyPlaybackParameters()
    }

    fun setPreservePitch(preserve: Boolean) {
        preferencesManager.setPreservePitch(preserve)
        applyPlaybackParameters()
    }

    private fun updatePlaybackState() {
        val controller = mediaController ?: return
        val currentMediaItem = controller.currentMediaItem
        val currentSong = currentMediaItem?.let { item ->
            val id = item.mediaId.toLongOrNull() ?: -1L
            currentQueueSongs.find { it.id == id } ?: songFromMediaItem(item)
        } ?: _playbackState.value.currentSong

        val repeat = when (controller.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        val duration = if (controller.duration > 0) controller.duration else (currentSong?.durationMs ?: 0L)

        val pos = controller.currentPosition.coerceAtLeast(0L)
        _playbackPositionMs.value = pos

        val keepError = if (controller.playbackState == Player.STATE_IDLE && !controller.isPlaying && !isResolvingOnline) {
            _playbackState.value.errorMessage
        } else {
            null
        }

        val newState = _playbackState.value.copy(
            currentSong = currentSong,
            isPlaying = controller.isPlaying,
            isBuffering = isResolvingOnline || controller.playbackState == Player.STATE_BUFFERING,
            currentPositionMs = pos,
            durationMs = duration,
            bufferedPositionMs = controller.bufferedPosition,
            shuffleModeEnabled = controller.shuffleModeEnabled,
            repeatMode = repeat,
            queue = currentQueueSongs.toList(),
            currentQueueIndex = controller.currentMediaItemIndex,
            errorMessage = keepError
        )
        _playbackState.value = newState
        pluginManager?.dispatchPlaybackStateChanged(newState)
    }

    fun clearError() {
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
    }

    fun retryCurrentTrack() {
        val current = _playbackState.value.currentSong ?: return
        val sourceUri = onlineSourceBySongId[current.id] ?: current.contentUriString
        val retrySong = if (sourceUri.startsWith("online://")) current.copy(contentUriString = sourceUri) else current
        val queue = currentQueueSongs.toList().ifEmpty { listOf(retrySong) }
        playSong(retrySong, queue)
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    _playbackPositionMs.value = pos

                    // Throttle full PlaybackState emission (~160ms) to cut unnecessary UI recompositions
                    if (tickCount % 4 == 0) {
                        val dur = if (controller.duration > 0) controller.duration else (_playbackState.value.currentSong?.durationMs ?: 0L)
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = pos,
                            durationMs = dur,
                            bufferedPositionMs = controller.bufferedPosition
                        )
                    }
                    tickCount++

                    val dur = if (controller.duration > 0) controller.duration else (_playbackState.value.currentSong?.durationMs ?: 0L)

                    // A-B Repeat Loop Check
                    val pA = _abPointA.value
                    val pB = _abPointB.value
                    if (pA != null && pB != null && pB > pA && pos >= pB) {
                        controller.seekTo(pA)
                    }

                    // Auto-crossfade nearing track end
                    val crossfadeEnabled = preferencesManager.crossfadeEnabled.value
                    val crossfadeDurationSec = preferencesManager.crossfadeDuration.value
                    val crossfadeMs = (crossfadeDurationSec * 1000).toLong()

                    if (crossfadeEnabled && crossfadeMs > 0 && dur > crossfadeMs * 2) {
                        val remaining = dur - pos
                        if (remaining in 1..(crossfadeMs + 100L) && !isCrossfading) {
                            val nextIndex = controller.nextMediaItemIndex
                            val targetIdx = if (nextIndex != C.INDEX_UNSET) {
                                nextIndex
                            } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
                                0
                            } else {
                                C.INDEX_UNSET
                            }

                            if (targetIdx != C.INDEX_UNSET) {
                                isCrossfading = true
                                val service = getService()
                                if (service != null) {
                                    service.crossfadeTo(targetIdx, crossfadeMs)
                                } else {
                                    performCrossfadeNext(crossfadeMs)
                                }
                                scope.launch {
                                    delay(crossfadeMs + 200L)
                                    isCrossfading = false
                                }
                            }
                        }
                    }

                    // Track End Fade Out Check (smooth fade to silence at track completion)
                    val trackEndFadeEnabled = preferencesManager.trackEndFadeOutEnabled.value
                    val trackEndFadeDurationSec = preferencesManager.trackEndFadeOutDuration.value
                    val trackEndFadeMs = (trackEndFadeDurationSec * 1000).toLong()
                    if (trackEndFadeEnabled && !isCrossfading && dur > trackEndFadeMs * 2) {
                        val remaining = dur - pos
                        val nextIdx = controller.nextMediaItemIndex
                        val isLastTrack = nextIdx == C.INDEX_UNSET && controller.repeatMode != Player.REPEAT_MODE_ALL
                        if (isLastTrack || !crossfadeEnabled) {
                            if (remaining in 1..trackEndFadeMs) {
                                val fraction = (remaining.toFloat() / trackEndFadeMs.toFloat()).coerceIn(0f, 1f)
                                val baseVol = preferencesManager.playerVolume.value
                                controller.volume = fraction * baseVol
                            }
                        }
                    }
                }
                delay(40)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun performCrossfadeNext(durationMs: Long) {
        val controller = mediaController ?: return
        val nextIdx = controller.nextMediaItemIndex
        val service = getService()
        if (nextIdx != C.INDEX_UNSET && service != null) {
            service.crossfadeTo(nextIdx, durationMs)
            return
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
        isCrossfading = false
    }

    private suspend fun fadeVolume(from: Float, to: Float, durationMs: Long) {
        val controller = mediaController ?: return
        val steps = 15
        val stepDelay = (durationMs / steps).coerceAtLeast(10L)
        val delta = (to - from) / steps

        for (i in 0..steps) {
            val volume = (from + delta * i).coerceIn(0.0f, 1.0f)
            controller.volume = volume
            delay(stepDelay)
        }
        controller.volume = to
    }

    // Playback control APIs
    fun restoreLastPlayedSong(song: Song, queue: List<Song> = listOf(song), positionMs: Long = 0L) {
        val startIndex = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val newState = _playbackState.value.copy(
            currentSong = song,
            isPlaying = false,
            currentPositionMs = positionMs.coerceAtLeast(0L),
            durationMs = song.durationMs,
            queue = queue,
            currentQueueIndex = startIndex
        )
        _playbackState.value = newState

        val controller = mediaController
        if (controller == null) {
            pendingRestore = Triple(song, queue, positionMs)
            return
        }
        if (controller.currentMediaItem != null) return
        originalQueue = queue.toMutableList()
        currentQueueSongs.clear()
        currentQueueSongs.addAll(queue)

        val mediaItems = queue.map { songToMediaItem(it) }

        controller.setMediaItems(mediaItems, startIndex, positionMs.coerceAtLeast(0L))
        controller.prepare()
        pluginManager?.dispatchQueueChanged(currentQueueSongs)
    }

    private var resolveJob: Job? = null
    private var preResolveJob: Job? = null
    private val onlineRetryCounts = mutableMapOf<Long, Int>()

    suspend fun resolveOnlineUriIfNeeded(song: Song): Song = withContext(Dispatchers.IO) {
        if (!song.contentUriString.startsWith("online://")) return@withContext song
        onlineSourceBySongId[song.id] = song.contentUriString

        val resolved = kotlinx.coroutines.withTimeoutOrNull(14_500L) {
            val uri = Uri.parse(song.contentUriString)
            val host = uri.host // "youtube", "spotify", "saavn", "audius"
            val path = uri.path?.removePrefix("/") ?: ""
            val metaTitle = uri.getQueryParameter("title")?.takeIf { it.isNotBlank() } ?: song.title
            val metaArtist = uri.getQueryParameter("artist")?.takeIf { it.isNotBlank() } ?: song.artist
            val metaDur = uri.getQueryParameter("dur")?.toLongOrNull() ?: song.durationMs
            val embeddedStream = uri.getQueryParameter("stream")?.takeIf { it.startsWith("http") }

            if (embeddedStream != null) {
                return@withTimeoutOrNull song.copy(contentUriString = embeddedStream)
            }

            val app = context.applicationContext as? com.example.MusicApplication
            val streamManager = app?.youTubeStreamManager ?: return@withTimeoutOrNull song

            if (host == "youtube" && path.isNotBlank()) {
                val resolvedUrl = streamManager.resolveStreamUrl(path)
                if (resolvedUrl != null) {
                    return@withTimeoutOrNull song.copy(contentUriString = resolvedUrl)
                }
            }

            val matched = streamManager.resolveTrackStreamForMetadata(metaTitle, metaArtist, metaDur)
            if (matched != null) {
                return@withTimeoutOrNull song.copy(
                    contentUriString = matched.first,
                    durationMs = if (song.durationMs <= 0L) matched.second else song.durationMs
                )
            }
            song
        }
        resolved ?: song
    }

    private fun resolveAndPlayIndex(index: Int, song: Song) {
        val controller = mediaController ?: return
        resolveJob?.cancel()
        isResolvingOnline = true
        _playbackState.value = _playbackState.value.copy(isBuffering = true, errorMessage = null)
        resolveJob = scope.launch {
            try {
                val resolvedSong = resolveOnlineUriIfNeeded(song)
                if (resolvedSong.contentUriString.startsWith("online://")) {
                    isResolvingOnline = false
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "Не удалось загрузить поток для «${song.title}». Проверьте сеть или повторите."
                    )
                    return@launch
                }
                onlineRetryCounts[song.id] = 0
                isResolvingOnline = false
                if (index in currentQueueSongs.indices && currentQueueSongs[index].id == song.id) {
                    currentQueueSongs[index] = resolvedSong
                    val oIdx = originalQueue.indexOfFirst { it.id == song.id }
                    if (oIdx in originalQueue.indices) originalQueue[oIdx] = resolvedSong

                    val mediaItem = songToMediaItem(resolvedSong)
                    controller.replaceMediaItem(index, mediaItem)
                    controller.seekTo(index, 0L)
                    controller.prepare()
                    controller.play()
                    updatePlaybackState()
                    preresolveUpcomingQueue(index)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                isResolvingOnline = false
                throw e
            } catch (e: Exception) {
                isResolvingOnline = false
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Ошибка потока для «${song.title}»"
                )
            }
        }
    }

    private fun recoverOnlineStreamError(index: Int, song: Song) {
        val controller = mediaController ?: return
        val attempts = onlineRetryCounts[song.id] ?: 0
        if (attempts >= 2) {
            onlineRetryCounts[song.id] = 0
            isResolvingOnline = false
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = "Источник временно недоступен для «${song.title}». Попробуйте повторить."
            )
            return
        }
        onlineRetryCounts[song.id] = attempts + 1
        resolveJob?.cancel()
        isResolvingOnline = true
        _playbackState.value = _playbackState.value.copy(isBuffering = true, errorMessage = null)
        resolveJob = scope.launch {
            try {
                if (song.contentUriString.startsWith("online://")) {
                    resolveAndPlayIndex(index, song)
                    return@launch
                }
                val originalOnlineUri = onlineSourceBySongId[song.id]
                val parsedVideoId = originalOnlineUri?.let { raw ->
                    val u = Uri.parse(raw)
                    if (u.host == "youtube") u.path?.removePrefix("/") else null
                }

                val app = context.applicationContext as? com.example.MusicApplication
                val streamManager = app?.youTubeStreamManager
                val alternate = streamManager?.resolveAlternateStream(
                    failedUrl = song.contentUriString,
                    videoId = parsedVideoId,
                    title = song.title,
                    artist = song.artist,
                    expectedDurationMs = song.durationMs
                )
                isResolvingOnline = false
                if (alternate != null && index in currentQueueSongs.indices && currentQueueSongs[index].id == song.id) {
                    val updated = song.copy(contentUriString = alternate.url)
                    currentQueueSongs[index] = updated
                    val oIdx = originalQueue.indexOfFirst { it.id == song.id }
                    if (oIdx in originalQueue.indices) originalQueue[oIdx] = updated
                    controller.replaceMediaItem(index, songToMediaItem(updated))
                    controller.seekTo(index, 0L)
                    controller.prepare()
                    controller.play()
                    updatePlaybackState()
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "Не удалось подобрать рабочее зеркало для «${song.title}»"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                isResolvingOnline = false
                throw e
            } catch (_: Exception) {
                isResolvingOnline = false
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Сбой загрузки потока для «${song.title}»"
                )
            }
        }
    }

    private fun preresolveUpcomingQueue(currentIndex: Int) {
        val controller = mediaController ?: return
        preResolveJob?.cancel()
        preResolveJob = scope.launch {
            val queueCopy = currentQueueSongs.toList()
            val indicesToCheck = listOf(currentIndex + 1, currentIndex + 2)
                .filter { it in queueCopy.indices }

            for (idx in indicesToCheck) {
                val candidate = queueCopy[idx]
                if (candidate.contentUriString.startsWith("online://")) {
                    val resolved = resolveOnlineUriIfNeeded(candidate)
                    if (!resolved.contentUriString.startsWith("online://") &&
                        idx in currentQueueSongs.indices &&
                        currentQueueSongs[idx].id == candidate.id
                    ) {
                        currentQueueSongs[idx] = resolved
                        controller.replaceMediaItem(idx, songToMediaItem(resolved))
                    }
                }
            }
        }
    }

    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        resolveJob?.cancel()
        preResolveJob?.cancel()
        isResolvingOnline = false
        isCrossfading = false
        originalQueue = queue.toMutableList()
        currentQueueSongs.clear()
        currentQueueSongs.addAll(queue)

        if (song.contentUriString.startsWith("online://")) {
            onlineSourceBySongId[song.id] = song.contentUriString
            val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            isResolvingOnline = true
            _playbackState.value = _playbackState.value.copy(
                currentSong = song,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0L,
                durationMs = song.durationMs,
                queue = queue,
                currentQueueIndex = idx,
                errorMessage = null
            )
            _playbackPositionMs.value = 0L

            resolveJob = scope.launch {
                try {
                    val resolvedSong = resolveOnlineUriIfNeeded(song)
                    if (resolvedSong.contentUriString.startsWith("online://")) {
                        isResolvingOnline = false
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "Не удалось загрузить поток для «${song.title}». Проверьте сеть или повторите."
                        )
                        return@launch
                    }

                    isResolvingOnline = false
                    onlineRetryCounts[song.id] = 0
                    val resolvedQueue = queue.map { if (it.id == song.id) resolvedSong else it }
                    currentQueueSongs.clear()
                    currentQueueSongs.addAll(resolvedQueue)
                    originalQueue = resolvedQueue.toMutableList()

                    val startIndex = resolvedQueue.indexOfFirst { it.id == resolvedSong.id }.coerceAtLeast(0)
                    val mediaItems = resolvedQueue.map { songToMediaItem(it) }

                    controller.setMediaItems(mediaItems, startIndex, 0L)
                    controller.prepare()
                    controller.volume = preferencesManager.playerVolume.value
                    controller.play()
                    updatePlaybackState()
                    pluginManager?.dispatchQueueChanged(currentQueueSongs)
                    preresolveUpcomingQueue(startIndex)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    isResolvingOnline = false
                    throw e
                } catch (_: Exception) {
                    isResolvingOnline = false
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "Не удалось запустить поток для «${song.title}»"
                    )
                }
            }
            return
        }

        val startIndex = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val mediaItems = queue.map { songToMediaItem(it) }

        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()

        if (preferencesManager.fadeInOnStart.value) {
            scope.launch {
                controller.volume = 0f
                controller.play()
                fadeVolume(0f, preferencesManager.playerVolume.value, 350L)
            }
        } else {
            controller.volume = preferencesManager.playerVolume.value
            controller.play()
        }
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(currentQueueSongs)
        preresolveUpcomingQueue(startIndex)
    }

    fun playQueue(queue: List<Song>, startIndex: Int = 0) {
        if (queue.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, queue.size - 1)
        val firstSong = queue[safeIdx]
        if (firstSong.contentUriString.startsWith("online://")) {
            playSong(firstSong, queue)
            return
        }
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        isCrossfading = false
        originalQueue = queue.toMutableList()
        currentQueueSongs.clear()
        currentQueueSongs.addAll(queue)

        val mediaItems = queue.map { songToMediaItem(it) }
        controller.setMediaItems(mediaItems, safeIdx, 0L)
        controller.prepare()
        controller.volume = preferencesManager.playerVolume.value
        controller.play()
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(currentQueueSongs)
        preresolveUpcomingQueue(safeIdx)
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            // Instantly update UI state so play/pause button morphs with ZERO delay!
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            if (preferencesManager.fadeOutOnPause.value) {
                volumeFadeJob?.cancel()
                volumeFadeJob = scope.launch {
                    val currentVol = controller.volume
                    val steps = 6
                    val delayStep = 16L
                    for (i in steps downTo 0) {
                        controller.volume = currentVol * (i.toFloat() / steps)
                        delay(delayStep)
                    }
                    controller.pause()
                    controller.volume = preferencesManager.playerVolume.value
                    updatePlaybackState()
                }
            } else {
                controller.pause()
                updatePlaybackState()
            }
        } else {
            val activeSong = _playbackState.value.currentSong
            val activeIdx = controller.currentMediaItemIndex.coerceAtLeast(0)
            if (activeSong != null && activeSong.contentUriString.startsWith("online://")) {
                _playbackState.value = _playbackState.value.copy(isBuffering = true)
                resolveAndPlayIndex(activeIdx, activeSong)
                return
            }
            // Instantly update UI state so play/pause button morphs with ZERO delay!
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            if (controller.playbackState == Player.STATE_IDLE || controller.mediaItemCount == 0) {
                if (currentQueueSongs.isNotEmpty()) {
                    controller.setMediaItems(currentQueueSongs.map { songToMediaItem(it) }, 0, 0L)
                    controller.prepare()
                }
            }
            if (preferencesManager.fadeInOnStart.value) {
                controller.volume = 0f
                controller.play()
                volumeFadeJob?.cancel()
                volumeFadeJob = scope.launch {
                    val targetVol = preferencesManager.playerVolume.value
                    val steps = 8
                    val delayStep = 18L
                    for (i in 1..steps) {
                        controller.volume = targetVol * (i.toFloat() / steps)
                        delay(delayStep)
                    }
                    controller.volume = targetVol
                    updatePlaybackState()
                }
            } else {
                controller.volume = preferencesManager.playerVolume.value
                controller.play()
                updatePlaybackState()
            }
        }
    }

    fun setABPointA(ms: Long?) {
        _abPointA.value = ms
    }

    fun setABPointB(ms: Long?) {
        _abPointB.value = ms
    }

    fun clearABRepeat() {
        _abPointA.value = null
        _abPointB.value = null
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        isCrossfading = false
        controller.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        val crossfadeEnabled = preferencesManager.crossfadeEnabled.value
        val crossfadeDurationSec = preferencesManager.crossfadeDuration.value
        val crossfadeMs = (crossfadeDurationSec * 1000).toLong()

        val nextIndex = controller.nextMediaItemIndex
        val targetIdx = if (nextIndex != C.INDEX_UNSET) {
            nextIndex
        } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
            0
        } else {
            C.INDEX_UNSET
        }

        if (targetIdx != C.INDEX_UNSET) {
            val service = getService()
            if (crossfadeEnabled && crossfadeMs > 0 && controller.isPlaying && service != null) {
                service.crossfadeTo(targetIdx, crossfadeMs)
            } else {
                controller.seekTo(targetIdx, 0L)
                controller.play()
            }
        }
        updatePlaybackState()
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000L) {
            controller.seekTo(0L)
        } else {
            val prevIndex = controller.previousMediaItemIndex
            val targetIdx = if (prevIndex != C.INDEX_UNSET) {
                prevIndex
            } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
                controller.mediaItemCount - 1
            } else {
                C.INDEX_UNSET
            }

            if (targetIdx != C.INDEX_UNSET) {
                val crossfadeEnabled = preferencesManager.crossfadeEnabled.value
                val crossfadeDurationSec = preferencesManager.crossfadeDuration.value
                val crossfadeMs = (crossfadeDurationSec * 1000).toLong()
                val service = getService()
                if (crossfadeEnabled && crossfadeMs > 0 && controller.isPlaying && service != null) {
                    service.crossfadeTo(targetIdx, crossfadeMs)
                } else {
                    controller.seekTo(targetIdx, 0L)
                    controller.play()
                }
            } else {
                controller.seekTo(0L)
            }
        }
        updatePlaybackState()
    }

    fun rewind10() {
        val controller = mediaController ?: return
        val newPos = (controller.currentPosition - 10000).coerceAtLeast(0)
        controller.seekTo(newPos)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = newPos)
    }

    fun forward10() {
        val controller = mediaController ?: return
        val newPos = (controller.currentPosition + 10000).coerceAtMost(controller.duration)
        controller.seekTo(newPos)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = newPos)
    }

    /**
     * Seamless shuffle toggle:
     * Toggles ExoPlayer native shuffle mode without stopping or resetting audio buffers.
     */
    fun toggleShuffle() {
        val controller = mediaController ?: return
        val enableShuffle = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = enableShuffle
        preferencesManager.setShuffleEnabled(enableShuffle)
        updatePlaybackState()
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val newMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = newMode
        val stateRepeat = when (newMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        preferencesManager.setRepeatMode(stateRepeat)
        _playbackState.value = _playbackState.value.copy(repeatMode = stateRepeat)
        updatePlaybackState()
    }

    fun stop() {
        val controller = mediaController ?: return
        controller.stop()
        updatePlaybackState()
    }

    fun getQueue(): List<Song> = currentQueueSongs.toList()

    fun addToQueue(song: Song) {
        val controller = mediaController ?: return
        if (song.contentUriString.startsWith("online://")) {
            scope.launch {
                val resolved = resolveOnlineUriIfNeeded(song)
                currentQueueSongs.add(resolved)
                originalQueue.add(resolved)
                controller.addMediaItem(songToMediaItem(resolved))
                updatePlaybackState()
                pluginManager?.dispatchQueueChanged(currentQueueSongs)
            }
            return
        }
        currentQueueSongs.add(song)
        originalQueue.add(song)
        controller.addMediaItem(songToMediaItem(song))
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(currentQueueSongs)
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        if (index in currentQueueSongs.indices) {
            val removedSong = currentQueueSongs.removeAt(index)
            originalQueue.removeAll { it.id == removedSong.id }
            controller.removeMediaItem(index)
            updatePlaybackState()
            pluginManager?.dispatchQueueChanged(currentQueueSongs)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        if (fromIndex in currentQueueSongs.indices && toIndex in currentQueueSongs.indices) {
            val item = currentQueueSongs.removeAt(fromIndex)
            currentQueueSongs.add(toIndex, item)
            controller.moveMediaItem(fromIndex, toIndex)
            updatePlaybackState()
            pluginManager?.dispatchQueueChanged(currentQueueSongs)
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        moveQueueItem(fromIndex, toIndex)
    }

    fun playQueueIndex(index: Int) {
        val controller = mediaController ?: return
        if (index in currentQueueSongs.indices) {
            val targetSong = currentQueueSongs[index]
            if (targetSong.contentUriString.startsWith("online://")) {
                _playbackState.value = _playbackState.value.copy(
                    currentSong = targetSong,
                    currentQueueIndex = index,
                    currentPositionMs = 0L,
                    durationMs = targetSong.durationMs,
                    errorMessage = null
                )
                resolveAndPlayIndex(index, targetSong)
                return
            }
            controller.seekTo(index, 0L)
            controller.prepare()
            controller.play()
            updatePlaybackState()
        }
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        currentQueueSongs.clear()
        originalQueue.clear()
        controller.clearMediaItems()
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(emptyList())
    }

    private fun songToMediaItem(song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUriString?.let { Uri.parse(it) })
            .build()

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(Uri.parse(song.contentUriString))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun songFromMediaItem(item: MediaItem): Song {
        val meta = item.mediaMetadata
        return Song(
            id = item.mediaId.toLongOrNull() ?: 0L,
            title = meta.title?.toString() ?: "Неизвестный трек",
            artist = meta.artist?.toString() ?: "Неизвестный исполнитель",
            album = meta.albumTitle?.toString() ?: "",
            albumId = 0L,
            durationMs = 0L,
            contentUriString = item.requestMetadata.mediaUri?.toString() ?: "",
            albumArtUriString = meta.artworkUri?.toString()
        )
    }

    fun release() {
        stopPositionUpdates()
        equalizerManager.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
    }
}
