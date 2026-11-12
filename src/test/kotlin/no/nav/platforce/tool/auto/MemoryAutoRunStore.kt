package no.nav.platforce.tool.auto

import java.time.Instant
import java.util.UUID

class MemoryAutoRunStore(
    private val optedIn: () -> Set<String> = { emptySet() },
) : AutoRunStore {
    private var current: AutoRun? = null
    private val history = mutableMapOf<String, AutoRun>()
    private val logs = mutableListOf<AppLogEntry>()
    private var nextLogId = 1L
    var healthy = true

    @Synchronized
    override fun tryStart(username: String): AutoRun? {
        if (current?.status == AutoRunStatus.RUNNING) return null
        current?.let { history[it.id] = it }
        current = AutoRun(UUID.randomUUID().toString(), username, AutoRunStatus.RUNNING, Instant.now().toString())
        append(autoStartedReport(optedIn()), "INFO")
        return current
    }

    @Synchronized
    override fun heartbeat(runId: String): Boolean = healthy && current?.id == runId && current?.status == AutoRunStatus.RUNNING

    @Synchronized
    override fun startStep(
        runId: String,
        step: String,
        repository: String?,
    ) {
        requireOwner(runId)
        current = current!!.copy(step = step, repository = repository, stepStartedAt = Instant.now().toString())
    }

    @Synchronized
    override fun report(
        runId: String,
        report: AutoReport,
    ) {
        requireOwner(runId)
        append(report, "INFO")
        current = current!!.copy(step = null, repository = null, stepStartedAt = null)
    }

    @Synchronized
    override fun finish(
        runId: String,
        status: AutoRunStatus,
        report: AutoReport,
    ) {
        requireOwner(runId)
        append(report, if (status == AutoRunStatus.COMPLETED) "INFO" else "WARN")
        current =
            current!!.copy(status = status, finishedAt = Instant.now().toString(), step = null, repository = null, stepStartedAt = null)
    }

    @Synchronized
    override fun snapshot(before: Long?): AutoProcessSnapshot {
        val page = logs.filter { before == null || it.id < before }.takeLast(101)
        val entries = page.takeLast(100)
        val ids = entries.map { it.runId }.toSet() + listOfNotNull(current?.id)
        val runs = (history.values + listOfNotNull(current)).filter { it.id in ids }
        return AutoProcessSnapshot(current, entries, if (page.size > 100) page[1].id else null, runs)
    }

    @Synchronized
    override fun deleteRun(runId: String): AutoRunDeletion {
        if (current?.id == runId) return AutoRunDeletion.PROTECTED
        val run = history[runId] ?: return AutoRunDeletion.NOT_FOUND
        if (run.status == AutoRunStatus.RUNNING) return AutoRunDeletion.PROTECTED
        logs.removeAll { it.runId == runId }
        history.remove(runId)
        return AutoRunDeletion.DELETED
    }

    private fun requireOwner(id: String) {
        if (current?.id != id || current?.status != AutoRunStatus.RUNNING) throw AutoInterrupted("Run was replaced")
    }

    private fun append(
        report: AutoReport,
        level: String,
    ) {
        val run = checkNotNull(current)
        logs +=
            AppLogEntry(
                nextLogId++,
                Instant.now().toString(),
                run.id,
                run.username,
                level,
                run.step,
                run.repository,
                report.message,
                report.details,
                report.url,
            )
    }
}
