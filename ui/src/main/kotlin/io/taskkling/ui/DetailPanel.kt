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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.taskkling.contract.TaskDto

/**
 * The detail panel (DESIGN §9): a fixed 320dp column with a `line` left border.
 * Empty state is a centred hint; a selection shows styled fields (absent values
 * as a faint "—"), computed-flag chips, clickable reference ids, and the quiet
 * outline mutation buttons wired through [onAction].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(
    task: TaskDto?,
    error: String?,
    onAction: (verb: String, id: String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val panel = Modifier
        .width(320.dp)
        .fillMaxHeight()
        .background(Tk.panel)
        .drawBehind {
            val x = 0.5.dp.toPx()
            drawLine(Tk.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }

    if (task == null) {
        Box(panel.padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error != null) {
                    Text("error: $error", color = Tk.blocked, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Select a node to inspect.\nEdges point from a blocker → the task it blocks.",
                    color = Tk.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    Column(panel.padding(16.dp).verticalScroll(rememberScrollState())) {
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
        Field("waiting on", task.waitingOn)
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

        RefField("blocks on (depends)", task.depends, onNavigate)
        RefField("blockers (unmet)", c.blockers, onNavigate)
        RefField("dependents", c.dependents, onNavigate)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("done") { onAction("done", task.id) }
            OutlineButton("drop") { onAction("drop", task.id) }
            OutlineButton("reopen") { onAction("reopen", task.id) }
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

/** A field whose value is a comma-separated list of `accent` reference links (DESIGN §9). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefField(label: String, ids: List<String>, onNavigate: (String) -> Unit) {
    Column(Modifier.padding(bottom = 9.dp)) {
        FieldLabel(label)
        Spacer(Modifier.height(2.dp))
        if (ids.isEmpty()) {
            Text("—", fontSize = 13.sp, color = Tk.faint)
        } else {
            FlowRow {
                ids.forEachIndexed { i, id ->
                    if (i > 0) Text(", ", fontSize = 13.sp, color = Tk.muted)
                    Text(
                        id,
                        fontSize = 13.sp,
                        color = Tk.accent,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onNavigate(id) },
                    )
                }
            }
        }
    }
}

/** Utilitarian outline button (DESIGN §9): `panel2` fill, `line` border, `txt` label — not primary. */
@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tk.panel2)
            .border(1.dp, Tk.line, RoundedCornerShape(6.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = Tk.txt)
    }
}
