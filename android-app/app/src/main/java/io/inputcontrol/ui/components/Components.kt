package io.inputcontrol.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

val rounded = RoundedCornerShape(18.dp)
val small = RoundedCornerShape(12.dp)
private val strokes =
    mapOf(
        "rocket" to
            "M8,15 Q9,7 20,3 Q21,13 12,17 Z M8,12 L4,12 L7,8 L12,8 M12,17 L12,21 L16,17 L16,13 M7,16 L3,21 L8,19 M15,8 A1.4,1.4 0 1,0 17.8,8 A1.4,1.4 0 1,0 15,8",
        "pc" to "M3,4 H21 V16 H3 Z M12,16 V20 M8,20 H16",
        "keyboard" to
            "M2,5 H22 V19 H2 Z M5,9 h2 m2,0 h2 m2,0 h2 m2,0 h2 M5,12 h2 m2,0 h2 m2,0 h2 m2,0 h2 M8,15 h8",
        "mouse" to "M6,11 Q6,3 12,3 Q18,3 18,11 V14 Q18,21 12,21 Q6,21 6,14 Z M12,3 V10 M6,11 H18",
        "move" to
            "M12,2 V22 M8,6 L12,2 L16,6 M8,18 L12,22 L16,18 M2,12 H22 M6,8 L2,12 L6,16 M18,8 L22,12 L18,16",
        "wifi" to "M2,8 Q12,-1 22,8 M5,12 Q12,5 19,12 M9,16 Q12,13 15,16 M12,19 v1",
        "bluetooth" to "M6,6 L18,17 L11,22 V2 L18,7 L6,18",
        "play" to "M3,5 L12,12 L3,19 Z M16,5 V19 M21,5 V19",
        "playOnly" to "M8,5 L19,12 L8,19 Z",
        "pause" to "M9,5 V19 M15,5 V19",
        "music" to
            "M9,17 V5 L20,3 V15 M9,17 A3,2.5 0 1,1 3,17 A3,2.5 0 1,1 9,17 M20,15 A3,2.5 0 1,1 14,15 A3,2.5 0 1,1 20,15",
        "volume" to "M2,9 H6 L12,4 V20 L6,15 H2 Z M16,8 Q20,12 16,16",
        "previous" to "M5,5 V19 M19,5 L8,12 L19,19 Z",
        "next" to "M19,5 V19 M5,5 L16,12 L5,19 Z",
        "mute" to "M2,9 H6 L12,4 V20 L6,15 H2 Z M16,9 L22,15 M22,9 L16,15",
        "copy" to "M4,4 H15 V7 M8,8 H20 V21 H8 Z",
        "paste" to "M8,5 H4 V21 H20 V5 H16 M8,3 H16 V7 H8 Z",
        "undo" to "M9,4 L3,10 L9,16 M4,10 H15 Q23,10 19,19",
        "lock" to "M5,10 H19 V21 H5 Z M8,10 V7 Q8,2 12,2 Q16,2 16,7 V10",
        "shot" to "M3,9 V4 H9 M15,4 H21 V9 M21,15 V20 H15 M9,20 H3 V15",
        "browser" to
            "M2,12 A10,10 0,1 0,22 12 A10,10 0,1 0,2 12 M2,12 H22 M12,2 Q3,12 12,22 Q21,12 12,2",
        "dots" to "M12,4 v1 M12,11 v1 M12,18 v1",
        "settings" to "M3,6 H21 M3,12 H21 M3,18 H21 M8,3 V9 M16,9 V15 M9,15 V21",
        "grid" to "M3,3 H10 V10 H3 Z M14,3 H21 V10 H14 Z M3,14 H10 V21 H3 Z M14,14 H21 V21 H14 Z",
    )

@Composable
fun Glyph(name: String, size: Dp = 22.dp) {
    val path = remember(name) { PathParser().parsePathString(strokes.getValue(name)).toPath() }
    val tint = LocalContentColor.current
    Canvas(Modifier.size(size)) {
        scale(
            this.size.width / 24f,
            this.size.height / 24f,
            androidx.compose.ui.geometry.Offset.Zero,
        ) {
            drawPath(
                path,
                tint,
                style =
                    Stroke(
                        if (name == "dots") 3.2f else 1.6f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
            )
        }
    }
}

@Composable
fun Group(title: String, icon: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(rounded)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.padding(start = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let { Glyph(it, 20.dp) }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
fun KeyTile(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    icon: String? = null,
    action: () -> Unit,
) {
    Surface(
        onClick = action,
        modifier = modifier.tactile().heightIn(min = 48.dp),
        shape = small,
        color =
            if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Glyph(icon, 19.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
