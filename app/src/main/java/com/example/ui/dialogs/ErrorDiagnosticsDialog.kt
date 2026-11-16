package com.example.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.Song
import com.example.online.OnlineTrack
import com.example.online.PlaybackDiagnosticReport
import com.example.online.SourceAttemptStatus
import com.example.online.SourceDiagnosticsLog
import com.example.online.SourceHealthStatus
import com.example.ui.animation.HapticType
import com.example.ui.animation.performHapticFeedback
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.groupedItemShape
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ErrorDiagnosticsDialog(
    report: PlaybackDiagnosticReport?,
    sourceHealthStatuses: List<SourceHealthStatus>,
    isCheckingHealth: Boolean,
    onRunHealthCheck: () -> Unit,
    onRetryPlayback: () -> Unit,
    onOpenSourceSwitcher: () -> Unit,
    onSelectAlternativeTrack: (OnlineTrack) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var showRawLog by remember(report) { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("error_diagnostics_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            border = BorderStroke(
                1.dp,
                if (report != null) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f)
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (report != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (report != null) Icons.Rounded.ErrorOutline else Icons.Rounded.NetworkCheck,
                                contentDescription = null,
                                tint = if (report != null) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (report != null) "Ошибка источника" else "Диагностика источников",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                        )
                        if (report != null) {
                            Text(
                                text = "${report.trackArtist} — ${report.trackTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Проверка доступности бесплатных каталогов в вашей сети",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("diagnostics_close_btn")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                    }
                }

                if (report != null) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Error Code & Summary Card
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.52f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.error
                                ) {
                                    Text(
                                        text = report.errorCodeLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = MonoFontFamily,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        runCatching { performHapticFeedback(view, HapticType.LIGHT) }
                                        runCatching {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "imux diagnostics",
                                                    SourceDiagnosticsLog.formatCopyableReport(report)
                                                )
                                            )
                                            Toast.makeText(context, "Лог скопирован", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.testTag("diagnostics_copy_log_btn")
                                ) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = "Копировать лог",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Копировать лог", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = report.summary,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                performHapticFeedback(view, HapticType.MEDIUM)
                                onDismiss()
                                onOpenSourceSwitcher()
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("diagnostics_auto_switch_btn")
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Другой источник", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        OutlinedButton(
                            onClick = {
                                performHapticFeedback(view, HapticType.LIGHT)
                                onRetryPlayback()
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("diagnostics_retry_btn")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Повтор")
                        }
                    }

                    // Suggested working alternatives found across other sources
                    if (report.suggestedAlternatives.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Найдены рабочие аналоги",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            report.suggestedAlternatives.forEachIndexed { idx, track ->
                                val shape = groupedItemShape(idx, report.suggestedAlternatives.size, 18.dp, 6.dp)
                                Surface(
                                    shape = shape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .clickable {
                                            performHapticFeedback(view, HapticType.MEDIUM)
                                            onSelectAlternativeTrack(track)
                                        }
                                        .defaultMinSize(minHeight = 52.dp)
                                        .testTag("diagnostics_alt_${track.sourceEngine}_$idx")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = "Включить",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
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
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (track.isPreviewOnly) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                                    ) {
                                                        Text(
                                                            text = "30с превью",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontSize = 9.5.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.secondaryContainer
                                                ) {
                                                    Text(
                                                        text = track.sourceDisplayName,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (track.durationMs > 0L) {
                                                Text(
                                                    text = Song.formatDuration(track.durationMs),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontFamily = MonoFontFamily,
                                                        fontSize = 10.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Detailed Attempt Log
                    if (report.attempts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Лог попыток (${report.attempts.size})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { showRawLog = !showRawLog }) {
                                Text(if (showRawLog) "Скрыть" else "Показать", fontSize = 12.sp)
                            }
                        }

                        AnimatedVisibility(visible = showRawLog) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    report.attempts.take(16).forEach { attempt ->
                                        val badgeColor = when (attempt.status) {
                                            SourceAttemptStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                            SourceAttemptStatus.RELEVANCE_REJECTED -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.error
                                        }
                                        val codePart = attempt.httpCode?.takeIf { it != 200 || attempt.status == SourceAttemptStatus.SUCCESS }
                                            ?.let { "HTTP $it" } ?: attempt.status.name
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "[$codePart]",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                ),
                                                color = badgeColor
                                            )
                                            Text(
                                                text = "${attempt.displayName} (${attempt.stage}, ${attempt.latencyMs}ms): ${attempt.detail}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = MonoFontFamily,
                                                    fontSize = 10.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Source Health Tester
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Пинг всех источников",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold)
                    )
                    Button(
                        onClick = {
                            performHapticFeedback(view, HapticType.LIGHT)
                            onRunHealthCheck()
                        },
                        enabled = !isCheckingHealth,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("diagnostics_ping_sources_btn")
                    ) {
                        if (isCheckingHealth) {
                            ExpressiveLoadingIndicator(
                                size = 24.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Проверить все", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (sourceHealthStatuses.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        sourceHealthStatuses.forEach { st ->
                            val bgColor = if (st.isHealthy) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                            }
                            val fgColor = if (st.isHealthy) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = bgColor,
                                border = BorderStroke(1.dp, fgColor.copy(alpha = 0.22f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = if (st.isHealthy) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = fgColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "${st.displayName} • ${st.latencyMs}ms",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp
                                            ),
                                            color = fgColor
                                        )
                                        Text(
                                            text = st.httpCode?.let { "HTTP $it • ${st.detail}" } ?: st.detail,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = MonoFontFamily,
                                                fontSize = 9.5.sp
                                            ),
                                            color = fgColor.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (!isCheckingHealth) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Нажмите «Проверить все», чтобы узнать задержку и HTTP-статус YouTube, SoundCloud, Audius, JioSaavn, Bandcamp, Archive, Deezer и iTunes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
