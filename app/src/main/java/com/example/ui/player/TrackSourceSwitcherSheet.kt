package com.example.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.Song
import com.example.online.OnlineTrack
import com.example.online.YouTubeStreamManager
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.ExpressiveOvalChoiceChip
import com.example.ui.components.groupedItemShape
import com.example.ui.theme.LocalPerformanceProfile
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.MusicViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSourceSwitcherSheet(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val profile = LocalPerformanceProfile.current

    val title by viewModel.sourceSwitcherTitle.collectAsStateWithLifecycle()
    val query by viewModel.sourceSwitcherQuery.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.alternativeSourceFilter.collectAsStateWithLifecycle()
    val candidates by viewModel.alternativeTracks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isAlternativeLoading.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeStreamEngine.collectAsStateWithLifecycle()
    val selectedDelivery by viewModel.selectedDeliveryEngine.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("track_source_switcher_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Источник и версия трека",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Сейчас: ${YouTubeStreamManager.formatEngineDisplay(activeEngine)} • «$title»",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Delivery mirror switcher for current YouTube stream
            Text(
                text = "Канал доставки текущего потока",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YouTubeStreamManager.DELIVERY_ENGINES.forEach { engine ->
                    val isSelected = selectedDelivery == engine.id || (selectedDelivery == "auto" && activeEngine == engine.id)
                    ExpressiveOvalChoiceChip(
                        label = engine.label,
                        selected = isSelected,
                        onClick = { viewModel.switchDeliveryEngine(engine.id) },
                        minHeight = 36.dp,
                        fontSizeSp = 11.5f
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Search across online sources to replace song / version
            Text(
                text = "Выбрать другую песню или источник",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setAlternativeQuery(it) },
                placeholder = { Text("Название, исполнитель, ремикс или live...") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.loadAlternativeCandidates()
                        },
                        modifier = Modifier.testTag("source_switcher_search_btn")
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Найти варианты")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    viewModel.loadAlternativeCandidates()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source_switcher_query_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Source tabs row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YouTubeStreamManager.SOURCE_FILTERS.forEach { source ->
                    ExpressiveOvalChoiceChip(
                        label = source.label,
                        subtitle = source.badge,
                        selected = sourceFilter == source.id,
                        onClick = { viewModel.setAlternativeSourceFilter(source.id) },
                        minHeight = 44.dp,
                        fontSizeSp = 12f,
                        modifier = Modifier.testTag("switcher_source_${source.id}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 380.dp)
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ExpressiveLoadingIndicator(size = 46.dp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Подбираем варианты трека...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    candidates.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Варианты не найдены. Измените запрос или выберите другой источник.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = candidates,
                                key = { idx, item -> "${item.sourceEngine}:${item.id}:$idx" },
                                contentType = { _, _ -> "source_candidate" }
                            ) { index, track ->
                                SourceCandidateRow(
                                    track = track,
                                    shape = groupedItemShape(index, candidates.size, outerRadius = 20.dp, innerRadius = 6.dp),
                                    thumbnailMaxPx = profile.thumbnailMaxPx,
                                    allowRgb565 = profile.allowRgb565,
                                    onClick = { viewModel.selectAlternativeTrack(track) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCandidateRow(
    track: OnlineTrack,
    shape: RoundedCornerShape,
    thumbnailMaxPx: Int,
    allowRgb565: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (!track.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(track.thumbnailUrl)
                            .size(thumbnailMaxPx)
                            .allowRgb565(allowRgb565)
                            .build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (track.directStreamUrl != null) Icons.Rounded.GraphicEq else Icons.Rounded.Public,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = track.sourceDisplayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (track.durationMs > 0L) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = Song.formatDuration(track.durationMs),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MonoFontFamily,
                            fontSize = 10.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
