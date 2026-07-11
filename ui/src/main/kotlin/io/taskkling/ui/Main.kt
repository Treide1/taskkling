package io.taskkling.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.taskkling.contract.ExportDto
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
        Window(
            onCloseRequest = ::exitApplication,
            title = "taskkling",
            icon = painterResource("icons/taskkling.png"),
        ) {
            TaskklingTheme {
                Box(Modifier.fillMaxSize().background(Tk.bg)) {
                    if (binary == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "taskkling binary not found.\nSet it on PATH, TASKKLING_BINARY, or config binary_path.",
                                color = Tk.muted,
                                fontSize = 13.sp,
                            )
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
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val current = export
                    when {
                        error != null && current == null ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("error: $error", color = Tk.blocked, fontSize = 13.sp)
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
                    onMutate = ::mutate,
                    onNavigate = ::navigateTo,
                )
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
 * appended at the row's end.
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource("icons/taskkling.svg"), contentDescription = null, modifier = Modifier.size(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("taskkling", fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, color = Tk.txt)
                Text(" · graph", fontSize = 14.sp, color = Tk.faint)
            }
        }
        if (export != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("generated ${fmtDateTime(export.generatedAt)}", fontSize = 11.sp, color = Tk.muted)
                // Re-runs export through the busy gate (DESIGN §9 refresh button).
                QuietIconButton(UiIcons.Refresh, "refresh", enabled = !busy, onClick = onRefresh)
            }
        }
        Spacer(Modifier.weight(1f))
        if (export != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CountChip(Tk.ready, export.counts.ready, "ready")
                CountChip(Tk.blocked, export.counts.blocked, "blocked")
                CountChip(Tk.waiting, export.counts.waiting, "waiting")
                CountChip(Tk.done, export.counts.done, "done")
                SettingsMenu(enabled = !busy, onArchive = onArchive, onPrune = onPrune)
            }
        }
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
