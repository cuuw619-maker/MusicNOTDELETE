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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

internal object PlaybackTransitionPolicy {
    fun effectiveTransitionDurationMs(playerDurationMs: Long): Long =
        if (playerDurationMs > 0L && playerDurationMs != C.TIME_UNSET) playerDurationMs else 0L

    fun shouldAttemptEarlyEofRecovery(
        isOnline: Boolean,
        playerDurationMs: Long,
        positionMs: Long,
        earlyEofCount: Int
    ): Boolean =
        isOnline &&
            playerDurationMs > 75_000L &&
            positionMs in 12_000L..(playerDurationMs - 25_000L) &&
            earlyEofCount < 1

    fun shouldAdvanceQueueAfterFailure(
        repeatMode: Int,
        hasNextMediaItem: Boolean,
        queueSize: Int
    ): Boolean =
        queueSize > 1 &&
            repeatMode != Player.REPEAT_MODE_ONE &&
            (hasNextMediaItem || repeatMode == Player.REPEAT_MODE_ALL)

    fun computePreresolveIndices(
        currentIndex: Int,
        nextMediaItemIndex: Int,
        queueSize: Int
    ): List<Int> {
        if (queueSize <= 0) return emptyList()
        val ordered = LinkedHashSet<Int>(3)
        if (nextMediaItemIndex in 0 until queueSize && nextMediaItemIndex != currentIndex) {
            ordered.add(nextMediaItemIndex)
        }
        for (offset in 1..2) {
            val candidate = currentIndex + offset
            if (candidate in 0 until queueSize && candidate != currentIndex) {
                ordered.add(candidate)
            }
        }
        return ordered.toList()
    }
}

class MusicControllerManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    val equalizerManager: EqualizerManager,
    private val onSongPlayed: (Long) -> Unit
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MusicControllerManager", "Unhandled coroutine exception", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
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

    private val _activeStreamEngine = MutableStateFlow("local")
    val activeStreamEngine: StateFlow<String> = _activeStreamEngine.asStateFlow()

    private val _diagnosticReport = MutableStateFlow<com.example.online.PlaybackDiagnosticReport?>(null)
    val diagnosticReport: StateFlow<com.example.online.PlaybackDiagnosticReport?> = _diagnosticReport.asStateFlow()

    fun dismissDiagnosticReport() {
        _diagnosticReport.value = null
    }

    private val queueLock = Any()
    private var originalQueue = mutableListOf<Song>()
    private var currentQueueSongs = mutableListOf<Song>()
    private var positionUpdateJob: Job? = null
    private var volumeFadeJob: Job? = null
    private var isCrossfading = false
    @Volatile
    private var isResolvingOnline = false
    @Volatile
    private var resolvingKey: Pair<Int, Long>? = null
    private var lastActiveSongId: Long? = null
    private val onlineSourceBySongId = ConcurrentHashMap<Long, String>()
    private val resolvedStreamExpiryBySongId = ConcurrentHashMap<Long, Long>()
    private val onlineRetryCounts = ConcurrentHashMap<Long, Int>()
    private val earlyEofRecoveryCounts = ConcurrentHashMap<Long, Int>()
    private val failedVideoIdsBySongId = ConcurrentHashMap<Long, MutableSet<String>>()
    @Volatile
    private var consecutiveAutoSkipCount: Int = 0
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
                if (state == Player.STATE_READY) {
                    consecutiveAutoSkipCount = 0
                }
                if (state == Player.STATE_ENDED) {
                    val cur = _playbackState.value.currentSong
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    // Use ONLY true ExoPlayer stream duration so truncated search metadata never triggers false early-EOF recovery
                    val actualDur = PlaybackTransitionPolicy.effectiveTransitionDurationMs(controller.duration)
                    val isOnline = cur != null && (
                        cur.contentUriString.startsWith("http") ||
                            cur.id < 0L ||
                            onlineSourceBySongId.containsKey(cur.id)
                        )
                    val eofCount = if (cur != null) (earlyEofRecoveryCounts[cur.id] ?: 0) else 0
                    if (cur != null && PlaybackTransitionPolicy.shouldAttemptEarlyEofRecovery(isOnline, actualDur, pos, eofCount)) {
                        earlyEofRecoveryCounts[cur.id] = eofCount + 1
                        val idx = controller.currentMediaItemIndex
                        recoverOnlineStreamError(
                            index = idx,
                            song = cur,
                            lastErrorCode = "EARLY_EOF",
                            lastErrorSummary = "Поток прервался на ${pos / 1000}с — переключение на зеркало",
                            resumeAtMs = pos
                        )
                        return
                    }
                    if (!isCrossfading) {
                        controller.volume = preferencesManager.playerVolume.value
                    }
                    stopPositionUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                getService()?.cancelCrossfade()
                isCrossfading = false
                controller.volume = preferencesManager.playerVolume.value
                updatePlaybackState()
                val currentSong = _playbackState.value.currentSong
                if (currentSong != null) {
                    if (lastActiveSongId != currentSong.id) {
                        lastActiveSongId = currentSong.id
                        _abPointA.value = null
                        _abPointB.value = null
                    }
                    onSongPlayed(currentSong.id)
                    preferencesManager.setLastPlayedSong(currentSong.id, 0L)
                    pluginManager?.dispatchTrackChanged(currentSong)
                    val idx = controller.currentMediaItemIndex
                    if (currentSong.contentUriString.startsWith("online://") || isTrackStreamNeedsRefresh(currentSong)) {
                        val isAutoTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                        val needsForceRefresh = !currentSong.contentUriString.startsWith("online://")
                        if (!(isResolvingOnline && resolvingKey == (idx to currentSong.id))) {
                            resolveAndPlayIndex(
                                index = idx,
                                song = currentSong,
                                resetRetryCount = true,
                                autoAdvanceOnFailure = isAutoTransition,
                                forceRefresh = needsForceRefresh
                            )
                        }
                    } else {
                        if (!onlineSourceBySongId.containsKey(currentSong.id) && !currentSong.contentUriString.startsWith("http")) {
                            _activeStreamEngine.value = "local"
                        }
                        preresolveUpcomingQueue(idx)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updatePlaybackState()
                val curIdx = controller.currentMediaItemIndex
                if (curIdx in 0 until controller.mediaItemCount) {
                    preresolveUpcomingQueue(curIdx)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePlaybackState()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("MusicControllerManager", "Player error: ${error.errorCodeName} ${error.message}", error)
                val cur = _playbackState.value.currentSong
                // Suppress placeholder online:// player errors while online URL resolution is already running
                if (isResolvingOnline && cur?.contentUriString?.startsWith("online://") == true) {
                    return
                }
                val (codeLabel, reasonText) = extractPlayerErrorDetails(error)
                com.example.online.SourceDiagnosticsLog.record(
                    com.example.online.SourceAttemptLog(
                        sourceId = _activeStreamEngine.value,
                        displayName = com.example.online.YouTubeStreamManager.formatEngineDisplay(_activeStreamEngine.value),
                        stage = "exoplayer",
                        status = com.example.online.SourceAttemptStatus.HTTP_ERROR,
                        httpCode = codeLabel.removePrefix("HTTP ").toIntOrNull(),
                        detail = "$codeLabel: $reasonText"
                    )
                )
                val idx = controller.currentMediaItemIndex
                if (cur != null && (cur.contentUriString.startsWith("online://") || cur.id < 0L || onlineSourceBySongId.containsKey(cur.id))) {
                    recoverOnlineStreamError(idx, cur, lastErrorCode = codeLabel, lastErrorSummary = reasonText)
                    return
                }
                isResolvingOnline = false
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Ошибка ($codeLabel): $reasonText"
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
        getService()?.player?.pauseAtEndOfMediaItems = !preferencesManager.gaplessPlayback.value
    }

    fun applyPlaybackParameters() {
        val controller = mediaController ?: return
        val speed = preferencesManager.playbackSpeed.value.coerceIn(0.5f, 2.0f)
        val semitones = preferencesManager.pitchSemitones.value.coerceIn(-12, 12)
        val cents = preferencesManager.pitchCents.value.coerceIn(-50, 50)
        val preservePitch = preferencesManager.preservePitch.value

        // Studio DSP pitch calculation: 2^((semitones + cents/100) / 12)
        // When preservePitch is false, pitch shifts naturally with playback speed (vinyl / tape style).
        val totalSemitones = semitones + (cents / 100.0f)
        val manualPitchFactor = 2.0f.pow(totalSemitones / 12.0f)
        val pitchMultiplier = if (preservePitch) {
            manualPitchFactor
        } else {
            (speed * manualPitchFactor).coerceIn(0.25f, 4.0f)
        }

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

    fun setGaplessPlayback(enabled: Boolean) {
        preferencesManager.setGaplessPlayback(enabled)
        runCatching {
            getService()?.player?.pauseAtEndOfMediaItems = !enabled
        }
    }

    private fun updatePlaybackState() {
        val controller = mediaController ?: return
        val queueSnapshot = synchronized(queueLock) { currentQueueSongs.toList() }
        val currentMediaItem = controller.currentMediaItem
        val currentSong = currentMediaItem?.let { item ->
            val id = item.mediaId.toLongOrNull() ?: -1L
            queueSnapshot.find { it.id == id } ?: songFromMediaItem(item)
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
            queue = queueSnapshot,
            currentQueueIndex = controller.currentMediaItemIndex,
            errorMessage = keepError
        )
        _playbackState.value = newState
        pluginManager?.dispatchPlaybackStateChanged(newState)
    }

    private fun safeReplaceMediaItem(controller: MediaController, index: Int, item: MediaItem): Boolean {
        if (index !in 0 until controller.mediaItemCount) return false
        return runCatching {
            controller.replaceMediaItem(index, item)
            true
        }.getOrDefault(false)
    }

    private fun safeSeekAndPlay(controller: MediaController, index: Int, posMs: Long = 0L): Boolean {
        if (index !in 0 until controller.mediaItemCount) return false
        return runCatching {
            controller.seekTo(index, posMs.coerceAtLeast(0L))
            controller.prepare()
            controller.volume = preferencesManager.playerVolume.value
            controller.play()
            true
        }.getOrDefault(false)
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

    fun getOriginalOnlineUriForSong(songId: Long): String? = onlineSourceBySongId[songId]

    fun getYouTubeVideoIdForSong(songId: Long): String? {
        val raw = onlineSourceBySongId[songId] ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return if (uri.host == "youtube") uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() } else null
    }

    fun switchDeliveryEngineForCurrent(preferredEngine: String) {
        preferencesManager.setPreferredDeliveryEngine(preferredEngine)
        val controller = mediaController ?: return
        val current = _playbackState.value.currentSong ?: return
        val sourceUri = onlineSourceBySongId[current.id] ?: current.contentUriString
        val idx = controller.currentMediaItemIndex.takeIf { it in currentQueueSongs.indices } ?: return
        val resumePos = controller.currentPosition.coerceAtLeast(0L)

        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull()
        val host = uri?.host
        val path = uri?.path?.removePrefix("/") ?: ""

        resolveJob?.cancel()
        isResolvingOnline = true
        _playbackState.value = _playbackState.value.copy(isBuffering = true, errorMessage = null)

        resolveJob = scope.launch {
            try {
                val app = context.applicationContext as? com.example.MusicApplication
                val streamManager = app?.youTubeStreamManager
                if (streamManager == null) {
                    isResolvingOnline = false
                    updatePlaybackState()
                    return@launch
                }

                val resolvedResult = if (host == "youtube" && path.isNotBlank()) {
                    streamManager.clearStreamCacheForId(path)
                    streamManager.resolveStreamResult(
                        videoId = path,
                        excludedEngines = emptySet(),
                        preferredEngine = preferredEngine,
                        expectedDurationMs = current.durationMs
                    )
                } else {
                    val matched = streamManager.resolveTrackStreamForMetadata(
                        targetTitle = current.title,
                        targetArtist = current.artist,
                        expectedDurationMs = current.durationMs,
                        preferredEngine = preferredEngine
                    )
                    matched?.let { (u, _, eng) ->
                        com.example.online.ResolvedStream(
                            url = u,
                            userAgent = "Mozilla/5.0",
                            expiresAtMs = System.currentTimeMillis() + 3600_000L,
                            engine = eng
                        )
                    }
                }

                isResolvingOnline = false
                val updatedQueueSnapshot = synchronized(queueLock) {
                    if (resolvedResult != null && idx in currentQueueSongs.indices) {
                        val updated = current.copy(contentUriString = resolvedResult.url)
                        currentQueueSongs[idx] = updated
                        val oIdx = originalQueue.indexOfFirst { it.id == current.id }
                        if (oIdx in originalQueue.indices) originalQueue[oIdx] = updated
                        updated
                    } else {
                        null
                    }
                }
                if (resolvedResult != null && updatedQueueSnapshot != null) {
                    _activeStreamEngine.value = resolvedResult.engine
                    recordResolvedStreamExpiry(current.id, resolvedResult.url, resolvedResult.expiresAtMs)
                    if (safeReplaceMediaItem(controller, idx, songToMediaItem(updatedQueueSnapshot))) {
                        safeSeekAndPlay(controller, idx, resumePos)
                    }
                    updatePlaybackState()
                } else {
                    _playbackState.value = _playbackState.value.copy(
                        isBuffering = false,
                        errorMessage = "Движок ${com.example.online.YouTubeStreamManager.formatEngineDisplay(preferredEngine)} не ответил для этого трека"
                    )
                }
            } catch (e: CancellationException) {
                isResolvingOnline = false
                throw e
            } catch (_: Throwable) {
                isResolvingOnline = false
                _playbackState.value = _playbackState.value.copy(
                    isBuffering = false,
                    errorMessage = "Не удалось переключить движок потока"
                )
            }
        }
    }

    fun replaceCurrentTrackWithOnlineCandidate(track: com.example.online.OnlineTrack) {
        _diagnosticReport.value = null
        val newSong = track.toSong()
        onlineSourceBySongId[newSong.id] = track.buildOnlineUri()
        val controller = mediaController
        val activeIdx = synchronized(queueLock) {
            controller?.currentMediaItemIndex?.takeIf { it in currentQueueSongs.indices } ?: -1
        }

        if (controller != null && activeIdx >= 0) {
            getService()?.cancelCrossfade()
            resolveJob?.cancel()
            preResolveJob?.cancel()
            isCrossfading = false
            onlineRetryCounts[newSong.id] = 0
            earlyEofRecoveryCounts[newSong.id] = 0
            _abPointA.value = null
            _abPointB.value = null

            val snapshot = synchronized(queueLock) {
                if (activeIdx in currentQueueSongs.indices) {
                    val oldSong = currentQueueSongs[activeIdx]
                    currentQueueSongs[activeIdx] = newSong
                    val origIdx = originalQueue.indexOfFirst { it.id == oldSong.id }
                    if (origIdx in originalQueue.indices) originalQueue[origIdx] = newSong
                }
                currentQueueSongs.toList()
            }

            if (newSong.contentUriString.startsWith("http")) {
                _activeStreamEngine.value = track.sourceEngine
                recordResolvedStreamExpiry(newSong.id, newSong.contentUriString)
                if (safeReplaceMediaItem(controller, activeIdx, songToMediaItem(newSong))) {
                    safeSeekAndPlay(controller, activeIdx, 0L)
                }
                updatePlaybackState()
            } else {
                _playbackState.value = _playbackState.value.copy(
                    currentSong = newSong,
                    isBuffering = true,
                    currentPositionMs = 0L,
                    durationMs = newSong.durationMs,
                    queue = snapshot,
                    currentQueueIndex = activeIdx,
                    errorMessage = null
                )
                resolveAndPlayIndex(activeIdx, newSong)
            }
        } else {
            playSong(newSong, listOf(newSong))
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var tickCount = 0
            while (isActive) {
                try {
                    val controller = mediaController
                    if (controller != null && controller.isPlaying) {
                        val pos = controller.currentPosition.coerceAtLeast(0L)
                        _playbackPositionMs.value = pos

                        // Genuine stream duration from ExoPlayer (never metadata fallback for cutoffs/crossfade)
                        val actualDur = PlaybackTransitionPolicy.effectiveTransitionDurationMs(controller.duration)
                        val uiDur = if (actualDur > 0L) actualDur else (_playbackState.value.currentSong?.durationMs ?: 0L)

                        // Throttle full PlaybackState emission (~160ms) to cut unnecessary UI recompositions
                        if (tickCount % 4 == 0) {
                            _playbackState.value = _playbackState.value.copy(
                                currentPositionMs = pos,
                                durationMs = uiDur,
                                bufferedPositionMs = controller.bufferedPosition
                            )
                        }
                        tickCount++

                        // A-B Repeat Loop Check
                        val pA = _abPointA.value
                        val pB = _abPointB.value
                        if (pA != null && pB != null && pB > pA && pos >= pB) {
                            controller.seekTo(pA)
                        }

                        // Auto-crossfade / Smart Auto-DJ nearing track end (strictly on confirmed actualDur, never onto unresolved online:// items)
                        val autoDjEnabled = preferencesManager.autoDjEnabled.value
                        val crossfadeEnabled = preferencesManager.crossfadeEnabled.value || autoDjEnabled
                        val crossfadeDurationSec = if (autoDjEnabled && !preferencesManager.crossfadeEnabled.value) {
                            3.5f
                        } else {
                            preferencesManager.crossfadeDuration.value
                        }
                        val crossfadeMs = (crossfadeDurationSec * 1000).toLong()
                        val repeatOne = controller.repeatMode == Player.REPEAT_MODE_ONE

                        if (!repeatOne && crossfadeEnabled && crossfadeMs > 0L && actualDur > crossfadeMs * 2) {
                            val remaining = actualDur - pos
                            if (remaining in 1..(crossfadeMs + 100L) && !isCrossfading) {
                                val nextIndex = controller.nextMediaItemIndex
                                val targetIdx = if (nextIndex != C.INDEX_UNSET) {
                                    nextIndex
                                } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 1) {
                                    0
                                } else {
                                    C.INDEX_UNSET
                                }
                                val targetSong = synchronized(queueLock) { currentQueueSongs.getOrNull(targetIdx) }
                                val targetReady = targetIdx != C.INDEX_UNSET &&
                                    targetIdx != controller.currentMediaItemIndex &&
                                    targetSong != null &&
                                    !isTrackStreamNeedsRefresh(targetSong)

                                if (targetReady) {
                                    isCrossfading = true
                                    val service = getService()
                                    if (service != null) {
                                        service.crossfadeTo(targetIdx, crossfadeMs)
                                    } else {
                                        performCrossfadeNext(crossfadeMs)
                                    }
                                    scope.launch {
                                        delay(crossfadeMs + 250L)
                                        isCrossfading = false
                                    }
                                }
                            }
                        }

                        // Track End Fade Out Check (strictly using true ExoPlayer duration so under-reported metadata never silences songs mid-way)
                        val trackEndFadeEnabled = preferencesManager.trackEndFadeOutEnabled.value
                        val trackEndFadeDurationSec = preferencesManager.trackEndFadeOutDuration.value
                        val trackEndFadeMs = (trackEndFadeDurationSec * 1000).toLong()
                        if (!repeatOne && trackEndFadeEnabled && !isCrossfading && actualDur > trackEndFadeMs * 2) {
                            val remaining = actualDur - pos
                            val nextIdx = controller.nextMediaItemIndex
                            val isLastTrack = nextIdx == C.INDEX_UNSET && controller.repeatMode != Player.REPEAT_MODE_ALL
                            if ((isLastTrack || !crossfadeEnabled) && remaining in 1..trackEndFadeMs) {
                                val fraction = (remaining.toFloat() / trackEndFadeMs.toFloat()).coerceIn(0f, 1f)
                                val baseVol = preferencesManager.playerVolume.value
                                controller.volume = fraction * baseVol
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("MusicControllerManager", "Position update tick error", t)
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
        val restoredQueue = synchronized(queueLock) {
            originalQueue = queue.toMutableList()
            currentQueueSongs.clear()
            currentQueueSongs.addAll(queue)
            currentQueueSongs.toList()
        }

        val mediaItems = restoredQueue.map { songToMediaItem(it) }

        controller.setMediaItems(mediaItems, startIndex, positionMs.coerceAtLeast(0L))
        controller.prepare()
        pluginManager?.dispatchQueueChanged(restoredQueue)
    }

    private var resolveJob: Job? = null
    private var preResolveJob: Job? = null

    private fun recordResolvedStreamExpiry(
        songId: Long,
        url: String,
        explicitExpiresAtMs: Long? = null
    ) {
        if (url.startsWith("http", ignoreCase = true)) {
            resolvedStreamExpiryBySongId[songId] = explicitExpiresAtMs
                ?: com.example.online.YouTubeStreamManager.extractStreamExpiryMs(url)
        }
    }

    private fun isTrackStreamNeedsRefresh(
        song: Song,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (song.contentUriString.startsWith("online://")) return true
        if (!song.contentUriString.startsWith("http", ignoreCase = true)) return false
        if (!onlineSourceBySongId.containsKey(song.id) && song.id >= 0L) return false
        val expiryMs = resolvedStreamExpiryBySongId[song.id]
            ?: com.example.online.YouTubeStreamManager.extractStreamExpiryMs(song.contentUriString, nowMs)
        return com.example.online.YouTubeStreamManager.isStreamNearExpiry(expiryMs, nowMs)
    }

    suspend fun resolveOnlineUriIfNeeded(
        song: Song,
        preferredEngine: String? = null,
        forceRefresh: Boolean = false
    ): Song = withContext(Dispatchers.IO) {
        val effectiveEngine = preferredEngine?.takeIf { it.isNotBlank() }
            ?: preferencesManager.preferredDeliveryEngine.value
        val isOnlinePlaceholder = song.contentUriString.startsWith("online://")
        val rawOnlineSource = if (isOnlinePlaceholder) song.contentUriString else onlineSourceBySongId[song.id]
        if (!forceRefresh && !isOnlinePlaceholder && !isTrackStreamNeedsRefresh(song)) {
            if (!song.contentUriString.startsWith("http")) {
                _activeStreamEngine.value = "local"
            }
            return@withContext song
        }
        if (rawOnlineSource != null) {
            onlineSourceBySongId[song.id] = rawOnlineSource
        } else if (!isOnlinePlaceholder && song.id >= 0L) {
            return@withContext song
        }

        val resolved = kotlinx.coroutines.withTimeoutOrNull(22_000L) {
            val parsedUri = rawOnlineSource?.let { Uri.parse(it) }
            val host = parsedUri?.host
            val path = parsedUri?.path?.removePrefix("/") ?: ""
            val metaTitle = parsedUri?.getQueryParameter("title")?.takeIf { it.isNotBlank() } ?: song.title
            val metaArtist = parsedUri?.getQueryParameter("artist")?.takeIf { it.isNotBlank() } ?: song.artist
            val metaDur = parsedUri?.getQueryParameter("dur")?.toLongOrNull() ?: song.durationMs
            val embeddedStream = parsedUri?.getQueryParameter("stream")?.takeIf { it.startsWith("http") }

            if (!forceRefresh && embeddedStream != null && (host != "youtube" || effectiveEngine == "auto")) {
                _activeStreamEngine.value = host ?: "online"
                recordResolvedStreamExpiry(song.id, embeddedStream)
                return@withTimeoutOrNull song.copy(contentUriString = embeddedStream)
            }

            val app = context.applicationContext as? com.example.MusicApplication
            val streamManager = app?.youTubeStreamManager ?: return@withTimeoutOrNull song

            if (host == "soundcloud" && path.isNotBlank()) {
                val scCandidate = com.example.online.OnlineTrack(
                    id = path,
                    title = metaTitle,
                    artist = metaArtist,
                    durationSeconds = metaDur / 1000L,
                    sourceEngine = "soundcloud"
                )
                val direct = streamManager.resolveDirectTrackPlayableUrl(scCandidate)
                if (direct != null) {
                    _activeStreamEngine.value = "soundcloud"
                    recordResolvedStreamExpiry(song.id, direct, System.currentTimeMillis() + 30 * 60_000L)
                    return@withTimeoutOrNull song.copy(contentUriString = direct)
                }
            }

            if (host == "archive" && path.isNotBlank()) {
                val archiveCandidate = com.example.online.OnlineTrack(
                    id = path,
                    title = metaTitle,
                    artist = metaArtist,
                    durationSeconds = metaDur / 1000L,
                    sourceEngine = "archive"
                )
                val direct = streamManager.resolveDirectTrackPlayableUrl(archiveCandidate)
                if (direct != null) {
                    _activeStreamEngine.value = "archive"
                    recordResolvedStreamExpiry(song.id, direct)
                    return@withTimeoutOrNull song.copy(contentUriString = direct)
                }
            }

            if (host == "bilibili" && path.isNotBlank()) {
                val biliCandidate = com.example.online.OnlineTrack(
                    id = path,
                    title = metaTitle,
                    artist = metaArtist,
                    durationSeconds = metaDur / 1000L,
                    sourceEngine = "bilibili"
                )
                val direct = streamManager.resolveDirectTrackPlayableUrl(biliCandidate)
                if (direct != null) {
                    _activeStreamEngine.value = "bilibili"
                    recordResolvedStreamExpiry(song.id, direct, System.currentTimeMillis() + 90 * 60_000L)
                    return@withTimeoutOrNull song.copy(contentUriString = direct)
                }
            }

            if (host == "audius" && path.isNotBlank()) {
                val audiusCandidate = com.example.online.OnlineTrack(
                    id = path,
                    title = metaTitle,
                    artist = metaArtist,
                    durationSeconds = metaDur / 1000L,
                    sourceEngine = "audius"
                )
                val direct = streamManager.resolveDirectTrackPlayableUrl(audiusCandidate)
                if (direct != null) {
                    _activeStreamEngine.value = "audius"
                    recordResolvedStreamExpiry(song.id, direct)
                    return@withTimeoutOrNull song.copy(contentUriString = direct)
                }
            }

            if (host == "youtube" && path.isNotBlank()) {
                if (forceRefresh) {
                    streamManager.clearStreamCacheForId(path)
                }
                val resolvedResult = streamManager.resolveStreamResult(
                    videoId = path,
                    excludedEngines = emptySet(),
                    preferredEngine = effectiveEngine,
                    expectedDurationMs = metaDur
                )
                if (resolvedResult != null) {
                    _activeStreamEngine.value = resolvedResult.engine
                    recordResolvedStreamExpiry(song.id, resolvedResult.url, resolvedResult.expiresAtMs)
                    return@withTimeoutOrNull song.copy(contentUriString = resolvedResult.url)
                }
            }

            val excludedIds = if (host == "youtube" && path.isNotBlank()) setOf(path) else emptySet()
            val matched = streamManager.resolveTrackStreamForMetadata(
                targetTitle = metaTitle,
                targetArtist = metaArtist,
                expectedDurationMs = metaDur,
                preferredEngine = effectiveEngine,
                allowPreviewFallback = false,
                excludedVideoIds = excludedIds
            )
            if (matched != null) {
                _activeStreamEngine.value = matched.third
                val exp = streamManager.getCachedStreamExpiryMs(null, matched.first)
                recordResolvedStreamExpiry(song.id, matched.first, exp)
                return@withTimeoutOrNull song.copy(
                    contentUriString = matched.first,
                    durationMs = if (song.durationMs <= 0L) matched.second else song.durationMs
                )
            }
            song
        }
        resolved ?: song
    }

    @OptIn(UnstableApi::class)
    private fun extractPlayerErrorDetails(error: PlaybackException): Pair<String, String> {
        var cause: Throwable? = error.cause
        var httpCode: Int? = null
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                httpCode = cause.responseCode
                break
            }
            cause = cause.cause
        }
        val codeLabel = if (httpCode != null) "HTTP $httpCode" else error.errorCodeName
        val rawMsg = error.cause?.localizedMessage ?: error.localizedMessage ?: ""
        val isBlock = com.example.online.YouTubeStreamManager.isDeprecationOrAppBlockMessage(rawMsg)
        val detail = when {
            isBlock -> "Клиент YouTube отклонил воспроизведение на этом устройстве — выберите другой движок или источник"
            httpCode == 403 -> "Сервер отклонил доступ (403 Forbidden / подпись ссылки устарела)"
            httpCode == 404 -> "Аудиофайл не найден на сервере (404 Not Found)"
            httpCode == 410 -> "Срок действия ссылки на поток истёк (410 Gone)"
            httpCode == 429 -> "Слишком много запросов к источнику (429 Too Many Requests)"
            httpCode in listOf(500, 502, 503) -> "Удалённый сервер источника перегружен (HTTP $httpCode)"
            else -> rawMsg.ifBlank { "Сбой сетевого потока" }
        }
        return codeLabel to detail
    }

    private suspend fun publishDiagnosticFailure(
        song: Song,
        errorCodeLabel: String,
        summary: String
    ) {
        val app = context.applicationContext as? com.example.MusicApplication
        val streamManager = app?.youTubeStreamManager
        val alternatives = runCatching {
            streamManager?.findRelevantAlternatives(song.title, song.artist, song.durationMs) ?: emptyList()
        }.getOrDefault(emptyList())
        val logs = com.example.online.SourceDiagnosticsLog.recent(18)
        _diagnosticReport.value = com.example.online.PlaybackDiagnosticReport(
            trackTitle = song.title,
            trackArtist = song.artist,
            errorCodeLabel = errorCodeLabel,
            summary = summary,
            attempts = logs,
            suggestedAlternatives = alternatives,
            canAutoRetryOtherSources = true
        )
    }

    private fun resolveAndPlayIndex(
        index: Int,
        song: Song,
        resetRetryCount: Boolean = true,
        autoAdvanceOnFailure: Boolean = false,
        forceRefresh: Boolean = false
    ) {
        val controller = mediaController ?: return
        resolveJob?.cancel()
        isResolvingOnline = true
        resolvingKey = index to song.id
        _playbackState.value = _playbackState.value.copy(isBuffering = true, errorMessage = null)
        val diagEpoch = com.example.online.SourceDiagnosticsLog.latestSeqId()
        resolveJob = scope.launch {
            try {
                val resolvedSong = resolveOnlineUriIfNeeded(song, forceRefresh = forceRefresh)
                val failedResolve = resolvedSong.contentUriString.startsWith("online://") ||
                    (forceRefresh && resolvedSong.contentUriString == song.contentUriString && isTrackStreamNeedsRefresh(resolvedSong))
                if (failedResolve) {
                    isResolvingOnline = false
                    resolvingKey = null
                    val recentSince = com.example.online.SourceDiagnosticsLog.recentSince(diagEpoch)
                    val lastCode = com.example.online.SourceDiagnosticsLog.resolveDiagnosticErrorCode(recentSince)
                    val summary = "Все зеркала не выдали рабочий поток для «${song.title}». Подберите альтернативу ниже."
                    val queueSize = synchronized(queueLock) { currentQueueSongs.size }
                    val canSkipBroken = PlaybackTransitionPolicy.shouldAdvanceQueueAfterFailure(
                        controller.repeatMode,
                        controller.hasNextMediaItem(),
                        queueSize
                    ) && (!resetRetryCount || autoAdvanceOnFailure) && consecutiveAutoSkipCount < minOf(queueSize, 5)
                    if (canSkipBroken) {
                        consecutiveAutoSkipCount++
                        advancePastBrokenTrack(controller, song, lastCode, summary)
                    } else {
                        consecutiveAutoSkipCount = 0
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "$summary ($lastCode)"
                        )
                        publishDiagnosticFailure(song, lastCode, summary)
                    }
                    return@launch
                }
                if (resetRetryCount) {
                    onlineRetryCounts[song.id] = 0
                }
                consecutiveAutoSkipCount = 0
                isResolvingOnline = false
                resolvingKey = null
                val updatedMatch = synchronized(queueLock) {
                    if (index in currentQueueSongs.indices && currentQueueSongs[index].id == song.id) {
                        currentQueueSongs[index] = resolvedSong
                        val oIdx = originalQueue.indexOfFirst { it.id == song.id }
                        if (oIdx in originalQueue.indices) originalQueue[oIdx] = resolvedSong
                        true
                    } else {
                        false
                    }
                }
                if (updatedMatch && safeReplaceMediaItem(controller, index, songToMediaItem(resolvedSong))) {
                    safeSeekAndPlay(controller, index, 0L)
                    updatePlaybackState()
                    preresolveUpcomingQueue(index)
                }
            } catch (e: CancellationException) {
                isResolvingOnline = false
                resolvingKey = null
                throw e
            } catch (t: Throwable) {
                isResolvingOnline = false
                resolvingKey = null
                val msg = t.localizedMessage ?: "Сетевая ошибка"
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Ошибка потока ($msg)"
                )
                publishDiagnosticFailure(song, "STREAM_EXCEPTION", msg)
            }
        }
    }

    private fun advancePastBrokenTrack(
        controller: MediaController,
        failedSong: Song,
        errorCode: String,
        summary: String
    ) {
        val nextIndex = controller.nextMediaItemIndex
        val targetIdx = if (nextIndex != C.INDEX_UNSET) {
            nextIndex
        } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 1) {
            0
        } else {
            C.INDEX_UNSET
        }
        if (targetIdx != C.INDEX_UNSET && targetIdx != controller.currentMediaItemIndex) {
            Log.w("MusicControllerManager", "Advancing past broken track '${failedSong.title}' ($errorCode) to index=$targetIdx")
            _playbackState.value = _playbackState.value.copy(
                isBuffering = true,
                errorMessage = "Пропущен недоступный трек «${failedSong.title}» ($errorCode)"
            )
            if (safeSeekAndPlay(controller, targetIdx, 0L)) {
                updatePlaybackState()
                return
            }
        }
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            isBuffering = false,
            errorMessage = "Ошибка ($errorCode): $summary"
        )
        scope.launch {
            publishDiagnosticFailure(failedSong, errorCode, summary)
        }
    }

    private fun recoverOnlineStreamError(
        index: Int,
        song: Song,
        lastErrorCode: String = "STREAM_ERROR",
        lastErrorSummary: String = "Источник недоступен",
        resumeAtMs: Long = 0L
    ) {
        val controller = mediaController ?: return
        val attempts = onlineRetryCounts[song.id] ?: 0
        if (attempts >= 3) {
            onlineRetryCounts[song.id] = 0
            failedVideoIdsBySongId.remove(song.id)
            isResolvingOnline = false
            resolvingKey = null
            val queueSize = synchronized(queueLock) { currentQueueSongs.size }
            if (PlaybackTransitionPolicy.shouldAdvanceQueueAfterFailure(
                    controller.repeatMode,
                    controller.hasNextMediaItem(),
                    queueSize
                )
            ) {
                advancePastBrokenTrack(controller, song, lastErrorCode, lastErrorSummary)
                return
            }
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                isBuffering = false,
                errorMessage = "Источник временно недоступен ($lastErrorCode): $lastErrorSummary"
            )
            scope.launch {
                publishDiagnosticFailure(song, lastErrorCode, lastErrorSummary)
            }
            return
        }
        onlineRetryCounts[song.id] = attempts + 1
        resolveJob?.cancel()
        isResolvingOnline = true
        resolvingKey = index to song.id
        _playbackState.value = _playbackState.value.copy(isBuffering = true, errorMessage = null)
        resolveJob = scope.launch {
            try {
                if (song.contentUriString.startsWith("online://")) {
                    resolveAndPlayIndex(index, song, resetRetryCount = false)
                    return@launch
                }
                val originalOnlineUri = onlineSourceBySongId[song.id]
                val parsedVideoId = originalOnlineUri?.let { raw ->
                    val u = Uri.parse(raw)
                    if (u.host == "youtube") u.path?.removePrefix("/") else null
                }
                val excludedIds = failedVideoIdsBySongId.getOrPut(song.id) {
                    java.util.Collections.synchronizedSet(mutableSetOf())
                }
                if (!parsedVideoId.isNullOrBlank() && attempts >= 1) {
                    excludedIds.add(parsedVideoId)
                }

                val app = context.applicationContext as? com.example.MusicApplication
                val streamManager = app?.youTubeStreamManager
                val alternate = streamManager?.resolveAlternateStream(
                    failedUrl = song.contentUriString,
                    videoId = parsedVideoId,
                    title = song.title,
                    artist = song.artist,
                    expectedDurationMs = song.durationMs,
                    excludedVideoIds = excludedIds.toSet()
                )
                isResolvingOnline = false
                resolvingKey = null
                val updated = synchronized(queueLock) {
                    if (alternate != null && index in currentQueueSongs.indices && currentQueueSongs[index].id == song.id) {
                        val nextSong = song.copy(contentUriString = alternate.url)
                        currentQueueSongs[index] = nextSong
                        val oIdx = originalQueue.indexOfFirst { it.id == song.id }
                        if (oIdx in originalQueue.indices) originalQueue[oIdx] = nextSong
                        nextSong
                    } else {
                        null
                    }
                }
                if (alternate != null && updated != null && safeReplaceMediaItem(controller, index, songToMediaItem(updated))) {
                    _activeStreamEngine.value = alternate.engine
                    recordResolvedStreamExpiry(song.id, alternate.url, alternate.expiresAtMs)
                    consecutiveAutoSkipCount = 0
                    safeSeekAndPlay(controller, index, resumeAtMs.coerceAtLeast(0L))
                    updatePlaybackState()
                } else {
                    val queueSize = synchronized(queueLock) { currentQueueSongs.size }
                    if (PlaybackTransitionPolicy.shouldAdvanceQueueAfterFailure(
                            controller.repeatMode,
                            controller.hasNextMediaItem(),
                            queueSize
                        ) && consecutiveAutoSkipCount < minOf(queueSize, 5)
                    ) {
                        consecutiveAutoSkipCount++
                        advancePastBrokenTrack(controller, song, lastErrorCode, lastErrorSummary)
                    } else {
                        consecutiveAutoSkipCount = 0
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "Ошибка ($lastErrorCode): не удалось подобрать зеркало для «${song.title}»"
                        )
                        publishDiagnosticFailure(song, lastErrorCode, lastErrorSummary)
                    }
                }
            } catch (e: CancellationException) {
                isResolvingOnline = false
                resolvingKey = null
                throw e
            } catch (t: Throwable) {
                isResolvingOnline = false
                resolvingKey = null
                val msg = t.localizedMessage ?: lastErrorSummary
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "Сбой загрузки ($lastErrorCode): $msg"
                )
                publishDiagnosticFailure(song, lastErrorCode, msg)
            }
        }
    }

    private fun preresolveUpcomingQueue(currentIndex: Int) {
        val controller = mediaController ?: return
        preResolveJob?.cancel()
        val nextShuffleIdx = controller.nextMediaItemIndex
        preResolveJob = scope.launch {
            try {
                val queueCopy = synchronized(queueLock) { currentQueueSongs.toList() }
                val indicesToCheck = PlaybackTransitionPolicy.computePreresolveIndices(
                    currentIndex = currentIndex,
                    nextMediaItemIndex = nextShuffleIdx,
                    queueSize = queueCopy.size
                )
                if (indicesToCheck.isEmpty()) return@launch

                // Pre-cache upcoming album artworks into Coil so swipes reveal art instantaneously
                val imageLoader = coil.Coil.imageLoader(context)
                for (idx in indicesToCheck) {
                    val artUri = queueCopy[idx].albumArtUriString
                    if (!artUri.isNullOrBlank()) {
                        runCatching {
                            imageLoader.enqueue(
                                coil.request.ImageRequest.Builder(context)
                                    .data(artUri)
                                    .size(320)
                                    .build()
                            )
                        }
                    }
                }

                // Parallel lookahead resolution for next 2 tracks
                val resolvedPairs = coroutineScope {
                    indicesToCheck.map { idx ->
                        async {
                            val candidate = queueCopy[idx]
                            val needsRefresh = isTrackStreamNeedsRefresh(candidate)
                            if (candidate.contentUriString.startsWith("online://") || needsRefresh) {
                                val force = !candidate.contentUriString.startsWith("online://")
                                val resolved = resolveOnlineUriIfNeeded(candidate, forceRefresh = force)
                                if (!resolved.contentUriString.startsWith("online://") &&
                                    (!force || resolved.contentUriString != candidate.contentUriString)
                                ) {
                                    Triple(idx, candidate.id, resolved)
                                } else null
                            } else {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for ((idx, expectedId, resolved) in resolvedPairs) {
                    if (!isActive) break
                    val updatedInQueue = synchronized(queueLock) {
                        if (idx in currentQueueSongs.indices && currentQueueSongs[idx].id == expectedId) {
                            currentQueueSongs[idx] = resolved
                            val oIdx = originalQueue.indexOfFirst { it.id == expectedId }
                            if (oIdx in originalQueue.indices) originalQueue[oIdx] = resolved
                            true
                        } else {
                            false
                        }
                    }
                    if (updatedInQueue) {
                        safeReplaceMediaItem(controller, idx, songToMediaItem(resolved))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("MusicControllerManager", "Queue pre-resolve skipped due to error", t)
            }
        }
    }

    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        resolveJob?.cancel()
        preResolveJob?.cancel()
        isResolvingOnline = false
        resolvingKey = null
        isCrossfading = false
        _abPointA.value = null
        _abPointB.value = null
        lastActiveSongId = song.id

        synchronized(queueLock) {
            originalQueue = queue.toMutableList()
            currentQueueSongs.clear()
            currentQueueSongs.addAll(queue)
        }

        onlineSourceBySongId.remove(song.id)
        failedVideoIdsBySongId.remove(song.id)
        onlineRetryCounts[song.id] = 0
        earlyEofRecoveryCounts[song.id] = 0
        consecutiveAutoSkipCount = 0
        queue.forEach { qSong ->
            if (qSong.contentUriString.startsWith("online://")) {
                onlineSourceBySongId[qSong.id] = qSong.contentUriString
            }
        }
        if (song.contentUriString.startsWith("online://")) {
            onlineSourceBySongId[song.id] = song.contentUriString
            val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            isResolvingOnline = true
            resolvingKey = idx to song.id
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
            val diagEpoch = com.example.online.SourceDiagnosticsLog.latestSeqId()

            resolveJob = scope.launch {
                try {
                    val resolvedSong = resolveOnlineUriIfNeeded(song)
                    if (resolvedSong.contentUriString.startsWith("online://")) {
                        isResolvingOnline = false
                        resolvingKey = null
                        val recentSince = com.example.online.SourceDiagnosticsLog.recentSince(diagEpoch)
                        val lastCode = com.example.online.SourceDiagnosticsLog.resolveDiagnosticErrorCode(recentSince)
                        val summary = "Источники не вернули аудиопоток для «${song.title}». Выберите другой источник."
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "$summary ($lastCode)"
                        )
                        publishDiagnosticFailure(song, lastCode, summary)
                        return@launch
                    }

                    isResolvingOnline = false
                    resolvingKey = null
                    onlineRetryCounts[song.id] = 0
                    val resolvedQueue = synchronized(queueLock) {
                        val mapped = queue.map { if (it.id == song.id) resolvedSong else it }
                        currentQueueSongs.clear()
                        currentQueueSongs.addAll(mapped)
                        originalQueue = mapped.toMutableList()
                        mapped
                    }

                    val startIndex = resolvedQueue.indexOfFirst { it.id == resolvedSong.id }.coerceAtLeast(0)
                    val mediaItems = resolvedQueue.map { songToMediaItem(it) }

                    controller.setMediaItems(mediaItems, startIndex, 0L)
                    controller.prepare()
                    controller.volume = preferencesManager.playerVolume.value
                    controller.play()
                    updatePlaybackState()
                    pluginManager?.dispatchQueueChanged(resolvedQueue)
                    preresolveUpcomingQueue(startIndex)
                } catch (e: CancellationException) {
                    isResolvingOnline = false
                    resolvingKey = null
                    throw e
                } catch (t: Throwable) {
                    isResolvingOnline = false
                    resolvingKey = null
                    val msg = t.localizedMessage ?: "Не удалось запустить поток"
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "Ошибка: $msg"
                    )
                    publishDiagnosticFailure(song, "PLAYBACK_INIT_ERROR", msg)
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
        pluginManager?.dispatchQueueChanged(queue)
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
        _abPointA.value = null
        _abPointB.value = null
        lastActiveSongId = firstSong.id
        earlyEofRecoveryCounts[firstSong.id] = 0
        onlineRetryCounts[firstSong.id] = 0
        synchronized(queueLock) {
            originalQueue = queue.toMutableList()
            currentQueueSongs.clear()
            currentQueueSongs.addAll(queue)
        }

        val mediaItems = queue.map { songToMediaItem(it) }
        controller.setMediaItems(mediaItems, safeIdx, 0L)
        controller.prepare()
        controller.volume = preferencesManager.playerVolume.value
        controller.play()
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(queue)
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
            if (activeSong != null && (activeSong.contentUriString.startsWith("online://") || isTrackStreamNeedsRefresh(activeSong))) {
                _playbackState.value = _playbackState.value.copy(isBuffering = true)
                resolveAndPlayIndex(
                    index = activeIdx,
                    song = activeSong,
                    forceRefresh = !activeSong.contentUriString.startsWith("online://")
                )
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
        getService()?.cancelCrossfade()
        isCrossfading = false
        val autoDjEnabled = preferencesManager.autoDjEnabled.value
        val crossfadeEnabled = preferencesManager.crossfadeEnabled.value || autoDjEnabled
        val crossfadeDurationSec = if (autoDjEnabled && !preferencesManager.crossfadeEnabled.value) {
            2.2f
        } else {
            preferencesManager.crossfadeDuration.value
        }
        val crossfadeMs = (crossfadeDurationSec * 1000).toLong()

        val nextIndex = controller.nextMediaItemIndex
        val targetIdx = if (nextIndex != C.INDEX_UNSET) {
            nextIndex
        } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && controller.mediaItemCount > 0) {
            0
        } else {
            C.INDEX_UNSET
        }

        if (targetIdx != C.INDEX_UNSET && targetIdx in 0 until controller.mediaItemCount) {
            val targetSong = synchronized(queueLock) { currentQueueSongs.getOrNull(targetIdx) }
            val service = getService()
            if (crossfadeEnabled && crossfadeMs > 0 && controller.isPlaying && service != null &&
                targetSong != null && !isTrackStreamNeedsRefresh(targetSong)
            ) {
                service.crossfadeTo(targetIdx, crossfadeMs)
            } else {
                safeSeekAndPlay(controller, targetIdx, 0L)
            }
        }
        updatePlaybackState()
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        isCrossfading = false
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

            if (targetIdx != C.INDEX_UNSET && targetIdx in 0 until controller.mediaItemCount) {
                val targetSong = synchronized(queueLock) { currentQueueSongs.getOrNull(targetIdx) }
                val autoDjEnabled = preferencesManager.autoDjEnabled.value
                val crossfadeEnabled = preferencesManager.crossfadeEnabled.value || autoDjEnabled
                val crossfadeDurationSec = if (autoDjEnabled && !preferencesManager.crossfadeEnabled.value) {
                    2.2f
                } else {
                    preferencesManager.crossfadeDuration.value
                }
                val crossfadeMs = (crossfadeDurationSec * 1000).toLong()
                val service = getService()
                if (crossfadeEnabled && crossfadeMs > 0 && controller.isPlaying && service != null &&
                    targetSong != null && !isTrackStreamNeedsRefresh(targetSong)
                ) {
                    service.crossfadeTo(targetIdx, crossfadeMs)
                } else {
                    safeSeekAndPlay(controller, targetIdx, 0L)
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

    fun getQueue(): List<Song> = synchronized(queueLock) { currentQueueSongs.toList() }

    fun addToQueue(song: Song) {
        val controller = mediaController ?: return
        if (song.contentUriString.startsWith("online://")) {
            scope.launch {
                val resolved = resolveOnlineUriIfNeeded(song)
                val snapshot = synchronized(queueLock) {
                    currentQueueSongs.add(resolved)
                    originalQueue.add(resolved)
                    currentQueueSongs.toList()
                }
                controller.addMediaItem(songToMediaItem(resolved))
                updatePlaybackState()
                pluginManager?.dispatchQueueChanged(snapshot)
            }
            return
        }
        val snapshot = synchronized(queueLock) {
            currentQueueSongs.add(song)
            originalQueue.add(song)
            currentQueueSongs.toList()
        }
        controller.addMediaItem(songToMediaItem(song))
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(snapshot)
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        val snapshot = synchronized(queueLock) {
            if (index !in currentQueueSongs.indices) return
            val removedSong = currentQueueSongs.removeAt(index)
            originalQueue.removeAll { it.id == removedSong.id }
            currentQueueSongs.toList()
        }
        if (index in 0 until controller.mediaItemCount) {
            runCatching { controller.removeMediaItem(index) }
        }
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(snapshot)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        val snapshot = synchronized(queueLock) {
            if (fromIndex !in currentQueueSongs.indices || toIndex !in currentQueueSongs.indices) return
            val item = currentQueueSongs.removeAt(fromIndex)
            currentQueueSongs.add(toIndex, item)
            currentQueueSongs.toList()
        }
        if (fromIndex in 0 until controller.mediaItemCount && toIndex in 0 until controller.mediaItemCount) {
            runCatching { controller.moveMediaItem(fromIndex, toIndex) }
        }
        updatePlaybackState()
        pluginManager?.dispatchQueueChanged(snapshot)
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        moveQueueItem(fromIndex, toIndex)
    }

    fun playQueueIndex(index: Int) {
        val controller = mediaController ?: return
        val targetSong = synchronized(queueLock) { currentQueueSongs.getOrNull(index) } ?: return
        getService()?.cancelCrossfade()
        isCrossfading = false
        earlyEofRecoveryCounts[targetSong.id] = 0
        onlineRetryCounts[targetSong.id] = 0
        _abPointA.value = null
        _abPointB.value = null
        lastActiveSongId = targetSong.id
        consecutiveAutoSkipCount = 0
        if (targetSong.contentUriString.startsWith("online://") || isTrackStreamNeedsRefresh(targetSong)) {
            _playbackState.value = _playbackState.value.copy(
                currentSong = targetSong,
                currentQueueIndex = index,
                currentPositionMs = 0L,
                durationMs = targetSong.durationMs,
                errorMessage = null
            )
            resolveAndPlayIndex(
                index = index,
                song = targetSong,
                forceRefresh = !targetSong.contentUriString.startsWith("online://")
            )
            return
        }
        safeSeekAndPlay(controller, index, 0L)
        updatePlaybackState()
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        getService()?.cancelCrossfade()
        isCrossfading = false
        synchronized(queueLock) {
            currentQueueSongs.clear()
            originalQueue.clear()
        }
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
