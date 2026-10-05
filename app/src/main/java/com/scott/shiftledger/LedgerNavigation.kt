package com.scott.shiftledger

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class LedgerTab(val label: String, val icon: ImageVector)

// One consistent outline family, drawn on a 24-unit grid.
private fun navIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero, pathBuilder = draw)
    }.build()

private val ledgerTabs = listOf(
    LedgerTab("Home", navIcon("Home") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); verticalLineTo(20f); horizontalLineTo(10f); verticalLineTo(14f)
        horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(19f); verticalLineTo(9f)
    }),
    LedgerTab("Earnings", navIcon("Earnings") {
        moveTo(6f, 3f); horizontalLineTo(18f); verticalLineTo(21f)
        lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f); lineTo(6f, 21f); close()
        moveTo(9f, 7f); horizontalLineTo(15f)
        moveTo(9f, 11f); horizontalLineTo(15f)
        moveTo(9f, 15f); horizontalLineTo(12f)
    }),
    LedgerTab("Shifts", navIcon("Shifts") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f)
        arcTo(9f, 9f, 0f, true, true, 21f, 12f); close()
        moveTo(12f, 7f); verticalLineTo(12f); lineTo(15.5f, 14f)
    }),
    LedgerTab("Costs", navIcon("Costs") {
        moveTo(20f, 8f); horizontalLineTo(5f)
        curveTo(2.5f, 8f, 2.5f, 4f, 5f, 4f); horizontalLineTo(18f); verticalLineTo(8f)
        moveTo(3f, 6f); verticalLineTo(18f); curveTo(3f, 19.1f, 3.9f, 20f, 5f, 20f)
        horizontalLineTo(20f); verticalLineTo(8f)
        moveTo(20f, 12f); horizontalLineTo(16f); verticalLineTo(16f); horizontalLineTo(20f)
        moveTo(16.5f, 14f); horizontalLineTo(17f)
    }),
    LedgerTab("Data", navIcon("Data") {
        moveTo(4f, 6f); curveTo(4f, 2f, 20f, 2f, 20f, 6f)
        curveTo(20f, 10f, 4f, 10f, 4f, 6f); close()
        moveTo(4f, 6f); verticalLineTo(18f); curveTo(4f, 22f, 20f, 22f, 20f, 18f); verticalLineTo(6f)
        moveTo(4f, 12f); curveTo(4f, 16f, 20f, 16f, 20f, 12f)
    }),
)

@Composable internal fun LedgerNavigation(selectedTab: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.background).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(30.dp), color = colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f))) {
            Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 6.dp, vertical = 6.dp)) {
                ledgerTabs.forEachIndexed { index, tab ->
                    val selected = selectedTab == index
                    val tint by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant, label = "tab icon")
                    val pill by animateColorAsState(if (selected) colors.primaryContainer else Color.Transparent, label = "tab pill")
                    val border by animateColorAsState(if (selected) colors.primary.copy(alpha = .35f) else Color.Transparent, label = "tab border")
                    Column(Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected, onClick = { onSelect(index) }, role = Role.Tab)
                        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                        Surface(Modifier.size(width = 48.dp, height = 32.dp), shape = RoundedCornerShape(16.dp),
                            color = pill, border = BorderStroke(1.dp, border)) {
                            Box(contentAlignment = Alignment.Center) { Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp)) }
                        }
                        Text(tab.label, color = tint, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
