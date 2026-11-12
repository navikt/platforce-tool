package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.Table

object AutoRepositoriesTable : Table("auto_repositories") {
    val repository = varchar("repository", 300)

    override val primaryKey = PrimaryKey(repository)
}
