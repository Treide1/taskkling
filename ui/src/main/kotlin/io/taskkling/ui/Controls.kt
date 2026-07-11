package io.taskkling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's small shared controls (DESIGN §9): the quiet outline button and the
 * quiet icon button. Both follow the same utilitarian chrome — `panel2` fill,
 * `line` border, never primary-colored — and render disabled at 0.4 alpha with
 * no hand cursor while a CLI call is in flight.
 */

/** Utilitarian outline button (DESIGN §9): `panel2` fill, `line` border, `txt` label — not primary. */
@Composable
internal fun OutlineButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(6.dp))
            .background(Tk.panel2)
            .border(1.dp, Tk.line, RoundedCornerShape(6.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = Tk.txt)
    }
}

/**
 * Quiet icon button (DESIGN §9): a small rounded-rect that is muted and
 * chrome-free at rest — [tint]-colored glyph on nothing — and quietly lifts on
 * hover (`panel2` fill, 1px `line` border, `txt` tint, hand cursor).
 */
@Composable
internal fun QuietIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 12.dp,
    tint: Color = Tk.muted,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val lifted = hovered && enabled
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (lifted) Tk.panel2 else Color.Transparent)
            .border(1.dp, if (lifted) Tk.line else Color.Transparent, shape)
            .hoverable(interactions)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onClick() }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (lifted) Tk.txt else tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
