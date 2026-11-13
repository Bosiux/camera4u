package it.bosiux.camera4u.ui.camera

import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.provider.Settings
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import it.bosiux.camera4u.camera.CameraModel
import it.bosiux.camera4u.model.Aspect
import it.bosiux.camera4u.rendering.FilmGlThread
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The 3:4 live preview with focus, zoom, grid and film overlays. */
@Composable
fun Viewfinder(camera: CameraModel, filmName: String, onFilmTap: () -> Unit, modifier: Modifier = Modifier) {
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusId by remember { mutableIntStateOf(0) }
    val shutterFlash = remember { Animatable(0f) }

    LaunchedEffect(camera.shutterCount) {
        if (camera.shutterCount == 0) return@LaunchedEffect
        shutterFlash.snapTo(0.85f)
        shutterFlash.animateTo(0f, tween(300))
    }
    LaunchedEffect(focusId) {
        if (focusPoint == null) return@LaunchedEffect
        delay(1500)
        focusPoint = null
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black),
        ) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()

            if (camera.renderer.failure == null) {
                CameraPreview(camera.renderer, Modifier.fillMaxSize())
            }

            // Gestures: tap to focus and expose, pinch to zoom.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                val point = Offset(offset.x / width, offset.y / height)
                                camera.focus(point.x, point.y, width, height)
                                focusPoint = point
                                focusId++
                            },
                            onLongPress = { offset ->
                                val point = Offset(offset.x / width, offset.y / height)
                                camera.lockAt(point.x, point.y, width, height)
                                focusPoint = point
                                focusId++
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) camera.setZoom(camera.zoomRatio * zoom, animated = false)
                        }
                    },
            )

            if (camera.showsGrid) GridOverlay()
            if (camera.aspect != Aspect.FourThree) AspectMatte(camera.aspect.shortSideFraction)

            focusPoint?.let { point ->
                FocusIndicator(
                    key = focusId,
                    modifier = Modifier.offset {
                        val half = 36.dp.roundToPx()
                        IntOffset((point.x * width).roundToInt() - half, (point.y * height).roundToInt() - half)
                    },
                )
            }

            StatusOverlay(camera.status)

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shutterFlash.value }
                    .background(Color.Black),
            )

            FilmChip(filmName, onFilmTap, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            ZoomControl(camera, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        }
    }
}

/** Live, film-processed camera preview drawn by the GL thread into a `TextureView`. */
@Composable
private fun CameraPreview(renderer: FilmGlThread, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        renderer.setOutput(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        renderer.resizeOutput(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        renderer.clearOutput()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        },
    )
}

/** Darkens the sides that the chosen framing will crop away. The viewfinder is portrait, so the short side is its width. */
@Composable
private fun AspectMatte(shortSideFraction: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val band = size.width * (1f - shortSideFraction) / 2f
        val color = Color.Black.copy(alpha = 0.7f)
        drawRect(color, Offset.Zero, Size(band, size.height))
        drawRect(color, Offset(size.width - band, 0f), Size(band, size.height))
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.35f)
        val stroke = 0.5.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

@Composable
private fun FocusIndicator(key: Int, modifier: Modifier = Modifier) {
    val settle = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { settle.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy)) }
    Box(
        modifier
            .size(72.dp)
            .graphicsLayer {
                val scale = 1.4f - 0.4f * settle.value
                scaleX = scale
                scaleY = scale
                alpha = settle.value.coerceIn(0f, 1f)
            }
            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun FilmChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(max = 200.dp))
        },
        leadingIcon = {
            Icon(Icons.Outlined.CameraRoll, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        trailingIcon = {
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        shape = CircleShape,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            labelColor = Color.White,
            trailingIconContentColor = Color.White.copy(alpha = 0.7f),
        ),
        border = null,
        modifier = modifier.semantics { contentDescription = "Film: $name. Change film" },
    )
}

/** Lens buttons (".5 · 1× · 2") in a translucent pill. */
@Composable
private fun ZoomControl(camera: CameraModel, modifier: Modifier = Modifier) {
    val info = camera.deviceInfo ?: return
    val stops = info.zoomStops
    if (stops.size < 2) return
    // The highest lens stop at or below the current zoom.
    val active = stops.lastOrNull { it <= camera.zoomRatio + 0.01f } ?: stops.first()

    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.35f), modifier = modifier) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (stop in stops) {
                val isActive = stop == active
                val size by animateFloatAsState(if (isActive) 40f else 32f, spring(stiffness = Spring.StiffnessMediumLow))
                Surface(
                    onClick = { camera.setZoom(stop) },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = if (isActive) 0.18f else 0.08f),
                    modifier = Modifier.size(size.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            zoomLabel(if (isActive) camera.zoomRatio else stop, isActive),
                            color = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                            fontSize = if (isActive) 13.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

internal fun zoomLabel(value: Float, isActive: Boolean): String {
    val text = if (value < 1f || abs(value - value.roundToInt()) > 0.05f) {
        String.format(Locale.US, "%.1f", value).replace("0.", ".")
    } else {
        value.roundToInt().toString()
    }
    return if (isActive) "$text×" else text
}

@Composable
private fun StatusOverlay(status: CameraModel.Status) {
    val context = LocalContext.current
    when (status) {
        CameraModel.Status.Unauthorized -> StatusMessage(
            icon = Icons.Outlined.CameraAlt,
            title = "Camera Access Needed",
            message = "Allow camera access in Settings to start shooting on film.",
        ) {
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Open Settings") }
        }
        is CameraModel.Status.Failed -> StatusMessage(Icons.Outlined.WarningAmber, "Camera Unavailable", status.message)
        CameraModel.Status.Idle, CameraModel.Status.Running -> Unit
    }
}

@Composable
private fun StatusMessage(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun ShutterButton(isBusy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessMedium))
    Surface(
        onClick = onClick,
        enabled = enabled && !isBusy,
        shape = CircleShape,
        color = Color.Transparent,
        interactionSource = interaction,
        modifier = Modifier
            .size(78.dp)
            .scale(scale)
            .semantics { contentDescription = "Take Photo" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().border(4.dp, Color.White, CircleShape))
            Box(Modifier.size(64.dp).background(Color.White.copy(alpha = if (enabled) 1f else 0.5f), CircleShape))
            if (isBusy) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        }
    }
}
