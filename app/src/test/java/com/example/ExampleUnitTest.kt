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
        assertEquals(setOf("auto", "innertube_tv_remix", "yt_web_rhino"), engineIds)
        assertEquals("YT TV/Remix Spoof", YouTubeStreamManager.formatEngineDisplay("innertube_tv_remix"))
        assertEquals("YT Web Rhino", YouTubeStreamManager.formatEngineDisplay("yt_web_rhino"))
        assertEquals("YT TV/Remix Spoof", YouTubeStreamManager.formatEngineDisplay("innertube_android_vr"))
        assertEquals("YT Web Rhino", YouTubeStreamManager.formatEngineDisplay("newpipe"))
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
        assertEquals(3, engineIds.size)
        assertTrue(engineIds.contains("auto"))
        assertTrue(engineIds.contains("innertube_tv_remix"))
        assertTrue(engineIds.contains("yt_web_rhino"))
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
        assertTrue(engineIds.contains("yt_web_rhino"))
        assertEquals("YT Web Rhino", YouTubeStreamManager.formatEngineDisplay("yt_web_rhino"))

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
    fun rhinoYouTubeDecipher_extractsAndEvaluatesSignatureCipherWithoutWebView() {
        val fakePlayerJs = """
            var Qv={sp:function(a,b){a.splice(0,b)},rv:function(a){a.reverse()}};
            dec=function(a){a=a.split("");Qv.rv(a,1);Qv.sp(a,2);return a.join("")};
        """.trimIndent()
        val snippet = com.example.online.RhinoYouTubeDecipher.extractSignatureDecipherSnippet(fakePlayerJs, "ABCDEF")
        assertTrue(snippet != null)
        assertTrue(snippet!!.contains("Qv"))
        val deciphered = com.example.online.RhinoYouTubeDecipher.evalInRhino(snippet)
        assertEquals("DCBA", deciphered)
    }

    @Test
    fun continuousWaveMath_wrapsCyclicMarqueeAndPulseContinuously() {
        val twoPi = (2.0 * Math.PI).toFloat()
        val atZero = com.example.ui.components.ContinuousWaveMath.cyclicMarqueeSegments(0f, widthFrac = 0.34f)
        assertEquals(1, atZero.size)
        assertEquals(0f, atZero[0].first, 0.001f)
        assertEquals(0.34f, atZero[0].second, 0.001f)

        // Approaching 2*PI should wrap around [0.85..1.0] + [0.0..0.19], seamlessly joining 0
        val nearEnd = com.example.ui.components.ContinuousWaveMath.cyclicMarqueeSegments(twoPi * 0.85f, widthFrac = 0.34f)
        assertEquals(2, nearEnd.size)
        assertEquals(0.85f, nearEnd[0].first, 0.001f)
        assertEquals(1.0f, nearEnd[0].second, 0.001f)
        assertEquals(0f, nearEnd[1].first, 0.001f)
        assertEquals(0.19f, nearEnd[1].second, 0.002f)

        // Pulse weight at x = 0.0 and x = 0.999 when center is 0.02 should match seamlessly across loop wrap
        val wLeft = com.example.ui.components.ContinuousWaveMath.cyclicPulseWeight(0.0f, 0.02f)
        val wRight = com.example.ui.components.ContinuousWaveMath.cyclicPulseWeight(0.999f, 0.02f)
        assertEquals(wLeft, wRight, 0.02f)
    }

    @Test
    fun youTubeStubDetector_rejectsDeprecationMessagesAndSwappedStubStreams() {
        assertTrue(YouTubeStreamManager.isDeprecationOrAppBlockMessage("YouTube doesn't support this application or device"))
        assertTrue(YouTubeStreamManager.isDeprecationOrAppBlockMessage("The following content is not available on this app. Watch on the latest version of YouTube."))
        assertTrue(YouTubeStreamManager.isDeprecationOrAppBlockMessage("Sign in to confirm you're not a bot"))
        assertFalse(YouTubeStreamManager.isDeprecationOrAppBlockMessage("MORGENSHTERN - ДОМ (Лондон, Прага, Ницца)"))

        // Swapped videoId stub rejected
        assertTrue(
            YouTubeStreamManager.isSuspiciousStubStream(
                requestedVideoId = "realTrack123",
                returnedVideoId = "stubError999",
                returnedTitle = "Normal Title",
                returnedDurationSec = 180L,
                expectedDurationMs = 180_000L
            )
        )
        // Short 38s error audio stub rejected for a 3-minute song
        assertTrue(
            YouTubeStreamManager.isSuspiciousStubStream(
                requestedVideoId = "realTrack123",
                returnedVideoId = "realTrack123",
                returnedTitle = "Track Title",
                returnedDurationSec = 38L,
                expectedDurationMs = 185_000L
            )
        )
        // Legitimate matching track passes
        assertFalse(
            YouTubeStreamManager.isSuspiciousStubStream(
                requestedVideoId = "realTrack123",
                returnedVideoId = "realTrack123",
                returnedTitle = "ДОМ (Лондон, Прага, Ницца)",
                returnedDurationSec = 179L,
                expectedDurationMs = 180_000L
            )
        )
    }

    @Test
    fun rhinoYouTubeDecipher_extractsAndEvaluatesNTransformInProcess() {
        val fakePlayerJs = """
            var xN=function(a){return a.split("").reverse().join("")+"_ok"};
            var nArr=[xN];
            if((b=c.get("n"))&&(b=nArr[0](b),c.set("n",b)))console.log(b);
        """.trimIndent()
        val snippet = com.example.online.RhinoYouTubeDecipher.extractNTransformSnippet(fakePlayerJs, "abc123")
        assertTrue(snippet != null)
        val out = com.example.online.RhinoYouTubeDecipher.evalInRhino(snippet!!)
        assertEquals("321cba_ok", out)
    }

    @Test
    fun rhinoYouTubeDecipher_extractsMultiHelperNTransformChain() {
        val fakePlayerJs = """
            var hlpA={rev:function(s){return s.split("").reverse().join("")}};
            function wrapN(a){return hlpA.rev(a)+"_2"}
            var nList=[wrapN];
            if((b=c.get("n"))&&(b=nList[0](b),c.set("n",b)))console.log(b);
        """.trimIndent()
        val snippet = com.example.online.RhinoYouTubeDecipher.extractNTransformSnippet(fakePlayerJs, "xyz789")
        assertTrue(snippet != null)
        val out = com.example.online.RhinoYouTubeDecipher.evalInRhino(snippet!!)
        assertEquals("987zyx_2", out)
    }

    @Test
    fun sourceDiagnosticsLog_neverBlamesHttp200OnFailedStreams() {
        com.example.online.SourceDiagnosticsLog.clear()
        val sabrEntry = com.example.online.SourceAttemptLog(
            sourceId = "youtube",
            displayName = "YouTube",
            stage = "innertube_TVHTML5",
            status = com.example.online.SourceAttemptStatus.SABR_ONLY,
            httpCode = 200,
            detail = "Only serverAbrStreamingUrl returned",
            latencyMs = 95L
        )
        assertEquals(
            "SABR_ONLY",
            com.example.online.SourceDiagnosticsLog.resolveDiagnosticErrorCode(listOf(sabrEntry))
        )

        val blockedWith200 = com.example.online.SourceAttemptLog(
            sourceId = "youtube",
            displayName = "YouTube",
            stage = "innertube_IOS",
            status = com.example.online.SourceAttemptStatus.PLAYABILITY_BLOCKED,
            httpCode = 200,
            detail = "Client blocked",
            latencyMs = 80L
        )
        assertEquals(
            "PLAYABILITY_BLOCKED",
            com.example.online.SourceDiagnosticsLog.resolveDiagnosticErrorCode(listOf(blockedWith200))
        )

        val report = com.example.online.PlaybackDiagnosticReport(
            trackTitle = "Test Track",
            trackArtist = "Test Artist",
            errorCodeLabel = com.example.online.SourceDiagnosticsLog.resolveDiagnosticErrorCode(listOf(sabrEntry)),
            summary = "SABR stream only",
            attempts = listOf(sabrEntry)
        )
        val formatted = com.example.online.SourceDiagnosticsLog.formatCopyableReport(report)
        assertFalse(formatted.contains("HTTP 200"))
        assertTrue(formatted.contains("SABR_ONLY"))
    }

    @Test
    fun playbackTransitionPolicy_stopsInfiniteEofLoopsAndRespectsShufflePreresolve() {
        assertEquals(
            0L,
            com.example.playback.PlaybackTransitionPolicy.effectiveTransitionDurationMs(androidx.media3.common.C.TIME_UNSET)
        )
        assertEquals(
            210_000L,
            com.example.playback.PlaybackTransitionPolicy.effectiveTransitionDurationMs(210_000L)
        )

        // First early-EOF on long online track allows 1 recovery; second is blocked to avoid endless replay
        assertTrue(
            com.example.playback.PlaybackTransitionPolicy.shouldAttemptEarlyEofRecovery(
                isOnline = true,
                playerDurationMs = 200_000L,
                positionMs = 95_000L,
                earlyEofCount = 0
            )
        )
        assertFalse(
            com.example.playback.PlaybackTransitionPolicy.shouldAttemptEarlyEofRecovery(
                isOnline = true,
                playerDurationMs = 200_000L,
                positionMs = 95_000L,
                earlyEofCount = 1
            )
        )
        // Normal end of song does not trigger early-EOF recovery
        assertFalse(
            com.example.playback.PlaybackTransitionPolicy.shouldAttemptEarlyEofRecovery(
                isOnline = true,
                playerDurationMs = 200_000L,
                positionMs = 198_000L,
                earlyEofCount = 0
            )
        )

        // Queue advancement rules
        assertTrue(
            com.example.playback.PlaybackTransitionPolicy.shouldAdvanceQueueAfterFailure(
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF,
                hasNextMediaItem = true,
                queueSize = 4
            )
        )
        assertFalse(
            com.example.playback.PlaybackTransitionPolicy.shouldAdvanceQueueAfterFailure(
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE,
                hasNextMediaItem = true,
                queueSize = 4
            )
        )

        // Shuffle nextMediaItemIndex prioritized ahead of linear fallback
        val indices = com.example.playback.PlaybackTransitionPolicy.computePreresolveIndices(
            currentIndex = 1,
            nextMediaItemIndex = 7,
            queueSize = 10
        )
        assertEquals(listOf(7, 2, 3), indices)
    }

    @Test
    fun lyricsManager_preprocessParallelLinesPlacesBackingVocalsBelowMainLine() {
        val manager = LyricsManager()
        val lrc = """
            [00:10.00] Hold on tight (yeah, ooh)
            [00:14.50] Shining in the night
            [00:14.50] bg: Keep on shining
        """.trimIndent()

        val lines = manager.parseLrc(lrc).filter { !it.isInstrumental }
        assertEquals(2, lines.size)

        assertEquals("Hold on tight", lines[0].primaryText)
        assertEquals(1, lines[0].parallelLines.size)
        assertEquals("yeah, ooh", lines[0].parallelLines[0].text)
        assertTrue(lines[0].parallelLines[0].words.isNotEmpty())

        assertEquals("Shining in the night", lines[1].primaryText)
        assertEquals(1, lines[1].parallelLines.size)
        assertEquals("Keep on shining", lines[1].parallelLines[0].text)
    }

    @Test
    fun equalizerManager_tenBandFrequenciesPreAmpBalanceAndBassCurve() {
        val freqs = com.example.playback.EqualizerManager.TEN_BAND_CENTER_FREQS_HZ
        assertEquals(10, freqs.size)
        assertEquals(31, freqs.first())
        assertEquals(16000, freqs.last())

        val bassCurve = com.example.playback.EqualizerManager.presetCurveDb("Bass Boost")
        assertTrue(bassCurve != null && bassCurve.size == 10)
        assertTrue(bassCurve!![0] > bassCurve[5])

        assertTrue(com.example.playback.EqualizerManager.calculateBassBoostDeltaDb(0, 1000) > 7.0f)
        assertEquals(0f, com.example.playback.EqualizerManager.calculateBassBoostDeltaDb(6, 1000), 0.001f)

        // Pre-Amp clamping and L/R balance attenuation
        val leftGainCentered = com.example.playback.EqualizerManager.calculateChannelInputGainDb(
            preAmpDb = 6f, loudnessStrengthMb = 200, audioBalance = 0f, channelIndex = 0
        )
        assertEquals(8.0f, leftGainCentered, 0.01f)

        val rightGainShiftedLeft = com.example.playback.EqualizerManager.calculateChannelInputGainDb(
            preAmpDb = 0f, loudnessStrengthMb = 0, audioBalance = -0.5f, channelIndex = 1
        )
        assertEquals(-12.0f, rightGainShiftedLeft, 0.01f)
    }

    @Test
    fun kugouLyricsProvider_decodesKrc1XorZlibAndParsesWordTimings() {
        val sampleKrc = "[offset:200]\n[1000,2000]<0,400,0>Hello <400,600,0>world\n"
        val rawBytes = sampleKrc.toByteArray(Charsets.UTF_8)
        val deflater = java.util.zip.Deflater()
        deflater.setInput(rawBytes)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        val compressed = out.toByteArray()

        val krcKey = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, (-50).toByte(), (-46).toByte(), 110, 105
        )
        val packet = ByteArray(4 + compressed.size)
        packet[0] = 'k'.code.toByte()
        packet[1] = 'r'.code.toByte()
        packet[2] = 'c'.code.toByte()
        packet[3] = '1'.code.toByte()
        for (i in compressed.indices) {
            packet[4 + i] = (compressed[i].toInt() xor krcKey[i % krcKey.size].toInt()).toByte()
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(packet)

        val decoded = com.example.lyrics.KuGouLyricsProvider.decodeKrcBase64(b64)
        assertEquals(sampleKrc, decoded)

        val manager = LyricsManager()
        val parsed = com.example.lyrics.KuGouLyricsProvider.parseKrcLines(
            rawKrc = decoded!!,
            inlineSpeakerParser = { Triple(LyricSpeaker.FIRST, null, it) },
            synthesizeWords = { txt, s, e -> manager.synthesizeNaturalWords(txt, s, e) }
        )
        assertEquals(1, parsed.size)
        val line = parsed.first()
        assertEquals(1200L, line.timestampMs) // 1000 + 200ms offset
        assertEquals("Hello world", line.primaryText)
        assertTrue(line.hasWordTiming)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(1200L, line.words[0].startTimeMs)
        assertEquals(1600L, line.words[0].endTimeMs)
    }

    @Test
    fun lyricsManager_endpointsIncludeAllFiveMirrorsAndScorerPrefersWordSyncParallel() {
        val hosts = LyricsManager.LYRICS_PLUS_ENDPOINTS
        assertEquals(5, hosts.size)
        assertTrue(hosts.any { it.contains("lyricsplus.binimum.org") })
        assertTrue(hosts.any { it.contains("lyricsplus.atomix.one") })
        assertTrue(hosts.any { it.contains("lyricsplus-seven.vercel.app") })
        assertTrue(hosts.any { it.contains("lyricsplus.prjktla.workers.dev") })
        assertTrue(hosts.any { it.contains("lyrics-plus-backend.vercel.app") })

        val manager = LyricsManager()
        val richLine = com.example.lyrics.DualLyricLine(
            timestampMs = 5_000L,
            endTimeMs = 8_000L,
            primaryText = "Feel the bass",
            words = listOf(com.example.lyrics.LyricWord("Feel", 5_000L, 5_500L)),
            isSynced = true,
            hasWordTiming = true,
            parallelLines = listOf(com.example.lyrics.ParallelLyricLine("ooh yeah", 5_400L, 6_800L))
        )
        val richState = com.example.lyrics.LyricsState(
            title = "Track",
            artist = "Artist",
            lines = listOf(richLine),
            source = "LyricsPlus Word-Sync",
            isSynced = true,
            hasWordSync = true
        )
        val plainSyncState = com.example.lyrics.LyricsState(
            title = "Track",
            artist = "Artist",
            lines = listOf(
                com.example.lyrics.DualLyricLine(
                    timestampMs = 5_000L,
                    endTimeMs = 8_000L,
                    primaryText = "Feel the bass",
                    isSynced = true,
                    hasWordTiming = false
                )
            ),
            source = "LRCLIB Sync",
            isSynced = true,
            hasWordSync = false
        )
        assertTrue(
            manager.scoreLyricsCandidate(richState, 180L) > manager.scoreLyricsCandidate(plainSyncState, 180L)
        )
    }

    @Test
    fun youTubeStreamManager_extractsGooglevideoExpiryAndFlagsNearExpiry() {
        val nowMs = 1_700_000_000_000L
        val futureExpireSec = (nowMs / 1000L) + 1200L // +20 min
        val url = "https://rr3---sn-test.googlevideo.com/videoplayback?expire=$futureExpireSec&itag=140"

        val parsedExpiry = YouTubeStreamManager.extractStreamExpiryMs(url, nowMs)
        // Expected: (now + 1200s) - 120s safety margin
        assertEquals(nowMs + 1_080_000L, parsedExpiry)
        assertFalse(YouTubeStreamManager.isStreamNearExpiry(parsedExpiry, nowMs))
        assertTrue(YouTubeStreamManager.isStreamNearExpiry(nowMs + 60_000L, nowMs))
        assertTrue(YouTubeStreamManager.isStreamNearExpiry(nowMs - 1_000L, nowMs))
    }

    @Test
    fun trackArtworkLoader_allowsFallbackOnlyForNamedAlbums() {
        assertFalse(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback("Unknown Album"))
        assertFalse(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback("<unknown>"))
        assertFalse(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback("Downloads"))
        assertFalse(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback("Music"))
        assertFalse(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback(""))
        assertTrue(com.example.ui.components.TrackArtworkLoader.shouldAllowAlbumFallback("Random Access Memories"))
    }

    @Test
    fun swipeTrackPolicy_resolvesLeftRightAndPreviewTarget() {
        val songs = listOf(
            com.example.model.Song(id = 1L, title = "One", artist = "A", album = "Al", albumId = 10L, durationMs = 180_000L, contentUriString = ""),
            com.example.model.Song(id = 2L, title = "Two", artist = "B", album = "Al", albumId = 11L, durationMs = 180_000L, contentUriString = ""),
            com.example.model.Song(id = 3L, title = "Three", artist = "C", album = "Al", albumId = 12L, durationMs = 180_000L, contentUriString = "")
        )

        // Default preference: RIGHT -> NEXT, LEFT -> PREVIOUS
        assertEquals(
            com.example.ui.player.SwipeTrackAction.NEXT,
            com.example.ui.player.SwipeTrackPolicy.resolveHorizontalSwipeAction(
                dragOffsetPx = 72f,
                velocityPxPerSec = 0f,
                thresholdPx = 56f
            )
        )
        assertEquals(
            com.example.ui.player.SwipeTrackAction.PREVIOUS,
            com.example.ui.player.SwipeTrackPolicy.resolveHorizontalSwipeAction(
                dragOffsetPx = -72f,
                velocityPxPerSec = 0f,
                thresholdPx = 56f
            )
        )
        // Reversed preference: LEFT -> NEXT
        assertEquals(
            com.example.ui.player.SwipeTrackAction.NEXT,
            com.example.ui.player.SwipeTrackPolicy.resolveHorizontalSwipeAction(
                dragOffsetPx = -32f,
                velocityPxPerSec = -650f,
                thresholdPx = 56f,
                swipeDirectionPref = com.example.ui.player.SwipeTrackPolicy.DIR_LEFT_NEXT_RIGHT_PREV
            )
        )
        // Below threshold without flick velocity -> NONE
        assertEquals(
            com.example.ui.player.SwipeTrackAction.NONE,
            com.example.ui.player.SwipeTrackPolicy.resolveHorizontalSwipeAction(
                dragOffsetPx = 22f,
                velocityPxPerSec = 100f,
                thresholdPx = 56f
            )
        )

        assertEquals(
            songs[2],
            com.example.ui.player.SwipeTrackPolicy.previewTargetSong(songs, 1, com.example.ui.player.SwipeTrackAction.NEXT)
        )
        assertEquals(
            songs[0],
            com.example.ui.player.SwipeTrackPolicy.previewTargetSong(songs, 1, com.example.ui.player.SwipeTrackAction.PREVIOUS)
        )
    }

    @Test
    fun audioEnergyEngine_and_shaderSafetyGuard_behaveSafely() {
        val paused = com.example.ui.components.AudioEnergyEngine.sample(
            isPlaying = false,
            positionMs = 15_000L,
            durationMs = 180_000L,
            songId = 42L
        )
        val playing = com.example.ui.components.AudioEnergyEngine.sample(
            isPlaying = true,
            positionMs = 60_000L,
            durationMs = 180_000L,
            songId = 42L
        )
        assertTrue(playing.compositeEnergy > paused.compositeEnergy)
        assertTrue(playing.subBass in 0f..1f && playing.beatPulse in 0f..1f)

        com.example.ui.components.ShaderSafetyGuard.resetForTesting()
        assertFalse(
            com.example.ui.components.ShaderSafetyGuard.isAgslAllowed(
                sdkInt = 31,
                profileEnableShaders = true,
                userShadersEnabled = true,
                reducedMotion = false,
                isEmulatedDevice = false
            )
        )
        assertFalse(
            com.example.ui.components.ShaderSafetyGuard.isAgslAllowed(
                sdkInt = 34,
                profileEnableShaders = true,
                userShadersEnabled = true,
                reducedMotion = false,
                isEmulatedDevice = true
            )
        )
        assertTrue(
            com.example.ui.components.ShaderSafetyGuard.isAgslAllowed(
                sdkInt = 34,
                profileEnableShaders = true,
                userShadersEnabled = true,
                reducedMotion = false,
                isEmulatedDevice = false
            )
        )
        com.example.ui.components.ShaderSafetyGuard.markAgslFailure()
        assertFalse(
            com.example.ui.components.ShaderSafetyGuard.isAgslAllowed(
                sdkInt = 34,
                profileEnableShaders = true,
                userShadersEnabled = true,
                reducedMotion = false,
                isEmulatedDevice = false
            )
        )
        com.example.ui.components.ShaderSafetyGuard.resetForTesting()
    }
}
