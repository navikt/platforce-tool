package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

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
