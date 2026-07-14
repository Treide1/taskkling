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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
private fun App(client: TaskklingClient) {
    val scope = rememberCoroutineScope()
    // All app state and every CLI call live in the store; this composable owns only
    // geometry (below) and renders/forwards intents. Keyed on the client so a swapped
    // client can never keep a stale store.
    val store = remember(client) { AppStore(client, scope) }
    LaunchedEffect(store) {
        store.start()
        store.checkVersionSkew()
    }
    // The user-dragged detail-panel width in dp (t-q8i2). Session-only — like the pin, it never
    // persists and resets to the default on relaunch. Held as a raw dp Float and re-clamped
    // against the live window width at layout time (see the BoxWithConstraints below), so the
    // panel never exceeds the 60% cap after a window resize.
    var panelWidth by remember { mutableStateOf(PANEL_DEFAULT_W) }

    // The canvas scroll state lives here, above GraphPane, so wheel scrolling, drag
    // panning, and programmatic pan-to-card all share one clamped position. The measured
    // card rects are hoisted alongside: GraphPane's measure pass fills the map, pan-to-card
    // below reads a target's centre from it. Snapshot-backed so that pan-to-card can AWAIT
    // a rect that does not exist yet (see navigateToNew) rather than poll for it.
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val cardRects = remember { mutableStateMapOf<String, CardRect>() }

    // Ref-id navigation (DESIGN §9): select the target AND centre its card in the
    // viewport, clamped to the scroll bounds, 150ms on both axes together. Plain card
    // clicks on the canvas select without panning (§1.4). The store owns the selection
    // and its t-nt8t guard; a refused target (archived, dangling dep) never pans either.
    fun navigateTo(id: String) {
        if (!store.select(id)) return
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

    /**
     * Select and centre a card that does not exist on screen YET (t-ctbc). [navigateTo]
     * reads a MEASURED rect, but a card created a moment ago has none until the graph's
     * next layout pass, so calling it inline after a create silently skipped the pan and
     * only selected — the reported "card is selected but not panned to". Select right
     * away (the panel shouldn't wait), then pan once the card has been measured.
     *
     * Waits for the rect itself rather than for a number of frames: [cardRects] is
     * snapshot state, so this suspends until the layout pass that measures [id] publishes
     * it, and resumes on exactly that event. No frame budget to tune, and nothing to
     * re-tune on a slower machine or a bigger graph.
     *
     * The wait terminates: [navigateTo] has already established that [id] is in the
     * current export, `GraphPane` lays out every task in the export unfiltered, and
     * nothing can mutate the export between the create's refresh and the next layout
     * pass — so the rect is guaranteed to arrive.
     *
     * Resuming off the rect also fixes the scroll range for free. The same layout pass
     * grows the canvas, and the scroll node writes its new `maxValue` AFTER our measure
     * block fills the map, both inside that one pass; [centerScrollOffset] clamps against
     * `maxValue`, so a target computed before it settled would land short of the new card.
     * Snapshot writes become visible together, once the pass has applied them.
     */
    suspend fun navigateToNew(id: String) {
        navigateTo(id)
        if (cardRects.containsKey(id)) return
        snapshotFlow { cardRects[id] }.filterNotNull().first()
        navigateTo(id)
    }

    // A create selects the minted card in the store right away and publishes its id here;
    // centring it is this composable's half, because only it holds the measured rects
    // (t-ctbc). Consuming the id resets the slot, so the effect idles until the next create.
    LaunchedEffect(store.newlyCreatedId) {
        store.newlyCreatedId?.let {
            navigateToNew(it)
            store.pannedToNew()
        }
    }

    // t-aq99: the root claims focus so canvas-level keys work before any click;
    // events from focused descendants (cards, text fields) bubble through here, and a
    // text field consumes its own editing keys before this sees them.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            // t-tm10 focus contract: Ctrl+F is captured here in the PREVIEW (capture)
            // phase — top-down, before any focused descendant — and consumed, so it
            // opens search and steals focus from whatever holds it, including a
            // detail-panel field mid-edit (whose draft the focus loss commits; see
            // EditableField/BodySection). Suppressed while a modal dialog is up: the
            // popup would fight the dialog's scrim and focus. While the search popup
            // itself is open it owns focus, so this never fires — the popup handles
            // its own Ctrl+F (re-select the query).
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F &&
                    store.canOpenSearch
                ) {
                    store.openSearch()
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    event.key == Key.Z && event.isCtrlPressed -> {
                        store.undoLast()
                        true
                    }
                    (event.key == Key.Delete || event.key == Key.Backspace) && store.selectedEdge != null -> {
                        store.unlinkSelectedEdge()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(
                store.export,
                busy = store.busy,
                onRefresh = store::reload,
                onAddCard = store::openCreate,
                onArchive = { store.openDialog(SettingsDialog.ARCHIVE) },
                onPrune = { store.openDialog(SettingsDialog.PRUNE) },
                search = SearchBridge(
                    open = store.searchOpen,
                    onOpen = store::openSearch,
                    // Esc / Enter / click-away all land here: close and hand focus
                    // back to the root so canvas-level keys work again (t-tm10).
                    onClose = {
                        store.closeSearch()
                        rootFocus.requestFocus()
                    },
                    onNavigate = ::navigateTo,
                ),
            )
            // BoxWithConstraints exposes the live window width so the panel's dragged width can be
            // re-clamped against the 60% cap every layout pass — a Modifier-level clamp at measure
            // time, so a window shrink pulls an over-wide panel back within the cap (t-q8i2).
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val windowWidth = maxWidth.value
                val clampedPanelWidth = clampPanelWidth(panelWidth, windowWidth)
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val current = store.export
                        when {
                            store.error != null && current == null ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    SelectionContainer {
                                        Text("error: ${store.error}", color = Tk.blocked, fontSize = 13.sp)
                                    }
                                }
                            current == null ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("loading…", color = Tk.muted, fontSize = 13.sp)
                                }
                            else -> GraphPane(
                                export = current,
                                selectedId = store.selectedId,
                                highlightedId = store.highlightedId,
                                pinnedId = store.pinnedId,
                                linkModeId = store.linkModeId,
                                selectedEdge = store.selectedEdge,
                                onSelectEdge = store::selectEdge,
                                onLink = { dependent, blocker -> store.performEdgeOp(dependent, blocker, link = true) },
                                onUnlink = { dependent, blocker -> store.performEdgeOp(dependent, blocker, link = false) },
                                onLinkModeToggle = store::toggleLinkMode,
                                onSelect = store::selectCard,
                                onPinToggle = store::togglePin,
                                onClearSelection = store::clearSelection,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                cardRects = cardRects,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    DetailPane(
                        task = store.selectedTask,
                        knownIds = store.knownIds,
                        pinnedId = store.pinnedId,
                        error = store.error,
                        busy = store.busy,
                        width = clampedPanelWidth.dp,
                        // Dragging the left-edge handle right (positive delta) shrinks the panel; the
                        // new raw width is re-clamped against the current window so a drag can't push
                        // it past the min or the 60% cap.
                        onWidthDrag = { deltaDp -> panelWidth = clampPanelWidth(panelWidth - deltaDp.value, windowWidth) },
                        onMutate = store::mutate,
                        onNavigate = ::navigateTo,
                        onLoadBody = store::loadBody,
                        onSaveBody = store::saveBody,
                    )
                }
            }
            Legend()
        }

        // t-aq99: toasts overlay everything, bottom-centre, above the legend.
        ToastHost(store.toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))

        // Settings dialogs overlay the whole app (scrim + centred card, DESIGN §9).
        val current = store.export
        if (current != null && store.dialog == SettingsDialog.ARCHIVE) {
            ArchiveDialog(
                doneCount = current.tasks.count { it.status == "done" },
                droppedCount = current.tasks.count { it.status == "dropped" },
                busy = store.busy,
                onConfirm = { done, dropped ->
                    store.closeDialog()
                    store.mutate(
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
                onDismiss = store::closeDialog,
            )
        }
        if (current != null && store.dialog == SettingsDialog.PRUNE) {
            PruneDialog(
                doneCount = current.tasks.count { it.status == "done" },
                droppedCount = current.tasks.count { it.status == "dropped" },
                busy = store.busy,
                onConfirm = { done, dropped ->
                    store.closeDialog()
                    val statuses = buildSet {
                        if (done) add("done")
                        if (dropped) add("dropped")
                    }
                    store.mutateAll(current.tasks.filter { it.status in statuses }.map { listOf("delete", it.id) })
                },
                onDismiss = store::closeDialog,
            )
        }
        // The add-card dialog (t-rjna), same overlay family. defaultThread prefills
        // the thread field from config via the export (pure-CLI UI, ADR-010 spirit).
        if (store.showCreate) {
            CreateCardDialog(
                defaultThread = current?.defaultThread ?: "",
                submitting = store.creating,
                error = store.createError,
                onCreate = store::createTask,
                onDismiss = store::dismissCreate,
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
    onAddCard: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
    search: SearchBridge,
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
                onAddCard = onAddCard,
                onArchive = onArchive,
                onPrune = onPrune,
                search = search,
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
    onAddCard: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
    search: SearchBridge,
    modifier: Modifier = Modifier,
) {
    // Probe compositions get the bridge with `open` forced off: a Popup composes its
    // content into an overlay layer the moment it enters composition, placed or not,
    // so an open search in a probe slot would spawn a SECOND popup beside the
    // winner's (t-tm10). The magnifier icon itself still renders in probes — it's a
    // fixed, non-degrading element, so every stage measures its true width.
    val probeSearch = search.copy(open = false)
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
                HeaderStageRow(export, HEADER_STAGES[i], busy, onRefresh, onAddCard, onArchive, onPrune, probeSearch)
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
            HeaderStageRow(export, HEADER_STAGES[winner], busy, onRefresh, onAddCard, onArchive, onPrune, search)
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
    onAddCard: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
    search: SearchBridge,
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
        CountChipRow(export, compact = stage.compactChips, busy = busy, onAddCard = onAddCard, onArchive = onArchive, onPrune = onPrune, search = search)
    }
}

@Composable
private fun CountChipRow(
    export: ExportDto,
    compact: Boolean,
    busy: Boolean,
    onAddCard: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onPrune: (() -> Unit)?,
    search: SearchBridge,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CountChip(Tk.ready, export.counts.ready, "ready", compact)
        CountChip(Tk.blocked, export.counts.blocked, "blocked", compact)
        CountChip(Tk.waiting, export.counts.waiting, "waiting", compact)
        CountChip(Tk.done, export.counts.done, "done", compact)
        // The add-card + (t-rjna). Opening the dialog is read-only, so the busy gate
        // here is a courtesy — the create submit itself respects busy.
        QuietIconButton(
            icon = UiIcons.Plus,
            contentDescription = "add task",
            enabled = !busy && onAddCard != null,
            onClick = { onAddCard?.invoke() },
            iconSize = 14.dp,
        )
        // The find-task magnifier (t-tm10), immediately left of the settings gear.
        // Fixed and non-degrading, like the refresh icon and the cog: constant size
        // in every HeaderLadder stage — only the timestamp / chip labels degrade.
        SearchControl(tasks = export.tasks, bridge = search)
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

/** Legend (DESIGN §9): a swatch + label per state. Legend rows read state, never narrate
 *  interactions — an interaction earns a visible affordance instead (§1 principle 9). */
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
