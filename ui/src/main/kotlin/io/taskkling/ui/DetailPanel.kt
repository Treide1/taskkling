package io.taskkling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.taskkling.contract.TaskDto
import java.awt.Cursor

/**
 * The detail panel (DESIGN §9): a [width]-wide column with a `line` left border. The width is
 * user-draggable via a left-edge resize gutter ([ResizeHandle] → [onWidthDrag]); the caller owns
 * the (session-only) width state and its clamping (t-q8i2).
 * Empty state is a centred hint; a selection shows styled fields (absent values
 * as a faint "—"), computed-flag chips, and clickable reference ids. Stored
 * fields are edited in place (DESIGN principle 8): enum values through
 * dropdowns, each edit forwarded as raw CLI args through [onMutate]. While a
 * mutation is in flight ([busy]) every editing affordance renders disabled so
 * actions can't stack.
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
    width: Dp,
    onWidthDrag: (Dp) -> Unit,
    onMutate: (args: List<String>) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Tk.panel)
            .drawBehind {
                val x = 0.5.dp.toPx()
                drawLine(Tk.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
    ) {
        // Panel text is selectable/copyable (DESIGN §9); interactive islands
        // inside opt out via DisableSelection where the two gestures fight.
        SelectionContainer {
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
                TaskDetails(task, error, busy, onMutate, onNavigate)
            }
        }
        if (pinnedId != null && pinnedId != task?.id) {
            PinReturnFab(
                onClick = { onNavigate(pinnedId) },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
        // Left-edge resize handle (t-q8i2): a slim full-height hit zone over the panel's left
        // border. Dragging horizontally resizes the panel live; positive delta (drag right)
        // shrinks it, negative (drag left) grows it — Main re-clamps against the window. The
        // hover/drag cursor is the E-resize arrow. Visual: a faint accent line on hover; the
        // panel's own 0.5dp border stays the resting divider.
        ResizeHandle(onWidthDrag = onWidthDrag, modifier = Modifier.align(Alignment.CenterStart))
    }
}

/** A left-anchored vertical resize gutter for the detail panel (t-q8i2). The visible line is
 *  thinner than the [HANDLE_HIT] hit area so the target is easy to grab without a heavy divider. */
@Composable
private fun ResizeHandle(onWidthDrag: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .fillMaxHeight()
            .width(HANDLE_HIT)
            .hoverable(interaction)
            .pointerHoverIcon(ResizeCursor)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx -> onWidthDrag(with(density) { deltaPx.toDp() }) },
            )
            .drawBehind {
                if (hovered) {
                    val x = 1.dp.toPx()
                    drawLine(Tk.accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            },
    )
}

/** Hit width of the panel resize gutter (t-q8i2): wide enough to grab, slimmer than the FAB. */
private val HANDLE_HIT = 6.dp

/** Horizontal-resize pointer for the panel's drag gutter (Compose Desktop → AWT cursor). */
private val ResizeCursor = PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

/** `status` dropdown values → their lifecycle verbs (DESIGN §9 mapping table). */
private fun statusArgs(id: String, status: String): List<String> = when (status) {
    "open" -> listOf("reopen", id)
    "done" -> listOf("done", id)
    "dropped" -> listOf("drop", id)
    else -> listOf("wait", id)
}

/** The selected task's scrolling field list — the panel body of DESIGN §9. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDetails(
    task: TaskDto,
    error: String?,
    busy: Boolean,
    onMutate: (args: List<String>) -> Unit,
    onNavigate: (String) -> Unit,
) {
    // key(task.id): switching the selection discards any open inline editor
    // (DESIGN §9) instead of leaking its draft into the next task's fields.
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (error != null) {
            Text("error: $error", color = Tk.blocked, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        key(task.id) {
            EditableField(
                label = null,
                value = task.title,
                enabled = !busy,
                textSize = 14.sp,
                bold = true,
                saveArgs = { listOf("set", task.id, "--title", it) },
                onMutate = onMutate,
            )
            Spacer(Modifier.height(2.dp))
            Text(task.id, fontSize = 11.sp, color = Tk.faint)
            Spacer(Modifier.height(14.dp))

            EnumField("status", task.status, listOf("open", "done", "dropped", "waiting"), enabled = !busy) {
                onMutate(statusArgs(task.id, it))
            }
            EditableField(
                label = "thread",
                value = task.thread,
                enabled = !busy,
                saveArgs = { listOf("set", task.id, "--thread", it) },
                clearArgs = listOf("set", task.id, "--clear", "thread"),
                onMutate = onMutate,
            )
            EnumField("priority", task.priority, listOf("low", "normal", "high"), enabled = !busy) {
                onMutate(listOf("set", task.id, "-p", it))
            }
            EditableField(
                label = "external requirement",
                value = task.waitingOn,
                enabled = !busy,
                // The CLI's own coupling: --on flips status to waiting; clearing
                // happens only through status transitions (DESIGN §9 table).
                saveArgs = { listOf("wait", task.id, "--on", it) },
                onMutate = onMutate,
            )
            EditableField(
                label = "due",
                value = task.due,
                display = task.due?.let(::fmtDateTime),
                enabled = !busy,
                saveArgs = { listOf("set", task.id, "--due", it) },
                clearArgs = listOf("set", task.id, "--clear", "due"),
                onMutate = onMutate,
            )
            EditableField(
                label = "defer",
                value = task.defer,
                display = task.defer?.let(::fmtDateTime),
                enabled = !busy,
                saveArgs = { listOf("set", task.id, "--defer", it) },
                clearArgs = listOf("set", task.id, "--clear", "defer"),
                onMutate = onMutate,
            )
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
        }
    }
}

/**
 * An enum-valued field edited in place (DESIGN §9, principle 8): the value reads
 * like a plain field, with a drawn chevron affordance that sharpens on hover;
 * clicking opens a quiet value dropdown. Picking the current value is a no-op
 * (no subprocess); anything else hands the raw option to [onSelect].
 */
