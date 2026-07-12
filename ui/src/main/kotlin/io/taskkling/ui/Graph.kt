package io.taskkling.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlin.math.hypot

/**
 * The canvas: a scrolling, dotted-grid surface of task cards over a layer of
 * S-curve dependency edges (DESIGN §5–§7). Clicking empty canvas clears the
 * selection — never the pin (§1.4, §5); highlighting dims everything outside
 * the highlighted node's Star. An LMB drag that starts on the background pans
 * the canvas 1:1 (§5).
 *
 * [selectedId] drives only the selection ring; [highlightedId] (the pinned
 * node if any, else the selected one — resolved by the caller) drives the
 * Star dim and the hot edges, so a selected card outside Star(pinned) keeps
 * its ring at the dimmed alpha (§6).
 *
 * [hScroll]/[vScroll] are hoisted to the caller so wheel scrolling, drag
 * panning, and programmatic pan-to-card all mutate the same clamped state.
 * [cardRects] is hoisted for the same reason: the measure pass below fills it,
 * and the caller's pan-to-card (§9) reads a target card's centre from it.
 *
 * t-aq99: dependency edges are authored here too — a drag from a card's
 * chain-link handle spawns a live S-curve that drops onto a target card ([onLink],
 * or [onUnlink] when that edge already exists — red feedback) or evaporates on
 * empty canvas; a tap near an existing edge selects it ([selectedEdge]/
 * [onSelectEdge]) and its midpoint (×) chip or Del unlinks it.
 */
