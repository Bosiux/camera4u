package it.bosiux.camera4u.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage

/** A film's sample image — local when downloaded or bundled, remote otherwise. */
@Composable
fun FilmThumbnail(model: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Placeholder(showsIcon = false) },
        error = { Placeholder(showsIcon = true) },
    )
}

@Composable
private fun Placeholder(showsIcon: Boolean) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (showsIcon) Icon(Icons.Outlined.CameraRoll, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown for the "no film" option. */
@Composable
fun NoFilmThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
