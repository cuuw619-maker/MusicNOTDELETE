package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
}
