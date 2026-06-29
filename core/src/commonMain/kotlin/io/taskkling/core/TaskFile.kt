package io.taskkling.core

/**
 * Filename = `<id>--<slug>.md` (PRD §8.3). The double-dash separates the
 * immutable id from a human slug; `depends` references the id, never the file,
 * so titles/slugs may change freely.
 */
public fun Task.fileName(): String = "$id--${slugify(title)}.md"

/** The single inverse of [fileName]: the id embedded in a `<id>--<slug>.md` name. */
public fun idOfFileName(fileName: String): String =
    fileName.removeSuffix(".md").substringBefore("--")

/**
 * Render a task to its on-disk markdown form: YAML frontmatter then body
 * (PRD §8.3). Optional fields are omitted when unset; `waiting_on` is emitted
 * only alongside `status: waiting` (the invariant lives in validation).
 */
public fun Task.toMarkdown(): String = buildString {
    append("---\n")
    append("id: ").append(id).append('\n')
    append("title: ").append(yamlScalar(title)).append('\n')
    if (thread != null) append("thread: ").append(yamlScalar(thread)).append('\n')
    append("status: ").append(status.wire).append('\n')
    if (waitingOn != null) append("waiting_on: ").append(yamlScalar(waitingOn)).append('\n')
    append("depends: ").append(yamlList(depends)).append('\n')
    if (due != null) append("due: ").append(due).append('\n')
    if (defer != null) append("defer: ").append(defer).append('\n')
    append("priority: ").append(priority.wire).append('\n')
    append("created: ").append(created).append('\n')
    if (closed != null) append("closed: ").append(closed).append('\n')
    append("---\n")
    if (body.isNotBlank()) {
        append('\n').append(body.trimEnd()).append('\n')
    }
}

/** Slugify a title: lowercase ASCII, German umlauts transliterated, `-` joins. */
public fun slugify(title: String): String {
    val sb = StringBuilder()
    for (ch in title.lowercase()) {
        when (ch) {
            'ä' -> sb.append("ae")
            'ö' -> sb.append("oe")
            'ü' -> sb.append("ue")
            'ß' -> sb.append("ss")
            in 'a'..'z', in '0'..'9' -> sb.append(ch)
            else -> sb.append('-')
        }
    }
    val slug = sb.toString().replace(Regex("-+"), "-").trim('-').take(50).trim('-')
    return slug.ifEmpty { "task" }
}

private val UNSAFE_YAML = ":#\"'[]{},&*!|>%@`".toSet()

/** Quote a scalar only when YAML would otherwise mis-parse it. */
private fun yamlScalar(s: String): String {
    val needsQuote = s.isEmpty() ||
        s.first().isWhitespace() ||
        s.last().isWhitespace() ||
        s.any { it in UNSAFE_YAML } ||
        s.startsWith("- ")
    return if (!needsQuote) s
    else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun yamlList(items: List<String>): String =
    if (items.isEmpty()) "[]" else items.joinToString(", ", prefix = "[", postfix = "]")
