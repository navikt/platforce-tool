package no.nav.platforce.tool.auto

enum class AutoRunStatus {
    RUNNING,
    COMPLETED,
    STOPPED,
    FAILED,
    INTERRUPTED,
}

data class AutoRun(
    val id: String,
    val username: String,
    val status: AutoRunStatus,
    val startedAt: String,
    val finishedAt: String? = null,
    val step: String? = null,
    val repository: String? = null,
    val stepStartedAt: String? = null,
)

data class AutoReport(
    val message: String,
    val details: String? = null,
    val url: String? = null,
)

internal fun autoStartedReport(repositories: Set<String>) =
    AutoReport(
        "Auto process started",
        if (repositories.isEmpty()) {
            "No repositories were opted in at start."
        } else {
            "Repositories opted in at start (${repositories.size}):\n${repositories.sorted().joinToString("\n")}"
        },
    )

data class AppLogEntry(
    val id: Long,
    val timestamp: String,
    val runId: String,
    val username: String,
    val level: String,
    val step: String?,
    val repository: String?,
    val message: String,
    val details: String? = null,
    val url: String? = null,
)

data class AutoProcessSnapshot(
    val run: AutoRun?,
    val logs: List<AppLogEntry>,
    val nextBefore: Long?,
)

interface AutoRunStore {
    fun tryStart(username: String): AutoRun?

    fun heartbeat(runId: String): Boolean

    fun startStep(
        runId: String,
        step: String,
        repository: String?,
    )

    fun report(
        runId: String,
        report: AutoReport,
    )

    fun finish(
        runId: String,
        status: AutoRunStatus,
        report: AutoReport,
    )

    fun snapshot(before: Long? = null): AutoProcessSnapshot
}

class AutoStopped(
    val report: AutoReport,
) : IllegalStateException(report.message)

class AutoInterrupted(
    message: String,
) : IllegalStateException(message)

class AutoSettingsLocked : IllegalStateException("Auto settings cannot be changed while an Auto process is running")
