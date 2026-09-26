package io.github.yahiaangelo.filmcamera.ui.library

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yahiaangelo.filmcamera.FilmCameraViewModel
import io.github.yahiaangelo.filmcamera.library.BulkDownloadProgress
import io.github.yahiaangelo.filmcamera.library.FilmLibrary
import io.github.yahiaangelo.filmcamera.model.Film
import io.github.yahiaangelo.filmcamera.ui.components.FilmThumbnail
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class Scope(val title: String) { Explore("Explore"), MyFilms("My Films") }

/**
 * Browse the full film catalog, download films for offline use, and manage
 * the films that appear in the camera's film strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmLibrarySheet(viewModel: FilmCameraViewModel, onDismiss: () -> Unit) {
    val library = viewModel.library
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var tab by rememberSaveable { mutableStateOf(Scope.Explore) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var confirmsDownloadAll by remember { mutableStateOf(false) }

    val filteredFilms = library.films.filter { film ->
        (category == null || film.category == category) &&
            (searchText.isBlank() || film.displayName.contains(searchText.trim(), ignoreCase = true) ||
                film.displayCategory.contains(searchText.trim(), ignoreCase = true))
    }

    fun dismiss() {
        keyboard?.hide()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun select(film: Film) {
        scope.launch {
            if (!library.download(film)) return@launch
            viewModel.select(film)
            dismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxSize()) {
            // Header: section switcher between the leading action and Done.
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (tab == Scope.Explore) {
                    IconButton(
                        onClick = { confirmsDownloadAll = true },
                        enabled = library.bulkDownload == null && library.notYetDownloaded(filteredFilms).isNotEmpty(),
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Icon(Icons.Outlined.DownloadForOffline, contentDescription = "Download All") }
                }
                SingleChoiceSegmentedButtonRow(Modifier.align(Alignment.Center)) {
                    Scope.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = tab == item,
                            onClick = { tab = item },
                            shape = SegmentedButtonDefaults.itemShape(index, Scope.entries.size),
                        ) { Text(item.title) }
                    }
                }
                IconButton(onClick = ::dismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Outlined.Check, contentDescription = "Done")
                }
            }

            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Kodak, Fuji, Ilford…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Search films" },
            )

            when (tab) {
                Scope.Explore -> Explore(
                    viewModel = viewModel,
                    films = filteredFilms,
                    category = category,
                    onCategory = { category = it },
                    searchText = searchText,
                    onSelect = ::select,
                )
                Scope.MyFilms -> MyFilms(
                    viewModel = viewModel,
                    searchText = searchText,
                    onSelect = ::select,
                    onExplore = { tab = Scope.Explore },
                )
            }
        }
    }

    if (confirmsDownloadAll) {
        val pending = library.notYetDownloaded(filteredFilms)
        val size = Formatter.formatShortFileSize(context, pending.size * FilmLibrary.APPROXIMATE_BYTES_PER_FILM)
        val scopeName = category?.let { " ${Film.prettyCategory(it)}" }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmsDownloadAll = false },
            title = {
                Text(if (pending.size == 1) "Download 1$scopeName film ($size)?" else "Download ${pending.size}$scopeName films ($size)?")
            },
            text = { Text("Downloaded films work offline and appear in your film strip.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsDownloadAll = false
                    library.downloadAll(filteredFilms)
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmsDownloadAll = false }) { Text("Cancel") } },
        )
    }

    library.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { library.errorMessage = null },
            title = { Text("Download Failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { library.errorMessage = null }) { Text("OK") } },
        )
    }
}

// MARK: - Explore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Explore(
    viewModel: FilmCameraViewModel,
    films: List<Film>,
    category: String?,
    onCategory: (String?) -> Unit,
    searchText: String,
    onSelect: (Film) -> Unit,
) {
    val library = viewModel.library
    val scope = rememberCoroutineScope()
    val sections = films.groupBy { it.category }
    val orderedCategories = library.categories.filter { it in sections }

    PullToRefreshBox(
        isRefreshing = library.isRefreshing,
        onRefresh = { scope.launch { library.refreshCatalog() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                CategoryChips(library.categories, category, onCategory)
            }
            for (key in orderedCategories) {
                item(key = "header-$key", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        Film.prettyCategory(key),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(sections[key].orEmpty(), key = { it.id }) { film ->
                    FilmCard(viewModel, film, onSelect = { onSelect(film) })
                }
            }
        }

        if (films.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = "No Results",
                message = if (searchText.isNotBlank()) "Check the spelling or try a new search." else "No films in this category.",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = library.bulkDownload != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        ) {
            library.bulkDownload?.let { BulkDownloadBar(it, onCancel = library::cancelBulkDownload) }
        }
    }
}

@Composable
private fun CategoryChips(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item(key = "all") { CategoryChip("All", selected == null) { onSelect(null) } }
        items(categories, key = { it }) { key -> CategoryChip(Film.prettyCategory(key), selected == key) { onSelect(key) } }
    }
}

@Composable
private fun CategoryChip(title: String, isSelected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(title) },
        leadingIcon = if (isSelected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilmCard(viewModel: FilmCameraViewModel, film: Film, onSelect: () -> Unit) {
    val library = viewModel.library
    val scope = rememberCoroutineScope()
    val isDownloading = film.id in library.downloading
    val isOwned = library.isInMyFilms(film)
    val isSelected = viewModel.camera.selectedFilmId == film.id
    var showsMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(enabled = !isDownloading, onClick = onSelect, onLongClick = { showsMenu = true })
            .semantics(mergeDescendants = true) {
                stateDescription = if (isOwned) "Downloaded" else "Not downloaded"
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .border(2.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
        ) {
            FilmThumbnail(library.thumbnailModel(film), Modifier.fillMaxSize())
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        isDownloading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        isOwned -> Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        else -> Icon(Icons.Outlined.ArrowDownward, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            film.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
        )

        DropdownMenu(expanded = showsMenu, onDismissRequest = { showsMenu = false }) {
            DropdownMenuItem(
                text = { Text(if (isOwned) "Use Film" else "Download & Use") },
                leadingIcon = { Icon(if (isOwned) Icons.Outlined.CameraAlt else Icons.Outlined.DownloadForOffline, null) },
                onClick = { showsMenu = false; onSelect() },
            )
            if (!isOwned) {
                DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Outlined.Download, null) },
                    onClick = { showsMenu = false; scope.launch { library.download(film) } },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Remove from My Films") },
                    leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
                    colors = androidx.compose.material3.MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = { showsMenu = false; viewModel.remove(film) },
                )
            }
        }
    }
}

@Composable
private fun BulkDownloadBar(progress: BulkDownloadProgress, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("Downloading films", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${progress.completed} of ${progress.total}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
        }
    }
}

// MARK: - My Films

@Composable
private fun MyFilms(
    viewModel: FilmCameraViewModel,
    searchText: String,
    onSelect: (Film) -> Unit,
    onExplore: () -> Unit,
) {
    val library = viewModel.library
    val films = library.myFilms.filter { searchText.isBlank() || it.displayName.contains(searchText.trim(), ignoreCase = true) }
    // Reordering a filtered list would be ambiguous, so it's only offered unfiltered.
    val canReorder = searchText.isBlank()
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        library.moveMyFilm(from.key as String, to.key as String)
    }

    if (library.myFilms.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = "No Films Yet",
                message = "Download films from Explore to use them offline.",
            ) { Button(onClick = onExplore) { Text("Explore Films") } }
        }
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        items(films, key = { it.id }) { film ->
            ReorderableItem(reorderState, key = film.id) { isDragging ->
                val dismissState = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) viewModel.remove(film) },
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) { Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.onErrorContainer) }
                    },
                ) {
                    val isSelected = viewModel.camera.selectedFilmId == film.id
                    ListItem(
                        headlineContent = { Text(film.displayName, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(film.displayCategory) },
                        leadingContent = {
                            FilmThumbnail(library.thumbnailModel(film), Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                                if (canReorder) {
                                    IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                        Icon(Icons.Outlined.DragHandle, contentDescription = "Reorder")
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier.clickable { onSelect(film) },
                    )
                }
            }
        }
        item(key = "footer") {
            Text(
                "Films in this list appear in the camera's film strip and work offline. Swipe left to remove.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}
