package io.taskkling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.taskkling.contract.TaskDto

/**
 * Find-task search (t-tm10): match [query] against the on-canvas corpus — the same
 * `export.tasks` the graph renders, so closed-but-unarchived cards are searchable and
 * archived tasks are excluded by construction.
 *
 * One plain case-insensitive substring test per task, no fuzzy scoring; no prefix-only
 * rule either, so a query with the `t-` dropped (`tm10`) still hits `t-tm10`. id matches
 * rank strictly before title matches; within a tier, export order is kept. A task whose
 * id AND title both match appears once, in the id tier.
 */
internal fun searchMatches(tasks: List<TaskDto>, query: String): List<TaskDto> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val byId = ArrayList<TaskDto>()
    val byTitle = ArrayList<TaskDto>()
    for (t in tasks) {
        when {
            t.id.contains(q, ignoreCase = true) -> byId += t
            t.title.contains(q, ignoreCase = true) -> byTitle += t
        }
    }
    return byId + byTitle
}

/**
 * The search UI's bridge to app state (t-tm10). Open/close is hoisted into [App] so
 * the global Ctrl+F capture can open the popup from the root key handler; everything
 * transient (query, cursor) lives inside the popup composition and is discarded on
 * close — search never restores a previous query, like the pin and link mode.
 */
internal data class SearchBridge(
    val open: Boolean,
    val onOpen: () -> Unit,
    /** Closes the popup AND hands focus back to the root [FocusRequester]. */
    val onClose: () -> Unit,
    val onNavigate: (String) -> Unit,
)

private val SEARCH_W = 300.dp
private val RESULT_ROW_H = 24.dp
private const val VISIBLE_ROWS = 8

/**
 * The header magnifier + its anchored search popup (t-tm10). Mirrors [SettingsMenu]
 * visually — a [QuietIconButton] with a `panel2` surface under it — but the surface is
 * a plain [Popup]: `DropdownMenu` would consume ↑/↓/Enter for its own item navigation,
 * which fights a text input driving a custom results list.
 */
@Composable
internal fun SearchControl(tasks: List<TaskDto>, bridge: SearchBridge) {
    Box {
        QuietIconButton(
            icon = UiIcons.Search,
            contentDescription = "find task",
            enabled = true, // search is read-only, so no busy gate
            onClick = bridge.onOpen,
            iconSize = 14.dp,
        )
        if (bridge.open) {
            // Right-aligned under the icon: TopEnd puts the popup's right edge flush
            // with the anchor's; the offset drops it below the icon's ~20dp footprint.
            val offsetY = with(LocalDensity.current) { 26.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = bridge.onClose,
                properties = PopupProperties(focusable = true),
            ) {
                SearchSurface(tasks, bridge.onNavigate, bridge.onClose)
            }
        }
    }
}

/**
 * The popup surface: a text input over a results column, keyboard-first (t-tm10).
 * Typing filters live and the cursor auto-lands on the best-ranked row; ↑/↓ preview the
 * cursored match on the canvas via `navigateTo` (the first press previews the top row
 * before moving); Enter commits — leaves the cursored card selected/centred — and
 * closes; Esc/click-away just close (no selection/scroll restore: cancel cancels the
 * search UI, not the navigation, browser-find style).
 */
@Composable
private fun SearchSurface(
    tasks: List<TaskDto>,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val results = remember(query.text, tasks) { searchMatches(tasks, query.text) }
    // Both reset on every keystroke: the cursor lands back on the first row and the
    // next arrow press previews it (rather than stepping past it unseen).
    var cursor by remember(query.text) { mutableStateOf(0) }
    var previewed by remember(query.text) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val listScroll = rememberScrollState()
    val density = LocalDensity.current

    fun preview(delta: Int) {
        if (results.isEmpty()) return
        if (previewed) cursor = (cursor + delta).coerceIn(0, results.lastIndex)
        previewed = true
        onNavigate(results[cursor].id)
    }

    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier
            .width(SEARCH_W)
            .clip(shape)
            .background(Tk.panel2)
            .border(1.dp, Tk.line, shape)
            .padding(8.dp)
            // Popup-content root, so these fire whatever inside the popup holds focus.
            .onPreviewKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.Escape -> {
                        onClose()
                        true
                    }
                    ev.key == Key.Enter -> {
                        results.getOrNull(cursor)?.let { onNavigate(it.id) }
                        onClose()
                        true
                    }
                    ev.key == Key.DirectionDown -> {
                        preview(1)
                        true
                    }
                    ev.key == Key.DirectionUp -> {
                        preview(-1)
                        true
                    }
                    // Ctrl+F while already open: the popup owns focus, so the root
                    // capture never sees this — re-select the query for overtyping.
                    ev.isCtrlPressed && ev.key == Key.F -> {
                        query = query.copy(selection = TextRange(0, query.text.length))
                        focus.requestFocus()
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Tk.txt),
            cursorBrush = SolidColor(Tk.accent),
            decorationBox = { inner ->
                Box {
                    if (query.text.isEmpty()) {
                        Text("find by id or title", fontSize = 13.sp, color = Tk.faint)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Tk.panel)
                .border(1.dp, Tk.line, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .focusRequester(focus),
        )
        if (query.text.isNotBlank()) {
            if (results.isEmpty()) {
                // Quiet, muted, no error-red: a no-match is not an error.
                Text(
                    "no matches",
                    fontSize = 12.sp,
                    color = Tk.faint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = RESULT_ROW_H * VISIBLE_ROWS)
                        .verticalScroll(listScroll),
                ) {
                    results.forEachIndexed { i, t ->
                        ResultRow(
                            task = t,
                            cursored = i == cursor,
                            onClick = {
                                onNavigate(t.id)
                                onClose()
                            },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Keep the cursored row visible while arrowing through an overflowing list. Rows
    // are fixed-height, so the target offset is plain math — no per-row geometry.
    LaunchedEffect(cursor, previewed) {
        if (!previewed) return@LaunchedEffect
        val rowPx = with(density) { RESULT_ROW_H.roundToPx() }
        val top = cursor * rowPx
        val bottom = top + rowPx
        when {
            top < listScroll.value -> listScroll.animateScrollTo(top)
            bottom > listScroll.value + listScroll.viewportSize ->
                listScroll.animateScrollTo(bottom - listScroll.viewportSize)
        }
    }
}

/** One result: state dot + accent id + truncated muted title. Click = commit (Enter on it). */
@Composable
private fun ResultRow(task: TaskDto, cursored: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(RESULT_ROW_H)
            .clip(RoundedCornerShape(4.dp))
            // The quiet cursor highlight: chrome `line` on `panel2`, no accent wash.
            .background(if (cursored) Tk.line else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(stateOf(task).color))
        Text(task.id, fontSize = 12.sp, color = Tk.accent)
        Text(
            task.title,
            fontSize = 12.sp,
            color = Tk.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
