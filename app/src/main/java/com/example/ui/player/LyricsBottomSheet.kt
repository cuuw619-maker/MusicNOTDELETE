package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lyrics.DualLyricLine
import com.example.model.Song
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper
import com.example.ui.viewmodel.MusicViewModel

enum class LyricsDisplayMode {
    SYNCED,
    PARALLEL,
    PLAIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    song: Song,
    currentPositionMs: Long,
    viewModel: MusicViewModel,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = rememberHapticHelper()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()

    var displayMode by remember { mutableStateOf(LyricsDisplayMode.SYNCED) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var customLyricsText by remember { mutableStateOf("") }

    // Load lyrics when sheet opens or song changes
    LaunchedEffect(song.id) {
        viewModel.loadLyrics(song)
    }

    val effectiveLines = remember(lyricsState.lines, customLyricsText, isEditMode) {
        if (customLyricsText.isNotBlank()) {
            parseCustomLrc(customLyricsText)
        } else {
            lyricsState.lines
        }
    }

    // Active line index calculation based on current playback timestamp
    val activeIndex = remember(effectiveLines, currentPositionMs) {
        val timed = effectiveLines.filter { it.timestampMs >= 0 }
        if (timed.isEmpty()) -1
        else {
            val idx = timed.indexOfLast { it.timestampMs <= currentPositionMs }
            if (idx != -1) idx else 0
        }
    }

    // Auto-scroll in synced or parallel modes
    LaunchedEffect(activeIndex, displayMode) {
        if (displayMode != LyricsDisplayMode.PLAIN && activeIndex in effectiveLines.indices) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
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
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Lyrics,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = lyricsState.title.ifBlank { song.title },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (lyricsState.source.isNotBlank()) "${lyricsState.artist.ifBlank { song.artist }} • ${lyricsState.source}"
                            else lyricsState.artist.ifBlank { song.artist },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isSearchExpanded = !isSearchExpanded },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Поиск текста",
                            tint = if (isSearchExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { viewModel.loadLyrics(song) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Обновить текст",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { isEditMode = !isEditMode },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = "Свой текст",
                            tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Search Bar (collapsible)
            AnimatedVisibility(visible = isSearchExpanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Исполнитель и название песни...", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    focusManager.clearFocus()
                                    viewModel.searchLyricsManually(searchQuery)
                                }
                            }),
                            shape = RoundedCornerShape(12.dp)
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
                            Text("Искать")
                        }
                    }
                }
            }

            // Display Mode Selector Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = displayMode == LyricsDisplayMode.SYNCED,
                    onClick = {
                        haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                        displayMode = LyricsDisplayMode.SYNCED
                    },
                    label = { Text("Синхронно") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = displayMode == LyricsDisplayMode.PARALLEL,
                    onClick = {
                        haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                        displayMode = LyricsDisplayMode.PARALLEL
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Параллельно")
                            if (lyricsState.hasDualLines) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Subtitles,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = displayMode == LyricsDisplayMode.PLAIN,
                    onClick = {
                        haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                        displayMode = LyricsDisplayMode.PLAIN
                    },
                    label = { Text("Текст") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Content Area
            if (lyricsState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Поиск синхронизированных слов...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (isEditMode) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Вставьте текст или LRC разметку [00:12.30] Текст песни:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = customLyricsText,
                        onValueChange = { customLyricsText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        placeholder = { Text("[00:05.00] Строка песни // Перевод строки") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { customLyricsText = "" }) {
                            Text("Очистить")
                        }
                        TextButton(onClick = { isEditMode = false }) {
                            Text("Применить")
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lyrics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = lyricsState.errorMessage ?: "Текст не найден",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            onClick = { isSearchExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "Найти вручную",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            } else {
                when (displayMode) {
                    LyricsDisplayMode.PLAIN -> {
                        val plainContent = lyricsState.plainLyrics.ifBlank {
                            effectiveLines.joinToString("\n") { it.primaryText }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = plainContent,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 28.sp,
                                    fontSize = 17.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    LyricsDisplayMode.SYNCED, LyricsDisplayMode.PARALLEL -> {
                        val showSecondary = (displayMode == LyricsDisplayMode.PARALLEL)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(effectiveLines) { index, line ->
                                val isActive = index == activeIndex
                                val primaryColor by animateColorAsState(
                                    targetValue = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    label = "lyric_color"
                                )

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (line.timestampMs >= 0) {
                                                haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                                onSeekTo(line.timestampMs)
                                            }
                                        }
                                        .padding(vertical = 6.dp, horizontal = 12.dp),
                                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = line.primaryText,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                                fontSize = if (isActive) 21.sp else 16.sp,
                                                lineHeight = if (isActive) 29.sp else 23.sp
                                            ),
                                            color = primaryColor,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        if (showSecondary && !line.secondaryText.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = line.secondaryText,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                                    fontSize = if (isActive) 16.sp else 13.sp,
                                                    lineHeight = if (isActive) 22.sp else 18.sp
                                                ),
                                                color = if (isActive) MaterialTheme.colorScheme.tertiary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
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
    }
}

private fun parseCustomLrc(raw: String): List<DualLyricLine> {
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
    val lines = mutableListOf<DualLyricLine>()

    for (line in raw.lines()) {
        val trimmed = line.trim()
        val match = regex.find(trimmed)
        if (match != null) {
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val msStr = match.groupValues[3]
            val ms = if (msStr.isNotEmpty()) {
                if (msStr.length == 2) msStr.toLong() * 10L else msStr.toLong()
            } else 0L
            val totalMs = (min * 60 + sec) * 1000L + ms
            val text = match.groupValues[4].trim()

            val (primary, secondary) = when {
                text.contains(" // ") -> {
                    val p = text.split(" // ", limit = 2)
                    Pair(p[0].trim(), p.getOrNull(1)?.trim())
                }
                text.contains(" | ") -> {
                    val p = text.split(" | ", limit = 2)
                    Pair(p[0].trim(), p.getOrNull(1)?.trim())
                }
                else -> Pair(text, null)
            }

            if (primary.isNotBlank()) {
                lines.add(DualLyricLine(totalMs, primary, secondary, isSynced = true))
            }
        } else if (trimmed.isNotBlank() && !trimmed.startsWith("[")) {
            lines.add(DualLyricLine(-1L, trimmed, null, isSynced = false))
        }
    }
    return lines.sortedBy { it.timestampMs }
}
