package io.github.yahiaangelo.filmcamera.ui.adjustments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yahiaangelo.filmcamera.model.AdjustmentControl
import io.github.yahiaangelo.filmcamera.model.FilmAdjustments

/**
 * Live image adjustments in a standard (non-modal) bottom sheet. Partially
 * expanded, the viewfinder stays visible and interactive above it, so changes
 * can be judged live.
 */
@Composable
fun AdjustmentsSheet(
    adjustments: FilmAdjustments,
    hasFilm: Boolean,
    onChange: (FilmAdjustments) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(
                onClick = { onChange(FilmAdjustments.Default) },
                enabled = adjustments != FilmAdjustments.Default,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Reset", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            Text("Adjust", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Outlined.Check, contentDescription = "Done")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            for (group in AdjustmentControl.Group.entries) {
                item(key = group.name) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(AdjustmentControl.all.filter { it.group == group }, key = { it.id }) { control ->
                    AdjustmentRow(
                        control = control,
                        value = control.get(adjustments),
                        enabled = control.group != AdjustmentControl.Group.Film || hasFilm,
                        onValueChange = { onChange(control.set(adjustments, it)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentRow(
    control: AdjustmentControl,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isModified = control.isModified(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // A tick when a slider passes through its default.
    LaunchedEffect(isModified) {
        if (!isModified) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(enabled) {
                    if (enabled) detectTapGestures(onDoubleTap = { currentOnValueChange(control.defaultValue) })
                }
                .semantics {
                    onClick("Reset to default") { currentOnValueChange(control.defaultValue); true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(control.icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(control.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(
                control.formatted(value),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isModified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = control.range,
            enabled = enabled,
            track = { state -> if (control.isBipolar) CenteredTrack(state, enabled) else SliderDefaults.Track(state, enabled = enabled) },
            modifier = Modifier.height(36.dp).semantics { contentDescription = control.title },
        )
    }
}

/** Fills from the neutral centre towards the thumb, for adjustments that go both ways. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CenteredTrack(state: SliderState, enabled: Boolean) {
    val colors = SliderDefaults.colors()
    val active = if (enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactive = if (enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val y = size.height / 2
        val range = state.valueRange
        val fraction = (state.value - range.start) / (range.endInclusive - range.start)
        val center = (0f - range.start) / (range.endInclusive - range.start) * size.width
        val thumb = fraction * size.width
        drawLine(inactive, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        drawLine(active, Offset(center, y), Offset(thumb, y), size.height, StrokeCap.Round)
    }
}
