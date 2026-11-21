package io.inputcontrol.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.inputcontrol.R

@Immutable
data class Finish(
    val id: String,
    val title: String,
    val description: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val accent: Color,
    val ink: Color,
    val border: Color,
    val art: Int,
    val header: Int,
    val dark: Boolean = false,
) {
    val card = lerp(surface, accent, .65f)
    val muted = lerp(ink, surface, .22f)
    val pressed = lerp(surface, primary, .30f)
    val disabled = lerp(surface, card, .48f)
    val keyGradient =
        Brush.verticalGradient(
            listOf(
                if (dark) lerp(surface, Color.White, .075f) else surface,
                lerp(surface, accent, .30f),
            )
        )
    val cardGradient = Brush.verticalGradient(listOf(card, surface))
    val elevation = if (dark) 1.dp else 2.dp
    val onPrimary = Color.White
    val scheme =
        (if (dark) darkColorScheme() else lightColorScheme()).copy(
            background = background,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            primary = if (dark) lerp(primary, Color.White, .56f) else primary,
            onPrimary = if (dark) background else onPrimary,
            secondary = muted,
            primaryContainer = accent,
            onPrimaryContainer = ink,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = card,
            surfaceContainerHighest = accent,
            surfaceVariant = accent,
            onSurfaceVariant = muted,
            outlineVariant = border,
            outline = muted,
        )
}

private fun rgb(hex: Int) = Color(hex or (0xff shl 24))

private fun finish(
    id: String,
    title: String,
    description: String,
    bg: Int,
    surface: Int,
    primary: Int,
    accent: Int,
    ink: Int,
    border: Int,
    art: Int,
    header: Int,
    dark: Boolean = false,
) =
    Finish(
        id,
        title,
        description,
        rgb(bg),
        rgb(surface),
        rgb(primary),
        rgb(accent),
        rgb(ink),
        rgb(border),
        art,
        header,
        dark,
    )

val finishes =
    listOf(
        finish(
            "modern-light",
            "Modern Light",
            "Cool whites and blue mountain ridges",
            0xF5F7FB,
            0xFFFFFF,
            0x315D91,
            0xDCEAF9,
            0x1D2D45,
            0xBDCDDF,
            R.drawable.scene_modern,
            R.drawable.header_modern,
        ),
        finish(
            "dark-focus",
            "Dark Focus",
            "Midnight blue, stars and moonlit pines",
            0x101B2C,
            0x293A50,
            0x3867A5,
            0x314A6C,
            0xF2F6FC,
            0x47607E,
            R.drawable.scene_night,
            R.drawable.header_night,
            true,
        ),
        finish(
            "minimal-sand",
            "Minimal Sand",
            "Ivory keys above warm desert dunes",
            0xF7F1E9,
            0xFFFCF8,
            0x805D3D,
            0xE9D8C4,
            0x3B342E,
            0xD7C7B6,
            R.drawable.scene_sand,
            R.drawable.header_sand,
        ),
        finish(
            "forest-camp",
            "Forest Camp",
            "Deep greens and a sheltered campsite",
            0x122E27,
            0x315346,
            0x306849,
            0x476D58,
            0xF4F7F1,
            0x517664,
            R.drawable.scene_forest,
            R.drawable.header_forest,
            true,
        ),
        finish(
            "tech-blue",
            "Tech Blue",
            "Ice blue, angled lines and a city skyline",
            0xF0F6FF,
            0xFFFFFF,
            0x2259B7,
            0xC9DFFF,
            0x17366B,
            0xB4CAF0,
            R.drawable.scene_tech,
            R.drawable.header_tech,
        ),
    )
val LocalFinish = staticCompositionLocalOf { finishes.first() }
val LocalDecorations = compositionLocalOf { true }

// Taper the horizon into the header so a transparent vignette has no rectangular edges.
fun Modifier.horizon() = drawWithCache {
    val w = size.width
    val h = size.height
    val outline =
        Path().apply {
            moveTo(0f, h)
            quadraticTo(w * .15f, h * .95f, w * .36f, h * .18f)
            lineTo(w * .80f, h * .12f)
            quadraticTo(w * .93f, h * .87f, w, h)
            close()
        }
    onDrawWithContent { clipPath(outline) { this@onDrawWithContent.drawContent() } }
}

@Composable
fun Scenery(
    modifier: Modifier = Modifier,
    finish: Finish = LocalFinish.current,
    art: Int = finish.art,
) {
    if (LocalDecorations.current)
        Image(painterResource(art), null, modifier, contentScale = ContentScale.FillBounds)
}
