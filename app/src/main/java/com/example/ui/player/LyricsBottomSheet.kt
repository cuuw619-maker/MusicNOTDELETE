package com.example.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.lyrics.DualLyricLine
import com.example.lyrics.LyricSpeaker
import com.example.lyrics.LyricWord
import com.example.model.Song
import com.example.ui.components.ConnectedGroupItem
import com.example.ui.components.ExpressiveConnectedButtonGroup
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.theme.MonoFontFamily
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper
import com.example.ui.viewmodel.MusicViewModel
import kotlin.math.abs

enum class LyricsDisplayMode {
    SYNCED,
    PARALLEL,
    PLAIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    song: Song,
    currentPositionMs: Long = 0L,
    viewModel: MusicViewModel,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = rememberHapticHelper()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsOffsetMs by viewModel.lyricsOffsetMs.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()

    var displayMode by remember { mutableStateOf(LyricsDisplayMode.SYNCED) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var isOffsetExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var customLyricsText by remember { mutableStateOf("") }

    // 60/120fps Display-Link Position Clock anchored softly to ExoPlayer's live position
    var smoothPosMs by remember(song.id) { mutableLongStateOf(viewModel.playbackPositionMs.value) }
    val latestLivePosRef = remember(song.id) { mutableLongStateOf(viewModel.playbackPositionMs.value) }
    val latestSpeed by rememberUpdatedState(playbackSpeed)

    LaunchedEffect(song.id) {
        viewModel.playbackPositionMs.collect { pos ->
            latestLivePosRef.longValue = pos
            if (!playbackState.isPlaying || abs(pos - smoothPosMs) > 110L) {
                smoothPosMs = pos
            }
        }
    }

    LaunchedEffect(playbackState.isPlaying, song.id) {
        smoothPosMs = latestLivePosRef.longValue
        if (!playbackState.isPlaying) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos ->
                val dtMs = ((frameNanos - lastFrameNanos) / 1_000_000.0 * latestSpeed).toLong()
                    .coerceIn(0L, 100L)
                lastFrameNanos = frameNanos
                val predicted = smoothPosMs + dtMs
                val playerNow = latestLivePosRef.longValue
                smoothPosMs = if (abs(playerNow - predicted) > 95L) playerNow else predicted
            }
        }
    }

    val latestOffsetMs by rememberUpdatedState(lyricsOffsetMs)
    val currentTimeProvider: () -> Long = remember(song.id) {
        { smoothPosMs + latestOffsetMs }
    }

    LaunchedEffect(song.id) {
        viewModel.loadLyrics(song)
    }

    val effectiveLines = remember(lyricsState.lines, customLyricsText) {
        if (customLyricsText.isNotBlank()) {
            viewModel.lyricsManager.parseLrc(customLyricsText)
        } else {
            lyricsState.lines
        }
    }

    val hasDuetLines = remember(effectiveLines, lyricsState.hasDuet) {
        lyricsState.hasDuet || effectiveLines.any {
            it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH
        }
    }

    // Simultaneous overlapping active intervals (supports duets singing at the same time!)
    val activeIndices by remember(effectiveLines) {
        derivedStateOf {
            if (effectiveLines.isEmpty()) emptySet()
            else {
                val clockMs = smoothPosMs + latestOffsetMs
                val overlapping = effectiveLines.indices.filter { i ->
                    val line = effectiveLines[i]
                    line.isSynced && clockMs >= line.timestampMs && clockMs <= line.endTimeMs
                }.toSet()
                if (overlapping.isNotEmpty()) {
                    overlapping
                } else {
                    val fallbackIdx = effectiveLines.indexOfLast { it.timestampMs <= clockMs }
                    if (fallbackIdx != -1) setOf(fallbackIdx) else setOf(0)
                }
            }
        }
    }

    val primaryActiveIndex = activeIndices.minOrNull() ?: -1

    LaunchedEffect(primaryActiveIndex, displayMode) {
        if (displayMode != LyricsDisplayMode.PLAIN && primaryActiveIndex in effectiveLines.indices) {
            listState.animateScrollToItem(
                index = (primaryActiveIndex - 2).coerceAtLeast(0)
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = Color(0xFF0E0F14),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("lyrics_bottom_sheet")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FluidBlurredArtworkBackdrop(
                artworkUri = song.albumArtUriString,
                isPlaying = playbackState.isPlaying,
                accentColor = MaterialTheme.colorScheme.primary
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp, bottom = 20.dp)
            ) {
                // Sheet Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.30f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.14f)
                        ) {
                            if (!song.albumArtUriString.isNullOrBlank()) {
                                AsyncImage(
                                    model = song.albumArtUriString,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lyrics,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = lyricsState.title.ifBlank { song.title },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = buildString {
                                        append(lyricsState.artist.ifBlank { song.artist })
                                        if (lyricsState.source.isNotBlank()) {
                                            append(" • ")
                                            append(lyricsState.source)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.74f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (hasDuetLines) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFA8E6FF).copy(alpha = 0.22f)
                                    ) {
                                        Text(
                                            text = "Дуэт",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color(0xFFA8E6FF),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { isOffsetExpanded = !isOffsetExpanded },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = "Калибровка тайминга",
                                tint = if (isOffsetExpanded || lyricsOffsetMs != 0L)
                                    MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(
                            onClick = { isSearchExpanded = !isSearchExpanded },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Поиск текста",
                                tint = if (isSearchExpanded) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.loadLyrics(song) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Обновить текст",
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(
                            onClick = { isEditMode = !isEditMode },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.EditNote,
                                contentDescription = "Редактировать текст",
                                tint = if (isEditMode) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }

                // Timing Offset Calibration Capsule
                AnimatedVisibility(visible = isOffsetExpanded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Сдвиг:",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                            OffsetStepButton("-0.25с") {
                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                viewModel.nudgeLyricsOffset(song.id, -250L)
                            }
                            OffsetStepButton("-0.05с") {
                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                viewModel.nudgeLyricsOffset(song.id, -50L)
                            }
                            Surface(
                                onClick = {
                                    haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                    viewModel.resetLyricsOffset(song.id)
                                },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                            ) {
                                val sign = if (lyricsOffsetMs > 0) "+" else ""
                                Text(
                                    text = "$sign${lyricsOffsetMs} мс",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            OffsetStepButton("+0.05с") {
                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                viewModel.nudgeLyricsOffset(song.id, 50L)
                            }
                            OffsetStepButton("+0.25с") {
                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                viewModel.nudgeLyricsOffset(song.id, 250L)
                            }
                        }
                    }
                }

                // Search Bar (collapsible)
                AnimatedVisibility(visible = isSearchExpanded) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.10f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        "Исполнитель и название песни...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (searchQuery.isNotBlank()) {
                                        focusManager.clearFocus()
                                        viewModel.searchLyricsManually(searchQuery)
                                    }
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.White.copy(alpha = 0.5f),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                                ),
                                shape = RoundedCornerShape(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            TextButton(
                                onClick = {
                                    if (searchQuery.isNotBlank()) {
                                        focusManager.clearFocus()
                                        viewModel.searchLyricsManually(searchQuery)
                                    }
                                }
                            ) {
                                Text("Найти", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // M3 Expressive Connected Display Mode Switcher
                val modeItems = remember(lyricsState.hasDualLines) {
                    listOf(
                        ConnectedGroupItem("Караоке", Icons.Rounded.Lyrics),
                        ConnectedGroupItem(
                            if (lyricsState.hasDualLines) "Перевод •" else "Параллельно",
                            Icons.Rounded.Subtitles
                        ),
                        ConnectedGroupItem("Текст", Icons.Rounded.TextFields)
                    )
                }
                ExpressiveConnectedButtonGroup(
                    items = modeItems,
                    selectedIndex = displayMode.ordinal,
                    onSelect = { idx ->
                        haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                        displayMode = LyricsDisplayMode.entries[idx]
                    },
                    containerColor = Color.White.copy(alpha = 0.09f),
                    selectedContainerColor = Color.White.copy(alpha = 0.26f),
                    selectedContentColor = Color.White,
                    unselectedContentColor = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Lyrics Content Stage
                if (lyricsState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ExpressiveLoadingIndicator(
                                size = 56.dp,
                                color = Color.White,
                                containerColor = Color.White.copy(alpha = 0.14f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Синхронизация по слогам и таймингам...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                } else if (isEditMode) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Поддерживается LRC (<00:12.30>Слово, v1:/v2:/both:/bg:), TTML и SRT:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.80f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = customLyricsText,
                            onValueChange = { customLyricsText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            placeholder = {
                                Text(
                                    "[00:05.00] v1: <00:05.00>Hello // Привет\n[00:05.00] v2: World together",
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White.copy(alpha = 0.6f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.28f)
                            ),
                            shape = RoundedCornerShape(18.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { customLyricsText = "" }) {
                                Text("Очистить", color = Color.White.copy(alpha = 0.7f))
                            }
                            TextButton(onClick = { isEditMode = false }) {
                                Text("Применить", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (effectiveLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.Lyrics,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(60.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = lyricsState.errorMessage ?: "Текст не найден",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Surface(
                                onClick = { isSearchExpanded = true },
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Найти вручную",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                } else {
                    when (displayMode) {
                        LyricsDisplayMode.PLAIN -> {
                            val plainContent = lyricsState.plainLyrics.ifBlank {
                                effectiveLines.filter { !it.isInstrumental }.joinToString("\n") { it.primaryText }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 16.dp)
                            ) {
                                Text(
                                    text = plainContent,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        lineHeight = 32.sp,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = Color.White.copy(alpha = 0.94f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        LyricsDisplayMode.SYNCED, LyricsDisplayMode.PARALLEL -> {
                            val showSecondary = (displayMode == LyricsDisplayMode.PARALLEL)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 48.dp, bottom = 140.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                itemsIndexed(
                                    items = effectiveLines,
                                    key = { idx, item -> "${idx}_${item.timestampMs}_${item.speaker.name}" }
                                ) { index, line ->
                                    val isActive = index in activeIndices
                                    val distance = if (isActive) {
                                        0
                                    } else {
                                        activeIndices.minOfOrNull { abs(index - it) } ?: abs(index - primaryActiveIndex)
                                    }

                                    BetterLyricLineItem(
                                        line = line,
                                        isActive = isActive,
                                        distanceFromActive = distance,
                                        hasDuet = hasDuetLines,
                                        currentTimeProvider = currentTimeProvider,
                                        showSecondary = showSecondary,
                                        onClick = {
                                            if (line.timestampMs >= 0) {
                                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                                onSeekTo((line.timestampMs - lyricsOffsetMs).coerceAtLeast(0L))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FluidBlurredArtworkBackdrop(
    artworkUri: String?,
    isPlaying: Boolean,
    accentColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "artwork_warp")

    val driftAngle1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot1"
    )
    val driftAngle2 by infiniteTransition.animateFloat(
        initialValue = 180f,
        targetValue = -180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 48000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot2"
    )
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 1.25f,
        targetValue = 1.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val warpOffset by infiniteTransition.animateFloat(
        initialValue = -70f,
        targetValue = 70f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "warp"
    )

    val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(75.dp)
    } else {
        Modifier
    }

    val motionFactor by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.15f,
        animationSpec = tween(700),
        label = "bg_play_factor"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 1.15f + (breatheScale - 1.15f) * motionFactor
                        scaleX = s * 1.15f
                        scaleY = s
                        rotationZ = driftAngle1 * 0.25f * motionFactor
                        translationX = warpOffset * 0.8f * motionFactor
                        translationY = -warpOffset * 0.5f * motionFactor
                        alpha = 0.85f
                    }
                    .then(blurMod)
            )
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.45f
                        scaleY = 1.55f
                        rotationZ = driftAngle2 * 0.35f * motionFactor
                        translationX = -warpOffset * motionFactor
                        translationY = warpOffset * 0.7f * motionFactor
                        alpha = 0.55f
                    }
                    .then(blurMod)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.45f),
                                Color(0xFF141824),
                                Color(0xFF0A0B0E)
                            )
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xA608090D),
                            Color(0x780A0C12),
                            Color(0xC207080C)
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BetterLyricLineItem(
    line: DualLyricLine,
    isActive: Boolean,
    distanceFromActive: Int,
    hasDuet: Boolean,
    currentTimeProvider: () -> Long,
    showSecondary: Boolean,
    onClick: () -> Unit
) {
    val isSecond = line.speaker == LyricSpeaker.SECOND
    val isBoth = line.speaker == LyricSpeaker.BOTH
    val isBg = line.isBackground || line.speaker == LyricSpeaker.BACKGROUND

    val columnAlignment = when {
        isSecond -> Alignment.End
        isBoth -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }
    val textAlign = when {
        isSecond -> TextAlign.End
        isBoth -> TextAlign.Center
        else -> TextAlign.Start
    }
    val flowAlignment = when {
        isSecond -> Alignment.End
        isBoth -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }

    val voiceHighlightColor = when {
        isSecond -> Color(0xFFA8E6FF)
        isBoth -> Color(0xFFFFE4A8)
        isBg -> Color.White.copy(alpha = 0.85f)
        else -> Color.White
    }

    val targetScale = when {
        isActive && isBg -> 1.0f
        isActive -> 1.03f
        distanceFromActive == 1 -> 0.95f
        else -> 0.90f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "line_scale"
    )

    val targetAlpha = when {
        isActive && isBg -> 0.86f
        isActive -> 1f
        distanceFromActive == 1 -> 0.54f
        distanceFromActive == 2 -> 0.35f
        else -> 0.22f
    }
    val lineAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(280),
        label = "line_alpha"
    )

    val depthBlurModifier = if (!isActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = when (distanceFromActive) {
            1 -> 1.2.dp
            2 -> 2.4.dp
            else -> 3.6.dp
        }
        Modifier.blur(radius)
    } else {
        Modifier
    }

    val targetContainerColor = when {
        !isActive || line.isInstrumental -> Color.Transparent
        isSecond -> Color(0xFFA8E6FF).copy(alpha = 0.10f)
        isBoth -> Color(0xFFFFE4A8).copy(alpha = 0.11f)
        isBg -> Color.White.copy(alpha = 0.05f)
        else -> Color.White.copy(alpha = 0.09f)
    }
    val containerBg by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(250),
        label = "line_bg"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = lineAlpha
            }
            .then(depthBlurModifier)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = containerBg,
        shape = RoundedCornerShape(20.dp)
    ) {
        if (line.isInstrumental) {
            InstrumentalInterludeDots(
                startMs = line.timestampMs,
                endMs = line.endTimeMs,
                currentTimeProvider = currentTimeProvider,
                isActive = isActive
            )
        } else {
            val fontSizeSp = if (isBg) 18.sp else 24.sp
            val lineHeightSp = if (isBg) 25.sp else 32.sp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isBg && !isSecond) 22.dp else 14.dp,
                        end = if (isBg && isSecond) 22.dp else 14.dp,
                        top = if (isBg) 6.dp else 10.dp,
                        bottom = if (isBg) 6.dp else 10.dp
                    ),
                horizontalAlignment = columnAlignment
            ) {
                if (hasDuet && !line.speakerLabel.isNullOrBlank() && !isBg) {
                    Surface(
                        shape = CircleShape,
                        color = voiceHighlightColor.copy(alpha = if (isActive) 0.22f else 0.10f),
                        modifier = Modifier.padding(bottom = 5.dp)
                    ) {
                        Text(
                            text = line.speakerLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = voiceHighlightColor.copy(alpha = if (isActive) 0.95f else 0.72f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                if (isActive && line.words.isNotEmpty() && line.isSynced) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp, flowAlignment),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        line.words.forEach { word ->
                            LetterSweepWord(
                                word = word,
                                highlightColor = voiceHighlightColor,
                                fontSizeSp = if (isBg) 18 else 25,
                                isItalic = isBg,
                                currentTimeProvider = currentTimeProvider
                            )
                        }
                    }
                } else {
                    Text(
                        text = line.primaryText,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = if (isActive && !isBg) FontWeight.ExtraBold else FontWeight.Bold,
                            fontStyle = if (isBg) FontStyle.Italic else FontStyle.Normal,
                            fontSize = fontSizeSp,
                            lineHeight = lineHeightSp
                        ),
                        color = if (isActive) voiceHighlightColor else voiceHighlightColor.copy(alpha = 0.72f),
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (showSecondary && !line.secondaryText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = line.secondaryText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = if (isActive) 16.sp else 14.sp,
                            lineHeight = 22.sp
                        ),
                        color = if (isActive) Color(0xFFB9E6FF) else Color.White.copy(alpha = 0.55f),
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Renders a single word with smooth left-to-right letter luminance sweep + soft glow & lift.
 * Reads [currentTimeProvider] strictly in Layer/Draw phases -> ZERO per-frame recompositions!
 */
@Composable
private fun LetterSweepWord(
    word: LyricWord,
    highlightColor: Color,
    fontSizeSp: Int,
    isItalic: Boolean,
    currentTimeProvider: () -> Long
) {
    val wordStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = if (isItalic) FontWeight.Bold else FontWeight.ExtraBold,
        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 8).sp
    )
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(60L).toFloat()

    Box(
        modifier = Modifier.graphicsLayer {
            val nowMs = currentTimeProvider()
            val progress = ((nowMs - word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)
            val isCurrentWord = nowMs in word.startTimeMs..word.endTimeMs
            val liftY = when {
                isCurrentWord -> -2.2f * (1f - abs(progress - 0.5f) * 2f)
                progress >= 1f -> -0.8f
                else -> 0f
            }
            translationY = liftY.dp.toPx()
        }
    ) {
        Text(
            text = word.text,
            style = wordStyle,
            color = highlightColor.copy(alpha = 0.34f)
        )

        Text(
            text = word.text,
            style = wordStyle.copy(
                shadow = Shadow(
                    color = highlightColor.copy(alpha = 0.52f),
                    blurRadius = 12f
                )
            ),
            color = highlightColor,
            modifier = Modifier.drawWithContent {
                val nowMs = currentTimeProvider()
                val progress = ((nowMs - word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)
                if (progress > 0f) {
                    clipRect(right = size.width * progress) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
        )
    }
}

@Composable
private fun InstrumentalInterludeDots(
    startMs: Long,
    endMs: Long,
    currentTimeProvider: () -> Long,
    isActive: Boolean
) {
    val span = (endMs - startMs).coerceAtLeast(1000L).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (dotIdx in 0..2) {
            val threshold = dotIdx / 3f
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        val overallProgress = if (isActive) {
                            ((currentTimeProvider() - startMs) / span).coerceIn(0f, 1f)
                        } else 0f
                        val dotProgress = ((overallProgress - threshold) * 3f).coerceIn(0f, 1f)
                        val dotScale = 0.78f + 0.38f * dotProgress
                        scaleX = dotScale
                        scaleY = dotScale
                        alpha = if (isActive) (0.30f + 0.70f * dotProgress) else 0.25f
                    }
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun OffsetStepButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.14f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}
