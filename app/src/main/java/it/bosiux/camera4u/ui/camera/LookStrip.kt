package it.bosiux.camera4u.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import it.bosiux.camera4u.FilmCameraViewModel
import it.bosiux.camera4u.model.Looks
import it.bosiux.camera4u.ui.components.FilmThumbnail
import it.bosiux.camera4u.ui.components.NoFilmThumbnail

/** Horizontal picker of the curated looks, plus a button into the full film library. */
@Composable
fun LookStrip(viewModel: FilmCameraViewModel, onBrowse: () -> Unit) {
    val camera = viewModel.camera
    val library = viewModel.library
    val haptics = LocalHapticFeedback.current

    LazyRow(
        modifier = Modifier.fillMaxWidth().height(112.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Looks.all, key = { it.id }) { look ->
            val film = look.lutId?.let(library::film)
            StripItem(title = look.name, isSelected = camera.selectedLookId == look.id, onClick = {
                viewModel.selectLook(look)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }) { modifier ->
                if (film != null) FilmThumbnail(library.thumbnailModel(film), modifier) else NoFilmThumbnail(modifier)
            }
        }
        item(key = "library") {
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(
                    onClick = onBrowse,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White,
                    ),
                    modifier = Modifier.size(60.dp),
                ) { Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Film Library") }
                Text("Films", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
