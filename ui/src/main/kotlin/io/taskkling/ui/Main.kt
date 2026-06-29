package io.taskkling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import java.io.File

private const val NODE_W = 200
private const val NODE_H = 64
private const val H_GAP = 244
private const val V_GAP = 88
private const val PAD = 24

/** Fill colour for a node from its stored status + computed state (PRD §13). */
private fun nodeColor(t: TaskDto): Color = when {
    t.status == "done" -> Color(0xFFB0BEC5)
    t.status == "dropped" -> Color(0xFFCFD8DC)
    t.computed.ready -> Color(0xFFA5D6A7)
    t.computed.deferred -> Color(0xFFB39DDB)
    t.status == "waiting" -> Color(0xFFFFE082)
    t.computed.blocked -> Color(0xFFEF9A9A)
    else -> Color(0xFFE0E0E0)
}

/**
 * The desktop app (PRD §13): a pure CLI client. Reads `export`, lays the DAG out
 * with [layout], renders a node-link graph with a detail panel, and performs
 * every mutation by shelling out to the CLI ([CliClient.mutate]) then refreshing
 * from the returned export. A headless smoke path (env `TASKKLING_SMOKE=1`)
 * exercises the export→layout data path and exits, for verification without a
 * display.
 */
fun main() {
    val binary = CliDiscovery.locate()
    if (System.getenv("TASKKLING_SMOKE") == "1") {
        if (binary == null) {
            System.err.println("smoke: taskkling binary not found (PATH / TASKKLING_BINARY / config)")
            kotlin.system.exitProcess(3)
        }
        val export = CliClient(binary).export()
        val gl = layout(export.tasks)
        println("smoke ok: binary=$binary nodes=${export.tasks.size} layers=${gl.layerCount} edges=${gl.edges.size}")
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "taskkling") {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (binary == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("taskkling binary not found.\nSet it on PATH, TASKKLING_BINARY, or config binary_path.")
                        }
                    } else {
                        App(CliClient(binary, File(System.getProperty("user.dir"))))
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
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh(next: ExportDto) {
        export = next
        error = null
        if (selectedId != null && next.tasks.none { it.id == selectedId }) selectedId = null
    }

    LaunchedEffect(Unit) {
        runCatching { client.export() }
            .onSuccess { refresh(it) }
            .onFailure { error = it.message }
    }

    fun mutate(args: List<String>) {
        runCatching { client.mutate(args) }
            .onSuccess { refresh(it) }
            .onFailure { error = it.message }
    }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val current = export
            when {
                error != null && current == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("error: $error") }
                current == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("loading…") }
                else -> GraphPane(current, selectedId, onSelect = { selectedId = it })
            }
        }
        Divider(Modifier.width(1.dp).fillMaxHeight())
        Box(Modifier.width(320.dp).fillMaxHeight()) {
            DetailPane(
                task = export?.tasks?.firstOrNull { it.id == selectedId },
                error = error,
                onAction = { verb, id -> mutate(listOf(verb, id)) },
                loadBody = { id -> runCatching { client.body(id) }.getOrElse { "" } },
            )
        }
    }
}

@Composable
private fun GraphPane(export: ExportDto, selectedId: String?, onSelect: (String) -> Unit) {
    val gl = remember(export) { layout(export.tasks) }
    val byId = remember(export) { export.tasks.associateBy { it.id } }
    fun cx(id: String) = PAD + gl.positions.getValue(id).layer * H_GAP
    fun cy(id: String) = PAD + gl.positions.getValue(id).indexInLayer * V_GAP

    val w = (PAD * 2 + gl.layerCount.coerceAtLeast(1) * H_GAP).dp
    val h = (PAD * 2 + gl.maxLayerSize.coerceAtLeast(1) * V_GAP).dp

    Box(
        Modifier.fillMaxSize()
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.size(w, h)) {
            Canvas(Modifier.fillMaxSize()) {
                for (e in gl.edges) {
                    if (e.from !in gl.positions || e.to !in gl.positions) continue
                    val start = Offset((cx(e.from) + NODE_W).dp.toPx(), (cy(e.from) + NODE_H / 2).dp.toPx())
                    val end = Offset(cx(e.to).dp.toPx(), (cy(e.to) + NODE_H / 2).dp.toPx())
                    drawLine(Color(0xFF90A4AE), start, end, strokeWidth = 2f)
                }
            }
            for (t in export.tasks) {
                val pos = gl.positions[t.id] ?: continue
                NodeCard(
                    task = t,
                    selected = t.id == selectedId,
                    modifier = Modifier
                        .offset(cx(t.id).dp, cy(t.id).dp)
                        .size(NODE_W.dp, NODE_H.dp)
                        .clickable { onSelect(t.id) },
                )
            }
        }
    }
}

@Composable
private fun NodeCard(task: TaskDto, selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .background(nodeColor(task), RoundedCornerShape(6.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color(0xFF1565C0) else Color(0xFF78909C),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(8.dp),
    ) {
        Column {
            Text(task.id, fontSize = 11.sp, color = Color(0xFF455A64))
            Text(
                task.title,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun DetailPane(
    task: TaskDto?,
    error: String?,
    onAction: (verb: String, id: String) -> Unit,
    loadBody: (String) -> String,
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (error != null) {
            Text("error: $error", color = Color(0xFFC62828), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (task == null) {
            Text("Select a node", color = Color(0xFF607D8B))
            return@Column
        }
        Text(task.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(task.id, fontSize = 12.sp, color = Color(0xFF607D8B))
        Spacer(Modifier.height(12.dp))
        field("status", task.status)
        task.thread?.let { field("thread", it) }
        field("priority", task.priority)
        task.due?.let { field("due", it) }
        task.defer?.let { field("defer", it) }
        if (task.depends.isNotEmpty()) field("depends", task.depends.joinToString(", "))
        if (task.computed.blockers.isNotEmpty()) field("blockers", task.computed.blockers.joinToString(", "))
        if (task.computed.dependents.isNotEmpty()) field("dependents", task.computed.dependents.joinToString(", "))
        field("ready", task.computed.ready.toString())

        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { onAction("done", task.id) }) { Text("Done") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAction("drop", task.id) }) { Text("Drop") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAction("reopen", task.id) }) { Text("Reopen") }
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))
        val body = remember(task.id) { loadBody(task.id) }
        Text("body", fontSize = 12.sp, color = Color(0xFF607D8B))
        Text(body.ifBlank { "(empty)" }, fontSize = 13.sp)
    }
}

@Composable
private fun field(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$name: ", fontSize = 13.sp, color = Color(0xFF607D8B))
        Text(value, fontSize = 13.sp)
    }
}
