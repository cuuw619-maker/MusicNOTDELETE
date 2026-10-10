package com.example.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EqualizerState
import com.example.ui.animation.HapticType
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.EmptyStateView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    equalizerState: EqualizerState,
    onToggleEnabled: (Boolean) -> Unit,
    onBandLevelChange: (Short, Short) -> Unit,
    onBassBoostChange: (Short) -> Unit,
    onVirtualizerChange: (Short) -> Unit,
    onLoudnessEnhancerChange: (Int) -> Unit = {},
    spatialReverbPreset: String = "NONE",
    stereoWidener: Int = 0,
    audioBalance: Float = 0f,
    onReverbPresetChange: (String) -> Unit = {},
    onStereoWidenerChange: (Int) -> Unit = {},
    onAudioBalanceChange: (Float) -> Unit = {},
    onSelectPreset: (Short) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier.testTag("equalizer_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with Switch & Reset Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Студийный Эквалайзер",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (equalizerState.isEnabled) "DSP обработка активна" else "Эффекты выключены",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reset / Flat Button
                    if (equalizerState.isEnabled && equalizerState.bands.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                equalizerState.bands.forEach { band ->
                                    onBandLevelChange(band.bandIndex, 0)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = "Сбросить в 0 dB",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = equalizerState.isEnabled,
                        onCheckedChange = {
                            performHapticFeedback(view, HapticType.TOGGLE)
                            onToggleEnabled(it)
                        },
                        modifier = Modifier.testTag("equalizer_master_switch")
                    )
                }
            }

            if (!equalizerState.isAvailable) {
                EmptyStateView(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Эквалайзер недоступен",
                    subtitle = "Запустите воспроизведение трека для подключения аудиоэффектов устройства.",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Interactive Frequency Response Spectrum Chart Canvas
                val primaryColor = MaterialTheme.colorScheme.primary
                val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .padding(vertical = 4.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val midY = h / 2f

                            // Center 0 dB guide line
                            drawLine(
                                color = outlineColor,
                                start = Offset(0f, midY),
                                end = Offset(w, midY),
                                strokeWidth = 1.5f
                            )

                            // Top +15dB & Bottom -15dB guide lines
                            drawLine(
                                color = outlineColor.copy(alpha = 0.15f),
                                start = Offset(0f, h * 0.15f),
                                end = Offset(w, h * 0.15f),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = outlineColor.copy(alpha = 0.15f),
                                start = Offset(0f, h * 0.85f),
                                end = Offset(w, h * 0.85f),
                                strokeWidth = 1f
                            )

                            val bands = equalizerState.bands
                            if (bands.isNotEmpty()) {
                                val bandPoints = bands.mapIndexed { index, band ->
                                    val x = (index / (bands.size - 1).toFloat()) * w
                                    val range = (band.maxLevelMilliBels - band.minLevelMilliBels).toFloat().coerceAtLeast(1f)
                                    val norm = (band.currentLevelMilliBels - band.minLevelMilliBels) / range
                                    // Invert Y: 1.0 = top (0.12h), 0.0 = bottom (0.88h)
                                    val y = (h * 0.88f) - (norm * (h * 0.76f))
                                    Offset(x, y)
                                }

                                // Build smooth spline path
                                val curvePath = Path().apply {
                                    moveTo(bandPoints.first().x, bandPoints.first().y)
                                    for (i in 0 until bandPoints.size - 1) {
                                        val p0 = bandPoints[i]
                                        val p1 = bandPoints[i + 1]
                                        val cX = (p0.x + p1.x) / 2f
                                        cubicTo(cX, p0.y, cX, p1.y, p1.x, p1.y)
                                    }
                                }

                                // Gradient Fill beneath curve
                                val fillPath = Path().apply {
                                    addPath(curvePath)
                                    lineTo(w, h)
                                    lineTo(0f, h)
                                    close()
                                }
                                drawPath(
                                    fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            primaryColor.copy(alpha = 0.35f),
                                            primaryColor.copy(alpha = 0.02f)
                                        )
                                    )
                                )

                                // Stroke Line
                                drawPath(
                                    curvePath,
                                    color = if (equalizerState.isEnabled) primaryColor else Color.Gray,
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )

                                // Control Points
                                bandPoints.forEach { pt ->
                                    drawCircle(
                                        color = if (equalizerState.isEnabled) primaryColor else Color.Gray,
                                        radius = 4.dp.toPx(),
                                        center = pt
                                    )
                                }
                            }
                        }

                        // dB Labels
                        Text(
                            text = "+15 dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.TopStart)
                        )
                        Text(
                            text = "0 dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                        Text(
                            text = "-15 dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets
                if (equalizerState.presets.isNotEmpty()) {
                    Text(
                        text = "Студийные пресеты",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(equalizerState.presets) { index, presetName ->
                            val isSelected = equalizerState.currentPresetIndex.toInt() == index
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    performHapticFeedback(view, HapticType.LIGHT)
                                    onSelectPreset(index.toShort())
                                },
                                label = { Text(presetName) },
                                leadingIcon = {
                                    val icon = when {
                                        presetName.contains("Rock", true) -> Icons.Rounded.GraphicEq
                                        presetName.contains("Pop", true) -> Icons.Rounded.MusicNote
                                        presetName.contains("Bass", true) -> Icons.Rounded.Speaker
                                        presetName.contains("Jazz", true) || presetName.contains("Classic", true) -> Icons.Rounded.AudioFile
                                        else -> Icons.Rounded.Tune
                                    }
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                enabled = equalizerState.isEnabled
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                // Frequency Bands Sliders
                if (equalizerState.bands.isNotEmpty()) {
                    Text(
                        text = "Частотные регуляторы",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            equalizerState.bands.forEach { band ->
                                val freqText = if (band.centerFreqHz >= 1000) {
                                    "${band.centerFreqHz / 1000} кГц"
                                } else {
                                    "${band.centerFreqHz} Гц"
                                }
                                val range = (band.maxLevelMilliBels - band.minLevelMilliBels).toFloat()
                                val fraction = if (range > 0) {
                                    (band.currentLevelMilliBels - band.minLevelMilliBels) / range
                                } else 0.5f

                                val dB = band.currentLevelMilliBels / 100

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = freqText,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.width(68.dp)
                                    )

                                    Slider(
                                        value = fraction.coerceIn(0f, 1f),
                                        onValueChange = { frac ->
                                            val newLevel = (band.minLevelMilliBels + (frac * range)).toInt().toShort()
                                            onBandLevelChange(band.bandIndex, newLevel)
                                        },
                                        enabled = equalizerState.isEnabled,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Text(
                                        text = "${if (dB > 0) "+$dB" else "$dB"} dB",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (dB != 0) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (dB != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(54.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }

                // Audio Effects: Bass Boost, Virtualizer & Loudness Enhancer
                Text(
                    text = "Звуковые эффекты студии",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Bass Boost
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speaker,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Усиление басов (Bass Boost)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Slider(
                                    value = (equalizerState.bassBoostStrength / 1000f).coerceIn(0f, 1f),
                                    onValueChange = {
                                        onBassBoostChange((it * 1000).toInt().toShort())
                                    },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(equalizerState.bassBoostStrength / 10)}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Virtualizer (3D Audio)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SurroundSound,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "3D Пространственный звук (Virtualizer)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Slider(
                                    value = (equalizerState.virtualizerStrength / 1000f).coerceIn(0f, 1f),
                                    onValueChange = {
                                        onVirtualizerChange((it * 1000).toInt().toShort())
                                    },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(equalizerState.virtualizerStrength / 10)}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Loudness Enhancer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Усиление громкости (Loudness Gain)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Slider(
                                    value = (equalizerState.loudnessEnhancerStrength / 1000f).coerceIn(0f, 1f),
                                    onValueChange = {
                                        onLoudnessEnhancerChange((it * 1000).toInt())
                                    },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${equalizerState.loudnessEnhancerStrength / 100} dB",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 3D Soundstage & Acoustic Environment (Reverb, Stereo Expander, L/R Balance)
                Text(
                    text = "3D Пространственная сцена и Акустика",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        // Reverb Room Presets
                        Text(
                            text = "Акустика помещения (Reverb)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val reverbPresets = listOf(
                            "NONE" to "Выкл",
                            "SMALL_ROOM" to "Комната",
                            "MEDIUM_ROOM" to "Студия",
                            "LARGE_ROOM" to "Зал",
                            "MEDIUM_HALL" to "Арена",
                            "LARGE_HALL" to "Собор",
                            "PLATE" to "Пластина"
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(reverbPresets.size) { index ->
                                val (key, label) = reverbPresets[index]
                                val isSelected = spatialReverbPreset == key
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        performHapticFeedback(view, HapticType.LIGHT)
                                        onReverbPresetChange(key)
                                    },
                                    label = { Text(label) },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Stereo Widener
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Стерео-панорама (Stereo Widener)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Slider(
                                    value = (stereoWidener / 100f).coerceIn(0f, 1f),
                                    onValueChange = {
                                        onStereoWidenerChange((it * 100).toInt())
                                    },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "$stereoWidener%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Audio Balance (L/R)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val balanceLabel = when {
                                    audioBalance < -0.05f -> "Левый канал (L ${(-audioBalance * 100).toInt()}%)"
                                    audioBalance > 0.05f -> "Правый канал (R ${(audioBalance * 100).toInt()}%)"
                                    else -> "Баланс L / R (Центр)"
                                }
                                Text(
                                    text = balanceLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Slider(
                                    value = audioBalance.coerceIn(-1.0f, 1.0f),
                                    valueRange = -1.0f..1.0f,
                                    onValueChange = {
                                        // Snap to center if near 0
                                        val snapped = if (kotlin.math.abs(it) < 0.08f) 0f else it
                                        onAudioBalanceChange(snapped)
                                    },
                                    enabled = equalizerState.isEnabled
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (kotlin.math.abs(audioBalance) < 0.05f) "0" else "%.1f".format(audioBalance),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
