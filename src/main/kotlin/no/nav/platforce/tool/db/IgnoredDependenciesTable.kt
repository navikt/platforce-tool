package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.Table

object IgnoredDependenciesTable : Table("ignored_dependencies") {
    val userId = varchar("user_id", 100)
    val dependency = varchar("dependency", 500)

    override val primaryKey = PrimaryKey(userId, dependency)
}
