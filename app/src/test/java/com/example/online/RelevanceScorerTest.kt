package com.example.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceScorerTest {

    @Test
    fun exactRussianTrackAndArtistAreAccepted() {
        val candidate = OnlineTrack(
            id = "video-1",
            title = "Привет, я Алишер (Official Audio)",
            artist = "MORGENSHTERN - Topic",
            durationSeconds = 185L
        )

        val result = RelevanceScorer.assess(
            candidate = candidate,
            targetTitle = "Привет я Алишер",
            targetArtist = "MORGENSHTERN",
            expectedDurationMs = 185_000L
        )

        assertTrue(result.isAcceptable)
        assertTrue("Expected a strong match score, got ${result.score}", result.score >= 90)
    }

    @Test
    fun sameArtistButDifferentSongIsRejected() {
        val candidate = OnlineTrack(
            id = "video-2",
            title = "OPA",
            artist = "MORGENSHTERN - Topic",
            durationSeconds = 180L
        )

        val result = RelevanceScorer.assess(
            candidate = candidate,
            targetTitle = "Привет я Алишер",
            targetArtist = "MORGENSHTERN",
            expectedDurationMs = 180_000L
        )

        assertFalse(result.isAcceptable)
    }

    @Test
    fun exactTitleByWrongArtistIsRejected() {
        val candidate = OnlineTrack(
            id = "video-3",
            title = "Привет я Алишер",
            artist = "Another Artist",
            durationSeconds = 185L
        )

        val result = RelevanceScorer.assess(
            candidate = candidate,
            targetTitle = "Привет я Алишер",
            targetArtist = "MORGENSHTERN",
            expectedDurationMs = 185_000L
        )

        assertFalse(result.isAcceptable)
    }

    @Test
    fun titlePunctuationDoesNotBreakMatching() {
        val candidate = OnlineTrack(
            id = "video-4",
            title = "Привет! Я — Алишер",
            artist = "MORGENSHTERN",
            durationSeconds = 185L
        )

        val result = RelevanceScorer.assess(
            candidate = candidate,
            targetTitle = "Привет я Алишер",
            targetArtist = "MORGENSHTERN",
            expectedDurationMs = 185_000L
        )

        assertTrue(result.isAcceptable)
    }
}
