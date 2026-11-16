package com.example.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.MusicApplication
import com.example.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MusicPlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_TOGGLE_SHUFFLE = "com.example.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.example.action.TOGGLE_REPEAT"
        var instance: MusicPlaybackService? = null
            private set
    }

    private var mediaSession: MediaSession? = null
    lateinit var player: ExoPlayer
        private set
    var crossfadePlayer: ExoPlayer? = null
        private set

    private var crossfadeJob: Job? = null
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MusicPlaybackService", "Unhandled service coroutine error", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + serviceExceptionHandler)
    @OptIn(UnstableApi::class)
    private var simpleCache: SimpleCache? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Studio Master 64-bit Audio DSP pipeline with float processing & 4x anti-crackle buffer headroom
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioTrackBufferSizeProvider { minBufferSizeInBytes, _, _, _, _, _, _ ->
                        // Quadruple buffer headroom (minimum 128KB) to completely eliminate buffer underruns,
                        // crackles, flutter, and "репение" even during deep time-stretching (0.5x speed)
                        (minBufferSizeInBytes * 4).coerceAtLeast(131072)
                    }
                    .build()
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30000,
                /* maxBufferMs = */ 60000,
                /* bufferForPlaybackMs = */ 2000,
                /* bufferForPlaybackAfterRebufferMs = */ 5000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val app = applicationContext as? MusicApplication
        val streamManager = app?.youTubeStreamManager

        // Header-aware DataSource so YouTube/SoundCloud/CDN URLs receive matching User-Agent & Referer
        val httpBaseFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(com.example.network.NetworkClient.BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val headerAwareDataSourceFactory = DataSource.Factory {
            val delegate = DefaultDataSource.Factory(this, httpBaseFactory).createDataSource()
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {
                    delegate.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val urlStr = dataSpec.uri.toString()
                    val headerMeta = streamManager?.getHeadersForStreamUrl(urlStr)
                    val modifiedSpec = if (headerMeta != null) {
                        val mergedHeaders = HashMap<String, String>(dataSpec.httpRequestHeaders)
                        mergedHeaders["User-Agent"] = headerMeta.first
                        mergedHeaders.putAll(headerMeta.second)
                        dataSpec.buildUpon().setHttpRequestHeaders(mergedHeaders).build()
                    } else {
                        dataSpec
                    }
                    return delegate.open(modifiedSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length)

                override fun getUri() = delegate.uri

                override fun getResponseHeaders() = delegate.responseHeaders

                override fun close() = delegate.close()
            }
        }

        val cacheDataSourceFactory: DataSource.Factory = try {
            val cacheDir = File(cacheDir, "exo_audio_cache")
            val perfMode = app?.preferencesManager?.performanceMode?.value
                ?: com.example.data.preferences.PreferencesManager.PerformanceMode.AUTO
            val profile = com.example.ui.theme.resolvePerformanceProfile(this, perfMode)
            val cacheBytes = profile.exoCacheMb.toLong() * 1024L * 1024L
            val evictor = LeastRecentlyUsedCacheEvictor(cacheBytes)
            val dbProvider = StandaloneDatabaseProvider(this)
            val cache = SimpleCache(cacheDir, evictor, dbProvider).also { simpleCache = it }
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(headerAwareDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (_: Exception) {
            headerAwareDataSourceFactory
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(5))

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setLoadControl(loadControl)
            .build()

        val crossfadeRenderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioTrackBufferSizeProvider { minBufferSizeInBytes, _, _, _, _, _, _ ->
                        (minBufferSizeInBytes * 4).coerceAtLeast(131072)
                    }
                    .build()
            }
        }

        crossfadePlayer = ExoPlayer.Builder(this, crossfadeRenderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false)
            .setLoadControl(loadControl)
            .build()

        // Bind equalizer directly to player session
        if (app != null && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            app.equalizerManager.bindAudioSession(player.audioSessionId)
        }
        app?.musicControllerManager?.attachService(this)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    app?.equalizerManager?.bindAudioSession(audioSessionId)
                }
            }
        })

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Custom commands for notification & lockscreen: Shuffle, Repeat
        // Using clean vector icons
        val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        val shuffleButton = CommandButton.Builder()
            .setDisplayName("Перемешать")
            .setIconResId(R.drawable.ic_shuffle_custom)
            .setSessionCommand(shuffleCommand)
            .build()

        val repeatCommand = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        val repeatButton = CommandButton.Builder()
            .setDisplayName("Повтор")
            .setIconResId(R.drawable.ic_repeat_custom)
            .setSessionCommand(repeatCommand)
            .build()

        // Exact layout: Shuffle, Repeat + MediaSession handles Previous, Play/Pause, Next
        val customLayout = listOf(shuffleButton, repeatButton)

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(shuffleCommand)
                    .add(repeatCommand)
                    .build()

                val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_SET_REPEAT_MODE)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setAvailablePlayerCommands(availablePlayerCommands)
                    .setCustomLayout(customLayout)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val appInst = applicationContext as? MusicApplication
                when (customCommand.customAction) {
                    ACTION_TOGGLE_SHUFFLE -> {
                        appInst?.musicControllerManager?.toggleShuffle()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    ACTION_TOGGLE_REPEAT -> {
                        appInst?.musicControllerManager?.cycleRepeatMode()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCallback(sessionCallback)
            .setCustomLayout(customLayout)
            .build()

        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showWhenCompact: Boolean
            ): ImmutableList<CommandButton> {
                val prevBtn = CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setIconResId(R.drawable.ic_notification_prev)
                    .setDisplayName("Предыдущий")
                    .build()

                val nextBtn = CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setIconResId(R.drawable.ic_notification_next)
                    .setDisplayName("Следующий")
                    .build()

                val playPauseBtn = CommandButton.Builder()
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setIconResId(
                        if (session.player.isPlaying) R.drawable.ic_notification_pause
                        else R.drawable.ic_notification_play
                    )
                    .setDisplayName(if (session.player.isPlaying) "Пауза" else "Воспроизведение")
                    .build()

                val shuffleBtn = customLayout.firstOrNull { it.sessionCommand?.customAction == ACTION_TOGGLE_SHUFFLE }
                    ?: shuffleButton

                val repeatBtn = customLayout.firstOrNull { it.sessionCommand?.customAction == ACTION_TOGGLE_REPEAT }
                    ?: repeatButton

                return if (showWhenCompact) {
                    ImmutableList.of(prevBtn, playPauseBtn, nextBtn)
                } else {
                    ImmutableList.of(prevBtn, shuffleBtn, playPauseBtn, nextBtn, repeatBtn)
                }
            }
        }
        setMediaNotificationProvider(notificationProvider)
    }

    fun crossfadeTo(targetIndex: Int, crossfadeMs: Long) {
        if (targetIndex !in 0 until player.mediaItemCount) return
        val currentItem = player.currentMediaItem
        val targetItem = player.getMediaItemAt(targetIndex)
        val targetScheme = targetItem.localConfiguration?.uri?.scheme
        val secPlayer = crossfadePlayer
        val savedPrimaryVol = player.volume.coerceIn(0.05f, 1.0f)

        crossfadeJob?.cancel()

        // Skip secondary player crossfade for unresolved online:// placeholders or when player isn't actively playing
        if (secPlayer == null || currentItem == null || crossfadeMs <= 0L || !player.isPlaying || targetScheme == "online") {
            player.volume = savedPrimaryVol
            player.seekTo(targetIndex, 0L)
            player.play()
            return
        }

        crossfadeJob = serviceScope.launch {
            try {
                secPlayer.stop()
                secPlayer.clearMediaItems()
                secPlayer.setMediaItem(targetItem, 0L)
                secPlayer.volume = 0f
                secPlayer.prepare()

                // Wait until secondary player is buffered & ready (bail cleanly if it errors or times out)
                var waitCount = 0
                while (isActive && secPlayer.playbackState == Player.STATE_BUFFERING && waitCount < 45) {
                    delay(15L)
                    waitCount++
                }

                if (!isActive || secPlayer.playbackState != Player.STATE_READY) {
                    secPlayer.stop()
                    secPlayer.clearMediaItems()
                    if (targetIndex in 0 until player.mediaItemCount) {
                        player.volume = savedPrimaryVol
                        player.seekTo(targetIndex, 0L)
                        player.play()
                    }
                    return@launch
                }

                secPlayer.play()

                val steps = 30
                val stepDelay = (crossfadeMs / steps).coerceAtLeast(10L)

                for (i in 1..steps) {
                    if (!isActive) break
                    val fraction = i / steps.toFloat()
                    val outVol = (kotlin.math.cos(fraction * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)) * savedPrimaryVol
                    val inVol = kotlin.math.sin(fraction * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f) * savedPrimaryVol

                    player.volume = outVol
                    secPlayer.volume = inVol
                    delay(stepDelay)
                }

                if (isActive && targetIndex in 0 until player.mediaItemCount) {
                    val handoffPos = secPlayer.currentPosition.coerceAtLeast(0L)
                    player.seekTo(targetIndex, handoffPos)
                    player.volume = savedPrimaryVol
                    player.play()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("MusicPlaybackService", "Crossfade fallback to direct seek", t)
                if (targetIndex in 0 until player.mediaItemCount) {
                    player.volume = savedPrimaryVol
                    player.seekTo(targetIndex, 0L)
                    player.play()
                }
            } finally {
                runCatching {
                    secPlayer.stop()
                    secPlayer.clearMediaItems()
                }
                if (player.volume < 0.05f) {
                    player.volume = savedPrimaryVol
                }
                crossfadeJob = null
            }
        }
    }

    fun cancelCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        crossfadePlayer?.stop()
        crossfadePlayer?.clearMediaItems()
        player.volume = 1.0f
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelCrossfade()
        val app = applicationContext as? MusicApplication
        app?.musicControllerManager?.detachService()
        if (instance === this) {
            instance = null
        }
        crossfadePlayer?.release()
        crossfadePlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        try {
            simpleCache?.release()
            simpleCache = null
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
