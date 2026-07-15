package io.taskkling.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's whole session state and every CLI orchestration, hoisted out of the `App`
 * composable so both are reachable without composing a window.
 *
 * The UI is a pure CLI client (PRD §13): this holds no parallel model. Every mutation
 * shells out through [TaskklingClient] off the UI thread and the graph refreshes from
 * the export the CLI returned (a TOCTOU-free read-after-write, PRD §7.3). All state
 * here is session-only — nothing persists, the CLI stays the single write path.
 *
 * State is Compose snapshot state ([mutableStateOf]) so the composable recomposes off
 * it, but nothing here composes or measures: geometry (scroll, card rects, panel width,
 * pan animation) stays in `App`, which is why [select] only selects and leaves the
 * caller to pan.
 *
 * [io] is injectable so tests can drive the busy-flag sequencing deterministically.
 */
internal class AppStore(
    private val client: TaskklingClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var export: ExportDto? by mutableStateOf(null)
        private set
    var selectedId: String? by mutableStateOf(null)
        private set

    /**
     * The pinned task (DESIGN §6): stays highlighted while selection moves freely.
     * Session-only UI state — never persisted; the CLI stays the single write path.
     */
    var pinnedId: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /**
     * True while a CLI call is in flight; the panel's mutation buttons render disabled
     * off it, so a double-click can't queue a second subprocess (t-t36o).
     */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** The open settings dialog (archive/prune), session-only like the pin. */
    var dialog: SettingsDialog? by mutableStateOf(null)
        private set

    // The add-card dialog (t-rjna): visibility, its in-flight flag, and its OWN
    // error slot — create failures surface inside the dialog, never on the panel
    // error line, so the dialog can stay open and re-enable for a retry.
    var showCreate: Boolean by mutableStateOf(false)
        private set
    var creating: Boolean by mutableStateOf(false)
        private set
    var createError: String? by mutableStateOf(null)
        private set

    /**
     * The find-task popup (t-tm10): only open/closed is held — the root Ctrl+F capture
     * needs to open it — the query itself lives inside the popup and dies with it
     * (transient, never restored). Session-only like the pin.
     */
    var searchOpen: Boolean by mutableStateOf(false)
        private set

    // t-aq99: the card currently in link mode (its → handles shown), the toast queue,
    // the edge selected for unlinking, and the one-shot undo memory — all session-only
    // UI state, like the pin; the CLI stays the single write path.
    var linkModeId: String? by mutableStateOf(null)
        private set
    val toasts: ToastState = ToastState()
    var selectedEdge: Edge? by mutableStateOf(null)
        private set
    private var lastOp: LinkOp? by mutableStateOf(null)

    /**
     * The id [createTask] just minted, published for the caller to centre once the graph has
     * measured it (t-ctbc). The store selects it immediately — the panel shouldn't wait — but
     * panning needs a MEASURED rect, which is geometry the store deliberately doesn't own.
     * One-shot: the caller calls [pannedToNew] when done.
     */
    var newlyCreatedId: String? by mutableStateOf(null)
        private set

    /** The task the detail panel renders, or null on the empty state. */
    val selectedTask: TaskDto? get() = export?.tasks?.firstOrNull { it.id == selectedId }

    /** Highlight source: the pin wins; without one, selection highlights. */
    val highlightedId: String? get() = pinnedId ?: selectedId

    /**
     * What to call this workspace, in the header and the OS window title (t-tlk0). The CLI
     * resolves it — config `workspace_name`, else the workspace directory's name — because
     * discovery walks *up* from the cwd, so this process can't derive the fallback itself.
     * Empty until the first export lands, and for an export predating the field; both
     * render as the bare wordmark rather than a dangling separator.
     */
    val workspaceName: String get() = export?.workspaceName.orEmpty()

    /**
     * Which ids the panel may render as navigable links — absence from the export is
     * how it spots an archived or dangling dep (t-nt8t).
     */
    val knownIds: Set<String> get() = export?.tasks?.mapTo(HashSet()) { it.id } ?: emptySet()

    /**
     * Ctrl+F is suppressed with no export (nothing to find) or under a modal dialog: the
     * popup would fight the dialog's scrim and focus (t-tm10).
     */
    val canOpenSearch: Boolean get() = export != null && dialog == null && !showCreate

    private fun refresh(next: ExportDto) {
        export = next
        error = null
        if (selectedId != null && next.tasks.none { it.id == selectedId }) selectedId = null
        if (pinnedId != null && next.tasks.none { it.id == pinnedId }) pinnedId = null
        if (linkModeId != null && next.tasks.none { it.id == linkModeId }) linkModeId = null
        // A selected edge that no longer exists (unlinked, task gone) drops its selection.
        selectedEdge?.let { e ->
            val dependent = next.tasks.firstOrNull { it.id == e.to }
            if (dependent == null || e.from !in dependent.depends) selectedEdge = null
        }
    }

    /** The first export, on app start. Outside the busy gate — nothing can be in flight yet. */
    fun start() {
        scope.launch {
            withContext(io) { runCatching { client.export() } }
                .onSuccess { refresh(it) }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    /**
     * Version-skew hint (t-eeze). `taskkling ui` fetches a UI pinned to the CLI's own
     * version (ADR-010), so an installed setup matches by construction and this stays
     * silent. It fires for the development configuration that produced the task: a UI
     * built from a branch with new CLI flags, run against an older resolved binary —
     * where the only symptom used to be an unexplained failure on the new flag. Any
     * disagreement warrants the hint, in either direction: comparing for equality keeps
     * semver logic out of the UI, which links only :contract and cannot reach :core's
     * isNewerVersion. A null probe means "couldn't tell" and says nothing.
     */
    fun checkVersionSkew() {
        scope.launch {
            val cliVersion = withContext(io) { client.version() }
            if (cliVersion != null && cliVersion != BUILD_VERSION) {
                toasts.show(
                    "version skew: UI $BUILD_VERSION, binary $cliVersion — " +
                        "commands the binary doesn't know will fail. Rebuild the UI or repin the binary.",
                    ToastKind.ERROR,
                )
            }
        }
    }

    /**
     * Select [id], reporting whether it took. An id with no task in the current export
     * (archived, or a dangling depends edge) is not a navigable target: selecting it would
     * land the panel on its empty state and lose the user's place (t-nt8t). The guard sits
     * here rather than at the call sites so the invariant is structural — no caller can
     * clear the selection by navigating to a task that isn't there. Callers that pan
     * (DESIGN §9 ref-id navigation) do so only on a `true` return.
     */
    fun select(id: String): Boolean {
        if (export?.tasks?.any { it.id == id } != true) return false
        selectedId = id
        return true
    }

    /** A plain card click: selects without panning (§1.4) and drops any edge selection. */
    fun selectCard(id: String) {
        select(id)
        selectedEdge = null
    }

    /** Background click clears the selection, never the pin (§5). */
    fun clearSelection() {
        selectedId = null
        selectedEdge = null
    }

    /**
     * Pinning selects too (one click = full focus); a second click on the pinned card's
     * pin unpins and leaves selection untouched.
     */
    fun togglePin(id: String) {
        if (pinnedId == id) {
            pinnedId = null
        } else {
            pinnedId = id
            selectedId = id
        }
    }

    /**
     * Link mode is single, session-only like the pin: toggling one card off, or
     * transferring it to another. Handles show on whichever card holds it.
     */
    fun toggleLinkMode(id: String) {
        linkModeId = if (linkModeId == id) null else id
    }

    fun selectEdge(edge: Edge?) {
        selectedEdge = edge
    }

    fun openDialog(which: SettingsDialog) {
        dialog = which
    }

    fun closeDialog() {
        dialog = null
    }

    fun openCreate() {
        showCreate = true
    }

    /** Dismissing the add-card dialog is refused mid-submit, and clears its error slot. */
    fun dismissCreate() {
        if (creating) return
        showCreate = false
        createError = null
    }

    fun openSearch() {
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
    }

    /**
     * Every mutation shells out OFF the UI thread — [CliClient]'s calls block on the
     * subprocess — then refreshes from the export the CLI returned (t-t36o). The
     * Result hops back to the UI thread before touching snapshot state.
     */
    fun mutate(args: List<String>) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(io) { runCatching { client.mutate(args) } }
                .onSuccess { refresh(it) }
                .onFailure { error = "${args.firstOrNull() ?: "cli"}: ${it.message ?: it}" }
            busy = false
        }
    }

    /**
     * Create a task from the add-card dialog (t-rjna): the same busy gate and
     * read-after-write refresh as [mutate], but success also closes the dialog and
     * selects the minted task — found by diffing task ids against the pre-call
     * export (`add` prints the id on stdout, but that channel already carries the
     * piggybacked export). The pan is the caller's half of the job: the minted card has
     * no measured rect yet, so the id goes out via [newlyCreatedId] (t-ctbc).
     */
    fun createTask(args: List<String>) {
        if (busy) return
        val before = export?.tasks?.map { it.id }?.toSet() ?: emptySet()
        busy = true
        creating = true
        createError = null
        scope.launch {
            withContext(io) { runCatching { client.mutate(args) } }
                .onSuccess { next ->
                    refresh(next)
                    showCreate = false
                    creating = false
                    next.tasks.firstOrNull { it.id !in before }?.id?.let { minted ->
                        select(minted)
                        newlyCreatedId = minted
                    }
                }
                .onFailure {
                    createError = "add: ${it.message ?: it}"
                    creating = false
                }
            busy = false
        }
    }

    /**
     * Prune runs one `delete` per selected task — the CLI has no batch delete
     * (t-m0zn decision) — serialized off the UI thread behind the same busy
     * flag. The graph refreshes from the last successful export; the first
     * failure stops the loop and surfaces on the panel error line.
     */
    fun mutateAll(argsList: List<List<String>>) {
        if (busy || argsList.isEmpty()) return
        busy = true
        scope.launch {
            var last: ExportDto? = null
            var failure: String? = null
            withContext(io) {
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

    /**
     * The selected card's body (its markdown notes) is fetched on demand — the graph
     * export omits it, so the panel pulls it per selection off the UI thread. Returns
     * empty on any failure so the well still renders (the error surfaces on save, the
     * path the user cares about).
     */
    suspend fun loadBody(id: String): String =
        withContext(io) { runCatching { client.body(id) }.getOrDefault("") }

    /**
     * Save the body straight through `write` (stdin), OUTSIDE the busy gate: a
     * focus-loss auto-save must never be dropped because a refresh happened to be in
     * flight, and the CLI's own file lock serializes it safely. No refresh — a body
     * edit changes nothing the graph draws, and the panel already holds the text.
     */
    fun saveBody(id: String, text: String) {
        scope.launch {
            withContext(io) { runCatching { client.writeBody(id, text) } }
                .onSuccess { error = null }
                .onFailure { error = "write: ${it.message ?: it}" }
        }
    }

    /**
     * The header refresh button: a plain re-export through the same busy gate as
     * mutations, so a refresh can't race a mutation's read-after-write.
     */
    fun reload() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(io) { runCatching { client.export() } }
                .onSuccess { refresh(it) }
                .onFailure { error = "export: ${it.message ?: it}" }
            busy = false
        }
    }

    /**
     * t-aq99: one link/unlink edge mutation. Result lands as a toast either
     * way — success carries the Ctrl+Z hint and arms the one-shot undo; failure
     * carries the CLI's own reason (cycle, unknown id, …). Both no-op directions are
     * pre-checked here against the LIVE export because the CLI treats them as silent
     * exit-0 no-ops (link `distinct()`s, unlink filters a missing edge away): without
     * these, a re-link would toast "already linked" only by luck and a re-unlink would
     * falsely toast success. The edge is present iff `dependent` lists `blocker`.
     */
    fun performEdgeOp(dependent: String, blocker: String, link: Boolean, isUndo: Boolean = false) {
        if (busy) {
            toasts.show("busy — try again in a moment", ToastKind.INFO)
            return
        }
        val current = export ?: return
        val edgeExists = current.tasks.firstOrNull { it.id == dependent }?.depends?.contains(blocker) == true
        if (link && edgeExists) {
            toasts.show("already linked: $blocker → $dependent", ToastKind.INFO)
            return
        }
        if (!link && !edgeExists) {
            toasts.show("not linked: $blocker → $dependent", ToastKind.INFO)
            return
        }
        val verb = if (link) "link" else "unlink"
        busy = true
        scope.launch {
            withContext(io) {
                runCatching { client.mutate(listOf(verb, dependent, "--depends", blocker)) }
            }
                .onSuccess {
                    refresh(it)
                    if (isUndo) {
                        lastOp = null
                        toasts.show("undid ${if (link) "unlink" else "link"}: $blocker → $dependent", ToastKind.SUCCESS)
                    } else {
                        lastOp = LinkOp(dependent, blocker, wasLink = link)
                        toasts.show("${if (link) "linked" else "unlinked"} $blocker → $dependent (Ctrl+Z to undo)", ToastKind.SUCCESS)
                    }
                }
                .onFailure { toasts.show("$verb failed: ${it.message ?: it}", ToastKind.ERROR) }
            busy = false
        }
    }

    /** Acknowledges [newlyCreatedId], once the caller has centred that card. */
    fun pannedToNew() {
        newlyCreatedId = null
    }

    /** One-shot inverse-command undo (the t-aq99 leaning) — no general undo stack. */
    fun undoLast() {
        val op = lastOp
        if (op == null) {
            toasts.show("nothing to undo", ToastKind.INFO)
            return
        }
        performEdgeOp(op.dependent, op.blocker, link = !op.wasLink, isUndo = true)
    }

    /** Unlink the currently selected edge, if any (Del/Backspace on the canvas). */
    fun unlinkSelectedEdge() {
        selectedEdge?.let { performEdgeOp(it.to, it.from, link = false) }
    }
}
