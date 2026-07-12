package io.taskkling.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.taskkling.contract.ExportDto
import java.awt.GraphicsEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Graph layout metrics (DESIGN §10). [layout] assigns each node a (layer, indexInLayer)
// slot; the graph's custom Layout turns `layer` into an x column and stacks each column's
// cards one-by-one by their MEASURED heights (indexInLayer = stacking order), so these
// drive column x, the constant vertical gap, and the canvas padding.
internal const val CARD_W = 210
internal const val CARD_MIN_H = 96
internal const val COL_GAP = 110
internal const val ROW_GAP = 24
internal const val PAD = 28

/**
 * The desktop app (PRD §13): a pure CLI client. Reads `export`, lays the DAG out
 * with [layout], renders a node-link graph with a detail panel, and performs
 * every mutation by shelling out to the CLI ([CliClient.mutate]) then refreshing
 * from the returned export. A headless smoke path (env `TASKKLING_SMOKE=1`)
 * exercises the export→layout data path and exits, for verification without a
 * display.
 */
fun main(args: Array<String>) {
    // ADR-010: when launched via `taskkling ui`, the CLI passes its resolved
    // workspace root as the single argument — the UI never re-runs discovery.
    // A bare `:ui:run` (no args) keeps the old cwd behavior for development.
    val workRoot = args.firstOrNull()?.let(::File) ?: File(System.getProperty("user.dir"))
    val binary = CliDiscovery.locate()
    if (System.getenv("TASKKLING_SMOKE") == "1") {
        if (binary == null) {
            System.err.println("smoke: taskkling binary not found (PATH / TASKKLING_BINARY / config)")
            kotlin.system.exitProcess(3)
        }
        val export = CliClient(binary, workRoot).export()
        val gl = layout(export.tasks)
        println("smoke ok: binary=$binary nodes=${export.tasks.size} layers=${gl.layerCount} edges=${gl.edges.size}")
        return
    }

    application {
        // Default window (t-9de2): ~85% of the screen's WORK area (excludes the taskbar,
        // unlike raw screen size), centred. Session-only — recomputed fresh each launch,
        // never persisted, so it tracks whichever monitor/work-area the app starts on.
        val windowState = rememberWindowState(
            size = remember {
                val work = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                DpSize((work.width * 0.85f).dp, (work.height * 0.85f).dp)
            },
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "taskkling",
            icon = painterResource("icons/taskkling.png"),
            state = windowState,
        ) {
            TaskklingTheme {
                Box(Modifier.fillMaxSize().background(Tk.bg)) {
                    if (binary == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SelectionContainer {
                                Text(
                                    "taskkling binary not found.\nSet it on PATH, TASKKLING_BINARY, or config binary_path.",
                                    color = Tk.muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    } else {
                        App(CliClient(binary, workRoot))
                    }
                }
            }
        }
    }
}

@Composable
private fun App(client: CliClient) {
    var export by remember { mutableStateOf<ExportDto?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // The pinned task (DESIGN §6): stays highlighted while selection moves freely.
    // Session-only UI state — never persisted; the CLI stays the single write path.
    var pinnedId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // True while a CLI call is in flight; the panel's mutation buttons render disabled
    // off it, so a double-click can't queue a second subprocess (t-t36o).
    var busy by remember { mutableStateOf(false) }
    // The open settings dialog (archive/prune), session-only like the pin.
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    // The user-dragged detail-panel width in dp (t-q8i2). Session-only — like the pin, it never
    // persists and resets to the default on relaunch. Held as a raw dp Float and re-clamped
    // against the live window width at layout time (see the BoxWithConstraints below), so the
    // panel never exceeds the 60% cap after a window resize.
    var panelWidth by remember { mutableStateOf(PANEL_DEFAULT_W) }
    val scope = rememberCoroutineScope()

    // The canvas scroll state lives here, above GraphPane, so wheel scrolling, drag
    // panning, and programmatic pan-to-card all share one clamped position. The measured
    // card rects are hoisted alongside: GraphPane's measure pass fills the map, pan-to-card
    // below reads a target's centre from it.
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val cardRects = remember { HashMap<String, CardRect>() }

    // Ref-id navigation (DESIGN §9): select the target AND centre its card in the
    // viewport, clamped to the scroll bounds, 150ms on both axes together. Plain card
    // clicks on the canvas select without panning (§1.4).
    fun navigateTo(id: String) {
        selectedId = id
        val rect = cardRects[id] ?: return
        scope.launch {
            launch {
                val x = centerScrollOffset(rect.centerX, hScroll.viewportSize, hScroll.maxValue)
                hScroll.animateScrollTo(x, tween(150))
            }
            launch {
                val y = centerScrollOffset(rect.centerY, vScroll.viewportSize, vScroll.maxValue)
                vScroll.animateScrollTo(y, tween(150))
            }
        }
    }

    fun refresh(next: ExportDto) {
        export = next
        error = null
        if (selectedId != null && next.tasks.none { it.id == selectedId }) selectedId = null
        if (pinnedId != null && next.tasks.none { it.id == pinnedId }) pinnedId = null
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { client.export() } }
            .onSuccess { refresh(it) }
            .onFailure { error = it.message ?: it.toString() }
    }

    // Every mutation shells out OFF the UI thread — CliClient.run blocks on the
    // subprocess — then refreshes from the export the CLI returned (t-t36o). The
    // Result hops back to the UI thread before touching snapshot state.
    fun mutate(args: List<String>) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { client.mutate(args) } }
                .onSuccess { refresh(it) }
                .onFailure { error = "${args.firstOrNull() ?: "cli"}: ${it.message ?: it}" }
            busy = false
        }
    }

    // Prune runs one `delete` per selected task — the CLI has no batch delete
    // (t-m0zn decision) — serialized off the UI thread behind the same busy
    // flag. The graph refreshes from the last successful export; the first
    // failure stops the loop and surfaces on the panel error line.
    fun mutateAll(argsList: List<List<String>>) {
        if (busy || argsList.isEmpty()) return
        busy = true
        scope.launch {
            var last: ExportDto? = null
            var failure: String? = null
            withContext(Dispatchers.IO) {
                for (args in argsList) {
                    runCatching { client.mutate(args) }
                        .onSuccess { last = it }
                        .onFailure { failure = "${args.firstOrNull() ?: "cli"}: ${it.message ?: it}" }
                    if (failure != null) break
                }
            }
            last?.let { refresh(it) }
            failure?.let { error = it } // after refresh: refresh() clears the error line
            busy = false
        }
    }

    // The header refresh button: a plain re-export through the same busy gate as
    // mutations, so a refresh can't race a mutation's read-after-write.
    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { client.export() } }
                .onSuccess { refresh(it) }
                .onFailure { error = "export: ${it.message ?: it}" }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(
                export,
                busy = busy,
                onRefresh = ::reload,
                onArchive = { dialog = SettingsDialog.ARCHIVE },
                onPrune = { dialog = SettingsDialog.PRUNE },
            )
            // BoxWithConstraints exposes the live window width so the panel's dragged width can be
            // re-clamped against the 60% cap every layout pass — a Modifier-level clamp at measure
            // time, so a window shrink pulls an over-wide panel back within the cap (t-q8i2).
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val windowWidth = maxWidth.value
                val clampedPanelWidth = clampPanelWidth(panelWidth, windowWidth)
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val current = export
                        when {
                            error != null && current == null ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    SelectionContainer {
                                        Text("error: $error", color = Tk.blocked, fontSize = 13.sp)
                                    }
                                }
                            current == null ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("loading…", color = Tk.muted, fontSize = 13.sp)
                                }
                            else -> GraphPane(
                                export = current,
                                selectedId = selectedId,
                                // Highlight source: the pin wins; without one, selection highlights.
                                highlightedId = pinnedId ?: selectedId,
                                pinnedId = pinnedId,
                                onSelect = { selectedId = it },
                                // Pinning selects too (one click = full focus); a second click on
                                // the pinned card's pin unpins and leaves selection untouched.
                                onPinToggle = { id ->
                                    if (pinnedId == id) {
                                        pinnedId = null
                                    } else {
                                        pinnedId = id
                                        selectedId = id
                                    }
                                },
                                // Background click clears the selection, never the pin (§5).
                                onClearSelection = { selectedId = null },
                                hScroll = hScroll,
                                vScroll = vScroll,
                                cardRects = cardRects,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    DetailPane(
                        task = export?.tasks?.firstOrNull { it.id == selectedId },
                        pinnedId = pinnedId,
                        error = error,
                        busy = busy,
                        width = clampedPanelWidth.dp,
                        // Dragging the left-edge handle right (positive delta) shrinks the panel; the
                        // new raw width is re-clamped against the current window so a drag can't push
                        // it past the min or the 60% cap.
                        onWidthDrag = { deltaDp -> panelWidth = clampPanelWidth(panelWidth - deltaDp.value, windowWidth) },
                        onMutate = ::mutate,
                        onNavigate = ::navigateTo,
                    )
                }
            }
            Legend()
        }

        // Settings dialogs overlay the whole app (scrim + centred card, DESIGN §9).
        val current = export
        if (current != null && dialog == SettingsDialog.ARCHIVE) {
            ArchiveDialog(
                doneCount = current.tasks.count { it.status == "done" },
                droppedCount = current.tasks.count { it.status == "dropped" },
                busy = busy,
                onConfirm = { done, dropped ->
                    dialog = null
                    mutate(
                        buildList {
                            add("cleanup")
                            // Both types = the verb's historical default; one type narrows.
                            if (!(done && dropped)) {
                                add("--only")
                                add(if (done) "done" else "dropped")
                            }
                        },
                    )
                },
                onDismiss = { dialog = null },
            )
        }
        if (current != null && dialog == SettingsDialog.PRUNE) {
            PruneDialog(
                doneCount = current.tasks.count { it.status == "done" },
                droppedCount = current.tasks.count { it.status == "dropped" },
                busy = busy,
                onConfirm = { done, dropped ->
                    dialog = null
                    val statuses = buildSet {
                        if (done) add("done")
                        if (dropped) add("dropped")
                    }
                    mutateAll(current.tasks.filter { it.status in statuses }.map { listOf("delete", it.id) })
                },
                onDismiss = { dialog = null },
            )
        }
    }
}

