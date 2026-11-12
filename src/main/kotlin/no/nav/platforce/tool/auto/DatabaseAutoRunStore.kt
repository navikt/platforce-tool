package no.nav.platforce.tool.auto

import no.nav.platforce.tool.db.AppLogsTable
import no.nav.platforce.tool.db.AutoRunLockTable
import no.nav.platforce.tool.db.AutoRunsTable
import no.nav.platforce.tool.db.PostgresDatabase
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class DatabaseAutoRunStore(
    private val now: () -> Instant = Instant::now,
) : AutoRunStore {
    private fun <T> locked(action: () -> T): T =
        transaction(Connection.TRANSACTION_READ_COMMITTED, db = PostgresDatabase.database) {
            AutoRunLockTable
                .selectAll()
                .where { AutoRunLockTable.id eq 1 }
                .forUpdate()
                .single()
            recoverExpiredRun()
            action()
        }

    override fun tryStart(username: String): AutoRun? =
        locked {
            if (lock()[AutoRunLockTable.runId] != null) {
                null
            } else {
                val id = UUID.randomUUID().toString()
                val started = now()
                AutoRunsTable.insert {
                    it[AutoRunsTable.id] = id
                    it[AutoRunsTable.username] = username
                    it[status] = AutoRunStatus.RUNNING.name
                    it[startedAt] = started
                }
                AutoRunLockTable.update({ AutoRunLockTable.id eq 1 }) {
                    it[runId] = id
                    it[leaseUntil] = started.plusSeconds(60)
                }
                val run = run(id)
                append(run, autoStartedReport(PostgresDatabase.getAutoRepositories()), "INFO")
                run
            }
        }

    override fun heartbeat(runId: String): Boolean =
        locked {
            if (lock()[AutoRunLockTable.runId] != runId) {
                false
            } else {
                AutoRunLockTable.update({ AutoRunLockTable.id eq 1 }) { it[leaseUntil] = now().plusSeconds(60) }
                true
            }
        }

    fun saveRepositorySetting(
        repository: String,
        enabled: Boolean,
    ) {
        locked {
            if (lock()[AutoRunLockTable.runId] != null) throw AutoSettingsLocked()
            PostgresDatabase.saveAutoRepository(repository, enabled)
        }
    }

    override fun startStep(
        runId: String,
        step: String,
        repository: String?,
    ) {
        locked {
            requireOwner(runId)
            AutoRunsTable.update({ AutoRunsTable.id eq runId }) {
                it[AutoRunsTable.step] = step
                it[AutoRunsTable.repository] = repository
                it[stepStartedAt] = now()
            }
        }
    }

    override fun report(
        runId: String,
        report: AutoReport,
    ) {
        locked {
            requireOwner(runId)
            val run = run(runId)
            if (run.repository == null || report.url != null) append(run, report, "INFO")
            clearStep(runId)
        }
    }

    override fun finish(
        runId: String,
        status: AutoRunStatus,
        report: AutoReport,
    ) {
        require(status != AutoRunStatus.RUNNING)
        locked {
            requireOwner(runId)
            finishRun(run(runId), status, report)
        }
    }

    override fun snapshot(before: Long?): AutoProcessSnapshot =
        locked {
            val latest = latestRun()
            val rows =
                AppLogsTable
                    .selectAll()
                    .apply {
                        if (before != null) where { AppLogsTable.id less before }
                    }.orderBy(AppLogsTable.id, SortOrder.DESC)
                    .limit(101)
                    .toList()
            val page = rows.take(100).map { it.toLog() }
            val runIds = (page.map { it.runId } + listOfNotNull(latest?.id)).toSet()
            val runs =
                AutoRunsTable.selectAll().where { AutoRunsTable.id inList runIds }.map { it.toRun() }
            AutoProcessSnapshot(latest, page.reversed(), if (rows.size > 100) page.last().id else null, runs)
        }

    override fun deleteRun(runId: String): AutoRunDeletion =
        locked {
            val target =
                AutoRunsTable
                    .selectAll()
                    .where { AutoRunsTable.id eq runId }
                    .singleOrNull()
                    ?.toRun()
            when {
                target == null -> {
                    AutoRunDeletion.NOT_FOUND
                }

                target.id == latestRun()?.id || target.status == AutoRunStatus.RUNNING ||
                    lock()[AutoRunLockTable.runId] == target.id -> {
                    AutoRunDeletion.PROTECTED
                }

                else -> {
                    AppLogsTable.deleteWhere { AppLogsTable.runId eq runId }
                    AutoRunsTable.deleteWhere { AutoRunsTable.id eq runId }
                    AutoRunDeletion.DELETED
                }
            }
        }

    private fun latestRun(): AutoRun? =
        AppLogsTable
            .select(AppLogsTable.runId)
            .orderBy(AppLogsTable.id, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let { run(it[AppLogsTable.runId]) }

    private fun lock(): ResultRow = AutoRunLockTable.selectAll().where { AutoRunLockTable.id eq 1 }.single()

    private fun run(id: String): AutoRun =
        AutoRunsTable
            .selectAll()
            .where { AutoRunsTable.id eq id }
            .single()
            .toRun()

    private fun requireOwner(id: String) {
        if (lock()[AutoRunLockTable.runId] != id) throw AutoInterrupted("Auto run no longer owns the global process lock")
    }

    private fun recoverExpiredRun() {
        val row = lock()
        val id = row[AutoRunLockTable.runId] ?: return
        if (row[AutoRunLockTable.leaseUntil]?.isAfter(now()) == true) return
        finishRun(
            run(id),
            AutoRunStatus.INTERRUPTED,
            AutoReport(
                "Auto process interrupted: worker heartbeat expired",
                "The application may have restarted. Review any created PRs before triggering another run.",
            ),
        )
    }

    private fun clearStep(id: String) {
        AutoRunsTable.update({ AutoRunsTable.id eq id }) {
            it[step] = null
            it[repository] = null
            it[stepStartedAt] = null
        }
    }

    private fun finishRun(
        run: AutoRun,
        status: AutoRunStatus,
        report: AutoReport,
    ) {
        append(run, report, if (status == AutoRunStatus.COMPLETED) "INFO" else "WARN")
        AutoRunsTable.update({ AutoRunsTable.id eq run.id }) {
            it[AutoRunsTable.status] = status.name
            it[finishedAt] = now()
        }
        clearStep(run.id)
        AutoRunLockTable.update({ AutoRunLockTable.id eq 1 }) {
            it[runId] = null
            it[leaseUntil] = null
        }
    }

    private fun append(
        run: AutoRun,
        report: AutoReport,
        level: String,
    ) {
        AppLogsTable.insert {
            it[timestamp] = now()
            it[runId] = run.id
            it[username] = run.username
            it[AppLogsTable.level] = level
            it[step] = run.step
            it[repository] = run.repository
            it[message] = report.message
            it[details] = report.details
            it[url] = report.url
        }
    }

    private fun ResultRow.toRun() =
        AutoRun(
            this[AutoRunsTable.id],
            this[AutoRunsTable.username],
            AutoRunStatus.valueOf(this[AutoRunsTable.status]),
            this[AutoRunsTable.startedAt].toString(),
            this[AutoRunsTable.finishedAt]?.toString(),
            this[AutoRunsTable.step],
            this[AutoRunsTable.repository],
            this[AutoRunsTable.stepStartedAt]?.toString(),
        )

    private fun ResultRow.toLog() =
        AppLogEntry(
            this[AppLogsTable.id],
            this[AppLogsTable.timestamp].toString(),
            this[AppLogsTable.runId],
            this[AppLogsTable.username],
            this[AppLogsTable.level],
            this[AppLogsTable.step],
            this[AppLogsTable.repository],
            this[AppLogsTable.message],
            this[AppLogsTable.details],
            this[AppLogsTable.url],
        )
}
