package no.nav.platforce.tool.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object DeploymentRefreshMarkerTable : Table("deployment_refresh_markers") {
    val userId = varchar("user_id", 100)
    val after = timestamp("after")

    override val primaryKey = PrimaryKey(userId)
}
