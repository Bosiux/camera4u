package it.bosiux.camera4u.ui.camera

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.bosiux.camera4u.camera.ProControls
import java.util.Locale

private enum class ProField { Iso, Shutter, Ev, Wb, Focus }

/** AUTO/M, ISO, S, EV, WB and AF chips; the tapped chip opens one slider or a row of choices above. */
@Composable
fun ProBar(pro: ProControls, modifier: Modifier = Modifier) {
    val r = pro.ranges ?: return
    var open by rememberSaveable { mutableStateOf<ProField?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (open) {
            ProField.Iso -> LogSlider(
                value = pro.iso.toDouble(), min = r.iso.lower.toDouble(), max = r.iso.upper.toDouble(),
                label = "ISO ${pro.iso}",
            ) { pro.iso = it.toInt(); pro.apply() }
            ProField.Shutter -> LogSlider(
                value = pro.exposureNs.toDouble(), min = r.exposureNs.lower.toDouble(), max = r.exposureNs.upper.toDouble(),
                label = ProControls.shutterLabel(pro.exposureNs),
            ) { pro.exposureNs = it.toLong(); pro.apply() }
            ProField.Ev -> ValueSlider(
                value = pro.evIndex.toFloat(), range = r.ev.lower.toFloat()..r.ev.upper.toFloat(),
                steps = (r.ev.upper - r.ev.lower - 1).coerceAtLeast(0), label = "EV " + ProControls.evLabel(pro.evIndex, r.evStep),
            ) { pro.evIndex = it.toInt(); pro.apply() }
            ProField.Wb -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (wb in pro.whiteBalances) {
                    Chip(wb.label, selected = pro.awbMode == wb.mode) { pro.awbMode = wb.mode; pro.apply() }
                }
            }
            ProField.Focus -> if (pro.manualFocus) ValueSlider(
                value = pro.focusDiopters, range = 0f..r.maxDiopters, steps = 0,
                label = if (pro.focusDiopters < 0.05f) "∞" else String.format(Locale.US, "%.2f m", 1f / pro.focusDiopters),
            ) { pro.focusDiopters = it; pro.apply() }
            null -> Unit
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(if (pro.manualExposure) "M" else "AUTO", selected = pro.manualExposure) {
                pro.manualExposure = !pro.manualExposure
                pro.apply()
                open = if (pro.manualExposure) ProField.Iso else null
            }
            if (pro.manualExposure) {
                Chip("ISO ${pro.iso}", selected = open == ProField.Iso) { open = open.toggle(ProField.Iso) }
                Chip("S ${ProControls.shutterLabel(pro.exposureNs)}", selected = open == ProField.Shutter) { open = open.toggle(ProField.Shutter) }
            } else {
                Chip("EV ${ProControls.evLabel(pro.evIndex, r.evStep)}", selected = open == ProField.Ev) { open = open.toggle(ProField.Ev) }
            }
            Chip("WB " + (pro.whiteBalances.firstOrNull { it.mode == pro.awbMode }?.label ?: "Auto"), selected = open == ProField.Wb) {
                open = open.toggle(ProField.Wb)
            }
            if (r.maxDiopters > 0f) {
                Chip(if (pro.manualFocus) "MF" else "AF", selected = pro.manualFocus) {
                    pro.manualFocus = !pro.manualFocus
                    pro.apply()
                    open = if (pro.manualFocus) ProField.Focus else null
                }
            }
            if (pro.locked) Text("AE/AWB LOCK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun ProField?.toggle(field: ProField): ProField? = if (this == field) null else field

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.SemiBold) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.1f), labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        border = null,
        modifier = Modifier.height(32.dp),
    )
}

@Composable
private fun LogSlider(value: Double, min: Double, max: Double, label: String, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = ProControls.logToSlider(value, min, max),
            onValueChange = { onChange(ProControls.sliderToLog(it, min, max)) },
            modifier = Modifier.height(36.dp),
        )
    }
}

@Composable
private fun ValueSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.height(36.dp))
    }
}
