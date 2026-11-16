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
    val potato = com.example.ui.theme.resolvePerformanceProfile(
      context,
      com.example.data.preferences.PreferencesManager.PerformanceMode.POTATO
    )
    org.junit.Assert.assertTrue(potato.isPotato)
    org.junit.Assert.assertFalse(potato.enableShaders)
    org.junit.Assert.assertFalse(potato.enableLiveBlur)
    org.junit.Assert.assertFalse(potato.detailedPerCharWave)
    org.junit.Assert.assertTrue(potato.allowRgb565)
    org.junit.Assert.assertEquals(64, potato.lyricBackdropResolutionPx)

    val flagship = com.example.ui.theme.resolvePerformanceProfile(
      context,
      com.example.data.preferences.PreferencesManager.PerformanceMode.FLAGSHIP
    )
    org.junit.Assert.assertTrue(flagship.isFlagship)
    org.junit.Assert.assertTrue(flagship.detailedPerCharWave)
    org.junit.Assert.assertTrue(flagship.enableHeavyShadows)
    org.junit.Assert.assertEquals(180, flagship.lyricBackdropResolutionPx)
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
}
