package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AutoRunsTable : Table("auto_runs") {
    val id = varchar("id", 36)
    val username = varchar("username", 100)
    val status = varchar("status", 20)
    val startedAt = timestamp("started_at").index()
    val finishedAt = timestamp("finished_at").nullable()
    val step = text("step").nullable()
    val repository = varchar("repository", 300).nullable()
    val stepStartedAt = timestamp("step_started_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AutoRunLockTable : Table("auto_run_lock") {
    val id = integer("id")
    val runId = varchar("run_id", 36).nullable()
    val leaseUntil = timestamp("lease_until").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AppLogsTable : Table("app_logs") {
    val id = long("id").autoIncrement()
    val timestamp = timestamp("timestamp")
    val runId = varchar("run_id", 36).index()
    val username = varchar("username", 100)
    val level = varchar("level", 10)
    val step = text("step").nullable()
    val repository = varchar("repository", 300).nullable()
    val message = text("message")
    val details = text("details").nullable()
    val url = text("url").nullable()
    override val primaryKey = PrimaryKey(id)
}
