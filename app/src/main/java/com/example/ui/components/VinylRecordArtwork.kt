package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R

/**
 * Realistic Vinyl Record LP with spinning physics, concentric sound grooves,
 * central album art label, and turntable tonearm.
 */
@Composable
fun VinylRecordArtwork(
    artUriString: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // Infinite continuous rotation when playing
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl_rotation"
    )

    val currentRotation = if (isPlaying) rotation else 0f

    // Tonearm angle: rotates towards record when playing, rests when paused
    val tonearmAngle by animateFloatAsState(
        targetValue = if (isPlaying) 26f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "tonearm_angle"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .shadow(20.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        // Rotating Vinyl Disc
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(currentRotation)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF232529),
                            Color(0xFF141518),
                            Color(0xFF0A0B0D)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Grooves Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // Outer edge ring
                drawCircle(
                    color = Color(0xFF32363E),
                    radius = radius - 4f,
                    center = center,
                    style = Stroke(width = 2.5f)
                )

                // Subtle sound grooves
                val grooveSteps = 24
                val innerBound = radius * 0.42f
                val outerBound = radius * 0.94f
                val stepSize = (outerBound - innerBound) / grooveSteps

                for (i in 0 until grooveSteps) {
                    val r = innerBound + i * stepSize
                    val alpha = if (i % 3 == 0) 0.18f else 0.08f
                    drawCircle(
                        color = Color.White.copy(alpha = alpha),
                        radius = r,
                        center = center,
                        style = Stroke(width = if (i % 4 == 0) 1.2f else 0.6f)
                    )
                }

                // Vinyl light sheen reflections (anisotropic highlights)
                drawLine(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)
                    ),
                    start = Offset(center.x - radius, center.y - radius),
                    end = Offset(center.x + radius, center.y + radius),
                    strokeWidth = 24f,
                    cap = StrokeCap.Round
                )
            }

            // Central Circular Artwork Label
            Box(
                modifier = Modifier
                    .fillMaxSize(0.44f)
                    .clip(CircleShape)
                    .border(2.dp, Color(0xFF404552), CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!artUriString.isNullOrEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(artUriString)
                            .crossfade(true)
                            .placeholder(R.drawable.ic_album_placeholder)
                            .error(R.drawable.ic_album_placeholder)
                            .build(),
                        contentDescription = "Обложка пластинки",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Center Spindle Hole
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF111215))
                        .border(1.5.dp, Color(0xFF888E9B), CircleShape)
                )
            }
        }
    }
}
