package com.example.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.PreferencesManager
import com.example.ui.components.ExpressiveOvalChoiceChip
import com.example.ui.components.LiquidGlassCausticBackdrop
import com.example.ui.components.LiquidGlassConfig
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.OvalTabItem
import com.example.ui.components.OvalTabRow
import com.example.ui.player.SpeedPitchBottomSheet
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.PerformanceTier
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper
import com.example.ui.viewmodel.MusicViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val amoledDark by viewModel.amoledDark.collectAsStateWithLifecycle()
    val resumePlayback by viewModel.resumePlayback.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val crossfadeEnabled by viewModel.crossfadeEnabled.collectAsStateWithLifecycle()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val trackEndFadeOutEnabled by viewModel.trackEndFadeOutEnabled.collectAsStateWithLifecycle()
    val trackEndFadeOutDuration by viewModel.trackEndFadeOutDuration.collectAsStateWithLifecycle()
    val fadeOutOnPause by viewModel.fadeOutOnPause.collectAsStateWithLifecycle()
    val fadeInOnStart by viewModel.fadeInOnStart.collectAsStateWithLifecycle()
    val vinylModeEnabled by viewModel.vinylModeEnabled.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val pitchSemitones by viewModel.pitchSemitones.collectAsStateWithLifecycle()
    val pitchCents by viewModel.pitchCents.collectAsStateWithLifecycle()

    val reducedMotion by viewModel.reducedMotion.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticEnabled.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val userPlugins by viewModel.userPlugins.collectAsStateWithLifecycle()

    val audioEngineMode by viewModel.audioEngineMode.collectAsStateWithLifecycle()
    val antiCrackleBuffer by viewModel.antiCrackleBuffer.collectAsStateWithLifecycle()
    val preservePitch by viewModel.preservePitch.collectAsStateWithLifecycle()
    val volumeNormalize by viewModel.volumeNormalize.collectAsStateWithLifecycle()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()

    val playerSwipeDirection by viewModel.playerSwipeDirection.collectAsStateWithLifecycle()
    val doubleTapSeekSeconds by viewModel.doubleTapSeekSeconds.collectAsStateWithLifecycle()
    val volumeGestureEnabled by viewModel.volumeGestureEnabled.collectAsStateWithLifecycle()
    val filterShortAudio by viewModel.filterShortAudio.collectAsStateWithLifecycle()
    val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
    val audioReactiveShadersEnabled by viewModel.audioReactiveShadersEnabled.collectAsStateWithLifecycle()
    val autoDjEnabled by viewModel.autoDjEnabled.collectAsStateWithLifecycle()
    val miniSpectrumMode by viewModel.miniSpectrumMode.collectAsStateWithLifecycle()

    val liquidGlassEnabled by viewModel.liquidGlassEnabled.collectAsStateWithLifecycle()
    val liquidGlassPreset by viewModel.liquidGlassPreset.collectAsStateWithLifecycle()
    val liquidGlassBlurDp by viewModel.liquidGlassBlurDp.collectAsStateWithLifecycle()
    val liquidGlassRefraction by viewModel.liquidGlassRefraction.collectAsStateWithLifecycle()
    val liquidGlassFrostAlpha by viewModel.liquidGlassFrostAlpha.collectAsStateWithLifecycle()
    val liquidGlassHighlight by viewModel.liquidGlassHighlight.collectAsStateWithLifecycle()

    val perfProfile = com.example.ui.theme.LocalPerformanceProfile.current

    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSpeedPitchSheet by remember { mutableStateOf(false) }
    var showPluginSheet by remember { mutableStateOf(false) }
    var showSwipeDirDialog by remember { mutableStateOf(false) }
    var showDoubleTapSeekDialog by remember { mutableStateOf(false) }

    val haptic = rememberHapticHelper()

    val categoryTabs = remember {
        listOf(
            OvalTabItem("Звук", Icons.Rounded.GraphicEq),
            OvalTabItem("Вид", Icons.Rounded.ColorLens),
            OvalTabItem("Жесты", Icons.Rounded.TouchApp),
            OvalTabItem("Медиатека", Icons.Rounded.Folder),
            OvalTabItem("Модули", Icons.Rounded.Extension)
        )
    }

    if (showSpeedPitchSheet) {
        SpeedPitchBottomSheet(
            currentSpeed = playbackSpeed,
            currentPitchSemitones = pitchSemitones,
            currentPitchCents = pitchCents,
            onSpeedChanged = { viewModel.setPlaybackSpeed(it) },
            onPitchChanged = { viewModel.setPitchSemitones(it) },
            onPitchCentsChanged = { viewModel.setPitchCents(it) },
            onDismiss = { showSpeedPitchSheet = false }
        )
    }

    if (showPluginSheet) {
        PluginManagerSheet(
            plugins = plugins,
            userProjects = userPlugins,
            onTogglePlugin = { id, enabled -> viewModel.setPluginEnabled(id, enabled) },
            onSaveUserPlugin = { project -> viewModel.saveUserPlugin(project) },
            onDeleteUserPlugin = { id -> viewModel.deleteUserPlugin(id) },
            onDismiss = { showPluginSheet = false }
        )
    }

    if (showThemeDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Выбор темы оформления", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreferencesManager.ThemeMode.values().forEach { mode ->
                        val label = when (mode) {
                            PreferencesManager.ThemeMode.SYSTEM -> "Как в системе"
                            PreferencesManager.ThemeMode.LIGHT -> "Светлая тема"
                            PreferencesManager.ThemeMode.DARK -> "Тёмная тема"
                        }
                        ExpressiveOvalChoiceChip(
                            label = label,
                            selected = themeMode == mode,
                            onClick = {
                                viewModel.setThemeMode(mode)
                                showThemeDialog = false
                            },
                            minHeight = 46.dp,
                            fontSizeSp = 13f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showSwipeDirDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSwipeDirDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Направление свайпа", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val options = listOf(
                        "RIGHT_NEXT_LEFT_PREV" to ("Влево: Назад • Вправо: Вперёд" to "Классический жест"),
                        "LEFT_NEXT_RIGHT_PREV" to ("Влево: Вперёд • Вправо: Назад" to "Зеркальный жест")
                    )
                    options.forEach { (key, titlePair) ->
                        ExpressiveOvalChoiceChip(
                            label = titlePair.first,
                            subtitle = titlePair.second,
                            selected = playerSwipeDirection == key,
                            onClick = {
                                viewModel.setPlayerSwipeDirection(key)
                                showSwipeDirDialog = false
                            },
                            minHeight = 52.dp,
                            fontSizeSp = 12.5f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSwipeDirDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showDoubleTapSeekDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDoubleTapSeekDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Шаг перемотки", fontWeight = FontWeight.ExtraBold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 30).forEach { sec ->
                        ExpressiveOvalChoiceChip(
                            label = "${sec}с",
                            selected = doubleTapSeekSeconds == sec,
                            onClick = {
                                viewModel.setDoubleTapSeekSeconds(sec)
                                showDoubleTapSeekDialog = false
                            },
                            minHeight = 46.dp,
                            fontSizeSp = 13f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDoubleTapSeekDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
    ) {
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        OvalTabRow(
            tabs = categoryTabs,
            selectedIndex = selectedCategoryIndex,
            onTabSelected = { selectedCategoryIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            containerHeightDp = 46
        )

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedContent(
            targetState = selectedCategoryIndex,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)) { it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { -it / 4 } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(tween(200, easing = LinearOutSlowInEasing)) { -it / 4 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { it / 4 } + fadeOut(tween(140)))
                }
            },
            label = "settings_category_transition"
        ) { categoryIndex ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 128.dp, top = 6.dp)
            ) {
                when (categoryIndex) {
                    0 -> {
                        item {
                            SectionTitle(title = "Студийный DSP движок звука (ExoPlayer)")
                            ExpressiveSettingsCard {
                                SettingsRow(
                                    icon = Icons.Rounded.Tune,
                                    title = "Режим Float DSP конвейера",
                                    subtitle = if (audioEngineMode == "STUDIO") {
                                        "Studio Biquad 64-bit (точный 10-полосный EQ, панорама, M/S сцена)"
                                    } else {
                                        "Sonic Soft-Knee Float (мягкое сатурированное звучание и бас)"
                                    },
                                    onClick = {
                                        val newMode = if (audioEngineMode == "STUDIO") "SONIC" else "STUDIO"
                                        viewModel.setAudioEngineMode(newMode)
                                    }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.Memory,
                                    title = "Студийный 4x буфер против треска",
                                    subtitle = "Увеличенный 128 КБ аудиобуфер (4x) исключает щелчки на замедлении",
                                    checked = antiCrackleBuffer,
                                    onCheckedChange = { viewModel.setAntiCrackleBuffer(it) }
                                )

                                SettingsRow(
                                    icon = Icons.Rounded.Speed,
                                    title = "Скорость и Тональность",
                                    subtitle = "Скорость: %.2fx • Тон: %+d st • Микро: %+d центов".format(playbackSpeed, pitchSemitones, pitchCents),
                                    onClick = { showSpeedPitchSheet = true }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.MusicNote,
                                    title = "Сохранять тональность при ускорении",
                                    subtitle = if (preservePitch) "Независимая регулировка скорости без сдвига высоты" else "Режим винила: скорость сдвигает тон естественно",
                                    checked = preservePitch,
                                    onCheckedChange = { viewModel.setPreservePitch(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.VolumeUp,
                                    title = "Нормализация громкости (AGC)",
                                    subtitle = if (volumeNormalize) "Выравнивание громкости между тихими и громкими треками" else "Выключена (оригинальный динамический диапазон)",
                                    checked = volumeNormalize,
                                    onCheckedChange = { viewModel.setVolumeNormalize(it) }
                                )

                                SettingsRow(
                                    icon = Icons.Rounded.Equalizer,
                                    title = "10-полосный Эквалайзер и эффекты",
                                    subtitle = if (equalizerState.isEnabled) {
                                        "Активен • Pre-Amp %+.1f dB • Bass %d%% • Reverb %s".format(
                                            equalizerState.preAmpDb,
                                            equalizerState.bassBoostStrength / 10,
                                            viewModel.spatialReverbPreset.collectAsStateWithLifecycle().value
                                        )
                                    } else {
                                        "10 полос (31 Гц – 16 кГц), Bass Boost, Stereo Widener, L/R баланс"
                                    },
                                    onClick = { viewModel.setEqualizerSheetVisible(true) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            SectionTitle(title = "Бесшовный переход (Crossfade & Gapless)")
                            ExpressiveSettingsCard {
                                SettingsSwitchRow(
                                    icon = Icons.Rounded.CallMerge,
                                    title = "Плавный переход (Crossfade)",
                                    subtitle = if (crossfadeEnabled) "Длительность: %.1f сек (Equal-Power)".format(crossfadeDuration) else "Выключен",
                                    checked = crossfadeEnabled,
                                    onCheckedChange = { viewModel.setCrossfadeEnabled(it) }
                                )

                                AnimatedVisibility(
                                    visible = crossfadeEnabled,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Длительность плавного перехода",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "%.1f с".format(crossfadeDuration),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = crossfadeDuration.coerceIn(0.5f, 10.0f),
                                            onValueChange = {
                                                val rounded = (it * 2).roundToInt() / 2f
                                                viewModel.setCrossfadeDuration(rounded)
                                            },
                                            valueRange = 0.5f..10.0f,
                                            steps = 18
                                        )
                                    }
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.GraphicEq,
                                    title = "Smart Auto-DJ переходы",
                                    subtitle = if (autoDjEnabled) "Автоматический плавный микс (~3.5 с) и кроссфейд при переключении" else "Выключен (используется стандартный переход)",
                                    checked = autoDjEnabled,
                                    onCheckedChange = { viewModel.setAutoDjEnabled(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.TrendingDown,
                                    title = "Затухание в конце трека",
                                    subtitle = if (trackEndFadeOutEnabled) "Плавный уход в тишину (%.1f с) перед окончанием".format(trackEndFadeOutDuration) else "Выключено",
                                    checked = trackEndFadeOutEnabled,
                                    onCheckedChange = { viewModel.setTrackEndFadeOutEnabled(it) }
                                )

                                AnimatedVisibility(
                                    visible = trackEndFadeOutEnabled,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Время затухания в конце",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "%.1f с".format(trackEndFadeOutDuration),
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Slider(
                                            value = trackEndFadeOutDuration.coerceIn(1.0f, 10.0f),
                                            onValueChange = {
                                                val rounded = (it * 2).roundToInt() / 2f
                                                viewModel.setTrackEndFadeOutDuration(rounded)
                                            },
                                            valueRange = 1.0f..10.0f,
                                            steps = 17
                                        )
                                    }
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.TrendingUp,
                                    title = "Плавное нарастание при старте",
                                    subtitle = "Мягкий старт громкости без резких ударов",
                                    checked = fadeInOnStart,
                                    onCheckedChange = { viewModel.setFadeInOnStart(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.PauseCircle,
                                    title = "Плавное затухание при паузе",
                                    subtitle = "Мягкий сход громкости перед остановкой трека",
                                    checked = fadeOutOnPause,
                                    onCheckedChange = { viewModel.setFadeOutOnPause(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.AllInclusive,
                                    title = "Устранение микропауз (Gapless)",
                                    subtitle = if (gaplessPlayback) "Бесшовный стык треков внутри ExoPlayer" else "Выключено (стандартная пауза между треками)",
                                    checked = gaplessPlayback,
                                    onCheckedChange = { viewModel.setGaplessPlayback(it) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    1 -> {
                        item {
                            SectionTitle(title = "Цвета и темы")
                            ExpressiveSettingsCard {
                                val themeLabel = when (themeMode) {
                                    PreferencesManager.ThemeMode.SYSTEM -> "Как в системе"
                                    PreferencesManager.ThemeMode.LIGHT -> "Светлая"
                                    PreferencesManager.ThemeMode.DARK -> "Тёмная"
                                }
                                SettingsRow(
                                    icon = when (themeMode) {
                                        PreferencesManager.ThemeMode.SYSTEM -> Icons.Rounded.Smartphone
                                        PreferencesManager.ThemeMode.LIGHT -> Icons.Rounded.LightMode
                                        PreferencesManager.ThemeMode.DARK -> Icons.Rounded.DarkMode
                                    },
                                    title = "Тема оформления",
                                    subtitle = themeLabel,
                                    onClick = { showThemeDialog = true }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.Contrast,
                                    title = "AMOLED Тёмная тема",
                                    subtitle = "Глубокий чёрный цвет фона для экранов OLED",
                                    checked = amoledDark,
                                    onCheckedChange = { viewModel.setAmoledDark(it) }
                                )

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    SettingsSwitchRow(
                                        icon = Icons.Rounded.Palette,
                                        title = "Динамические цвета (Material You)",
                                        subtitle = "Палитра на основе системных обоев устройства",
                                        checked = dynamicColor,
                                        onCheckedChange = { viewModel.setDynamicColor(it) }
                                    )
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.Album,
                                    title = "Режим виниловой пластинки",
                                    subtitle = "Вращающийся виниловый диск со звуковыми дорожками в плеере",
                                    checked = vinylModeEnabled,
                                    onCheckedChange = { viewModel.setVinylModeEnabled(it) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            SectionTitle(title = "Производительность и графика")
                            ExpressiveSettingsCard {
                                val resolvedName = when (perfProfile.tier) {
                                    PerformanceTier.LOW -> "Низкая графика (Макс. FPS)"
                                    PerformanceTier.HIGH -> "Большая графика (Эффекты и детали)"
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Режим графики",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = "Активно сейчас: $resolvedName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val modes = listOf(
                                            PreferencesManager.PerformanceMode.HIGH to ("Большая графика" to "Эффекты и детали"),
                                            PreferencesManager.PerformanceMode.LOW to ("Низкая графика" to "Макс. FPS")
                                        )
                                        modes.forEach { (mode, labels) ->
                                            ExpressiveOvalChoiceChip(
                                                label = labels.first,
                                                subtitle = labels.second,
                                                selected = performanceMode == mode,
                                                onClick = { viewModel.setPerformanceMode(mode) },
                                                minHeight = 54.dp,
                                                fontSizeSp = 12.5f,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("perf_mode_${mode.name.lowercase()}")
                                            )
                                        }
                                    }
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.GraphicEq,
                                    title = "Аудио-реактивные GPU-шейдеры",
                                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        "Безопасная подсветка и пульсация в такт басу с автозащитой от сбоев"
                                    } else {
                                        "Адаптивная пульсация Canvas (оптимизировано для Android < 13)"
                                    },
                                    checked = audioReactiveShadersEnabled,
                                    onCheckedChange = { viewModel.setAudioReactiveShadersEnabled(it) }
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Живой спектр в мини-плеере",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = "Отображение эквалайзера прямо внутри нижней капсулы мини-плеера",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val spectrumOptions = listOf(
                                            "BARS" to "Столбики",
                                            "WAVE" to "Волна",
                                            "OFF" to "Выкл"
                                        )
                                        spectrumOptions.forEach { (key, label) ->
                                            ExpressiveOvalChoiceChip(
                                                label = label,
                                                selected = miniSpectrumMode.equals(key, ignoreCase = true),
                                                onClick = { viewModel.setMiniSpectrumMode(key) },
                                                minHeight = 42.dp,
                                                fontSizeSp = 12.5f,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("settings_spectrum_${key.lowercase()}")
                                            )
                                        }
                                    }
                                }

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.MotionPhotosOn,
                                    title = "Уменьшение движения (Reduced Motion)",
                                    subtitle = "Отключает пружинные и инерционные эффекты",
                                    checked = reducedMotion,
                                    onCheckedChange = { viewModel.setReducedMotion(it) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            SectionTitle(title = "Жидкое стекло (AndroidLiquidGlass)")
                            ExpressiveSettingsCard {
                                SettingsSwitchRow(
                                    icon = Icons.Rounded.BlurOn,
                                    title = "Жидкое стекло везде",
                                    subtitle = if (liquidGlassEnabled) {
                                        "Активно • Пресет: $liquidGlassPreset • Оптические линзы и блики Френеля"
                                    } else {
                                        "Выключено по умолчанию — включите для стеклянных панелей и преломления"
                                    },
                                    checked = liquidGlassEnabled,
                                    onCheckedChange = { viewModel.setLiquidGlassEnabled(it) },
                                    switchTestTag = "settings_liquid_glass_switch"
                                )

                                AnimatedVisibility(
                                    visible = liquidGlassEnabled,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Live Caustic Backdrop + LiquidGlassSurface Preview Card
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(112.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            LiquidGlassCausticBackdrop(modifier = Modifier.fillMaxSize())
                                            LiquidGlassSurface(
                                                shape = RoundedCornerShape(20.dp),
                                                fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(14.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "Предпросмотр линзы • $liquidGlassPreset",
                                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold)
                                                        )
                                                        Text(
                                                            text = "Блюр ${liquidGlassBlurDp.roundToInt()}dp • Преломление ${(liquidGlassRefraction * 100).roundToInt()}%",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Rounded.AutoAwesome,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }

                                        Text(
                                            text = "Оптический пресет стекла",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        val presets = listOf(
                                            LiquidGlassConfig.PRESET_CRYSTAL to "Кристалл",
                                            LiquidGlassConfig.PRESET_SILK to "Шёлк",
                                            LiquidGlassConfig.PRESET_PRISM to "Призма",
                                            LiquidGlassConfig.PRESET_OBSIDIAN to "Обсидиан"
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            presets.chunked(2).forEach { rowItems ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    rowItems.forEach { (key, title) ->
                                                        ExpressiveOvalChoiceChip(
                                                            label = title,
                                                            selected = liquidGlassPreset.equals(key, ignoreCase = true),
                                                            onClick = { viewModel.applyLiquidGlassPreset(key) },
                                                            minHeight = 44.dp,
                                                            fontSizeSp = 12.5f,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .testTag("liquid_glass_preset_${key.lowercase()}")
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        LiquidGlassSliderRow(
                                            label = "Размытие линзы (Blur)",
                                            valueText = "${liquidGlassBlurDp.roundToInt()} dp",
                                            value = liquidGlassBlurDp,
                                            valueRange = 0f..32f,
                                            onValueChange = { viewModel.setLiquidGlassBlurDp(it) }
                                        )
                                        LiquidGlassSliderRow(
                                            label = "Дисперсия и преломление",
                                            valueText = "${(liquidGlassRefraction * 100).roundToInt()}%",
                                            value = liquidGlassRefraction,
                                            valueRange = 0f..1f,
                                            onValueChange = { viewModel.setLiquidGlassRefraction(it) }
                                        )
                                        LiquidGlassSliderRow(
                                            label = "Матовость (Frost Alpha)",
                                            valueText = "${(liquidGlassFrostAlpha * 100).roundToInt()}%",
                                            value = liquidGlassFrostAlpha,
                                            valueRange = 0.05f..0.85f,
                                            onValueChange = { viewModel.setLiquidGlassFrostAlpha(it) }
                                        )
                                        LiquidGlassSliderRow(
                                            label = "Блики и кайма Френеля",
                                            valueText = "${(liquidGlassHighlight * 100).roundToInt()}%",
                                            value = liquidGlassHighlight,
                                            valueRange = 0f..1f,
                                            onValueChange = { viewModel.setLiquidGlassHighlight(it) }
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                                    viewModel.applyLiquidGlassPreset(LiquidGlassConfig.PRESET_CRYSTAL)
                                                }
                                            ) {
                                                Text("Сбросить оптику", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    2 -> {
                        item {
                            SectionTitle(title = "Жесты полноэкранного плеера")
                            ExpressiveSettingsCard {
                                SettingsRow(
                                    icon = Icons.Rounded.Swipe,
                                    title = "Направление свайпа по обложке",
                                    subtitle = if (playerSwipeDirection == "RIGHT_NEXT_LEFT_PREV") "Свайп влево: Предыдущий • Вправо: Следующий" else "Свайп влево: Следующий • Вправо: Предыдущий",
                                    onClick = { showSwipeDirDialog = true }
                                )

                                SettingsRow(
                                    icon = Icons.Rounded.TouchApp,
                                    title = "Двойное касание по краям обложки",
                                    subtitle = "Перемотка назад / вперед на $doubleTapSeekSeconds сек",
                                    onClick = { showDoubleTapSeekDialog = true }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.VolumeDown,
                                    title = "Жест громкости на экране",
                                    subtitle = "Вертикальное скольжение по экрану меняет громкость",
                                    checked = volumeGestureEnabled,
                                    onCheckedChange = { viewModel.setVolumeGestureEnabled(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.Vibration,
                                    title = "Тактильный отклик (Haptic Feedback)",
                                    subtitle = "Вибрация при свайпах, переключении треков и табов",
                                    checked = hapticEnabled,
                                    onCheckedChange = { viewModel.setHapticEnabled(it) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    3 -> {
                        item {
                            SectionTitle(title = "Медиатека и поиск")
                            ExpressiveSettingsCard {
                                SettingsRow(
                                    icon = Icons.Rounded.Refresh,
                                    title = "Сканировать аудиофайлы",
                                    subtitle = if (isScanning) "Идет сканирование..." else "В библиотеке ${allSongs.size} треков",
                                    onClick = { viewModel.scanLibrary() }
                                )

                                SettingsRow(
                                    icon = Icons.Rounded.Tune,
                                    title = "Поведение при смене сортировки",
                                    subtitle = "Всегда закреплять список вверху (без прыжков к треку)",
                                    onClick = {}
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.FilterAlt,
                                    title = "Скрывать короткие аудиозаписи (< 30с)",
                                    subtitle = "Исключать системные звуки, рингтоны и диктофон",
                                    checked = filterShortAudio,
                                    onCheckedChange = { viewModel.setFilterShortAudio(it) }
                                )

                                SettingsSwitchRow(
                                    icon = Icons.Rounded.History,
                                    title = "Возобновлять воспроизведение",
                                    subtitle = "Запоминать последний трек и позицию при старте",
                                    checked = resumePlayback,
                                    onCheckedChange = { viewModel.setResumePlayback(it) }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    4 -> {
                        item {
                            SectionTitle(title = "Расширения и модули")
                            ExpressiveSettingsCard {
                                val activeCount = plugins.count { it.isEnabled }
                                SettingsRow(
                                    icon = Icons.Rounded.Extension,
                                    title = "Менеджер плагинов",
                                    subtitle = "Активно: $activeCount из ${plugins.size} модулей",
                                    onClick = { showPluginSheet = true }
                                )

                                SettingsRow(
                                    icon = Icons.Rounded.Science,
                                    title = "Экспериментальная лаборатория",
                                    subtitle = "Пользовательские скрипты и DSP фильтры",
                                    onClick = { showPluginSheet = true }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        item {
                            SectionTitle(title = "О приложении")
                            ExpressiveSettingsCard {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = "imux player",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                                        )
                                        Text(
                                            text = "Версия 2.3.0 • Material 3 Expressive & Multi-Engine",
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = MonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
private fun ExpressiveSettingsCard(
    content: @Composable () -> Unit
) {
    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
        fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val haptic = rememberHapticHelper()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable {
                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String = ""
) {
    val haptic = rememberHapticHelper()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable {
                haptic.performHaptic(HapticFeedbackType.MEDIUM_TICK)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        com.example.ui.components.ExpressiveGeometricSwitch(
            checked = checked,
            onCheckedChange = {
                haptic.performHaptic(HapticFeedbackType.MEDIUM_TICK)
                onCheckedChange(it)
            },
            pattern = com.example.ui.components.GeometricPatternKind.ARC,
            modifier = if (switchTestTag.isNotBlank()) Modifier.testTag(switchTestTag) else Modifier
        )
    }
}