@Composable
internal fun GraphPane(
    export: ExportDto,
    selectedId: String?,
    highlightedId: String?,
    pinnedId: String?,
    handlesNeedSelection: Boolean,
    selectedEdge: Edge?,
    onSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectEdge: (Edge) -> Unit,
    onLink: (dependent: String, blocker: String) -> Unit,
    onUnlink: (dependent: String, blocker: String) -> Unit,
    hScroll: ScrollState,
    vScroll: ScrollState,
    cardRects: HashMap<String, CardRect>,
    modifier: Modifier = Modifier,
) {
    val gl = remember(export) { layout(export.tasks) }

    val cols = gl.layerCount.coerceAtLeast(1)

    // Cards ordered by (layer, indexInLayer): column then the intra-column stacking order
    // that [layout] fixed. Emitting content in this order lets the measure pass keep a
    // simple per-column running sum (§10). Keyed on `export`, NOT `gl`: a status-only
    // mutation yields a value-equal GraphLayout, and keying on it alone would keep the
    // stale TaskDtos cached here while the detail panel shows the fresh ones.
    val placed = remember(export) {
        export.tasks.mapNotNull { t -> gl.positions[t.id]?.let { PlacedNode(t, it) } }
            .sortedWith(compareBy({ it.pos.layer }, { it.pos.indexInLayer }))
    }

    // Star(highlighted) (DOMAIN_LANGUAGE §7): the highlighted node plus every node
    // one edge away — the subgraph kept at full prominence while the rest dims.
    val star = remember(highlightedId, gl) {
        if (highlightedId == null) emptySet() else buildSet {
            add(highlightedId)
            for (e in gl.edges) {
                if (e.from == highlightedId) add(e.to)
                if (e.to == highlightedId) add(e.from)
            }
        }
    }

    // Drives the fade of non-highlighted edges while a highlight is active (§7, §11).
    val dimFraction by animateFloatAsState(if (highlightedId != null) 1f else 0f, tween(150))
    val canvasClick = remember { MutableInteractionSource() }

    // True while a background drag is panning; drives the grab→grabbing cursor swap.
    var panning by remember { mutableStateOf(false) }

    // t-aq99: the in-flight link drag, if any. Anchored in content px; the
    // pointer position is snapshot state so the temp-arrow draw follows each move.
    var drag by remember { mutableStateOf<LinkDrag?>(null) }

    fun startDrag(id: String, side: HandleSide) {
        val rect = cardRects[id] ?: return
        val anchor = Offset(if (side == HandleSide.LEFT) rect.left else rect.right, rect.centerY)
        // Target classification (unlink / invalid / link) computed ONCE at drag start
        // from the in-memory export.
        val targets = classifyLinkTargets(export.tasks, id, side)
        drag = LinkDrag(id, side, anchor, targets)
    }

    fun endDrag() {
        val d = drag ?: return
        drag = null
        val target = cardRects.entries.firstOrNull { d.pointer in it.value }?.key ?: return
        if (target == d.sourceId || target in d.targets.invalid) return
        val (dependent, blocker) =
            if (d.side == HandleSide.LEFT) d.sourceId to target else target to d.sourceId
        // Same gesture, inverse effect: an existing edge in this direction unlinks.
        if (target in d.targets.unlink) onUnlink(dependent, blocker) else onLink(dependent, blocker)
    }

    // The droppable target under the pointer, driving the target's ring (green = would
    // link, red = would unlink) and the temp arrow's snap. Recomposes per drag move —
    // cheap, everything else skips.
    val activeDrag = drag
    val dragHoverTarget = activeDrag?.let { d ->
        cardRects.entries.firstOrNull { d.pointer in it.value }?.key
            ?.takeIf { it != d.sourceId && it !in d.targets.invalid }
    }

    // The canvas covers at least the viewport (§5): these pre-scroll constraints are
    // the viewport size, and the measure pass clamps the content to them, so the grid
    // and the click/pan surface reach the window edges even on small graphs.
    BoxWithConstraints(modifier.background(Tk.bg)) {
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        Box(
            Modifier
                // Grab cursor over the background at rest, grabbing while panning (§5). Cards
                // override with their own hand cursor — except mid-pan, where crossing a card
                // means nothing, so the override flag pins the grabbing cursor everywhere.
                .pointerHoverIcon(if (panning) GRABBING_CURSOR else GRAB_CURSOR, overrideDescendants = panning)
                .pointerInput(hScroll, vScroll, gl, selectedEdge) {
                    // Pan on LMB background drag (§5): pointer delta == scroll delta (1:1, no
                    // inertia), through the same clamped ScrollStates the wheel mutates. The
                    // gesture claims a press only when it lands OUTSIDE every card — hit-tested
                    // against the measured rects the edge layer already uses — so cards keep
                    // their click/hover behaviour untouched.
                    //
                    // t-aq99: two background-tap targets slot in ahead of the pan — the
                    // selected edge's midpoint (×) chip, and edge proximity (tap within ~6dp of
                    // a curve selects it). Both read the DOWN in the Initial pass and consume it
                    // so the Layout's click-to-clear never sees those taps.
                    val arrowPx = ARROW_LEN.toPx()
                    val minDxPx = 40.dp.toPx()
                    val edgeHitPx = 6.dp.toPx()
                    val chipHitPx = (CHIP_R + 4.dp).toPx()
                    val handleOverflowPx = 12.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        // The gesture sees viewport coordinates; the rects live in content px.
                        val inContent = down.position + Offset(hScroll.value.toFloat(), vScroll.value.toFloat())

                        // 1. The unlink chip on the selected edge.
                        val sel = selectedEdge
                        if (sel != null) {
                            val a = cardRects[sel.from]
                            val b = cardRects[sel.to]
                            if (a != null && b != null) {
                                val mid = cubicPoint(edgeCurve(a, b, arrowPx, minDxPx), 0.5f)
                                if ((inContent - mid).getDistance() <= chipHitPx) {
                                    down.consume()
                                    if (awaitTapCompletion(down.id, PAN_SLOP.toPx())) onUnlink(sel.to, sel.from)
                                    return@awaitEachGesture
                                }
                            }
                        }

                        // 2. Cards own their presses — inflated so the overflowing (+) handles
                        // never double as a pan grip.
                        if (cardRects.values.any { it.containsInflated(inContent, handleOverflowPx) }) return@awaitEachGesture

                        // 3. A tap near an edge selects it for unlinking.
                        var hit: Edge? = null
                        var bestD = edgeHitPx
                        for (e in gl.edges) {
                            val a = cardRects[e.from] ?: continue
                            val b = cardRects[e.to] ?: continue
                            val d = distanceToCurve(inContent, edgeCurve(a, b, arrowPx, minDxPx))
                            if (d <= bestD) {
                                bestD = d
                                hit = e
                            }
                        }
                        if (hit != null) {
                            down.consume()
                            if (awaitTapCompletion(down.id, PAN_SLOP.toPx())) onSelectEdge(hit)
                            return@awaitEachGesture
                        }
                        // A few px of slop before committing keeps click-to-clear (§1.4) alive
                        // under mouse jitter; the accrued delta is replayed on commit, so the
                        // total pan stays 1:1 with the pointer.
                        val slop = PAN_SLOP.toPx()
                        var acc = Offset.Zero
                        try {
                            drag(down.id) { change ->
                                val delta = change.positionChange()
                                if (!panning) {
                                    acc += delta
                                    if (acc.getDistance() > slop) {
                                        panning = true
                                        hScroll.dispatchRawDelta(-acc.x)
                                        vScroll.dispatchRawDelta(-acc.y)
                                        change.consume()
                                    }
                                } else {
                                    hScroll.dispatchRawDelta(-delta.x)
                                    vScroll.dispatchRawDelta(-delta.y)
                                    change.consume()
                                }
                            }
                        } finally {
                            panning = false
                        }
                    }
                }
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll),
        ) {
            Layout(
                content = {
                    for (p in placed) {
                        NodeCard(
                            task = p.task,
                            selected = p.task.id == selectedId,
                            pinned = p.task.id == pinnedId,
                            // During a link drag the Star dim yields to validity: only the
                            // targets the drag must not drop on dim.
                            dimmed = if (activeDrag != null) {
                                p.task.id in activeDrag.targets.invalid && p.task.id != activeDrag.sourceId
                            } else {
                                highlightedId != null && p.task.id !in star
                            },
                            dropIndication = when {
                                p.task.id != dragHoverTarget || activeDrag == null -> null
                                p.task.id in activeDrag.targets.unlink -> DropIndication.UNLINK
                                else -> DropIndication.LINK
                            },
                            handlesNeedSelection = handlesNeedSelection,
                            dragActive = activeDrag != null,
                            dragSource = activeDrag?.sourceId == p.task.id,
                            onSelect = { onSelect(p.task.id) },
                            onPinToggle = { onPinToggle(p.task.id) },
                            onHandleDragStart = { side -> startDrag(p.task.id, side) },
                            onHandleDrag = { delta -> drag?.let { it.pointer += delta } },
                            onHandleDragEnd = { endDrag() },
                            onHandleDragCancel = { drag = null },
                        )
                    }
                },
                modifier = Modifier
                    // Grid + edges render UNDER the cards (the Layout's children) and never
                    // capture pointer input (§5, §7). Edge anchors are each card's MEASURED
                    // vertical centre (§10), read from the map the measure pass just filled.
                    .drawBehind {
                        drawDottedGrid()
                        for (e in gl.edges) {
                            val a = cardRects[e.from] ?: continue
                            val b = cardRects[e.to] ?: continue
                            val isSelected = e == selectedEdge
                            val highlighted = highlightedId != null && (e.from == highlightedId || e.to == highlightedId)
                            val color = if (isSelected || highlighted) Tk.accent else Tk.line
                            val strokeW = if (isSelected || highlighted) 2.4.dp.toPx() else 1.6.dp.toPx()
                            val alpha = if (isSelected || highlighted) 1f else 1f - 0.8f * dimFraction
                            drawSCurveEdge(a.right, a.centerY, b.left, b.centerY, color, strokeW, alpha)
                        }
                        // t-aq99: the selected edge's midpoint (×) chip — the explicit
                        // unlink affordance (option a). Clicking it or pressing Del unlinks.
                        val sel = selectedEdge
                        if (sel != null) {
                            val a = cardRects[sel.from]
                            val b = cardRects[sel.to]
                            if (a != null && b != null) {
                                val mid = cubicPoint(edgeCurve(a, b, ARROW_LEN.toPx(), 40.dp.toPx()), 0.5f)
                                drawCircle(Tk.panel2, CHIP_R.toPx(), mid)
                                drawCircle(Tk.line, CHIP_R.toPx(), mid, style = Stroke(1.dp.toPx()))
                                val r = 3.5.dp.toPx()
                                val w = 1.5.dp.toPx()
                                drawLine(Tk.blocked, mid + Offset(-r, -r), mid + Offset(r, r), w, cap = StrokeCap.Round)
                                drawLine(Tk.blocked, mid + Offset(-r, r), mid + Offset(r, -r), w, cap = StrokeCap.Round)
                            }
                        }
                        // t-aq99: the in-flight drag arrow. Drawn in its FINAL
                        // orientation — for a left-handle drag the head points into the
                        // source, since the dragged card is the blocker's dependent. Green
                        // over a would-link target, red over a would-UNLINK one (both
                        // snapped to the target's anchor), muted over an inert invalid
                        // card, accent over empty canvas.
                        val d = drag
                        if (d != null) {
                            val src = cardRects[d.sourceId]
                            if (src != null) {
                                val hoverEntry = cardRects.entries.firstOrNull { d.pointer in it.value && it.key != d.sourceId }
                                val hoverRect = hoverEntry?.value
                                val hoverId = hoverEntry?.key
                                val droppable = hoverId != null && hoverId !in d.targets.invalid
                                val color = when {
                                    hoverId == null -> Tk.accent
                                    !droppable -> Tk.muted
                                    hoverId in d.targets.unlink -> Tk.blocked
                                    else -> Tk.ready
                                }
                                val w = 2.4.dp.toPx()
                                if (d.side == HandleSide.LEFT) {
                                    val tail = if (droppable && hoverRect != null) Offset(hoverRect.right, hoverRect.centerY) else d.pointer
                                    drawSCurveEdge(tail.x, tail.y, src.left, src.centerY, color, w, 0.95f)
                                } else {
                                    val head = if (droppable && hoverRect != null) Offset(hoverRect.left, hoverRect.centerY) else d.pointer
                                    drawSCurveEdge(src.right, src.centerY, head.x, head.y, color, w, 0.95f)
                                }
                            }
                        }
                    }
                    .clickable(interactionSource = canvasClick, indication = null) { onClearSelection() },
            ) { measurables, _ ->
                val cardWpx = CARD_W.dp.roundToPx()
                val colGapPx = COL_GAP.dp.roundToPx()
                val rowGapPx = ROW_GAP.dp.roundToPx()
                val padPx = PAD.dp.roundToPx()

                // Measure every card (~60 nodes) at its content-driven height; `placed[i]`
                // pairs with `measurables[i]` since content was emitted in that same order.
                val measured = measurables.mapIndexed { i, m -> placed[i] to m.measure(Constraints()) }

                cardRects.clear()
                val placements = ArrayList<Pair<Placeable, IntOffset>>(measured.size)
                var maxBottom = padPx
                for ((col, entries) in measured.groupBy { it.first.pos.layer }) {
                    val x = padPx + col * (cardWpx + colGapPx)
                    val tops = stackTops(entries.map { it.second.height }, rowGapPx, padPx)
                    entries.forEachIndexed { j, entry ->
                        val node = entry.first
                        val placeable = entry.second
                        val top = tops[j]
                        cardRects[node.task.id] =
                            CardRect(x.toFloat(), top.toFloat(), placeable.width.toFloat(), placeable.height.toFloat())
                        placements += placeable to IntOffset(x, top)
                        maxBottom = maxOf(maxBottom, top + placeable.height)
                    }
                }

                // Width from the column count, height from the tallest column's measured
                // stack — each clamped to the viewport so the canvas never falls short
                // of the window (§5, §10).
                val contentWidth = maxOf(padPx * 2 + cols * cardWpx + (cols - 1) * colGapPx, viewportW)
                val contentHeight = maxOf(maxBottom + padPx, viewportH)
                layout(contentWidth, contentHeight) {
                    placements.forEach { (placeable, offset) -> placeable.place(offset) }
                }
            }
        }
    }
}

