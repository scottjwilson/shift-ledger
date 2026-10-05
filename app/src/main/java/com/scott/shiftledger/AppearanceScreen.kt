package com.scott.shiftledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun AppearanceScreen(mode: ThemeMode, onMode: (ThemeMode) -> Unit, onPalette: (ColorPalette) -> Unit,
    onFont: (LedgerFont) -> Unit, onReset: () -> Unit) {
    val appearance = LocalLedgerAppearance.current
    LazyColumn(Modifier.fillMaxSize().testTag("appearance_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Make it yours", "Mix colors and fonts. Your choices apply instantly and stay saved on this phone.") }
        item { AppearancePreview(appearance) }
        item { Panel {
            Text("Light & dark", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { choice -> FilterChip(selected = mode == choice, onClick = { onMode(choice) }, label = { Text(choice.label) }) }
            }
            Text(if (appearance.palette == ColorPalette.BLACK) "Pure Black keeps backgrounds black in every mode." else "System follows your phone's setting.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("Color palette", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        ColorPalette.entries.chunked(2).forEach { pair ->
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { palette -> PaletteOption(palette, appearance.palette == palette, { onPalette(palette) }, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            } }
        }
        item { Text("Font style", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        LedgerFont.entries.forEach { font -> item {
            val active = appearance.font == font
            ChoiceCard(active, { onFont(font) }, Modifier.fillMaxWidth().testTag("font_${font.name}")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(font.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontFamily = font.family), fontWeight = FontWeight.Bold)
                    if (active) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
                Text("$1,500.00 · 42.5 mi", style = MaterialTheme.typography.titleMedium.copy(fontFamily = font.family))
                Text(font.description, style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Reset appearance") } }
        item { Text("Reset uses Forest colors, Modern text, and your phone's light/dark setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun PaletteOption(palette: ColorPalette, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ChoiceCard(active, onClick, modifier.testTag("palette_${palette.name}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(palette.lightAccent, palette.darkAccent, palette.container).forEach { color ->
                Box(Modifier.size(20.dp).background(color, CircleShape))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(palette.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (active) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun ChoiceCard(active: Boolean, onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick = onClick, modifier = modifier.semantics { selected = active; role = Role.RadioButton }, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun AppearancePreview(appearance: LedgerAppearance) {
    val accents = LocalLedgerAccents.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LIVE PREVIEW · ${appearance.palette.label} / ${appearance.font.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = accents.earnings)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Weekly net goal", style = MaterialTheme.typography.titleMedium, color = accents.earningsDetail)
                Text("$960.00", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = accents.onEarnings)
                LinearProgressIndicator(progress = { .64f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = accents.onEarnings, trackColor = accents.onEarnings.copy(alpha = .20f))
                Text("64% of $1,500.00", style = MaterialTheme.typography.bodyLarge, color = accents.onEarnings)
                Text("Sample earnings and goal", style = MaterialTheme.typography.bodySmall, color = accents.earningsDetail)
            }
        }
    }
}
