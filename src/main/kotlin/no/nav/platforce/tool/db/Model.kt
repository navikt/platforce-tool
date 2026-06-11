package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

const val TARGET_VERSIONS = "target_versions"

object TargetVersionsTable : Table(TARGET_VERSIONS) {
    val userId = varchar("user_id", 100)
    val team = varchar("team", 100).nullable()
    val type = varchar("type", 20)
    val key = varchar("dependency_key", 500)
    val version = varchar("version", 100)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey =
        PrimaryKey(
            userId,
            type,
            key,
        )
}

data class TargetVersion(
    val userId: String,
    val team: String?,
    val type: TargetVersionType,
    val key: String,
    val version: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class TargetVersionType {
    PLUGIN,
    DEPENDENCY,
}

fun ResultRow.toTargetVersion() =
    TargetVersion(
        userId = this[TargetVersionsTable.userId],
        team = this[TargetVersionsTable.team],
        type = TargetVersionType.valueOf(this[TargetVersionsTable.type]),
        key = this[TargetVersionsTable.key],
        version = this[TargetVersionsTable.version],
        createdAt = this[TargetVersionsTable.createdAt],
        updatedAt = this[TargetVersionsTable.updatedAt],
    )