/** A task paired with its layered-layout slot ([NodePos]); ordered for per-column stacking. */
private class PlacedNode(val task: TaskDto, val pos: NodePos)

/**
 * One card's measured geometry in canvas px, produced by [GraphPane]'s measure pass and
 * consumed by the edge underlay (§7), the pan hit-test (§5), and pan-to-card (§9).
 * [right]/[centerY] give an S-curve its source and target anchors: the right edge and
 * MEASURED vertical centre of a card (§10). The hoisted map holding these is a plain
 * (non-snapshot) HashMap deliberately: writing it during measure and reading it during
 * the same frame's draw needs no invalidation — draw always runs after measure, and any
 * re-measure re-runs the draw that reads the map. The caller's pan-to-card reads it from
 * a click handler, which likewise always runs after the frame that measured the cards.
 */
internal class CardRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    operator fun contains(p: Offset): Boolean =
        p.x >= left && p.x < left + width && p.y >= top && p.y < top + height
}

/** Pan cursors (§5): AWT has no grab/grabbing pair, so hand stands in for grab, move for grabbing. */
private val GRAB_CURSOR = PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR))
private val GRABBING_CURSOR = PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR))

/** Pointer travel before a background press commits to panning instead of a click (§1.4 vs §5). */
private val PAN_SLOP = 3.dp

