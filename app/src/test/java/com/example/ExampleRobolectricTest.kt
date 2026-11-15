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
}
