package no.nav.platforce.tool.nais

import com.google.gson.JsonObject
import mu.KotlinLogging
import no.nav.platforce.tool.db.PostgresDatabase
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit

fun deploymentRefreshRoutes(
    read: (String) -> Instant? = PostgresDatabase::getDeploymentRefreshMarker,
    save: (String, Instant?) -> Unit = PostgresDatabase::saveDeploymentRefreshMarker,
    now: () -> Instant = { Instant.now().truncatedTo(ChronoUnit.MILLIS) },
    username: (Request) -> String = { it.userContext().username },
) = listOf(Method.GET, Method.POST, Method.DELETE).map { method ->
    "/internal/api/deployments/refresh-marker" bind method to { request ->
        val userId = username(request)
        try {
            val after =
                when (method) {
                    Method.POST -> now().also { save(userId, it) }
                    Method.DELETE -> null.also { save(userId, null) }
                    else -> read(userId)
                }
            deploymentRefreshMarkerResponse(after)
        } catch (failure: SQLException) {
            KotlinLogging.logger { }.error(failure) { "Could not access deployment refresh marker" }
            Response(Status.SERVICE_UNAVAILABLE)
                .header("Content-Type", "application/json")
                .body("""{"error":"Could not access deployment refresh marker"}""")
        }
    }
}

internal fun deploymentRefreshMarkerResponse(after: Instant?): Response =
    Response(Status.OK)
        .header("Content-Type", "application/json")
        .body(JsonObject().apply { addProperty("after", after?.toString()) }.toString())
