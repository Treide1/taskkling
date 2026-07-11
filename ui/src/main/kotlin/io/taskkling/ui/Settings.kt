package io.taskkling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which settings dialog is open — session-only UI state hoisted into the app. */
internal enum class SettingsDialog { ARCHIVE, PRUNE }

/**
 * The header settings menu (DESIGN §9): a cogwheel at the end of the count-chip
 * row opening a quiet dropdown of workspace actions. The menu holds no state of
 * its own — every action runs a CLI verb and the app refreshes from its
 * returned export. An entry whose dialog hasn't landed yet arrives as a null
 * action and renders disabled.
 */
@Composable
internal fun SettingsMenu(
    enabled: Boolean,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        QuietIconButton(
            icon = UiIcons.Gear,
            contentDescription = "settings",
            enabled = enabled,
            onClick = { open = true },
            iconSize = 14.dp,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Tk.panel2),
        ) {
            MenuEntry("Archive tasks…", onArchive) { open = false }
            MenuEntry("Prune tasks…", onPrune) { open = false }
        }
    }
}

@Composable
private fun MenuEntry(text: String, action: (() -> Unit)?, dismiss: () -> Unit) {
    DropdownMenuItem(
        enabled = action != null,
        onClick = {
            dismiss()
            action?.invoke()
        },
    ) {
        Text(text, fontSize = 13.sp, color = if (action != null) Tk.txt else Tk.faint)
    }
}

/**
 * The archive dialog (DESIGN §9, settings dialogs): pick which closed types to
 * sweep into the archive. Archiving-amber accent (`waiting`) — noticeable, not
 * alarming. Confirm hands the selection to [onConfirm], which runs `cleanup`
 * (`--only` when narrowed) through the mutation path.
 */
@Composable
internal fun ArchiveDialog(
    doneCount: Int,
    droppedCount: Int,
    busy: Boolean,
    onConfirm: (done: Boolean, dropped: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    SweepDialog(
        title = "Archive tasks",
        copy = "Sweep closed tasks from the active set into the archive. Archived tasks stay restorable (taskkling restore).",
        accent = Tk.waiting,
        confirmLabel = "archive",
        doneCount = doneCount,
        droppedCount = droppedCount,
        busy = busy,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The prune dialog (DESIGN §9, settings dialogs): the archive dialog's options
 * with the high-alert `blocked` red — this one is destructive. Confirm hands
 * the selection to [onConfirm], which runs the `delete` verb per matching task
 * (trash + cascade-prune from dependents' depends).
 */
@Composable
internal fun PruneDialog(
    doneCount: Int,
    droppedCount: Int,
    busy: Boolean,
    onConfirm: (done: Boolean, dropped: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    SweepDialog(
        title = "Prune tasks",
        copy = "Delete closed tasks: each moves to trash and is pruned from every dependent's blocked-by list. Restorable (taskkling restore) only until the trash is purged.",
        accent = Tk.blocked,
        confirmLabel = "prune",
        doneCount = doneCount,
        droppedCount = droppedCount,
        busy = busy,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The shared sweep-dialog scaffold: a scrim, a centred `panel` card with an
 * [accent]-colored border/title/confirm, one checkbox row per closed type with
 * its live count, and quiet cancel/confirm buttons. Confirm is disabled while a
 * CLI call is in flight or when the selection matches zero tasks.
 */
@Composable
private fun SweepDialog(
    title: String,
    copy: String,
    accent: Color,
    confirmLabel: String,
    doneCount: Int,
    droppedCount: Int,
    busy: Boolean,
    onConfirm: (done: Boolean, dropped: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var done by remember { mutableStateOf(true) }
    var dropped by remember { mutableStateOf(true) }
    val selectedCount = (if (done) doneCount else 0) + (if (dropped) droppedCount else 0)
    val shape = RoundedCornerShape(7.dp)
    // Scrim: click-away dismisses; the card consumes its own clicks.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .clip(shape)
                .background(Tk.panel)
                .border(1.dp, accent, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(copy, fontSize = 12.sp, color = Tk.muted, lineHeight = 18.sp)
            CheckRow("done", doneCount, done, accent, enabled = !busy) { done = it }
            CheckRow("dropped", droppedCount, dropped, accent, enabled = !busy) { dropped = it }
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineButton("cancel") { onDismiss() }
                AccentButton(confirmLabel, accent, enabled = !busy && selectedCount > 0) {
                    onConfirm(done, dropped)
                }
            }
        }
    }
}

/** A checkbox row: drawn box + type label + its live count from the current export. */
@Composable
private fun CheckRow(
    label: String,
    count: Int,
    checked: Boolean,
    accent: Color,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) accent else Tk.panel2)
                .border(1.dp, if (checked) accent else Tk.line, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                // Drawn checkmark (no font-fallback risk), bg-colored on the accent fill.
                Canvas(Modifier.size(8.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.1f, size.height * 0.55f)
                        lineTo(size.width * 0.4f, size.height * 0.85f)
                        lineTo(size.width * 0.9f, size.height * 0.15f)
                    }
                    drawPath(p, Tk.bg, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
        Text(label, fontSize = 13.sp, color = Tk.txt)
        Text("($count)", fontSize = 12.sp, color = Tk.muted)
    }
}

/** The dialog confirm button: the outline-button chrome with the dialog accent on border + label. */
@Composable
private fun AccentButton(text: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(6.dp))
            .background(Tk.panel2)
            .border(1.dp, accent, RoundedCornerShape(6.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
    }
}
