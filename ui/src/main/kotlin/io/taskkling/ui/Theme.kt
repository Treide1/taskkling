package io.taskkling.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import io.taskkling.contract.TaskDto

/**
 * The one place the DESIGN.md §3 tokens live (PRD §13, DESIGN §12). Every colour
 * on screen resolves through [Tk] — no hex literals are scattered through the
 * composables. Names match the DESIGN §3 token names 1:1 so code and contract
 * stay auditable against each other.
 */
public object Tk {
    // Chrome (surfaces, lines, text) — DESIGN §3.
    val bg = Color(0xFF0E1116)
    val panel = Color(0xFF161B22)
    val panel2 = Color(0xFF1C232D)
    val line = Color(0xFF2A3340)
    val txt = Color(0xFFD6DEE8)
    val muted = Color(0xFF8B97A7)
    val faint = Color(0xFF5A6675)
    val accent = Color(0xFF4DA3FF)
    val dot = Color(0xFF1A212B)

    // State palette — DESIGN §3.
    val ready = Color(0xFF3FB950)
    val blocked = Color(0xFFF85149)
    val waiting = Color(0xFFD29922)
    val deferred = Color(0xFF58A6FF)
    val done = Color(0xFF6E7681)
    val dropped = Color(0xFF484F58)
    val open = Color(0xFF5A6675) // == faint
    val overdue = Color(0xFFFF7B72)

    // Semantic tag border tints (outline variant) — DESIGN §8.
    val dueBorder = Color(0xFF5A2A28)
    val deferBorder = Color(0xFF284263)
    val waitBorder = Color(0xFF5A4A1A)
    val prioHighBorder = Color(0xFF5A2A28)

    // Text placed on a filled `dropped` pill (its fill is too dark for `bg` text) — DESIGN §3.
    val droppedPillText = Color(0xFFC9D1D9)
}

/**
 * Monospace everywhere (DESIGN §4): bundled JetBrains Mono (OFL-1.1, license ships
 * beside the TTFs in resources/fonts/). Only the two weights the UI uses are bundled;
 * Skia falls back to a system font for glyphs the family lacks.
 */
public val Mono: FontFamily = FontFamily(
    Font(resource = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/JetBrainsMono-Bold.ttf", weight = FontWeight.Bold),
)

/**
 * A task's **primary state** (DESIGN §2). Drives the card's accent border, its
 * state pill, and its legend bucket. Each carries its state-palette [color].
 */
public enum class TaskState(public val label: String, public val color: Color) {
    READY("ready", Tk.ready),
    BLOCKED("blocked", Tk.blocked),
    WAITING("waiting", Tk.waiting),
    DEFERRED("deferred", Tk.deferred),
    DONE("done", Tk.done),
    DROPPED("dropped", Tk.dropped),
    OPEN("open", Tk.open),
}

/**
 * Primary-state precedence, first match wins (DESIGN §2). This is the *only*
 * semantic derivation the UI performs — a pure presentation choice; every other
 * field comes straight from `taskkling export`.
 */
public fun stateOf(t: TaskDto): TaskState = when {
    t.status == "done" -> TaskState.DONE
    t.status == "dropped" -> TaskState.DROPPED
    t.status == "waiting" -> TaskState.WAITING
    t.computed.blocked -> TaskState.BLOCKED
    t.computed.deferred -> TaskState.DEFERRED
    t.computed.ready -> TaskState.READY
    else -> TaskState.OPEN
}

/** Text colour drawn on a filled state pill: bold `bg`, except `dropped` (too dark). */
public fun pillTextColor(state: TaskState): Color =
    if (state == TaskState.DROPPED) Tk.droppedPillText else Tk.bg

/** ISO-8601 → `YYYY-MM-DD` (DESIGN date tags). */
public fun fmtDate(iso: String): String = iso.take(10)

/** ISO-8601 → `YYYY-MM-DD HH:MM UTC`, dropping seconds (header/panel timestamps, DESIGN §9). */
public fun fmtDateTime(iso: String): String =
    iso.replace("T", " ").replace(Regex(":\\d\\dZ$"), "").replace("Z", "") + " UTC"

/**
 * The dark, monospace theme (DESIGN §12). Material is *at most a carrier* for the
 * §3 tokens: it seeds a dark colour set and a monospace default text style so
 * stray Material defaults never leak a light surface or a serif glyph.
 */
@Composable
public fun TaskklingTheme(content: @Composable () -> Unit) {
    val colors = darkColors(
        primary = Tk.accent,
        secondary = Tk.accent,
        background = Tk.bg,
        surface = Tk.panel,
        onPrimary = Tk.bg,
        onBackground = Tk.txt,
        onSurface = Tk.txt,
    )
    val selection = TextSelectionColors(handleColor = Tk.accent, backgroundColor = Tk.accent.copy(alpha = 0.35f))
    MaterialTheme(colors = colors) {
        CompositionLocalProvider(
            LocalContentColor provides Tk.txt,
            LocalTextSelectionColors provides selection,
        ) {
            ProvideTextStyle(
                TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.5.sp, color = Tk.txt),
                content,
            )
        }
    }
}
