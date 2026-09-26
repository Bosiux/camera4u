package io.github.yahiaangelo.filmcamera.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yahiaangelo.filmcamera.FilmCameraViewModel
import io.github.yahiaangelo.filmcamera.ui.components.FilmThumbnail
import io.github.yahiaangelo.filmcamera.ui.components.NoFilmThumbnail

private val ItemWidth = 68.dp

/** Horizontal picker of the user's films, like a roll of film canisters. */
@Composable
fun FilmStrip(viewModel: FilmCameraViewModel, onBrowse: () -> Unit) {
    val camera = viewModel.camera
    val library = viewModel.library
    val films = library.myFilms
    val selectedId = camera.selectedFilmId
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val itemWidthPx = with(LocalDensity.current) { ItemWidth.roundToPx() }

    // Keep the selected film centred.
    LaunchedEffect(selectedId, films.size) {
        val index = if (selectedId == null) 0 else films.indexOfFirst { it.id == selectedId } + 1
        if (index < 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.width
        listState.animateScrollToItem(index, -(viewport - itemWidthPx) / 2)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(112.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "none") {
            StripItem(title = "Original", isSelected = selectedId == null, onClick = {
                viewModel.select(null)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }) { NoFilmThumbnail(it) }
        }
        items(films, key = { it.id }) { film ->
            StripItem(title = film.displayName, isSelected = selectedId == film.id, onClick = {
                viewModel.select(film)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }) { FilmThumbnail(library.thumbnailModel(film), it) }
        }
        item(key = "more") {
            Column(Modifier.width(ItemWidth), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(
                    onClick = onBrowse,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White,
                    ),
                    modifier = Modifier.size(60.dp),
                ) { Icon(Icons.Outlined.Add, contentDescription = "Browse Films") }
                Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StripItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    thumbnail: @Composable (Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val scale by animateFloatAsState(if (isSelected) 1.06f else 1f, spring(stiffness = Spring.StiffnessMedium))
    val accent by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
    Column(
        modifier = Modifier
            .width(ItemWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        thumbnail(
            Modifier
                .size(60.dp)
                .scale(scale)
                .clip(shape)
                .border(if (isSelected) 2.5.dp else 1.dp, accent, shape),
        )
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 13.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}
