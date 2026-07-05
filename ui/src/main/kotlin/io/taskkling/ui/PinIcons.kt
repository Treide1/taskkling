package io.taskkling.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The pin glyph (DESIGN §6/§9), hand-authored so `:ui` takes no icon-library
 * dependency. Both variants share one traced silhouette: [Outline] hollows the
 * head with an inner cutout subpath (even-odd), [Filled] is the silhouette
 * alone. Rendered via `Icon(tint = …)`, so the build-time fill color is inert.
 */
internal object PinIcons {

    /** Hollow-head pin: the hover-reveal toggle on unpinned cards. */
    val Outline: ImageVector by lazy {
        pin("pin.outline", PathFillType.EvenOdd) {
            silhouette()
            headCutout()
        }
    }

    /** Solid pin: always shown on the pinned card, and in the panel's return FAB. */
    val Filled: ImageVector by lazy {
        pin("pin.filled", PathFillType.NonZero) {
            silhouette()
        }
    }

    private fun pin(name: String, fillType: PathFillType, content: PathBuilder.() -> Unit) =
        ImageVector.Builder(
            name = name,
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).apply {
            path(fill = SolidColor(Color.White), pathFillType = fillType) { content() }
        }.build()

    // Geometry traced 1:1 from the reference pin.svg (viewBox "-10.5 0 32 32");
    // its x offset is folded into the two absolute moveTo's (+10.5), every other
    // command is relative and transcribes verbatim.

    /** The outer pin silhouette: flag-shaped head plus the stem. */
    private fun PathBuilder.silhouette() {
        moveTo(20.86f, 16.48f)
        lineToRelative(-2f, -3.16f)
        lineToRelative(1.12f, -6.12f)
        curveToRelative(0.04f, -0.24f, 0f, -0.48f, -0.16f, -0.68f)
        reflectiveCurveToRelative(-0.4f, -0.32f, -0.64f, -0.32f)
        horizontalLineToRelative(-6.8f)
        curveToRelative(-0.24f, 0f, -0.48f, 0.12f, -0.64f, 0.32f)
        reflectiveCurveToRelative(-0.24f, 0.44f, -0.16f, 0.68f)
        lineToRelative(1.16f, 6.12f)
        lineToRelative(-2.08f, 3.24f)
        curveToRelative(-0.16f, 0.24f, -0.16f, 0.6f, -0.04f, 0.84f)
        curveToRelative(0.16f, 0.28f, 0.44f, 0.44f, 0.72f, 0.44f)
        horizontalLineToRelative(3.6f)
        verticalLineToRelative(7.12f)
        curveToRelative(0f, 0.48f, 0.36f, 0.84f, 0.84f, 0.84f)
        reflectiveCurveToRelative(0.84f, -0.36f, 0.84f, -0.84f)
        verticalLineToRelative(-7.12f)
        horizontalLineToRelative(3.6f)
        curveToRelative(0.48f, 0f, 0.84f, -0.36f, 0.84f, -0.84f)
        curveToRelative(0f, -0.2f, -0.12f, -0.4f, -0.2f, -0.52f)
        close()
    }

    /** The head's inner region; subtracted (even-odd) to hollow the outline variant. */
    private fun PathBuilder.headCutout() {
        moveTo(12.9f, 16.16f)
        lineToRelative(1.4f, -2.2f)
        curveToRelative(0.12f, -0.16f, 0.16f, -0.4f, 0.12f, -0.6f)
        lineToRelative(-1.04f, -5.48f)
        horizontalLineToRelative(4.84f)
        lineToRelative(-1.04f, 5.44f)
        curveToRelative(-0.04f, 0.2f, 0f, 0.44f, 0.12f, 0.6f)
        lineToRelative(1.4f, 2.2f)
        lineToRelative(-5.8f, 0.04f)
        close()
    }
}
