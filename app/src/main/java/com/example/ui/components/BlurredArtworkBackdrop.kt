package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.LocalPerformanceProfile

/**
 * Ultra-fast, zero-jank blurred album artwork backdrop.
 * Decodes artwork into a compact 36-44px bitmap and lets the GPU hardware bilinear texture sampler
 * upscale it across the screen, avoiding expensive offscreen RenderEffect blur passes on open.
 */
@Composable
fun BlurredArtworkBackdrop(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") blurRadius: Dp = 14.dp,
    @Suppress("UNUSED_PARAMETER") allowLiveBlur: Boolean = true,
    artworkAlpha: Float = 0.60f,
    scrimTopColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
    scrimMidColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
    scrimBottomColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    fallbackBaseColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest
) {
    val context = LocalContext.current
    val profile = LocalPerformanceProfile.current
    val effectiveSizePx = remember(profile) {
        if (profile.isPotato) 28 else 38
    }
    val scrimBrush = remember(scrimTopColor, scrimMidColor, scrimBottomColor) {
        Brush.verticalGradient(
            colors = listOf(scrimTopColor, scrimMidColor, scrimBottomColor)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(fallbackBaseColor)
    ) {
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .size(effectiveSizePx)
                    .allowRgb565(true)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                        alpha = if (profile.isPotato) (artworkAlpha * 0.65f) else artworkAlpha
                    }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush)
        )
    }
}
