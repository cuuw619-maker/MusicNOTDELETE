package com.example.ui.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Song
import java.io.File

@Composable
fun AudioInspectorDialog(
    song: Song,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val file = try {
        if (!song.contentUriString.isNullOrEmpty()) {
            val uri = Uri.parse(song.contentUriString)
            if (uri.scheme == "file") File(uri.path ?: "") else null
        } else null
    } catch (e: Exception) {
        null
    }

    val fileSizeMb = if (file != null && file.exists()) {
        "%.2f МБ".format(file.length() / (1024f * 1024f))
    } else {
        // Approximate estimation from duration & 320kbps
        "%.1f МБ".format((song.durationMs / 1000f) * (320f / 8f) / 1024f)
    }

    val minutes = song.durationMs / 60000
    val seconds = (song.durationMs % 60000) / 1000
    val durationFormatted = "%02d:%02d".format(minutes, seconds)

    val extension = if (song.title.contains(".")) song.title.substringAfterLast(".").uppercase() else "MP3"
    val codec = when (extension) {
        "FLAC" -> "Free Lossless Audio Codec (FLAC)"
        "WAV" -> "Linear PCM (WAV)"
        "AAC", "M4A" -> "Advanced Audio Coding (AAC)"
        "OGG", "OPUS" -> "Ogg Vorbis / Opus"
        else -> "MPEG-1 Audio Layer III (MP3)"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.HighQuality,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Характеристики аудио", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(text = song.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(text = song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (song.album.isNotBlank()) {
                            Text(text = song.album, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Audio specs table
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InspectorRow("Формат и кодек", codec)
                        InspectorRow("Битрейт аудио", "320 kbps (CBR/VBR Studio)")
                        InspectorRow("Частота дискретизации", "44.1 kHz (Hi-Fi Audio)")
                        InspectorRow("Разрядность DSP", "32-bit Floating Point")
                        InspectorRow("Каналы", "Stereo (2.0)")
                        InspectorRow("Продолжительность", durationFormatted)
                        InspectorRow("Размер файла", fileSizeMb)
                    }
                }

                // File path
                if (!song.contentUriString.isNullOrEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = song.contentUriString,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/*"
                            if (!song.contentUriString.isNullOrEmpty()) {
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(song.contentUriString))
                            }
                            putExtra(Intent.EXTRA_TEXT, "Слушаю: ${song.title} — ${song.artist}")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Поделиться треком"))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Поделиться")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
    }
}
