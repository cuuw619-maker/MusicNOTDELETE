package com.example

import com.example.lyrics.LyricSpeaker
import com.example.lyrics.LyricsManager
import com.example.online.OnlineTrack
import com.example.online.YouTubeStreamManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun parseLrc_supportsEnhancedWordTagsAndParallelTranslations() {
        val manager = LyricsManager()
        val lrc = """
            [00:10.00] <00:10.00>Hello <00:10.50>world // Привет мир
            [00:12.00] Second line
            [00:25.00] After instrumental break
        """.trimIndent()

        val lines = manager.parseLrc(lrc)
        assertTrue(lines.size >= 4)
        assertTrue(lines.first().isInstrumental)

        val firstLyric = lines.first { !it.isInstrumental }
        assertEquals(10_000L, firstLyric.timestampMs)
        assertEquals("Hello world", firstLyric.primaryText)
        assertEquals("Привет мир", firstLyric.secondaryText)
        assertTrue(firstLyric.hasWordTiming)
        assertEquals(2, firstLyric.words.size)
        assertEquals("Hello", firstLyric.words[0].text)
        assertEquals(10_000L, firstLyric.words[0].startTimeMs)
        assertEquals(10_500L, firstLyric.words[0].endTimeMs)

        val secondLyric = lines.first { it.primaryText == "Second line" }
        assertFalse(secondLyric.hasWordTiming)
        assertEquals(2, secondLyric.words.size)
        assertTrue(secondLyric.words[0].endTimeMs <= secondLyric.words[1].startTimeMs)

        val hasInterlude = lines.drop(1).any { it.isInstrumental }
        assertTrue(hasInterlude)
    }

    @Test
    fun parseLrc_preservesSimultaneousDuetLinesAndBackgroundTags() {
        val manager = LyricsManager()
        val lrc = """
            [00:08.00] v1: I hear you calling
            [00:08.00] v2: Holding on tonight
            [00:11.50] both: Together we rise
            [00:14.00] bg: (Ooh yeah)
        """.trimIndent()

        val lines = manager.parseLrc(lrc).filter { !it.isInstrumental }
        assertEquals(4, lines.size)

        assertEquals(LyricSpeaker.FIRST, lines[0].speaker)
        assertEquals("I hear you calling", lines[0].primaryText)
        assertEquals(8_000L, lines[0].timestampMs)

        assertEquals(LyricSpeaker.SECOND, lines[1].speaker)
        assertEquals("Holding on tonight", lines[1].primaryText)
        assertEquals(8_000L, lines[1].timestampMs)

        assertEquals(LyricSpeaker.BOTH, lines[2].speaker)
        assertEquals("Together we rise", lines[2].primaryText)

        assertEquals(LyricSpeaker.BACKGROUND, lines[3].speaker)
        assertTrue(lines[3].isBackground)
    }

    @Test
    fun parseLrc_autoDetectsTtmlWithDuetAgentsBackgroundAndTranslations() {
        val manager = LyricsManager()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="person"><ttm:name>Alex</ttm:name></ttm:agent>
                  <ttm:agent xml:id="v2" type="person"><ttm:name>Mia</ttm:name></ttm:agent>
                  <ttm:agent xml:id="all" type="group"><ttm:name>Duet</ttm:name></ttm:agent>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:05.000" end="00:09.000" ttm:agent="v1">
                    <span begin="00:05.000" end="00:06.200">First</span>
                    <span begin="00:06.200" end="00:07.800">voice</span>
                    <span ttm:role="x-bg">(echo)</span>
                    <span ttm:role="x-translation">Первый голос</span>
                  </p>
                  <p begin="00:06.000" end="00:09.500" ttm:agent="v2">Second voice overlap</p>
                  <p begin="00:10.000" end="00:13.000" ttm:agent="all">Singing together</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = manager.parseLrc(ttml).filter { !it.isInstrumental }
        assertTrue(lines.size >= 3)

        val v1Main = lines.first { it.primaryText == "First voice" }
        assertEquals(LyricSpeaker.FIRST, v1Main.speaker)
        assertEquals("Alex", v1Main.speakerLabel)
        assertEquals("Первый голос", v1Main.secondaryText)
        assertTrue(v1Main.hasWordTiming)
        assertEquals(2, v1Main.words.size)

        val v2Line = lines.first { it.primaryText == "Second voice overlap" }
        assertEquals(LyricSpeaker.SECOND, v2Line.speaker)
        assertEquals("Mia", v2Line.speakerLabel)

        val bothLine = lines.first { it.primaryText == "Singing together" }
        assertEquals(LyricSpeaker.BOTH, bothLine.speaker)
    }

    @Test
    fun parseLrc_autoDetectsSrtWithDialogueAndItalics() {
        val manager = LyricsManager()
        val srt = """
            1
            00:00:05,000 --> 00:00:08,500
            - First singer line
            - Second singer line

            2
            00:00:09,000 --> 00:00:11,000
            <i>Whispering background</i>
        """.trimIndent()

        val lines = manager.parseLrc(srt).filter { !it.isInstrumental }
        assertEquals(3, lines.size)
        assertEquals(LyricSpeaker.FIRST, lines[0].speaker)
        assertEquals("First singer line", lines[0].primaryText)
        assertEquals(LyricSpeaker.SECOND, lines[1].speaker)
        assertEquals("Second singer line", lines[1].primaryText)
        assertTrue(lines[2].isBackground)
        assertEquals(LyricSpeaker.BACKGROUND, lines[2].speaker)
    }

    @Test
    fun synthesizeNaturalWords_weightsLongerAndPunctuatedWords() {
        val manager = LyricsManager()
        val words = manager.synthesizeNaturalWords("Hi extraordinary, world!", 1000L, 4000L)
        assertEquals(3, words.size)
        assertEquals(1000L, words.first().startTimeMs)
        assertTrue(words[1].endTimeMs - words[1].startTimeMs > words[0].endTimeMs - words[0].startTimeMs)
        assertTrue(words.last().endTimeMs <= 4000L)
    }

    @Test
    fun scoreCandidate_prefersExactDurationAndPenalizesKaraokeOrSlowed() {
        val manager = YouTubeStreamManager()
        val official = OnlineTrack(
            id = "track_official",
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationSeconds = 200L,
            isOfficialAudio = true,
            sourceEngine = "saavn",
            directStreamUrl = "https://cdn.example/blinding.mp4"
        )
        val karaoke = OnlineTrack(
            id = "track_karaoke",
            title = "Blinding Lights (Karaoke Remix)",
            artist = "Cover Band",
            durationSeconds = 250L,
            isOfficialAudio = false,
            sourceEngine = "youtube"
        )

        val officialScore = manager.scoreCandidate(official, "Blinding Lights", "The Weeknd", 201_000L)
        val karaokeScore = manager.scoreCandidate(karaoke, "Blinding Lights", "The Weeknd", 201_000L)

        assertTrue(officialScore > karaokeScore + 100)
        assertEquals("Онлайн", official.album)
        val song = official.toSong()
        assertEquals("Онлайн", song.album)
        assertEquals("Онлайн", song.genre)
        assertEquals("https://cdn.example/blinding.mp4", song.contentUriString)
    }
}
