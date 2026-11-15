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
        assertEquals("JioSaavn", official.sourceDisplayName)
        val song = official.toSong()
        assertEquals("Онлайн", song.album)
        assertEquals("JioSaavn", song.genre)
        assertEquals("https://cdn.example/blinding.mp4", song.contentUriString)
    }

    @Test
    fun streamManager_exposesAllSourcesAndDeliveryEngines() {
        val sourceIds = YouTubeStreamManager.SOURCE_FILTERS.map { it.id }.toSet()
        assertTrue(sourceIds.containsAll(setOf("all", "youtube", "audius", "saavn", "archive", "deezer")))

        val engineIds = YouTubeStreamManager.DELIVERY_ENGINES.map { it.id }.toSet()
        assertTrue(engineIds.containsAll(setOf("auto", "innertube_android_vr", "piped", "invidious", "cobalt")))
        assertEquals("Cobalt Tunnel", YouTubeStreamManager.formatEngineDisplay("cobalt"))
        assertEquals("Archive.org", YouTubeStreamManager.formatEngineDisplay("archive"))
    }

    @Test
    fun morphShapeLut_hasTenValidShapesWithSmoothStep() {
        assertEquals(10, com.example.ui.components.MorphShapeLut.SHAPE_COUNT)
        for (s in 0 until com.example.ui.components.MorphShapeLut.SHAPE_COUNT) {
            val table = com.example.ui.components.MorphShapeLut.radii[s]
            assertEquals(com.example.ui.components.MorphShapeLut.STEPS, table.size)
            for (r in table) {
                assertTrue("Radius out of bounds: $r", r in 0.35f..1.05f)
            }
        }
        assertEquals(0f, com.example.ui.components.MorphShapeLut.smoothStep(-1f), 0.0001f)
        assertEquals(0.5f, com.example.ui.components.MorphShapeLut.smoothStep(0.5f), 0.0001f)
        assertEquals(1f, com.example.ui.components.MorphShapeLut.smoothStep(2f), 0.0001f)
    }

    @Test
    fun parseTtml_preservesSyllableContinuityAndWordGrouping() {
        val manager = LyricsManager()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:10.000" end="00:13.000">
                    <span begin="00:10.000" end="00:10.400">Hel</span><span begin="00:10.400" end="00:10.900">lo</span> <span begin="00:11.000" end="00:11.800">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = manager.parseLrc(ttml).filter { !it.isInstrumental }
        assertEquals(1, lines.size)
        val line = lines.first()
        assertEquals("Hello world", line.primaryText)
        assertEquals(3, line.words.size)
        assertFalse(line.words[0].trailingSpace)
        assertTrue(line.words[1].trailingSpace)
        assertEquals(line.words[0].syllableGroupId, line.words[1].syllableGroupId)
        assertTrue(line.words[2].syllableGroupId != line.words[1].syllableGroupId)
    }

    @Test
    fun relevanceScorer_acceptsExactMatchAndRejectsUnrelatedOrWrongDuration() {
        val goodMatch = OnlineTrack(
            id = "sc_1",
            title = "Midnight City",
            artist = "M83",
            durationSeconds = 243L,
            isOfficialAudio = true,
            sourceEngine = "soundcloud",
            directStreamUrl = "https://cdn.example/m83.mp3"
        )
        val unrelated = OnlineTrack(
            id = "arch_bad",
            title = "Random Classical Lecture Episode 14",
            artist = "Public Podcast",
            durationSeconds = 243L,
            sourceEngine = "archive"
        )
        val wrongDuration = OnlineTrack(
            id = "yt_short",
            title = "Midnight City",
            artist = "M83",
            durationSeconds = 40L,
            sourceEngine = "youtube"
        )

        val goodAssess = com.example.online.RelevanceScorer.assess(goodMatch, "Midnight City", "M83", 244_000L)
        val badAssess = com.example.online.RelevanceScorer.assess(unrelated, "Midnight City", "M83", 244_000L)
        val durAssess = com.example.online.RelevanceScorer.assess(wrongDuration, "Midnight City", "M83", 244_000L)

        assertTrue(goodAssess.isAcceptable)
        assertFalse(badAssess.isAcceptable)
        assertFalse(durAssess.isAcceptable)
    }

    @Test
    fun sourceDiagnosticsLog_recordsAndFormatsCopyableReport() {
        com.example.online.SourceDiagnosticsLog.clear()
        val entry = com.example.online.SourceAttemptLog(
            sourceId = "soundcloud",
            displayName = "SoundCloud",
            stage = "stream",
            status = com.example.online.SourceAttemptStatus.HTTP_ERROR,
            httpCode = 403,
            detail = "Expired client_id",
            latencyMs = 142L
        )
        com.example.online.SourceDiagnosticsLog.record(entry)
        val recent = com.example.online.SourceDiagnosticsLog.recent(5)
        assertEquals(1, recent.size)

        val report = com.example.online.PlaybackDiagnosticReport(
            trackTitle = "Starboy",
            trackArtist = "The Weeknd",
            errorCodeLabel = "HTTP 403",
            summary = "Доступ запрещён",
            attempts = recent
        )
        val formatted = com.example.online.SourceDiagnosticsLog.formatCopyableReport(report)
        assertTrue(formatted.contains("HTTP 403"))
        assertTrue(formatted.contains("SoundCloud"))
        assertTrue(formatted.contains("The Weeknd"))
    }

    @Test
    fun sourceFilters_distinguishFullTracksFromExplicit30sPreviews() {
        val allFilter = YouTubeStreamManager.SOURCE_FILTERS.first { it.id == "all" }
        val deezerFilter = YouTubeStreamManager.SOURCE_FILTERS.first { it.id == "deezer" }
        val itunesFilter = YouTubeStreamManager.SOURCE_FILTERS.first { it.id == "itunes" }
        assertTrue(allFilter.label.contains("полн", ignoreCase = true))
        assertTrue(deezerFilter.label.contains("30"))
        assertTrue(itunesFilter.label.contains("30"))

        val engineIds = YouTubeStreamManager.DELIVERY_ENGINES.map { it.id }.toSet()
        assertTrue(engineIds.contains("innertube_ios"))
        assertTrue(engineIds.contains("innertube_android_music"))
    }

    @Test
    fun lyricsState_distinguishesSyncedVsPlainStatusCorrectly() {
        val syncedWord = com.example.lyrics.LyricsState(
            title = "Song",
            artist = "Artist",
            isSynced = true,
            hasWordSync = true,
            source = "BetterLyrics"
        )
        val unsyncedPlain = com.example.lyrics.LyricsState(
            title = "Song",
            artist = "Artist",
            plainLyrics = "Plain verse line",
            isSynced = false,
            hasWordSync = false,
            source = "YouTube Music"
        )
        assertTrue(syncedWord.isSynced && syncedWord.hasWordSync)
        assertFalse(unsyncedPlain.isSynced)
        assertFalse(unsyncedPlain.hasWordSync)
    }

    @Test
    fun soundCloudExtractor_detectsClientIdAcrossBundlePatternsAndSaavnDropsDeadHost() {
        val id1 = "aBcDeFgHiJkLmNoPqRsTuVwXyZ012345"
        assertEquals(
            id1,
            com.example.online.FreeCatalogProviders.extractSoundCloudClientIdFromText("""var x={client_id:"$id1",env:"prod"};""")
        )
        assertEquals(
            id1,
            com.example.online.FreeCatalogProviders.extractSoundCloudClientIdFromText("""{"client_id":'$id1'}""")
        )
        assertEquals(
            id1,
            com.example.online.FreeCatalogProviders.extractSoundCloudClientIdFromText("""https://api-v2.soundcloud.com/tracks?client_id=$id1&limit=10""")
        )

        val mirrors = com.example.online.FreeCatalogProviders.getJioSaavnMirrorHosts()
        assertFalse(mirrors.any { it.contains("saavn.dev") })
    }

    @Test
    fun bilibiliAndJamendo_exposeFiltersUrisAndDeterministicWbiSignature() {
        val sourceIds = YouTubeStreamManager.SOURCE_FILTERS.map { it.id }.toSet()
        assertTrue(sourceIds.containsAll(setOf("jamendo", "bilibili")))
        assertEquals("Jamendo", com.example.online.displayNameForSource("jamendo"))
        assertEquals("Bilibili", com.example.online.displayNameForSource("bilibili"))

        val engineIds = YouTubeStreamManager.DELIVERY_ENGINES.map { it.id }.toSet()
        assertTrue(engineIds.contains("webview_decipher"))
        assertEquals("YouTube • WebView JS", YouTubeStreamManager.formatEngineDisplay("webview_decipher"))

        val mixin = com.example.online.FreeCatalogProviders.computeBilibiliMixinKey(
            "7cd084941338484aae1ad9425b84077c",
            "4932caff0ff746eab6f01bf08b70ac45"
        )
        assertEquals(32, mixin.length)
        val signed = com.example.online.FreeCatalogProviders.signBilibiliWbiQuery(
            params = mapOf("keyword" to "test", "search_type" to "video"),
            mixinKey = mixin,
            wtsSeconds = 1700000000L
        )
        assertTrue(signed.contains("wts=1700000000"))
        assertTrue(signed.contains("w_rid="))
    }

    @Test
    fun webViewYouTubeHelper_extractsSignatureCipherSnippetFromPlayerJs() {
        val fakePlayerJs = """
            var Qv={sp:function(a,b){a.splice(0,b)},rv:function(a){a.reverse()}};
            dec=function(a){a=a.split("");Qv.rv(a,1);Qv.sp(a,2);return a.join("")};
        """.trimIndent()
        val snippet = com.example.online.WebViewYouTubeHelper.extractSignatureDecipherSnippet(fakePlayerJs, "ABCDEF")
        assertTrue(snippet != null)
        assertTrue(snippet!!.contains("Qv"))
        assertTrue(snippet.contains("ABCDEF"))
    }
}
