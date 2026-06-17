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

const val REPOSITORY_NOTES = "repository_notes"

object RepositoryNotesTable : Table(REPOSITORY_NOTES) {
    val userId = varchar("user_id", 100)
    val team = varchar("team", 100).nullable()

    val repository = varchar("repository", 300)

    val note = text("note")

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey =
        PrimaryKey(
            userId,
            repository,
        )
}

data class RepositoryNote(
    val userId: String,
    val team: String?,
    val repository: String,
    val note: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun ResultRow.toRepositoryNote() =
    RepositoryNote(
        userId = this[RepositoryNotesTable.userId],
        team = this[RepositoryNotesTable.team],
        repository = this[RepositoryNotesTable.repository],
        note = this[RepositoryNotesTable.note],
        createdAt = this[RepositoryNotesTable.createdAt],
        updatedAt = this[RepositoryNotesTable.updatedAt],
    )

const val IGNORED_REPOSITORIES = "ignored_repositories"

object IgnoredRepositoriesTable : Table(IGNORED_REPOSITORIES) {
    val userId = varchar("user_id", 100)
    val team = varchar("team", 100).nullable()
    val repository = varchar("repository", 500)

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey =
        PrimaryKey(
            userId,
            repository,
        )
}

data class IgnoredRepository(
    val userId: String,
    val team: String?,
    val repository: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun ResultRow.toIgnoredRepository() =
    IgnoredRepository(
        userId = this[IgnoredRepositoriesTable.userId],
        team = this[IgnoredRepositoriesTable.team],
        repository = this[IgnoredRepositoriesTable.repository],
        createdAt = this[IgnoredRepositoriesTable.createdAt],
        updatedAt = this[IgnoredRepositoriesTable.updatedAt],
    )
