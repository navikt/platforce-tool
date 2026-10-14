package no.nav.platforce.tool.internal

import no.nav.platforce.tool.db.PostgresDatabase
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind

fun databaseAdminRoutes() =
    listOf(
        "/internal/add-transient" bind Method.GET to { _ ->
            try {
                PostgresDatabase.expandTargetVersionsWithTransient()

                Response(Status.OK)
                    .header("Content-Type", "text/plain")
                    .body("target_versions.transient added")
            } catch (e: Exception) {
                Response(Status.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "text/plain")
                    .body(
                        "Failed to add target_versions.transient: " +
                            (e.message ?: e.javaClass.simpleName),
                    )
            }
        },
        "/internal/clearDb" bind Method.GET to clearDbHandler,
        "/internal/initDb" bind Method.GET to initDbHandler,
    )

private val clearDbHandler: HttpHandler = {
    PostgresDatabase.createTargetVersionsTable(true)
    PostgresDatabase.createRepositoryNotesTable(true)
    PostgresDatabase.createIgnoredRepositoriesTable(true)
    Response(OK).body("Tables recreated")
}

private val initDbHandler: HttpHandler = {
    // PostgresDatabase.createTargetVersionsTable(false)
    // PostgresDatabase.createRepositoryNotesTable(false)
    PostgresDatabase.createIgnoredRepositoriesTable(false)
    Response(OK).body("Tables created")
}
