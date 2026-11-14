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
}