// --- t-aq99: drag-to-link machinery -------------------------------------------------

/** Radius of the selected edge's midpoint (×) unlink chip. */
private val CHIP_R = 9.dp

/** Crosshair over the (+) link handles — a "precision authoring" cue distinct from the card hand. */
private val HANDLE_CURSOR = PointerIcon(java.awt.Cursor(java.awt.Cursor.CROSSHAIR_CURSOR))

/** What dropping on a hovered card would do — drives the ring colour (green links, red unlinks). */
internal enum class DropIndication { LINK, UNLINK }

/**
 * One in-flight link drag: fixed source/side/[targets] classification, a moving
 * [pointer] in content px. Only [pointer] is snapshot state — the draw pass and the
 * drop-target ring read it, so each move invalidates exactly those.
 */
internal class LinkDrag(
    val sourceId: String,
    val side: HandleSide,
    anchor: Offset,
    val targets: DragTargets,
) {
    var pointer: Offset by mutableStateOf(anchor)
}

/** [CardRect.contains] with a [m]-px margin — the pan hit-test uses it so the edge-straddling handles stay grabbable. */
internal fun CardRect.containsInflated(p: Offset, m: Float): Boolean =
    p.x >= left - m && p.x < left + width + m && p.y >= top - m && p.y < top + height + m