/**
 * Header (DESIGN §9): app title + faint suffix, a muted generated-note with the
 * refresh button beside it, count chips pushed right with the settings cogwheel
 * appended at the row's end. Everything right of the fixed title block scales
 * through the [HeaderLadder] degradation ladder (t-2on2).
 */
@Composable
private fun Header(
    export: ExportDto?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tk.panel)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Tk.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Title block — the icon + wordmark. Fixed, OUTSIDE the ladder (t-2on2): it never
        // shrinks or drops out however narrow the window gets.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource("icons/taskkling.svg"), contentDescription = null, modifier = Modifier.size(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("taskkling", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Tk.txt)
                Text(" · graph", fontSize = 14.sp, color = Tk.faint)
            }
        }
        // The rest of the header — generated-note + refresh, the flexible gap, and the
        // chips + settings cog — is one degradation ladder (t-2on2). weight(1f) hands its
        // SubcomposeLayout a real bounded width (the leftover after the title), which is
        // what lets it pick the widest stage that fits. With no export there's nothing to
        // scale — a plain spacer holds the row open, unchanged from before.
        if (export != null) {
            HeaderLadder(
                export,
                busy = busy,
                onRefresh = onRefresh,
                onArchive = onArchive,
                onPrune = onPrune,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One stage of the header degradation ladder (t-2on2): a (timestamp, chips) pairing.
 * [timestamp] is the generated-note's font size, or null to hide the note text entirely
 * (the refresh icon always stays); [compactChips] switches the count chips to their
 * t-era5 dot+count form. The refresh and settings icons never appear here as anything
 * scalable — they render at a fixed size in every stage.
 */
private data class HeaderStage(val timestamp: TextUnit?, val compactChips: Boolean)

/**
 * The scaling policy, as an ordered list, most-preferred FIRST (t-2on2). Reading it
 * top-to-bottom is the exact order header elements give way as the window narrows: the
 * timestamp is the least important element, so it degrades (full → compact chips beside
 * it → shrunk → hidden) before the icons are ever touched. To reorder the ladder, change
 * a stage's font, or add a stage, edit THIS list — [HeaderLadder]'s measure loop needs no
 * other change.
 */
private val HEADER_STAGES: List<HeaderStage> = listOf(
    HeaderStage(timestamp = 11.sp, compactChips = false), // 1. full note + full labelled chips
    HeaderStage(timestamp = 11.sp, compactChips = true),  // 2. full note + compact dot+count chips (t-era5)
    HeaderStage(timestamp = 9.sp, compactChips = true),   // 3. shrunk note + compact chips
    HeaderStage(timestamp = null, compactChips = true),   // 4. note hidden (refresh icon stays) + compact chips
)

/**
 * Header degradation ladder (t-2on2): renders the FIRST [HEADER_STAGES] entry whose natural
 * width fits the leftover header width, so the timestamp scales/vanishes before the icons.
 * Folds in the old HeaderCounts probe — stages 1 vs 2+ differ only by the chips' `compact`
 * flag, so one SubcomposeLayout drives both the timestamp ladder and the chip degrade rather
 * than nesting two. Measured, not breakpointed, so it tracks the space the header actually
 * has (detail-panel width, multi-monitor DPI, etc).
 */
@Composable
private fun HeaderLadder(
    export: ExportDto,
    busy: Boolean,
    onRefresh: () -> Unit,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        // Walk the stages most-preferred first; a stage's NATURAL width is measured with
        // unbounded Constraints(), under which the weighted gap collapses to its 18dp
        // minimum — so this is the tightest width the stage needs. The first stage that
        // fits the leftover header width wins; probing STOPS there, so the common wide
        // window measures a single stage. The last stage is the unconditional fallback
        // (never probed — it wins by default when nothing narrower fits).
        var winner = HEADER_STAGES.lastIndex
        for (i in 0 until HEADER_STAGES.lastIndex) {
            val natural = subcompose("probe-$i") {
                HeaderStageRow(export, HEADER_STAGES[i], busy, onRefresh, onArchive, onPrune)
            }.maxOf { it.measure(Constraints()).width }
            if (natural <= constraints.maxWidth) {
                winner = i
                break
            }
        }

        // Render the winner under a distinct slot key — a probe measurable can't be
        // remeasured — this time with the real bounded constraints, so its weighted gap
        // expands and the chip block + cog sit flush at the header's right edge.
        val placeables = subcompose("stage-$winner") {
            HeaderStageRow(export, HEADER_STAGES[winner], busy, onRefresh, onArchive, onPrune)
        }.map { it.measure(constraints) }
        val width = placeables.maxOf { it.width }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * The content of one ladder stage (t-2on2): generated-note + refresh on the left, count
 * chips + settings cog on the right, a weighted gap between them. Under the probe's
 * unbounded measure the weighted spacer is 0, so the row measures its true content width;
 * under the winner's bounded measure the spacer absorbs all slack, keeping the chips
 * right-aligned in whichever stage wins — the fixed 18dp spacer guarantees a minimum gap
 * so the note never touches the chips even at the tightest fit.
 */
@Composable
private fun HeaderStageRow(
    export: ExportDto,
    stage: HeaderStage,
    busy: Boolean,
    onRefresh: () -> Unit,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Generated-note: the timestamp text is the ladder's first casualty — it shrinks
        // (stage 3) then drops out entirely (stage 4). The refresh button never moves size
        // and always renders, so even the hidden-timestamp stage keeps a way to re-export.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stage.timestamp != null) {
                Text("generated ${fmtDateTime(export.generatedAt)}", fontSize = stage.timestamp, color = Tk.muted)
            }
            // Re-runs export through the busy gate (DESIGN §9 refresh button).
            QuietIconButton(UiIcons.Refresh, "refresh", enabled = !busy, onClick = onRefresh)
        }
        Spacer(Modifier.width(18.dp))
        Spacer(Modifier.weight(1f))
        CountChipRow(export, compact = stage.compactChips, busy = busy, onArchive = onArchive, onPrune = onPrune)
    }
}

@Composable
private fun CountChipRow(
    export: ExportDto,
    compact: Boolean,
    busy: Boolean,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CountChip(Tk.ready, export.counts.ready, "ready", compact)
        CountChip(Tk.blocked, export.counts.blocked, "blocked", compact)
        CountChip(Tk.waiting, export.counts.waiting, "waiting", compact)
        CountChip(Tk.done, export.counts.done, "done", compact)
        SettingsMenu(enabled = !busy, onArchive = onArchive, onPrune = onPrune)
    }
}

private val LEGEND_ITEMS: List<Pair<Color, String>> = listOf(
    Tk.ready to "ready",
    Tk.blocked to "blocked",
    Tk.waiting to "waiting",
    Tk.deferred to "deferred",
    Tk.done to "done",
    Tk.dropped to "dropped",
    Tk.open to "open",
)

/** Legend (DESIGN §9): a swatch + label per state. */
@Composable
private fun Legend() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tk.panel)
            .drawBehind {
                val y = 0.5.dp.toPx()
                drawLine(Tk.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for ((color, label) in LEGEND_ITEMS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Text(label, fontSize = 11.sp, color = Tk.muted)
            }
        }
    }
}
