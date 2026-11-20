package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.Table

object IgnoredNotesTable : Table("ignored_notes") {
    val kind = varchar("kind", 20)
    val key = varchar("key", 500)
    val note = text("note")

    override val primaryKey = PrimaryKey(kind, key)
}
