package io.taskkling.core

import okio.FileSystem
import okio.Path

/**
 * Parse a task file (YAML frontmatter + body) into a [Task] (PRD §8.3). The
 * reader is **lenient** — unknown keys are ignored (forward-compat) and bad
 * enums fall back to defaults rather than throwing, so a single hand-edited file
 * can't break a lock-free `export`. Structural problems are the `doctor`/
 * `validate` verbs' job (PRD §7.5), not the read path.
 */
public fun parseTask(fileName: String, content: String): Task {
    val text = content.replace("\r\n", "\n")
    val lines = text.split("\n")

    var fmLines: List<String> = emptyList()
    var body = ""
    if (lines.firstOrNull()?.trim() == "---") {
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
        if (end != null) {
            fmLines = lines.subList(1, end)
            body = lines.subList(minOf(end + 1, lines.size), lines.size).joinToString("\n").trim()
        }
    }

    val map = HashMap<String, String>()
    for (line in fmLines) {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) continue
        val colon = l.indexOf(':')
        if (colon < 0) continue
        map[l.substring(0, colon).trim()] = l.substring(colon + 1).trim()
    }

    return Task(
        id = scalar(map["id"]) ?: fileName.removeSuffix(".md").substringBefore("--"),
        title = scalar(map["title"]) ?: "(untitled)",
        thread = scalar(map["thread"]),
        status = map["status"]?.let { runCatching { Status.from(it.trim()) }.getOrNull() } ?: Status.OPEN,
        waitingOn = scalar(map["waiting_on"]),
        depends = parseList(map["depends"]),
        due = scalar(map["due"]),
        defer = scalar(map["defer"]),
        priority = map["priority"]?.let { runCatching { Priority.from(it.trim()) }.getOrNull() } ?: Priority.NORMAL,
        created = scalar(map["created"]) ?: "",
        closed = scalar(map["closed"]),
        body = body,
    )
}

/**
 * Load the active set (top-level `.md` files in `tasks/`), optionally plus the
 * `archive/` subtree (PRD §9). `trash/` is never read; unreadable files skipped.
 */
public fun Workspace.loadTasks(includeArchived: Boolean = false): List<Task> {
    val fs = FileSystem.SYSTEM
    val dirs = buildList {
        add(tasksDir)
        if (includeArchived) add(archiveDir)
    }
    val out = ArrayList<Task>()
    for (dir in dirs) {
        if (!fs.exists(dir)) continue
        for (p in fs.list(dir)) {
            if (!p.name.endsWith(".md")) continue
            val content = runCatching { fs.read(p) { readUtf8() } }.getOrNull() ?: continue
            out.add(parseTask(p.name, content))
        }
    }
    return out
}

private fun scalar(raw: String?): String? {
    val s = raw?.trim()
    if (s.isNullOrEmpty()) return null
    if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) return unquote(s)
    return s
}

private fun unquote(s: String): String {
    val inner = s.substring(1, s.length - 1)
    val sb = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c == '\\' && i + 1 < inner.length) {
            when (val n = inner[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                else -> { sb.append(c); sb.append(n) }
            }
            i += 2
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}

private fun parseList(raw: String?): List<String> {
    val s = raw?.trim() ?: return emptyList()
    if (!s.startsWith("[")) return emptyList()
    val inner = s.removePrefix("[").removeSuffix("]").trim()
    if (inner.isEmpty()) return emptyList()
    return inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