/**
 * Follow [id] (Initial pass, matching the down we consumed) until release: true if it
 * stayed a tap (release within [slopPx] total travel), false once it strays or is lost.
 */
private suspend fun AwaitPointerEventScope.awaitTapCompletion(id: PointerId, slopPx: Float): Boolean {
    var total = Offset.Zero
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) return total.getDistance() <= slopPx
        total += change.positionChange()
        change.consume()
        if (total.getDistance() > slopPx) return false
    }
}

/**
 * A chain-link handle straddling a card edge: 16dp circle in a 24dp hit box whose
 * centre sits ON the edge (12dp overflows the card). The glyph is a link — not a
 * (+) — because the drag authors link AND unlink with equal weight. It shares the
 * card's hover [MutableInteractionSource] so crossing from card onto handle never
 * drops the hover that keeps the handle mounted, and adds its own for the hover
 * accent.
 */
@Composable
private fun LinkHandle(
    side: HandleSide,
    cardHover: MutableInteractionSource,
    onStart: (HandleSide) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val own = remember { MutableInteractionSource() }
    val hovered by own.collectIsHoveredAsState()
    Box(
        modifier
            .size(24.dp)
            .hoverable(cardHover)
            .hoverable(own)
            .pointerHoverIcon(HANDLE_CURSOR)
            .pointerInput(side) {
                detectDragGestures(
                    onDragStart = { onStart(side) },
                    onDrag = { change, delta ->
                        change.consume()
                        onMove(delta)
                    },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onCancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            drawCircle(Tk.panel2)
            drawCircle(if (hovered) Tk.muted else Tk.line, style = Stroke(1.dp.toPx()))
            val glyph = if (hovered) Tk.txt else Tk.muted
            // Chain-link glyph at 45°: two overlapping stadium outlines.
            rotate(45f) {
                val w = 6.dp.toPx()
                val h = 3.6.dp.toPx()
                val overlap = 1.dp.toPx()
                val sw = 1.2.dp.toPx()
                val cr = CornerRadius(h / 2)
                drawRoundRect(
                    color = glyph,
                    topLeft = Offset(center.x - w + overlap, center.y - h / 2),
                    size = Size(w, h),
                    cornerRadius = cr,
                    style = Stroke(sw),
                )
                drawRoundRect(
                    color = glyph,
                    topLeft = Offset(center.x - overlap, center.y - h / 2),
                    size = Size(w, h),
                    cornerRadius = cr,
                    style = Stroke(sw),
                )
            }
        }
    }
}

/** Dotted grid: 1dp-radius `dot` circles on a 26dp grid, anchored at `(1,1)` (DESIGN §5). */
private fun DrawScope.drawDottedGrid() {
    val step = 26.dp.toPx()
    val radius = 1.dp.toPx()
    val origin = 1.dp.toPx()
    var y = origin
    while (y <= size.height) {
        var x = origin
        while (x <= size.width) {
            drawCircle(Tk.dot, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Arrowhead length along the end tangent (DESIGN §7) — shared so the stroke can stop at the head's base. */
private val ARROW_LEN = 8.dp

/**
 * One horizontal-tangent cubic Bézier (the "S-curve", DESIGN §7) from the
 * blocker's right-centre `(x1,y1)` towards the dependent's left-centre `(x2,y2)`.
 * The stroke stops at the arrowhead's BASE and the solid head is drawn over the
 * joint with its tip at `(x2,y2)` — a round-capped stroke ending at the tip
 * would poke past the triangle's point.
 */
private fun DrawScope.drawSCurveEdge(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, strokeW: Float, alpha: Float,
) {
    val endX = x2 - ARROW_LEN.toPx()
    val dx = maxOf(40.dp.toPx(), (endX - x1) * 0.5f)
    val path = Path().apply {
        moveTo(x1, y1)
        cubicTo(x1 + dx, y1, endX - dx, y2, endX, y2)
    }
    drawPath(path, color, alpha = alpha, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    drawArrowhead(tipX = x2, tipY = y2, fromX = endX - dx, fromY = y2, color = color, alpha = alpha)
}

/** Solid ~7px triangle at the edge's target end, pointing along the tangent (DESIGN §7). */
private fun DrawScope.drawArrowhead(
    tipX: Float, tipY: Float, fromX: Float, fromY: Float, color: Color, alpha: Float,
) {
    val vx = tipX - fromX
    val vy = tipY - fromY
    val len = hypot(vx, vy)
    if (len == 0f) return
    val dirX = vx / len
    val dirY = vy / len
    val perpX = -dirY
    val perpY = dirX
    val aLen = ARROW_LEN.toPx()
    val aHalf = 4.dp.toPx()
    val baseX = tipX - dirX * aLen
    val baseY = tipY - dirY * aLen
    val head = Path().apply {
        moveTo(tipX, tipY)
        lineTo(baseX + perpX * aHalf, baseY + perpY * aHalf)
        lineTo(baseX - perpX * aHalf, baseY - perpY * aHalf)
        close()
    }
    drawPath(head, color, alpha = alpha)
}

/**
 * A task card (DESIGN §6): 210 wide, min-height 96, `panel` surface with a 4dp
 * left accent border in the primary-state colour. Composable states — hover lift,
 * selection ring, Star dim, pin — layer on top of that resting look. The id row
 * carries the pin toggle: outline on hover, filled always while [pinned].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeCard(
    task: TaskDto,
    selected: Boolean,
    pinned: Boolean,
    dimmed: Boolean,
    dropIndication: DropIndication?,
    handlesNeedSelection: Boolean,
    dragActive: Boolean,
    dragSource: Boolean,
    onSelect: () -> Unit,
    onPinToggle: () -> Unit,
    onHandleDragStart: (HandleSide) -> Unit,
    onHandleDrag: (Offset) -> Unit,
    onHandleDragEnd: () -> Unit,
    onHandleDragCancel: () -> Unit,
) {
    val state = stateOf(task)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val lift by animateDpAsState(if (hovered) (-1).dp else 0.dp, tween(120))
    val elevation by animateDpAsState(
        when {
            selected -> 12.dp
            hovered -> 8.dp
            else -> 0.dp
        },
        tween(150),
    )
    val alpha by animateFloatAsState(if (dimmed) 0.28f else 1f, tween(150))
    val shape = RoundedCornerShape(7.dp)

    // t-aq99: when the chain-link handles reveal. Leaning: selected AND
    // hovered (zero noise otherwise); env-overridable to hover-only for comparison.
    // Every status gets handles — linking closed tasks is legal and organizationally
    // useful. The drag's source card keeps its handles mounted for the whole gesture —
    // unmounting the composable mid-drag would cancel the pointer input under the
    // user's hand.
    val showHandles = dragSource || (!dragActive && hovered && (selected || !handlesNeedSelection))

    // The card proper is a clipped inner Box so the edge-straddling handles can
    // overflow the outer (unclipped) one; the Layout measures the outer, whose size
    // the align+offset handles never extend, so card rects are unchanged.
    Box(
        Modifier
            // The parent Layout fixes this card's (x, y) slot; the hover lift is a pure
            // draw-layer translation (§6, §11) so animating it never re-measures the graph.
            .width(CARD_W.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = lift.toPx()
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CARD_MIN_H.dp)
                .shadow(elevation, shape, clip = false)
                .clip(shape)
                .background(Tk.panel)
                .drawBehind {
                    val bw = 1.dp.toPx()
                    val r = 7.dp.toPx()
                    // 1dp `line` border, inset so its full width sits inside the clip.
                    drawRoundRect(
                        color = Tk.line,
                        topLeft = Offset(bw / 2, bw / 2),
                        size = Size(size.width - bw, size.height - bw),
                        cornerRadius = CornerRadius(r - bw / 2),
                        style = Stroke(bw),
                    )
                    // 4dp accent left bar (corners clipped to the card rounding).
                    drawRect(color = state.color, size = Size(4.dp.toPx(), size.height))
                    // Selection ring: 2dp accent outline.
                    if (selected) {
                        val rw = 2.dp.toPx()
                        drawRoundRect(
                            color = Tk.accent,
                            topLeft = Offset(rw / 2, rw / 2),
                            size = Size(size.width - rw, size.height - rw),
                            cornerRadius = CornerRadius(r - rw / 2),
                            style = Stroke(rw),
                        )
                    }
                    // t-aq99: droppable target under a link drag — green ring for
                    // a would-link drop, red for a would-unlink one.
                    if (dropIndication != null) {
                        val rw = 2.dp.toPx()
                        drawRoundRect(
                            color = if (dropIndication == DropIndication.UNLINK) Tk.blocked else Tk.ready,
                            topLeft = Offset(rw / 2, rw / 2),
                            size = Size(size.width - rw, size.height - rw),
                            cornerRadius = CornerRadius(r - rw / 2),
                            style = Stroke(rw),
                        )
                    }
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null) { onSelect() }
                .padding(vertical = 8.dp, horizontal = 10.dp),
        ) {
            CardContent(task, state, pinned, hovered, onPinToggle)
        }
        if (showHandles) {
            LinkHandle(
                side = HandleSide.LEFT,
                cardHover = interaction,
                onStart = onHandleDragStart,
                onMove = onHandleDrag,
                onEnd = onHandleDragEnd,
                onCancel = onHandleDragCancel,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = (-12).dp),
            )
            LinkHandle(
                side = HandleSide.RIGHT,
                cardHover = interaction,
                onStart = onHandleDragStart,
                onMove = onHandleDrag,
                onEnd = onHandleDragEnd,
                onCancel = onHandleDragCancel,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 12.dp),
            )
        }
    }
}

/** The card's inner content column, unchanged by the feature — split out so [NodeCard] stays readable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardContent(
    task: TaskDto,
    state: TaskState,
    pinned: Boolean,
    hovered: Boolean,
    onPinToggle: () -> Unit,
) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(task.id, fontSize = 11.sp, color = Tk.faint)
                Spacer(Modifier.weight(1f))
                // Fixed 20dp slot so the hover reveal never re-measures the card (§6,
                // §11): filled pin always visible while pinned, outline pin on hover
                // elsewhere. The clickable spans a 36dp hit target overflowing the slot
                // (requiredSize; pointer hits aren't clipped to parent bounds) with the
                // glyph drawn at 20dp inside, and consumes the press so toggling never
                // falls through to the card's select.
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (pinned || hovered) {
                        Icon(
                            imageVector = if (pinned) PinIcons.Filled else PinIcons.Outline,
                            contentDescription = if (pinned) "unpin" else "pin",
                            tint = if (pinned) Tk.accent else Tk.muted,
                            modifier = Modifier
                                .requiredSize(36.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onPinToggle() }
                                .padding(8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                task.title,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = if (state == TaskState.DONE || state == TaskState.DROPPED) Tk.muted else Tk.txt,
                textDecoration = if (state == TaskState.DROPPED) TextDecoration.LineThrough else null,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CardTags(task, state)
            }
        }
}

/** The card's wrapping metadata row, in the fixed DESIGN §8 tag order. */
@Composable
private fun CardTags(task: TaskDto, state: TaskState) {
    StatePill(state)
    task.thread?.let { OutlineTag("#$it") }
    when (task.priority) {
        "high" -> OutlineTag("!high", textColor = Tk.blocked, border = Tk.prioHighBorder)
        "low" -> OutlineTag("low", textColor = Tk.faint)
    }
    if (task.depends.isNotEmpty()) OutlineTag("⛓ ${task.depends.size}")
    val due = task.due
    if (due != null) {
        val overdue = task.computed.overdue
        OutlineTag(
            (if (overdue) "overdue " else "due ") + fmtDate(due),
            textColor = Tk.overdue,
            border = Tk.dueBorder,
        )
    }
    task.defer?.let { OutlineTag("⏳ ${fmtDate(it)}", textColor = Tk.deferred, border = Tk.deferBorder) }
    task.waitingOn?.let { OutlineTag("⌛ $it", textColor = Tk.waiting, border = Tk.waitBorder) }
}
