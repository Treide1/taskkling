package io.taskkling.core

/** Stored, human-decided lifecycle state (PRD §8.1). */
public enum class Status(public val wire: String) {
    OPEN("open"),
    WAITING("waiting"),
    DONE("done"),
    DROPPED("dropped");

    public companion object {
        public fun from(s: String): Status =
            entries.firstOrNull { it.wire == s }
                ?: throw TkError(ExitCode.VALIDATION, "invalid status '$s' (open|waiting|done|dropped)")
    }
}

/** Stored priority (PRD §8.1); default `normal`. */
public enum class Priority(public val wire: String) {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    public companion object {
        public fun from(s: String): Priority =
            entries.firstOrNull { it.wire == s }
                ?: throw TkError(ExitCode.VALIDATION, "invalid priority '$s' (low|normal|high)")
    }
}

/**
 * One node = one task: the **stored** fields only (PRD §8.1). Computed
 * attributes (ready/blocked/…) are derived at read time and never live here.
 * Datetimes are canonical ISO-8601 UTC strings (PRD §8.1, §11).
 */
public data class Task(
    val id: String,
    val title: String,
    val thread: String? = null,
    val status: Status = Status.OPEN,
    val waitingOn: String? = null,
    val depends: List<String> = emptyList(),
    val due: String? = null,
    val defer: String? = null,
    val priority: Priority = Priority.NORMAL,
    val created: String,
    val closed: String? = null,
    val body: String = "",
)
