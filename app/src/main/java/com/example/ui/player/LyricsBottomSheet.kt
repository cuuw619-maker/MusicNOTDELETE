package com.example.ui.player

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Song
import com.example.ui.util.HapticFeedbackType
import com.example.ui.util.rememberHapticHelper

data class LyricLine(val timeMs: Long, val text: String)

fun parseLrcLyrics(rawLyrics: String): List<LyricLine> {
    val lines = rawLyrics.lines()
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)""")
    val result = mutableListOf<LyricLine>()

    for (line in lines) {
        val match = regex.find(line.trim())
        if (match != null) {
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val millisStr = match.groupValues[3]
            val millis = if (millisStr.isNotEmpty()) {
                if (millisStr.length == 2) millisStr.toLong() * 10 else millisStr.toLong()
            } else 0L
            val timeMs = minutes * 60000L + seconds * 1000L + millis
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty()) {
                result.add(LyricLine(timeMs, text))
            }
        } else if (line.isNotBlank() && !line.startsWith("[")) {
            // Plain text without timestamps
            result.add(LyricLine(-1L, line.trim()))
        }
    }
    return result.sortedBy { it.timeMs }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(
    song: Song,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = rememberHapticHelper()
    val listState = rememberLazyListState()

    var isEditMode by remember { mutableStateOf(false) }
    var lyricsText by remember(song.id) {
        mutableStateOf(
            // Pre-seed clean sample lyrics if empty
            """
                [00:00.00]${song.title} — ${song.artist}
                [00:04.00]♪ Музыкальное вступление ♪
                [00:12.00]Слушайте любимую музыку в чистом звучании
                [00:18.00]Студийный движок с плавным затуханием
                [00:24.00]И кристально чистыми частотами
                [00:32.00]Наслаждайтесь каждым звуком и нотой
                [00:40.00]♪ Проигрыш ♪
                [00:48.00]Плеер нового поколения Material 3
                [00:56.00]Каждая строка синхронизирована с музыкой
                [01:06.00]♪ Завершение композиции ♪
            """.trimIndent()
        )
    }

    val parsedLines = remember(lyricsText) { parseLrcLyrics(lyricsText) }

    // Find active line index
    val activeIndex = remember(parsedLines, currentPositionMs) {
        val timed = parsedLines.filter { it.timeMs >= 0 }
        if (timed.isEmpty()) -1
        else {
            val idx = timed.indexOfLast { it.timeMs <= currentPositionMs }
            if (idx != -1) idx else 0
        }
    }

    // Auto-scroll to active line
    LaunchedEffect(activeIndex) {
        if (activeIndex in 0 until parsedLines.size) {
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
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Lyrics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Текст песни",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (parsedLines.any { it.timeMs >= 0 }) "Синхронизированный LRC • Нажмите на строку для перехода" else "Обычный текст",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { isEditMode = !isEditMode }) {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = "Редактировать текст",
                        tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isEditMode) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = lyricsText,
                        onValueChange = { lyricsText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text("Вставьте текст в формате [мм:сс.хх] или без меток") },
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { isEditMode = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Готово")
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(parsedLines) { index, line ->
                        val isActive = index == activeIndex
                        val textColor by animateColorAsState(
                            targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            label = "lyric_color"
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (line.timeMs >= 0) {
                                        haptic.performHaptic(HapticFeedbackType.LIGHT_TICK)
                                        onSeekTo(line.timeMs)
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontSize = if (isActive) 20.sp else 16.sp,
                                    lineHeight = if (isActive) 28.sp else 22.sp
                                ),
                                color = textColor,
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
