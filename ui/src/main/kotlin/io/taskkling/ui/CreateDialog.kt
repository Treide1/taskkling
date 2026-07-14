package io.taskkling.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The add-card dialog (DESIGN §9 dialog family): scrim + centred `panel` card with
 * the `accent` border, opened from the header's + button. Unlike the panel's
 * commit-per-edit fields, every field here is a local DRAFT — nothing touches the
 * CLI until "create" submits the whole form once as one `add` invocation
 * ([onCreate] receives the raw args; the client appends `--export-on-success`).
 *
 * Title/status/thread are always visible; priority / external requirement / due /
 * defer / body sit in a collapsible, collapsed by default. While [submitting],
 * every affordance is inert (fields disabled, scrim + Escape ignored) and the
 * button row is a spinner — the dialog can only resolve through the CLI's answer:
 * success closes it, failure lands on the inline [error] line and re-enables.
 *
 * The keyboard never submits (user decision 2026-07-14): Enter types a newline in
 * the body field and is a no-op elsewhere; Escape (when not submitting) dismisses.
 */
@Composable
internal fun CreateCardDialog(
    defaultThread: String,
    submitting: Boolean,
    error: String?,
    onCreate: (args: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("open") }
    var thread by remember { mutableStateOf(defaultThread) }
    var priority by remember { mutableStateOf("normal") }
    var req by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var defer by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    fun submit() {
        if (title.isBlank() || submitting) return
        // Flag grammar per HANDOFF-add-card-3 §A: defaults are omitted, blanks are
        // omitted, body keeps inner newlines (trim strips the ends only).
        val args = buildList {
            add("add")
            add(title.trim())
            add("--status")
            add(status)
            if (thread.isNotBlank()) { add("--thread"); add(thread.trim()) }
            if (priority != "normal") { add("--priority"); add(priority) }
            if (req.isNotBlank()) { add("--req"); add(req.trim()) }
            if (due.isNotBlank()) { add("--due"); add(due.trim()) }
            if (defer.isNotBlank()) { add("--defer"); add(defer.trim()) }
            if (body.isNotBlank()) { add("--body"); add(body.trim()) }
        }
        onCreate(args)
    }

    val shape = RoundedCornerShape(7.dp)
    // Scrim: click-away dismisses (unless mid-flight); the card consumes its own clicks.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!submitting) onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(shape)
                .background(Tk.panel)
                .border(1.dp, Tk.accent, shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                // Escape is the dialog's only keyboard action; it rides preview so a
                // focused text field can't swallow it first.
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && !submitting) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Create task", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tk.accent)

            val titleFocus = remember { FocusRequester() }
            FormSection("title") {
                FormTextField(title, { title = it }, enabled = !submitting, modifier = Modifier.focusRequester(titleFocus))
            }
            LaunchedEffect(Unit) { titleFocus.requestFocus() }

            FormSection("status") {
                FormDropdown(status, listOf("open", "waiting", "done", "dropped"), enabled = !submitting) { status = it }
            }
            FormSection("thread") {
                FormTextField(thread, { thread = it }, enabled = !submitting)
            }

            DisclosureRow(expanded, enabled = !submitting) { expanded = !expanded }
            if (expanded) {
                FormSection("priority") {
                    FormDropdown(priority, listOf("low", "normal", "high"), enabled = !submitting) { priority = it }
                }
                FormSection("external requirement") {
                    FormTextField(req, { req = it }, enabled = !submitting)
                }
                FormSection("due") {
                    FormTextField(due, { due = it }, enabled = !submitting, placeholder = "YYYY-MM-DD or ISO datetime")
                }
                FormSection("defer") {
                    FormTextField(defer, { defer = it }, enabled = !submitting, placeholder = "YYYY-MM-DD or ISO datetime")
                }
                FormSection("body") {
                    FormTextField(
                        body,
                        { body = it },
                        enabled = !submitting,
                        placeholder = "Add a description…",
                        singleLine = false,
                        modifier = Modifier.heightIn(min = 60.dp),
                    )
                }
            }

            if (error != null) {
                Text("error: $error", color = Tk.blocked, fontSize = 12.sp)
            }

            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (submitting) {
                    CircularProgressIndicator(color = Tk.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    OutlineButton("cancel") { onDismiss() }
                    AccentCreateButton("create", enabled = title.isNotBlank()) { submit() }
                }
            }
        }
    }
}

/** A labelled form slot: the uppercase caption over whatever field [content] renders. */
@Composable
private fun FormSection(label: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(label)
        Spacer(Modifier.height(3.dp))
        content()
    }
}

/** The dialog's field caption — same voice as the panel's field label. */
@Composable
private fun SectionLabel(label: String) {
    Text(label.uppercase(), fontSize = 11.sp, color = Tk.muted, letterSpacing = 0.5.sp)
}

/**
 * A draft text field styled like the panel's inline editor: `panel2` well, `line`
 * border, mono text, accent cursor. Pure local state — commits nothing; the dialog
 * submits the draft once. [singleLine] false turns it into the body's multiline well.
 */
@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String? = null,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Tk.txt),
        cursorBrush = SolidColor(Tk.accent),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(4.dp))
            .background(Tk.panel2)
            .border(1.dp, Tk.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        decorationBox = { inner ->
            // Overlay so the placeholder sits under the cursor, not above the field.
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, fontSize = 13.sp, color = Tk.faint)
                }
                inner()
            }
        },
    )
}

/**
 * A draft enum field: the value + chevron affordance opening a quiet dropdown, like
 * the panel's enum editor but writing only local state. Picking the current value
 * is a no-op.
 */
@Composable
private fun FormDropdown(
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
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
            DialogChevron(if (hovered && enabled) Tk.txt else Tk.muted)
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
                    Text(option, fontSize = 13.sp, color = if (option == value) Tk.accent else Tk.txt)
                }
            }
        }
    }
}

/**
 * The collapsible's disclosure row: chevron + "optional fields", the whole row a
 * click target. The chevron points right when collapsed and rotates down on expand.
 */
@Composable
private fun DisclosureRow(expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, tween(120))
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DialogChevron(Tk.muted, Modifier.rotate(rotation))
        Text("OPTIONAL FIELDS", fontSize = 11.sp, color = Tk.muted, letterSpacing = 0.5.sp)
    }
}

/** The dropdown/disclosure affordance: a small drawn triangle (no icon dependency). */
@Composable
private fun DialogChevron(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(7.dp, 4.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        drawPath(p, color)
    }
}

/** The dialog's confirm button: the outline-button chrome with `accent` border + label. */
@Composable
private fun AccentCreateButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(6.dp))
            .background(Tk.panel2)
            .border(1.dp, Tk.accent, RoundedCornerShape(6.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = Tk.accent, fontWeight = FontWeight.Bold)
    }
}
