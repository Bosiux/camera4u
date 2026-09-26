package io.github.yahiaangelo.filmcamera.ui.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Grid3x3
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.yahiaangelo.filmcamera.FilmCameraViewModel
import io.github.yahiaangelo.filmcamera.camera.CameraModel
import io.github.yahiaangelo.filmcamera.model.FilmAdjustments
import io.github.yahiaangelo.filmcamera.ui.adjustments.AdjustmentsSheet
import io.github.yahiaangelo.filmcamera.ui.library.FilmLibrarySheet
import io.github.yahiaangelo.filmcamera.ui.theme.FilmCameraTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(viewModel: FilmCameraViewModel, initialSheet: String? = null) {
    val camera = viewModel.camera
    val library = viewModel.library
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var showsLibrary by rememberSaveable { mutableStateOf(false) }
    val adjustmentsState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = adjustmentsState)

    // MARK: Permission and lifecycle

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { camera.start(lifecycleOwner) } else camera.onPermissionDenied()
    }

    // Debug screenshot automation can open a sheet at launch.
    LaunchedEffect(initialSheet) {
        when (initialSheet) {
            "library" -> showsLibrary = true
            "adjustments" -> adjustmentsState.partialExpand()
        }
    }

    LaunchedEffect(Unit) {
        if (hasCameraPermission()) camera.start(lifecycleOwner)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Coming back from Settings with access granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (camera.status == CameraModel.Status.Unauthorized && hasCameraPermission()) {
            scope.launch { camera.start(lifecycleOwner) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { camera.stop() }

    LaunchedEffect(camera.shutterCount) {
        if (camera.shutterCount > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    // MARK: Layout

    FilmCameraTheme(darkTheme = true) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.42f,
            sheetContent = {
                AdjustmentsSheet(
                    adjustments = camera.adjustments,
                    hasFilm = camera.selectedFilmId != null,
                    onChange = camera::updateAdjustments,
                    onDone = { scope.launch { adjustmentsState.hide() } },
                )
            },
            containerColor = Color.Black,
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Reserve the status bar area even while it's hidden: it reappears with a
                    // swipe, and the camera privacy indicator draws there.
                    .windowInsetsPadding(
                        WindowInsets.statusBarsIgnoringVisibility
                            .union(WindowInsets.displayCutout)
                            .union(WindowInsets.navigationBars),
                    )
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(
                    camera = camera,
                    adjustmentsModified = camera.adjustments != FilmAdjustments.Default,
                    onAdjustments = {
                        scope.launch {
                            if (adjustmentsState.currentValue == SheetValue.Hidden) adjustmentsState.partialExpand()
                            else adjustmentsState.hide()
                        }
                    },
                    onLibrary = { showsLibrary = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Viewfinder(
                    camera = camera,
                    filmName = library.film(camera.selectedFilmId)?.displayName ?: "Original",
                    onFilmTap = { showsLibrary = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                FilmStrip(viewModel = viewModel, onBrowse = { showsLibrary = true })
                BottomBar(camera = camera, modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 8.dp))
            }
        }
    }

    if (showsLibrary) {
        FilmCameraTheme {
            FilmLibrarySheet(viewModel = viewModel, onDismiss = { showsLibrary = false })
        }
    }

    // Library errors are shown inside the library sheet while it's open.
    val error = camera.captureError ?: library.errorMessage.takeIf { !showsLibrary }
    if (error != null) {
        FilmCameraTheme(darkTheme = true) {
            AlertDialog(
                onDismissRequest = { camera.captureError = null; library.errorMessage = null },
                confirmButton = {
                    TextButton(onClick = { camera.captureError = null; library.errorMessage = null }) { Text("OK") }
                },
                title = { Text("Something Went Wrong") },
                text = { Text(error) },
            )
        }
    }
}

// MARK: - Top bar

@Composable
private fun TopBar(
    camera: CameraModel,
    adjustmentsModified: Boolean,
    onAdjustments: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (camera.deviceInfo?.hasFlash == true) {
            val icon = when (camera.flashMode) {
                CameraModel.FlashMode.Off -> Icons.Outlined.FlashOff
                CameraModel.FlashMode.Auto -> Icons.Outlined.FlashAuto
                CameraModel.FlashMode.On -> Icons.Outlined.FlashOn
            }
            ControlButton(icon, "Flash ${camera.flashMode.title}", isOn = camera.flashMode != CameraModel.FlashMode.Off,
                onClick = camera::cycleFlash)
        }
        ControlButton(Icons.Outlined.Grid3x3, "Grid", isOn = camera.showsGrid) { camera.showsGrid = !camera.showsGrid }
        Spacer(Modifier.weight(1f))
        ControlButton(Icons.Outlined.Tune, "Adjustments", isOn = adjustmentsModified, onClick = onAdjustments)
        ControlButton(Icons.Outlined.PhotoLibrary, "Film Library", isOn = false, onClick = onLibrary)
    }
}

@Composable
internal fun ControlButton(
    icon: ImageVector,
    label: String,
    isOn: Boolean,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = if (isOn) MaterialTheme.colorScheme.primary else Color.White,
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

// MARK: - Bottom bar

@Composable
private fun BottomBar(camera: CameraModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val shape = RoundedCornerShape(12.dp)
        Surface(
            onClick = {
                val intent = camera.lastPhoto?.let {
                    Intent(Intent.ACTION_VIEW, it.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                runCatching { context.startActivity(intent) }
            },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(54.dp)
                .border(1.dp, Color.White.copy(alpha = 0.2f), shape)
                .semantics { contentDescription = "Open Photos" },
        ) {
            val photo = camera.lastPhoto
            if (photo != null) {
                Image(photo.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape))
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Collections, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        ShutterButton(
            isBusy = camera.isCapturing,
            enabled = camera.status == CameraModel.Status.Running,
            onClick = camera::capture,
        )

        Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) {
            ControlButton(Icons.Outlined.Cameraswitch, "Switch Camera", isOn = false, size = 54.dp,
                onClick = camera::flipCamera)
        }
    }
}
