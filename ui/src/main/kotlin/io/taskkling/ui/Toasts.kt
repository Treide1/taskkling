package io.taskkling.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * t-aq99: a minimal toast/snackbar primitive — the UI had none. Queue of
 * auto-dismissing notices rendered bottom-centre over the canvas, styled like a
 * slim card: `panel2` surface, 1dp `line` border, a 4dp kind-coloured left bar
 * (mirroring the card accent-edge language, DESIGN §6). Click dismisses early.
 * Kept deliberately small; a real primitive would grow variants/actions later
 * (also wanted by t-nt8t / t-tm10 feedback).
 */
public enum class ToastKind(internal val color: Color, internal val holdMs: Long) {
    SUCCESS(Tk.ready, 4000),
    ERROR(Tk.blocked, 7000),
    INFO(Tk.accent, 3000),
}

public class ToastItem(
    public val id: Long,
    public val text: String,
    public val kind: ToastKind,
) {
    /** Flipped just before removal so the card can fade out. */
    public var leaving: Boolean by mutableStateOf(false)
}

/** Owns the visible queue; capped so a burst of failures can't wallpaper the canvas. */
public class ToastState {
    public val items: MutableList<ToastItem> = mutableStateListOf()
    private var nextId = 0L

    public fun show(text: String, kind: ToastKind) {
        items.add(ToastItem(nextId++, text, kind))
        while (items.size > 3) items.removeAt(0)
    }

    public fun dismiss(item: ToastItem) {
        items.remove(item)
    }
}

private const val FADE_MS = 150

/** The overlay host: stack newest-at-bottom, each toast timing out on its own clock. */
@Composable
public fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (item in state.items) {
            ToastCard(item, onDismiss = { state.dismiss(item) })
        }
    }
}

@Composable
private fun ToastCard(item: ToastItem, onDismiss: () -> Unit) {
    // Fade in on entry, fade out ahead of removal; the timeout clock lives here so
    // every toast dismisses itself even if the caller never touches it again.
    val alpha by animateFloatAsState(if (item.leaving) 0f else 1f, tween(FADE_MS))
    LaunchedEffect(item.id) {
        delay(item.kind.holdMs)
        item.leaving = true
        delay(FADE_MS.toLong())
        onDismiss()
    }
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(Tk.panel2.copy(alpha = alpha))
            .drawBehind {
                val bw = 1.dp.toPx()
                drawRoundRect(
                    color = Tk.line.copy(alpha = alpha),
                    topLeft = androidx.compose.ui.geometry.Offset(bw / 2, bw / 2),
                    size = Size(size.width - bw, size.height - bw),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx() - bw / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(bw),
                )
                drawRect(color = item.kind.color.copy(alpha = alpha), size = Size(4.dp.toPx(), size.height))
            }
            .clickable(interactionSource = MutableInteractionSource(), indication = null) { onDismiss() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(item.text, fontSize = 12.sp, color = Tk.txt.copy(alpha = alpha))
    }
}
