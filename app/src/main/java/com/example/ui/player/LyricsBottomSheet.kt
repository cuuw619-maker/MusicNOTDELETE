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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.request.ImageRequest
import com.example.lyrics.DualLyricLine
import com.example.lyrics.LyricSpeaker
import com.example.lyrics.LyricWord
import com.example.model.Song
import com.example.ui.components.AgslAmbientGlowCanvas
import com.example.ui.components.AmbientGeometricShapesOverlay
import com.example.ui.components.ConnectedGroupItem
import com.example.ui.components.ExpressiveConnectedButtonGroup
import com.example.ui.components.ExpressiveHorizontalFloatingToolbar
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.FloatingToolbarStyle
import com.example.ui.components.ToolbarActionItem
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.PerformanceProfile
import com.example.ui.theme.SpringTokens
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper
import com.example.ui.viewmodel.MusicViewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current

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
            if (!playbackState.isPlaying || abs(pos - smoothPosMs) > 420L) {
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
                    .coerceIn(0L, 80L)
                lastFrameNanos = frameNanos
                val predicted = smoothPosMs + dtMs
                val playerNow = latestLivePosRef.longValue
                val drift = playerNow - predicted
                smoothPosMs = when {
                    abs(drift) > 420L -> playerNow
                    abs(drift) > 8L -> predicted + (drift * 0.16f).toLong()
                    else -> predicted
                }
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

    val isSyncedAvailable = remember(effectiveLines, lyricsState.isSynced, customLyricsText) {
        if (customLyricsText.isNotBlank()) {
            effectiveLines.any { it.isSynced }
        } else {
            lyricsState.isSynced || effectiveLines.any { it.isSynced }
        }
    }
    val isWordSyncedAvailable = remember(effectiveLines, lyricsState.hasWordSync) {
        lyricsState.hasWordSync || effectiveLines.any { it.hasWordTiming }
    }

    LaunchedEffect(song.id, lyricsState.isLoading, isSyncedAvailable, effectiveLines.size) {
        if (!lyricsState.isLoading && effectiveLines.isNotEmpty() && !isSyncedAvailable && displayMode == LyricsDisplayMode.SYNCED) {
            displayMode = LyricsDisplayMode.PLAIN
        } else if (!lyricsState.isLoading && isSyncedAvailable && displayMode == LyricsDisplayMode.PLAIN) {
            displayMode = LyricsDisplayMode.SYNCED
        }
    }

    val hasDuetLines = remember(effectiveLines, lyricsState.hasDuet) {
        lyricsState.hasDuet || effectiveLines.any {
            it.speaker == LyricSpeaker.SECOND || it.speaker == LyricSpeaker.BOTH
        }
    }

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
            AdaptiveLyricsBackdrop(
                artworkUri = song.albumArtUriString,
                isPlaying = playbackState.isPlaying,
                accentColor = MaterialTheme.colorScheme.primary,
                secondaryColor = MaterialTheme.colorScheme.tertiary,
                profile = profile
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp)
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
                                    model = ImageRequest.Builder(context)
                                        .data(song.albumArtUriString)
                                        .size(profile.thumbnailMaxPx)
                                        .allowRgb565(profile.allowRgb565)
                                        .crossfade(profile.crossfadeImages)
                                        .build(),
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
                            Spacer(modifier = Modifier.height(5.dp))
                            val (statusLabel, statusBg, statusFg) = when {
                                lyricsState.isLoading -> Triple(
                                    "Поиск текста...",
                                    Color.White.copy(alpha = 0.16f),
                                    Color.White
                                )
                                effectiveLines.isEmpty() -> Triple(
                                    "Не найдено",
                                    Color(0xFFFF8A80).copy(alpha = 0.22f),
                                    Color(0xFFFFCDD2)
                                )
                                isWordSyncedAvailable && isSyncedAvailable -> Triple(
                                    "Синхронизировано • По словам",
                                    Color(0xFF69F0AE).copy(alpha = 0.22f),
                                    Color(0xFFB9F6CA)
                                )
                                isSyncedAvailable -> Triple(
                                    "Синхронизировано • По строкам",
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    Color(0xFFD0E4FF)
                                )
                                else -> Triple(
                                    "Не синхронизировано • Обычный текст",
                                    Color(0xFFFFD180).copy(alpha = 0.22f),
                                    Color(0xFFFFE0B2)
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = statusBg,
                                modifier = Modifier.testTag("lyrics_sync_status_badge")
                            ) {
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = statusFg,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("lyrics_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрыть",
                            tint = Color.White.copy(alpha = 0.9f)
                        )
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
                                size = 54.dp,
                                color = Color.White,
                                containerColor = Color.White.copy(alpha = 0.16f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Поиск текста (BetterLyrics • YouTube Music)...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                } else if (isEditMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 86.dp)
                    ) {
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
                                    .padding(top = 16.dp, bottom = 110.dp)
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
                                contentPadding = PaddingValues(top = 42.dp, bottom = 148.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                itemsIndexed(
                                    items = effectiveLines,
                                    key = { idx, item -> "${idx}_${item.timestampMs}_${item.speaker.name}" },
                                    contentType = { _, item -> if (item.isInstrumental) "interlude" else "lyric" }
                                ) { index, line ->
                                    val isActive = index in activeIndices
                                    val distance = if (isActive) {
                                        0
                                    } else {
                                        activeIndices.minOfOrNull { abs(index - it) } ?: abs(index - primaryActiveIndex)
                                    }

                                    KineticLyricLineItem(
                                        line = line,
                                        isActive = isActive,
                                        distanceFromActive = distance,
                                        hasDuet = hasDuetLines,
                                        currentTimeProvider = currentTimeProvider,
                                        showSecondary = showSecondary,
                                        profile = profile,
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

            // Bottom M3 Expressive Floating Toolbar for quick live controls
            ExpressiveHorizontalFloatingToolbar(
                actions = listOf(
                    ToolbarActionItem(
                        icon = Icons.Rounded.Tune,
                        contentDescription = "Калибровка тайминга",
                        isActive = isOffsetExpanded || lyricsOffsetMs != 0L,
                        testTag = "lyrics_toolbar_offset",
                        onClick = { isOffsetExpanded = !isOffsetExpanded }
                    ),
                    ToolbarActionItem(
                        icon = Icons.Rounded.Search,
                        contentDescription = "Поиск текста",
                        isActive = isSearchExpanded,
                        testTag = "lyrics_toolbar_search",
                        onClick = { isSearchExpanded = !isSearchExpanded }
                    ),
                    ToolbarActionItem(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Обновить текст",
                        testTag = "lyrics_toolbar_refresh",
                        onClick = { viewModel.loadLyrics(song) }
                    ),
                    ToolbarActionItem(
                        icon = Icons.Rounded.EditNote,
                        contentDescription = "Редактировать текст",
                        isActive = isEditMode,
                        testTag = "lyrics_toolbar_edit",
                        onClick = { isEditMode = !isEditMode }
                    )
                ),
                style = FloatingToolbarStyle.STANDARD,
                containerColor = Color(0xD91A1D26),
                contentColor = Color.White,
                activeContainerColor = MaterialTheme.colorScheme.primary,
                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            )
        }
    }
}

/**
 * Hardware-adaptive Lyrics Backdrop:
 * - Flagship (Android 13+): real-time AGSL RuntimeShader plasma mesh + subtle low-res blurred art layer
 * - Balanced: single downsampled artwork + single RenderEffect blur
 * - Potato: ultra-light 64px thumbnail with zero live blurs and zero overlapping full-res bitmaps
 */
@Composable
private fun AdaptiveLyricsBackdrop(
    artworkUri: String?,
    isPlaying: Boolean,
    accentColor: Color,
    secondaryColor: Color,
    profile: PerformanceProfile
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (profile.enableShaders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslAmbientGlowCanvas(
                isPlaying = isPlaying,
                primaryColor = accentColor,
                secondaryColor = secondaryColor,
                accentColor = Color(0xFF3E68FF),
                baseIntensity = if (isPlaying) 0.72f else 0.35f,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.36f),
                                Color(0xFF141824),
                                Color(0xFF0A0B0E)
                            )
                        )
                    )
            )
        }

        AmbientGeometricShapesOverlay(
            isPlaying = isPlaying,
            primaryColor = accentColor,
            secondaryColor = Color(0xFF6BA6FF),
            tertiaryColor = secondaryColor,
            modifier = Modifier.fillMaxSize()
        )

        if (!artworkUri.isNullOrBlank()) {
            val blurModifier = if (profile.enableLiveBlur && !profile.isPotato && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(54.dp)
            } else {
                Modifier
            }
            val driftScale = if (profile.isPotato) {
                1.18f
            } else {
                val infiniteTransition = rememberInfiniteTransition(label = "artwork_warp_opt")
                val breathe by infiniteTransition.animateFloat(
                    initialValue = 1.18f,
                    targetValue = 1.42f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 11000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "breathe_opt"
                )
                if (isPlaying) breathe else 1.20f
            }

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .size(profile.lyricBackdropResolutionPx)
                    .allowRgb565(true)
                    .crossfade(profile.crossfadeImages)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = driftScale
                        scaleY = driftScale
                        alpha = if (profile.isPotato) 0.32f else if (profile.enableShaders) 0.36f else 0.56f
                    }
                    .then(blurModifier)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB308090D),
                            Color(0x800A0C12),
                            Color(0xD907080C)
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KineticLyricLineItem(
    line: DualLyricLine,
    isActive: Boolean,
    distanceFromActive: Int,
    hasDuet: Boolean,
    currentTimeProvider: () -> Long,
    showSecondary: Boolean,
    profile: PerformanceProfile,
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
        isActive -> 1.035f
        distanceFromActive == 1 -> 0.95f
        else -> 0.90f
    }
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = SpringTokens.spatialDefault(),
        label = "line_scale"
    )

    val targetAlpha = when {
        isActive && isBg -> 0.86f
        isActive -> 1f
        distanceFromActive == 1 -> 0.56f
        distanceFromActive == 2 -> 0.36f
        else -> 0.22f
    }
    val lineAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(260),
        label = "line_alpha"
    )

    val activeSlideOffsetDp by animateFloatAsState(
        targetValue = when {
            isActive -> 0f
            isSecond -> 10f
            isBoth -> 0f
            else -> -8f
        },
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "duet_slide_x"
    )

    // Depth blur is strictly disabled on Potato tier to preserve 60fps during scrolls
    val depthBlurModifier = if (!isActive && profile.enableLiveBlur && !profile.isPotato && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = when (distanceFromActive) {
            1 -> 1.2.dp
            2 -> 2.2.dp
            else -> 3.4.dp
        }
        Modifier.blur(radius)
    } else {
        Modifier
    }

    val targetContainerColor = when {
        !isActive || line.isInstrumental -> Color.Transparent
        isSecond -> Color(0xFFA8E6FF).copy(alpha = 0.11f)
        isBoth -> Color(0xFFFFE4A8).copy(alpha = 0.12f)
        isBg -> Color.White.copy(alpha = 0.06f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val containerBg by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(240),
        label = "line_bg"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = lineAlpha
                translationX = activeSlideOffsetDp.dp.toPx()
            }
            .then(depthBlurModifier)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        color = containerBg,
        shape = RoundedCornerShape(22.dp)
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
            val lineHeightSp = if (isBg) 25.sp else 33.sp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isBg && !isSecond) 22.dp else 14.dp,
                        end = if (isBg && isSecond) 22.dp else 14.dp,
                        top = if (isBg) 7.dp else 11.dp,
                        bottom = if (isBg) 7.dp else 11.dp
                    ),
                horizontalAlignment = columnAlignment
            ) {
                if (hasDuet && !line.speakerLabel.isNullOrBlank() && !isBg) {
                    Surface(
                        shape = CircleShape,
                        color = voiceHighlightColor.copy(alpha = if (isActive) 0.24f else 0.10f),
                        modifier = Modifier.padding(bottom = 5.dp)
                    ) {
                        Text(
                            text = line.speakerLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = voiceHighlightColor.copy(alpha = if (isActive) 0.96f else 0.72f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                if (isActive && line.words.isNotEmpty() && line.isSynced) {
                    val wordGroups = remember(line.words) {
                        val groups = mutableListOf<List<LyricWord>>()
                        var current = mutableListOf<LyricWord>()
                        for (w in line.words) {
                            current.add(w)
                            if (w.trailingSpace) {
                                groups.add(current)
                                current = mutableListOf()
                            }
                        }
                        if (current.isNotEmpty()) groups.add(current)
                        groups
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, flowAlignment),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        wordGroups.forEach { group ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                group.forEach { word ->
                                    if (profile.detailedPerCharWave && word.text.length in 1..20) {
                                        KineticSyllablePerCharWord(
                                            word = word,
                                            highlightColor = voiceHighlightColor,
                                            fontSizeSp = if (isBg) 18 else 25,
                                            isItalic = isBg,
                                            enableGlow = profile.enableHeavyShadows,
                                            currentTimeProvider = currentTimeProvider
                                        )
                                    } else {
                                        KineticWordWaveSweep(
                                            word = word,
                                            highlightColor = voiceHighlightColor,
                                            fontSizeSp = if (isBg) 18 else 25,
                                            isItalic = isBg,
                                            enableGlow = profile.enableHeavyShadows,
                                            currentTimeProvider = currentTimeProvider
                                        )
                                    }
                                }
                            }
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
 * Flagship Fluid BetterLyrics Syllable & Per-Character Renderer:
 * Combines smooth sine-eased lift + spring zoom with a soft-feathered horizontal gradient
 * (`BlendMode.DstIn`) mask instead of sharp clip cuts or shaky rotation jitter.
 */
@Composable
private fun KineticSyllablePerCharWord(
    word: LyricWord,
    highlightColor: Color,
    fontSizeSp: Int,
    isItalic: Boolean,
    enableGlow: Boolean,
    currentTimeProvider: () -> Long
) {
    val wordStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = if (isItalic) FontWeight.Bold else FontWeight.ExtraBold,
        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 9).sp
    )
    val glowStyle = if (enableGlow) {
        wordStyle.copy(
            shadow = Shadow(
                color = highlightColor.copy(alpha = 0.64f),
                blurRadius = 16f
            )
        )
    } else {
        wordStyle
    }

    val totalDur = (word.endTimeMs - word.startTimeMs).coerceAtLeast(80L).toFloat()
    val chars = word.text
    val charCount = chars.length.coerceAtLeast(1)

    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until charCount) {
            val ch = chars[i].toString()
            val cStart = i.toFloat() / charCount.toFloat()
            val cEnd = (i + 1).toFloat() / charCount.toFloat()
            val cMid = (cStart + cEnd) * 0.5f

            Box(
                modifier = Modifier.graphicsLayer {
                    val nowMs = currentTimeProvider()
                    val rawP = (nowMs - word.startTimeMs).toFloat() / totalDur
                    // Gentle 90ms anticipation swell before syllable attack
                    val anticipation = (((nowMs - (word.startTimeMs - 90L)).toFloat() / 90f).coerceIn(0f, 1f)) * 0.18f
                    val wordProgress = rawP.coerceIn(0f, 1.35f)
                    val isWordActive = wordProgress in 0f..1.25f

                    val distFromWave = abs(wordProgress - cMid)
                    val bell = if (isWordActive && distFromWave < 0.38f) {
                        val norm = 1f - (distFromWave / 0.38f)
                        sin(norm * (PI.toFloat() * 0.5f))
                    } else 0f

                    val crest = maxOf(bell, anticipation * (1f - cMid * 0.5f))
                    val zoom = 1.0f + 0.11f * crest
                    scaleX = zoom
                    scaleY = zoom
                    translationY = ((-3.4f * bell) + if (wordProgress >= cEnd) -1.0f else -0.4f * anticipation).dp.toPx()
                }
            ) {
                Text(
                    text = ch,
                    style = wordStyle,
                    color = highlightColor.copy(alpha = 0.30f)
                )
                Text(
                    text = ch,
                    style = glowStyle,
                    color = highlightColor,
                    modifier = Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            val nowMs = currentTimeProvider()
                            val rawProgress = ((nowMs - word.startTimeMs).toFloat() / totalDur).coerceIn(0f, 1f)
                            val span = (cEnd - cStart).coerceAtLeast(0.01f)
                            val fill = ((rawProgress - cStart) / span).coerceIn(0f, 1f)
                            if (fill >= 0.995f) {
                                drawContent()
                            } else if (fill > 0.005f) {
                                drawContent()
                                val feather = 0.28f
                                val edgeEnd = (fill * (1f + feather)).coerceIn(0.01f, 1f)
                                val edgeStart = (edgeEnd - feather).coerceIn(0f, edgeEnd - 0.005f)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.White,
                                        edgeStart to Color.White,
                                        edgeEnd to Color.Transparent,
                                        1f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                )
            }
        }
    }
}

/**
 * Low-End / Long-Word Optimized Fluid Glow Wave Renderer:
 * Smooth feathered gradient wipe (`BlendMode.DstIn`) + damped spring lift using only 2 Text nodes total.
 */
@Composable
private fun KineticWordWaveSweep(
    word: LyricWord,
    highlightColor: Color,
    fontSizeSp: Int,
    isItalic: Boolean,
    enableGlow: Boolean,
    currentTimeProvider: () -> Long
) {
    val wordStyle = MaterialTheme.typography.headlineSmall.copy(
        fontWeight = if (isItalic) FontWeight.Bold else FontWeight.ExtraBold,
        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 9).sp
    )
    val glowStyle = if (enableGlow) {
        wordStyle.copy(
            shadow = Shadow(
                color = highlightColor.copy(alpha = 0.56f),
                blurRadius = 14f
            )
        )
    } else {
        wordStyle
    }
    val duration = (word.endTimeMs - word.startTimeMs).coerceAtLeast(60L).toFloat()

    Box(
        modifier = Modifier.graphicsLayer {
            val nowMs = currentTimeProvider()
            val progress = ((nowMs - word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)
            val isCurrentWord = nowMs in word.startTimeMs..word.endTimeMs
            val bell = if (isCurrentWord) sin(progress * PI.toFloat()).coerceIn(0f, 1f) else 0f
            val liftY = when {
                isCurrentWord -> -3.0f * bell
                progress >= 1f -> -0.9f
                else -> 0f
            }
            val zoom = 1.0f + 0.075f * bell
            scaleX = zoom
            scaleY = zoom
            translationY = liftY.dp.toPx()
        }
    ) {
        Text(
            text = word.text,
            style = wordStyle,
            color = highlightColor.copy(alpha = 0.30f)
        )

        Text(
            text = word.text,
            style = glowStyle,
            color = highlightColor,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val nowMs = currentTimeProvider()
                    val progress = ((nowMs - word.startTimeMs).toFloat() / duration).coerceIn(0f, 1f)
                    if (progress >= 0.995f) {
                        drawContent()
                    } else if (progress > 0.005f) {
                        drawContent()
                        val feather = 0.18f
                        val edgeEnd = (progress * (1f + feather)).coerceIn(0.01f, 1f)
                        val edgeStart = (edgeEnd - feather).coerceIn(0f, edgeEnd - 0.005f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.White,
                                edgeStart to Color.White,
                                edgeEnd to Color.Transparent,
                                1f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
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