@Composable
private fun EnumField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 9.dp)) {
        FieldLabel(label)
        Spacer(Modifier.height(2.dp))
        DisableSelection {
            Box {
                val interactions = remember { MutableInteractionSource() }
                val hovered by interactions.collectIsHoveredAsState()
                Row(
                    Modifier
                        .alpha(if (enabled) 1f else 0.4f)
                        .hoverable(interactions)
                        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                        .clickable(enabled = enabled) { open = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(value, fontSize = 13.sp, color = Tk.txt)
                    Chevron(if (hovered && enabled) Tk.txt else Tk.muted)
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.background(Tk.panel2),
                ) {
                    for (option in options) {
                        DropdownMenuItem(onClick = {
                            open = false
                            if (option != value) onSelect(option)
                        }) {
                            Text(
                                option,
                                fontSize = 13.sp,
                                color = if (option == value) Tk.accent else Tk.txt,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A free-text field edited in place (DESIGN §9, principle 8). Read mode renders
 * like [Field] — faint "—" when blank, [display] formatting if it differs from
 * the stored [value] — but is clickable: hand cursor, underline on hover, and a
 * click swaps in an inline editor prefilled with the raw stored value plus a
 * row-trailing quiet save button. Enter/save commits through [onMutate];
 * Escape cancels. The editor closes when the refreshed export changes [value]
 * (a successful round-trip); a CLI rejection leaves it open with the error on
 * the panel's error line. Saving an unchanged value — or an emptied one when
 * [clearArgs] is null (not clearable) — is a local no-op close.
 */
@Composable
private fun EditableField(
    label: String?,
    value: String?,
    enabled: Boolean,
    saveArgs: (String) -> List<String>,
    onMutate: (List<String>) -> Unit,
    clearArgs: List<String>? = null,
    display: String? = value,
    textSize: TextUnit = 13.sp,
    bold: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // A changed stored value means the save round-tripped: close the editor.
    LaunchedEffect(value) { editing = false }

    fun commit() {
        val text = draft.trim()
        when {
            text == (value ?: "") -> editing = false
            text.isEmpty() -> if (clearArgs == null) editing = false else onMutate(clearArgs)
            else -> onMutate(saveArgs(text))
        }
    }

    Column(Modifier.padding(bottom = if (label == null) 0.dp else 9.dp)) {
        if (label != null) {
            FieldLabel(label)
            Spacer(Modifier.height(2.dp))
        }
        if (!editing) {
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val shown = display?.takeIf { it.isNotEmpty() }
            Text(
                shown ?: "—",
                fontSize = textSize,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (shown == null) Tk.faint else Tk.txt,
                textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.4f)
                    .hoverable(interactions)
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .clickable(enabled = enabled) {
                        draft = value ?: ""
                        editing = true
                    },
            )
        } else {
            DisableSelection {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val focus = remember { FocusRequester() }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        enabled = enabled,
                        textStyle = TextStyle(
                            fontFamily = Mono,
                            fontSize = textSize,
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                            color = Tk.txt,
                        ),
                        cursorBrush = SolidColor(Tk.accent),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Tk.panel2)
                            .border(1.dp, Tk.line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { ev ->
                                when {
                                    ev.type != KeyEventType.KeyDown -> false
                                    ev.key == Key.Escape -> { editing = false; true }
                                    ev.key == Key.Enter -> { commit(); true }
                                    else -> false
                                }
                            },
                    )
                    OutlineButton("save", enabled = enabled) { commit() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
    }
}

/** The dropdown affordance: a small drawn triangle (no icon dependency, no font-fallback risk). */
@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(7.dp, 4.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        drawPath(p, color)
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

