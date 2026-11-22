package com.example

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.online.PlaybackDiagnosticReport
import com.example.online.SourceAttemptLog
import com.example.online.SourceAttemptStatus
import com.example.ui.dialogs.ErrorDiagnosticsDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposeUiRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun errorDiagnosticsDialog_rendersExpandedAttemptLogWithoutNestedScrollCrash() {
        val report = PlaybackDiagnosticReport(
            trackTitle = "Night Call",
            trackArtist = "Kavinsky",
            errorCodeLabel = "SABR_ONLY",
            summary = "YouTube вернул только SABR-поток без прямого аудио.",
            attempts = (1..8).map { idx ->
                SourceAttemptLog(
                    sourceId = "youtube",
                    displayName = "YouTube #$idx",
                    stage = "innertube_TVHTML5",
                    status = SourceAttemptStatus.SABR_ONLY,
                    httpCode = 200,
                    detail = "Попытка #$idx не дала прямого аудио",
                    latencyMs = 45L * idx
                )
            }
        )

        composeTestRule.setContent {
            MaterialTheme {
                ErrorDiagnosticsDialog(
                    report = report,
                    sourceHealthStatuses = emptyList(),
                    isCheckingHealth = false,
                    onRunHealthCheck = {},
                    onRetryPlayback = {},
                    onOpenSourceSwitcher = {},
                    onSelectAlternativeTrack = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("error_diagnostics_dialog"))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(hasText("SABR_ONLY"))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun equalizerSheet_rendersTenBandsPreAmpAndLimiterControls() {
        val bands = com.example.playback.EqualizerManager.TEN_BAND_CENTER_FREQS_HZ.mapIndexed { idx, freq ->
            com.example.model.EqualizerBand(
                bandIndex = idx.toShort(),
                centerFreqHz = freq,
                minLevelMilliBels = -1500,
                maxLevelMilliBels = 1500,
                currentLevelMilliBels = if (idx == 0) 400 else 0
            )
        }
        val state = com.example.model.EqualizerState(
            isAvailable = true,
            isEnabled = true,
            bands = bands,
            preAmpDb = 3.5f,
            limiterEnabled = true,
            isDynamicsProcessing = true,
            presets = com.example.playback.EqualizerManager.STANDARD_PRESETS,
            currentPresetIndex = 0
        )

        composeTestRule.setContent {
            MaterialTheme {
                com.example.ui.equalizer.EqualizerSheet(
                    equalizerState = state,
                    onToggleEnabled = {},
                    onBandLevelChange = { _, _ -> },
                    onBassBoostChange = {},
                    onVirtualizerChange = {},
                    onSelectPreset = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("equalizer_bottom_sheet"))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(hasText("10-полосный DynamicsProcessing DSP"))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("equalizer_preamp_slider"))
                .fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("equalizer_limiter_switch"))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun expandingPlayerContainer_collapsedMiniPlayerIsVisibleWithNonZeroBoundsAndSpectrum() {
        val sampleSongs = listOf(
            com.example.model.Song(
                id = 101L,
                title = "Starlight",
                artist = "Muse",
                album = "Black Holes",
                albumId = 1001L,
                durationMs = 240_000L,
                contentUriString = ""
            ),
            com.example.model.Song(
                id = 102L,
                title = "Time",
                artist = "Pink Floyd",
                album = "DSOTM",
                albumId = 1002L,
                durationMs = 300_000L,
                contentUriString = ""
            )
        )
        val state = com.example.model.PlaybackState(
            currentSong = sampleSongs[0],
            isPlaying = true,
            currentPositionMs = 45_000L,
            durationMs = 240_000L,
            queue = sampleSongs,
            currentQueueIndex = 0
        )

        composeTestRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(
                        width = androidx.compose.ui.unit.Dp(400f),
                        height = androidx.compose.ui.unit.Dp(800f)
                    )
                ) {
                    com.example.ui.player.ExpandingPlayerContainer(
                        isExpanded = false,
                        onExpandedChange = {},
                        playbackState = state,
                        onPlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onSeekTo = {},
                        onRewind10 = {},
                        onForward10 = {},
                        onToggleShuffle = {},
                        onCycleRepeatMode = {},
                        onToggleFavorite = {},
                        onOpenQueue = {},
                        onOpenEqualizer = {},
                        onAddToPlaylist = {},
                        onOpenSleepTimer = {},
                        audioReactiveShadersEnabled = true,
                        miniSpectrumMode = "BARS"
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val miniNodes = composeTestRule.onAllNodes(hasTestTag("mini_player")).fetchSemanticsNodes()
        assertTrue("Collapsed mini_player must be visible", miniNodes.isNotEmpty())
        val bounds = miniNodes.first().boundsInRoot
        assertTrue("Mini player height must not collapse to 0 (was ${bounds.height})", bounds.height > 40f)
        assertTrue("Mini player width must be substantial (was ${bounds.width})", bounds.width > 180f)

        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("mini_player_live_spectrum"))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun nowPlayingScreen_rendersAudioReactiveShadersAndAutoDjToggleWithoutCrash() {
        val sampleSong = com.example.model.Song(
            id = 202L,
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up",
            albumId = 2002L,
            durationMs = 243_000L,
            contentUriString = ""
        )
        val state = com.example.model.PlaybackState(
            currentSong = sampleSong,
            isPlaying = true,
            currentPositionMs = 30_000L,
            durationMs = 243_000L,
            queue = listOf(sampleSong),
            currentQueueIndex = 0
        )

        var autoDjToggled = false
        composeTestRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(
                        width = androidx.compose.ui.unit.Dp(412f),
                        height = androidx.compose.ui.unit.Dp(915f)
                    )
                ) {
                    com.example.ui.player.NowPlayingScreen(
                        playbackState = state,
                        onCollapse = {},
                        onPlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onSeekTo = {},
                        onRewind10 = {},
                        onForward10 = {},
                        onToggleShuffle = {},
                        onCycleRepeatMode = {},
                        onToggleFavorite = {},
                        onOpenQueue = {},
                        onOpenEqualizer = {},
                        onAddToPlaylist = {},
                        onOpenSleepTimer = {},
                        audioReactiveShadersEnabled = true,
                        autoDjEnabled = false,
                        onToggleAutoDj = { autoDjToggled = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("now_playing_artwork_stage"))
                .fetchSemanticsNodes().isNotEmpty()
        )
        val chipNodes = composeTestRule.onAllNodes(hasTestTag("now_playing_autodj_chip"))
            .fetchSemanticsNodes()
        assertTrue("Auto-DJ chip should be rendered", chipNodes.isNotEmpty())
        composeTestRule.onNode(hasTestTag("now_playing_autodj_chip"))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertTrue("Clicking Auto-DJ chip should invoke callback", autoDjToggled)
    }

    @Test
    fun expandingPlayerContainer_expandsToNowPlayingAndRendersAmbientGeometryWithoutHang() {
        val sampleSong = com.example.model.Song(
            id = 303L,
            title = "Veridis Quo",
            artist = "Daft Punk",
            album = "Discovery",
            albumId = 3003L,
            durationMs = 345_000L,
            contentUriString = ""
        )
        val state = com.example.model.PlaybackState(
            currentSong = sampleSong,
            isPlaying = true,
            currentPositionMs = 42_000L,
            durationMs = 345_000L,
            queue = listOf(sampleSong),
            currentQueueIndex = 0
        )

        var expanded by androidx.compose.runtime.mutableStateOf(false)

        composeTestRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.size(
                        width = androidx.compose.ui.unit.Dp(412f),
                        height = androidx.compose.ui.unit.Dp(915f)
                    )
                ) {
                    com.example.ui.components.AmbientGeometricShapesOverlay(
                        isPlaying = true,
                        reducedMotion = false,
                        alphaMultiplier = 0.8f
                    )
                    com.example.ui.player.ExpandingPlayerContainer(
                        isExpanded = expanded,
                        onExpandedChange = { expanded = it },
                        playbackState = state,
                        onPlayPause = {},
                        onSkipNext = {},
                        onSkipPrevious = {},
                        onSeekTo = {},
                        onRewind10 = {},
                        onForward10 = {},
                        onToggleShuffle = {},
                        onCycleRepeatMode = {},
                        onToggleFavorite = {},
                        onOpenQueue = {},
                        onOpenEqualizer = {},
                        onAddToPlaylist = {},
                        onOpenSleepTimer = {},
                        audioReactiveShadersEnabled = true,
                        miniSpectrumMode = "BARS"
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(
            "Collapsed mini_player should initially be visible",
            composeTestRule.onAllNodes(hasTestTag("mini_player")).fetchSemanticsNodes().isNotEmpty()
        )

        composeTestRule.onNode(hasTestTag("mini_player")).performClick()
        composeTestRule.waitForIdle()

        assertTrue("Expanded flag must toggle to true on mini_player tap", expanded)
        val containerNodes = composeTestRule.onAllNodes(hasTestTag("now_playing_container"))
            .fetchSemanticsNodes()
        assertTrue("now_playing_container must be visible after expansion", containerNodes.isNotEmpty())
        assertTrue(
            "now_playing_container bounds height must be > 400",
            containerNodes.first().boundsInRoot.height > 400f
        )
        assertTrue(
            composeTestRule.onAllNodes(hasTestTag("now_playing_artwork_stage"))
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
