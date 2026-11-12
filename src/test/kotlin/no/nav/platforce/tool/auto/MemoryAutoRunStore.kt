package no.nav.platforce.tool.auto

import java.time.Instant
import java.util.UUID

class MemoryAutoRunStore : AutoRunStore {
    private var current: AutoRun? = null
    private val logs = mutableListOf<AppLogEntry>()
    var healthy = true

    @Synchronized
    override fun tryStart(username: String): AutoRun? {
        if (current?.status == AutoRunStatus.RUNNING) return null
        current = AutoRun(UUID.randomUUID().toString(), username, AutoRunStatus.RUNNING, Instant.now().toString())
        append(AutoReport("Auto process started"), "INFO")
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
        return AutoProcessSnapshot(current, page.takeLast(100), if (page.size > 100) page[1].id else null)
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
                logs.size + 1L,
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
