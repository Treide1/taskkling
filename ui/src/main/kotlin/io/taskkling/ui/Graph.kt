package io.taskkling.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
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
 * selection (§1.4); selecting a node dims everything outside its neighbourhood.
 * An LMB drag that starts on the background pans the canvas 1:1 (§5).
 *
 * [hScroll]/[vScroll] are hoisted to the caller so wheel scrolling, drag
 * panning, and programmatic pan-to-card all mutate the same clamped state.
 */
@Composable
internal fun GraphPane(
    export: ExportDto,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    hScroll: ScrollState,
    vScroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val gl = remember(export) { layout(export.tasks) }

    val cols = gl.layerCount.coerceAtLeast(1)

    // Cards ordered by (layer, indexInLayer): column then the intra-column stacking order
    // that [layout] fixed. Emitting content in this order lets the measure pass keep a
    // simple per-column running sum (§10).
    val placed = remember(gl) {
        export.tasks.mapNotNull { t -> gl.positions[t.id]?.let { PlacedNode(t, it) } }
            .sortedWith(compareBy({ it.pos.layer }, { it.pos.indexInLayer }))
    }

    // Highlight neighbourhood: the selected node plus every node one edge away.
    val neighborhood = remember(selectedId, gl) {
        if (selectedId == null) emptySet() else buildSet {
            add(selectedId)
            for (e in gl.edges) {
                if (e.from == selectedId) add(e.to)
                if (e.to == selectedId) add(e.from)
            }
        }
    }

    // Drives the fade of non-highlighted edges while a selection is active (§7, §11).
    val dimFraction by animateFloatAsState(if (selectedId != null) 1f else 0f, tween(150))
    val canvasClick = remember { MutableInteractionSource() }

    // Every card's MEASURED rect in canvas px, filled by the custom Layout's single
    // measure pass below and read straight back by the edge underlay's `drawBehind`.
    // One measurement feeds both cards and edges, so an edge can never lag its cards by
    // a frame. A plain (non-snapshot) map is deliberate: writing it during measure and
    // reading it during the same frame's draw needs no invalidation — draw always runs
    // after measure, and any re-measure re-runs the draw that reads the map.
    val cardRects = remember { HashMap<String, CardRect>() }

    // True while a background drag is panning; drives the grab→grabbing cursor swap.
    var panning by remember { mutableStateOf(false) }

    Box(
        modifier
            .background(Tk.bg)
            // Grab cursor over the background at rest, grabbing while panning (§5). Cards
            // override with their own hand cursor — except mid-pan, where crossing a card
            // means nothing, so the override flag pins the grabbing cursor everywhere.
            .pointerHoverIcon(if (panning) GRABBING_CURSOR else GRAB_CURSOR, overrideDescendants = panning)
            .pointerInput(hScroll, vScroll) {
                // Pan on LMB background drag (§5): pointer delta == scroll delta (1:1, no
                // inertia), through the same clamped ScrollStates the wheel mutates. The
                // gesture claims a press only when it lands OUTSIDE every card — hit-tested
                // against the measured rects the edge layer already uses — so cards keep
                // their click/hover behaviour untouched.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    // The gesture sees viewport coordinates; the rects live in content px.
                    val inContent = down.position + Offset(hScroll.value.toFloat(), vScroll.value.toFloat())
                    if (cardRects.values.any { inContent in it }) return@awaitEachGesture
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
                        dimmed = selectedId != null && p.task.id !in neighborhood,
                        onSelect = { onSelect(p.task.id) },
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
                        val highlighted = selectedId != null && (e.from == selectedId || e.to == selectedId)
                        val color = if (highlighted) Tk.accent else Tk.line
                        val strokeW = if (highlighted) 2.4.dp.toPx() else 1.6.dp.toPx()
                        val alpha = if (highlighted) 1f else 1f - 0.8f * dimFraction
                        drawSCurveEdge(a.right, a.centerY, b.left, b.centerY, color, strokeW, alpha)
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

            // Width unchanged (§10); height follows the tallest column's measured stack.
            val contentWidth = padPx * 2 + cols * cardWpx + (cols - 1) * colGapPx
            val contentHeight = maxBottom + padPx
            layout(contentWidth, contentHeight) {
                placements.forEach { (placeable, offset) -> placeable.place(offset) }
            }
        }
    }
}

/** A task paired with its layered-layout slot ([NodePos]); ordered for per-column stacking. */
private class PlacedNode(val task: TaskDto, val pos: NodePos)

/**
 * One card's measured geometry in canvas px, produced by [GraphPane]'s measure pass and
 * consumed by the edge underlay (§7). [right]/[centerY] give an S-curve its source and
 * target anchors: the right edge and MEASURED vertical centre of a card (§10).
 */
private class CardRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val centerY: Float get() = top + height / 2f
    operator fun contains(p: Offset): Boolean =
        p.x >= left && p.x < left + width && p.y >= top && p.y < top + height
}

/** Pan cursors (§5): AWT has no grab/grabbing pair, so hand stands in for grab, move for grabbing. */
private val GRAB_CURSOR = PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR))
private val GRABBING_CURSOR = PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR))

/** Pointer travel before a background press commits to panning instead of a click (§1.4 vs §5). */
private val PAN_SLOP = 3.dp

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
 * selection ring, neighbourhood dim — layer on top of that resting look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeCard(
    task: TaskDto,
    selected: Boolean,
    dimmed: Boolean,
    onSelect: () -> Unit,
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

    Box(
        Modifier
            // The parent Layout fixes this card's (x, y) slot; the hover lift is a pure
            // draw-layer translation (§6, §11) so animating it never re-measures the graph.
            .width(CARD_W.dp)
            .heightIn(min = CARD_MIN_H.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationY = lift.toPx()
            }
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
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Column {
            Text(task.id, fontSize = 11.sp, color = Tk.faint)
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
