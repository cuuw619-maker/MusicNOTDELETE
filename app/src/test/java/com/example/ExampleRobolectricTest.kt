package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("imux player", appName)
  }

  @Test
  fun `resolvePerformanceProfile adapts shaders blur and thumbnails per tier`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val low = com.example.ui.theme.resolvePerformanceProfile(
      context,
      com.example.data.preferences.PreferencesManager.PerformanceMode.LOW
    )
    org.junit.Assert.assertTrue(low.isPotato)
    org.junit.Assert.assertFalse(low.enableShaders)
    org.junit.Assert.assertFalse(low.enableLiveBlur)
    org.junit.Assert.assertFalse(low.detailedPerCharWave)
    org.junit.Assert.assertTrue(low.allowRgb565)
    org.junit.Assert.assertEquals(64, low.lyricBackdropResolutionPx)

    val high = com.example.ui.theme.resolvePerformanceProfile(
      context,
      com.example.data.preferences.PreferencesManager.PerformanceMode.HIGH
    )
    org.junit.Assert.assertTrue(high.isFlagship)
    org.junit.Assert.assertTrue(high.detailedPerCharWave)
    org.junit.Assert.assertTrue(high.enableHeavyShadows)
    org.junit.Assert.assertEquals(180, high.lyricBackdropResolutionPx)
  }

  @Test
  fun `preferencesManager migrates legacy performance modes and keeps liquid glass off by default`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val rawPrefs = context.getSharedPreferences("imux_player_prefs", Context.MODE_PRIVATE)
    rawPrefs.edit().clear().commit()

    val freshPrefs = com.example.data.preferences.PreferencesManager(context)
    assertEquals(com.example.data.preferences.PreferencesManager.PerformanceMode.HIGH, freshPrefs.performanceMode.value)
    assertEquals(false, freshPrefs.liquidGlassEnabled.value)
    assertEquals("CRYSTAL", freshPrefs.liquidGlassPreset.value)

    freshPrefs.setLiquidGlassEnabled(true)
    freshPrefs.applyLiquidGlassPreset("PRISM")
    val reloaded = com.example.data.preferences.PreferencesManager(context)
    assertEquals(true, reloaded.liquidGlassEnabled.value)
    assertEquals("PRISM", reloaded.liquidGlassPreset.value)
    assertEquals(14f, reloaded.liquidGlassBlurDp.value, 0.01f)

    rawPrefs.edit().putString("key_performance_mode", "POTATO").commit()
    val migratedLow = com.example.data.preferences.PreferencesManager(context)
    assertEquals(com.example.data.preferences.PreferencesManager.PerformanceMode.LOW, migratedLow.performanceMode.value)

    rawPrefs.edit().putString("key_performance_mode", "BALANCED").commit()
    val migratedHigh = com.example.data.preferences.PreferencesManager(context)
    assertEquals(com.example.data.preferences.PreferencesManager.PerformanceMode.HIGH, migratedHigh.performanceMode.value)
  }

  @Test
  fun `decryptJioSaavnUrl decrypts DES ECB media url and upgrades bitrate`() {
    val plainUrl = "https://aac.saavncdn.com/123/test_track_96.mp4"
    val keySpec = javax.crypto.spec.SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
    val cipher = javax.crypto.Cipher.getInstance("DES/ECB/PKCS5Padding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
    val encryptedBytes = cipher.doFinal(plainUrl.toByteArray(Charsets.UTF_8))
    val encryptedB64 = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)

    val decrypted320 = com.example.online.FreeCatalogProviders.decryptJioSaavnUrl(encryptedB64, prefer320kbps = true)
    assertEquals("https://aac.saavncdn.com/123/test_track_320.mp4", decrypted320)

    val decrypted160 = com.example.online.FreeCatalogProviders.decryptJioSaavnUrl(encryptedB64, prefer320kbps = false)
    assertEquals("https://aac.saavncdn.com/123/test_track_160.mp4", decrypted160)
  }

  @Test
  fun `crashHandler formats and clears persisted crash reports cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.util.CrashHandler.clearLastCrashReport(context)
    org.junit.Assert.assertNull(com.example.util.CrashHandler.getLastCrashReport(context))

    val err = IllegalStateException("Test search crash")
    val formatted = com.example.util.CrashHandler.formatReport(context, Thread.currentThread(), err)
    org.junit.Assert.assertTrue(formatted.contains("IllegalStateException"))
    org.junit.Assert.assertTrue(formatted.contains("Test search crash"))
    org.junit.Assert.assertTrue(formatted.contains("Стек вызовов"))
  }

  @Test
  fun `preferencesManager persists selected youtube delivery engine and normalizes legacy ids`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs1 = com.example.data.preferences.PreferencesManager(context)

    prefs1.setPreferredDeliveryEngine("yt_web_rhino")
    prefs1.setOnlineSourceFilter("youtube")

    val prefs2 = com.example.data.preferences.PreferencesManager(context)
    assertEquals("yt_web_rhino", prefs2.preferredDeliveryEngine.value)
    assertEquals("youtube", prefs2.onlineSourceFilter.value)

    prefs2.setPreferredDeliveryEngine("innertube_tv_remix")
    val prefs3 = com.example.data.preferences.PreferencesManager(context)
    assertEquals("innertube_tv_remix", prefs3.preferredDeliveryEngine.value)

    // Legacy 2-engine IDs migrate cleanly onto the upgraded engine IDs
    prefs3.setPreferredDeliveryEngine("innertube_android_vr")
    assertEquals("innertube_tv_remix", prefs3.preferredDeliveryEngine.value)
    prefs3.setPreferredDeliveryEngine("newpipe")
    assertEquals("yt_web_rhino", prefs3.preferredDeliveryEngine.value)

    // Unrecognized legacy engine IDs must normalize cleanly to auto
    prefs3.setPreferredDeliveryEngine("piped")
    assertEquals("auto", prefs3.preferredDeliveryEngine.value)
  }

  @Test
  fun `innerTubePayloadBuilder includes sts and html5Preference for TVHTML5 and WEB_REMIX`() {
    val tvPayload = com.example.online.YouTubeStreamManager.buildInnerTubePlayerPayload(
      videoId = "abc123XYZ",
      clientName = "TVHTML5",
      clientVersion = "7.20250909.18.00",
      signatureTimestamp = 20123
    )
    assertEquals("abc123XYZ", tvPayload.getString("videoId"))
    org.junit.Assert.assertTrue(tvPayload.getBoolean("contentCheckOk"))
    val client = tvPayload.getJSONObject("context").getJSONObject("client")
    assertEquals("TVHTML5", client.getString("clientName"))
    assertEquals("7.20250909.18.00", client.getString("clientVersion"))
    val cpc = tvPayload.getJSONObject("playbackContext").getJSONObject("contentPlaybackContext")
    assertEquals(20123, cpc.getInt("signatureTimestamp"))
    assertEquals("HTML5_PREF_WANTS", cpc.getString("html5Preference"))

    val remixPayload = com.example.online.YouTubeStreamManager.buildInnerTubePlayerPayload(
      videoId = "def456UVW",
      clientName = "WEB_REMIX",
      clientVersion = "1.20250909.01.00",
      signatureTimestamp = 20124
    )
    val remixClient = remixPayload.getJSONObject("context").getJSONObject("client")
    assertEquals("WEB_REMIX", remixClient.getString("clientName"))
    assertEquals(20124, remixPayload.getJSONObject("playbackContext").getJSONObject("contentPlaybackContext").getInt("signatureTimestamp"))
  }

  @Test
  fun `youtubeStreamManager diagnoses SabrOnly and PlayabilityBlocked JSON payloads`() {
    val manager = com.example.online.YouTubeStreamManager()
    val sabrJson = org.json.JSONObject(
      """
      {
        "playabilityStatus": { "status": "OK" },
        "streamingData": {
          "serverAbrStreamingUrl": "https://rr1.googlevideo.com/sabr?id=1",
          "adaptiveFormats": []
        }
      }
      """.trimIndent()
    )
    val sabrDiag = manager.diagnosePlayerJsonFailure(sabrJson)
    assertEquals(com.example.online.SourceAttemptStatus.SABR_ONLY, sabrDiag?.first)

    val blockedJson = org.json.JSONObject(
      """
      {
        "playabilityStatus": {
          "status": "UNPLAYABLE",
          "reason": "The following content is not available on this app."
        }
      }
      """.trimIndent()
    )
    val blockedDiag = manager.diagnosePlayerJsonFailure(blockedJson)
    assertEquals(com.example.online.SourceAttemptStatus.PLAYABILITY_BLOCKED, blockedDiag?.first)
  }

  @Test
  fun `trackArtworkLoader builds distinct URIs and cache keys for tracks sharing albumId`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val uri1 = com.example.ui.components.TrackArtworkLoader.buildTrackArtUri(
      songId = 101L,
      albumId = 42L,
      album = "Downloads",
      contentUri = "content://media/external/audio/media/101",
      dataPath = "/storage/emulated/0/Download/song1.mp3"
    )
    val uri2 = com.example.ui.components.TrackArtworkLoader.buildTrackArtUri(
      songId = 102L,
      albumId = 42L,
      album = "Downloads",
      contentUri = "content://media/external/audio/media/102",
      dataPath = "/storage/emulated/0/Download/song2.mp3"
    )
    org.junit.Assert.assertNotEquals(uri1, uri2)
    org.junit.Assert.assertTrue(uri1.contains("fallback=0"))

    val keyer = com.example.ui.components.TrackArtworkKeyer()
    val options = coil.request.Options(context)
    val key1 = keyer.key(android.net.Uri.parse(uri1), options)
    val key2 = keyer.key(android.net.Uri.parse(uri2), options)
    org.junit.Assert.assertNotNull(key1)
    org.junit.Assert.assertNotNull(key2)
    org.junit.Assert.assertNotEquals(key1, key2)
  }

  @Test
  fun `preferencesManager persists 10-band levels preamp and limiter state`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs1 = com.example.data.preferences.PreferencesManager(context)
    prefs1.set10BandLevel(0, 650)
    prefs1.set10BandLevel(9, -400)
    prefs1.setPreAmpDb(18f) // clamped to 12f
    prefs1.setLimiterEnabled(false)

    val prefs2 = com.example.data.preferences.PreferencesManager(context)
    assertEquals(650.toShort(), prefs2.get10BandLevel(0, 0))
    assertEquals((-400).toShort(), prefs2.get10BandLevel(9, 0))
    assertEquals(12f, prefs2.preAmpDb.value, 0.001f)
    assertEquals(false, prefs2.limiterEnabled.value)
  }

  @Test
  fun `lyricsManager parses LyricsPlus and SimpMusic JSON with word sync and parallel cues`() {
    val manager = com.example.lyrics.LyricsManager()
    val lpJson = """
      {
        "type": "Word",
        "lyrics": [
          {
            "time": 1000,
            "duration": 3000,
            "text": "Shine bright",
            "syllabus": [
              {"time": 1000, "duration": 600, "text": "Shine"},
              {"time": 1700, "duration": 900, "text": "bright"}
            ],
            "translation": {"text": "Сияй ярко"}
          },
          {
            "time": 1400,
            "duration": 1800,
            "text": "(Ooh yeah)",
            "element": {"isBackground": true},
            "syllabus": [
              {"time": 1400, "duration": 700, "text": "(Ooh"},
              {"time": 2100, "duration": 800, "text": "yeah)"}
            ]
          }
        ]
      }
    """.trimIndent()

    val lpState = manager.parseLyricsPlusJson(lpJson, "Diamonds", "Rihanna", "lyricsplus.binimum.org")
    org.junit.Assert.assertNotNull(lpState)
    org.junit.Assert.assertTrue(lpState!!.isSynced)
    org.junit.Assert.assertTrue(lpState.hasWordSync)
    org.junit.Assert.assertTrue(lpState.lines.any { it.parallelLines.isNotEmpty() })

    val simpJson = """
      {
        "data": [
          {
            "richSyncLyrics": "[00:04.00] <00:04.00>Night <00:04.50>call"
          }
        ]
      }
    """.trimIndent()
    val simpState = manager.parseSimpMusicResponse(simpJson, "Nightcall", "Kavinsky")
    org.junit.Assert.assertNotNull(simpState)
    org.junit.Assert.assertTrue(simpState!!.hasWordSync)
  }
}
