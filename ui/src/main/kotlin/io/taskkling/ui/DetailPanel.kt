package io.taskkling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.taskkling.contract.TaskDto

/**
 * The detail panel (DESIGN §9): a fixed 320dp column with a `line` left border.
 * Empty state is a centred hint; a selection shows styled fields (absent values
 * as a faint "—"), computed-flag chips, clickable reference ids, and the quiet
 * outline mutation buttons wired through [onAction]. While a mutation is in
 * flight ([busy]) the buttons render disabled so actions can't stack.
 *
 * Whenever a pin exists but [task] isn't the pinned one — another selection or
 * the empty state — the pinned-card return FAB floats top-right; clicking it
 * hands [pinnedId] to [onNavigate] (re-select + pan-to-card).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(
    task: TaskDto?,
    pinnedId: String?,
    error: String?,
    busy: Boolean,
    onAction: (verb: String, id: String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Box(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(Tk.panel)
            .drawBehind {
                val x = 0.5.dp.toPx()
                drawLine(Tk.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
    ) {
        if (task == null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (error != null) {
                        Text("error: $error", color = Tk.blocked, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Select a task to inspect.\nEdges point from a blocker → the task it blocks.",
                        color = Tk.muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            TaskDetails(task, error, busy, onAction, onNavigate)
        }
        if (pinnedId != null && pinnedId != task?.id) {
            PinReturnFab(
                onClick = { onNavigate(pinnedId) },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
    }
}

/** The selected task's scrolling field list — the panel body of DESIGN §9. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetails(
    task: TaskDto,
    error: String?,
    busy: Boolean,
    onAction: (verb: String, id: String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (error != null) {
            Text("error: $error", color = Tk.blocked, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        Text(task.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tk.txt)
        Spacer(Modifier.height(2.dp))
        Text(task.id, fontSize = 11.sp, color = Tk.faint)
        Spacer(Modifier.height(14.dp))

        Field("status", task.status)
        Field("thread", task.thread)
        Field("priority", task.priority)
        Field("external requirement", task.waitingOn)
        Field("due", task.due?.let(::fmtDateTime))
        Field("defer", task.defer?.let(::fmtDateTime))
        Field("created", fmtDateTime(task.created))
        Field("closed", task.closed?.let(::fmtDateTime))

        val c = task.computed
        FieldLabel("computed")
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FlagChip("ready", c.ready, Tk.ready)
            FlagChip("blocked", c.blocked, Tk.blocked)
            FlagChip("deferred", c.deferred, Tk.deferred)
            FlagChip("overdue", c.overdue, Tk.waiting)
            FlagChip("resurfaced", c.resurfaced, Tk.waiting)
        }
        Spacer(Modifier.height(9.dp))

        RefField("blocked by", task.depends, onNavigate, resolved = task.depends.toSet() - c.blockers.toSet())
        RefField("blocker of", c.dependents, onNavigate)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("done", enabled = !busy) { onAction("done", task.id) }
            OutlineButton("drop", enabled = !busy) { onAction("drop", task.id) }
            OutlineButton("reopen", enabled = !busy) { onAction("reopen", task.id) }
        }
    }
}

/** An uppercase, muted, letter-spaced caption — the panel's field label (DESIGN §4/§9). */
@Composable
private fun FieldLabel(label: String) {
    Text(label.uppercase(), fontSize = 11.sp, color = Tk.muted, letterSpacing = 0.5.sp)
}

/** A labeled field; an absent [value] renders as a faint "—" so the panel shape stays stable. */
@Composable
private fun Field(label: String, value: String?) {
    Column(Modifier.padding(bottom = 9.dp)) {
        FieldLabel(label)
        Spacer(Modifier.height(2.dp))
        val shown = value?.takeIf { it.isNotEmpty() }
        Text(shown ?: "—", fontSize = 13.sp, color = if (shown == null) Tk.faint else Tk.txt)
    }
}

/**
 * A field whose value is a comma-separated list of `accent` reference links (DESIGN §9).
 * Ids in [resolved] (upstream tasks already done — no longer blocking) render muted +
 * struck through instead of accent, but stay clickable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefField(
    label: String,
    ids: List<String>,
    onNavigate: (String) -> Unit,
    resolved: Set<String> = emptySet(),
) {
    Column(Modifier.padding(bottom = 9.dp)) {
        FieldLabel(label)
        Spacer(Modifier.height(2.dp))
        if (ids.isEmpty()) {
            Text("—", fontSize = 13.sp, color = Tk.faint)
        } else {
            FlowRow {
                ids.forEachIndexed { i, id ->
                    if (i > 0) Text(", ", fontSize = 13.sp, color = Tk.muted)
                    val isResolved = id in resolved
                    Text(
                        id,
                        fontSize = 13.sp,
                        color = if (isResolved) Tk.muted else Tk.accent,
                        textDecoration = if (isResolved) TextDecoration.LineThrough else null,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onNavigate(id) },
                    )
                }
            }
        }
    }
}

/**
 * The pinned-card return FAB (DESIGN §9): a small floating rounded-rect card
 * reading "filled-pin →". Same quiet chrome as the outline buttons but with a
 * shadow — it floats over the panel instead of sitting in its flow.
 */
@Composable
private fun PinReturnFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(Tk.panel2)
            .border(1.dp, Tk.line, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = PinIcons.Filled,
            contentDescription = "return to pinned task",
            tint = Tk.accent,
            modifier = Modifier.size(13.dp),
        )
        Text("→", fontSize = 13.sp, color = Tk.txt)
    }
}

/** Utilitarian outline button (DESIGN §9): `panel2` fill, `line` border, `txt` label — not primary. */
@Composable
private fun OutlineButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
