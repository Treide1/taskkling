package io.taskkling.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlin.math.hypot

/**
 * The canvas: a scrolling, dotted-grid surface of task cards over a layer of
 * S-curve dependency edges (DESIGN §5–§7). Clicking empty canvas clears the
 * selection (§1.4); selecting a node dims everything outside its neighbourhood.
 */
@Composable
internal fun GraphPane(
    export: ExportDto,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gl = remember(export) { layout(export.tasks) }

    val cols = gl.layerCount.coerceAtLeast(1)
    val rows = gl.maxLayerSize.coerceAtLeast(1)
    val w = (PAD * 2 + cols * CARD_W + (cols - 1) * COL_GAP).dp
    val h = (PAD * 2 + rows * CARD_MIN_H + (rows - 1) * ROW_GAP).dp

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

    Box(
        modifier
            .background(Tk.bg)
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .size(w, h)
                .drawBehind { drawDottedGrid() }
                .clickable(interactionSource = canvasClick, indication = null) { onClearSelection() },
        ) {
            // Edges render UNDER the cards and never capture pointer input (§7).
            Canvas(Modifier.matchParentSize()) {
                for (e in gl.edges) {
                    val a = gl.positions[e.from] ?: continue
                    val b = gl.positions[e.to] ?: continue
                    val x1 = (PAD + a.layer * (CARD_W + COL_GAP) + CARD_W).dp.toPx()
                    val y1 = (PAD + a.indexInLayer * (CARD_MIN_H + ROW_GAP) + EDGE_ANCHOR_Y).dp.toPx()
                    val x2 = (PAD + b.layer * (CARD_W + COL_GAP)).dp.toPx()
                    val y2 = (PAD + b.indexInLayer * (CARD_MIN_H + ROW_GAP) + EDGE_ANCHOR_Y).dp.toPx()
                    val highlighted = selectedId != null && (e.from == selectedId || e.to == selectedId)
                    val color = if (highlighted) Tk.accent else Tk.line
                    val strokeW = if (highlighted) 2.4.dp.toPx() else 1.6.dp.toPx()
                    val alpha = if (highlighted) 1f else 1f - 0.8f * dimFraction
                    drawSCurveEdge(x1, y1, x2, y2, color, strokeW, alpha)
                }
            }
            for (t in export.tasks) {
                val pos = gl.positions[t.id] ?: continue
                NodeCard(
                    task = t,
                    selected = t.id == selectedId,
                    dimmed = selectedId != null && t.id !in neighborhood,
                    offsetX = (PAD + pos.layer * (CARD_W + COL_GAP)).dp,
                    offsetY = (PAD + pos.indexInLayer * (CARD_MIN_H + ROW_GAP)).dp,
                    onSelect = { onSelect(t.id) },
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

/**
 * One horizontal-tangent cubic Bézier (the "S-curve", DESIGN §7) from the
 * blocker's right-centre `(x1,y1)` to the dependent's left-centre `(x2,y2)`,
 * capped with a solid arrowhead oriented along the end tangent.
 */
private fun DrawScope.drawSCurveEdge(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, strokeW: Float, alpha: Float,
) {
    val dx = maxOf(40.dp.toPx(), (x2 - x1) * 0.5f)
    val path = Path().apply {
        moveTo(x1, y1)
        cubicTo(x1 + dx, y1, x2 - dx, y2, x2, y2)
    }
    drawPath(path, color, alpha = alpha, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    drawArrowhead(tipX = x2, tipY = y2, fromX = x2 - dx, fromY = y2, color = color, alpha = alpha)
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
    val aLen = 8.dp.toPx()
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
    offsetX: androidx.compose.ui.unit.Dp,
    offsetY: androidx.compose.ui.unit.Dp,
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
            .offset(x = offsetX, y = offsetY + lift)
            .width(CARD_W.dp)
            .heightIn(min = CARD_MIN_H.dp)
            .graphicsLayer { this.alpha = alpha }
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

/** The card's wrapping metadata row (DESIGN §6/§8), in the spike's order. */
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
