package no.nav.platforce.tool.auto

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.user.extractPreferredUsername
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

fun autoProcessRoutes(
    service: AutoProcessService,
    username: (Request) -> String = { extractPreferredUsername(it.header("Authorization")) },
) = listOf(
    "/internal/triggerAuto" bind Method.GET to { request ->
        val user = username(request)
        try {
            val run = service.start(user)
            if (run == null) {
                Response(Status.CONFLICT).body("Auto process is already ongoing")
            } else {
                Response(Status.OK).body("process started")
            }
        } catch (failure: SQLException) {
            autoProcessFailure(failure)
        } catch (failure: RejectedExecutionException) {
            autoProcessFailure(failure)
        }
    },
    "/internal/api/auto/status" bind Method.GET to { request ->
        username(request)
        val before = request.query("before")
        if (before != null && (before.toLongOrNull() == null || before.toLong() <= 0)) {
            Response(Status.BAD_REQUEST).body("Expected a positive log cursor")
        } else {
            try {
                Response(Status.OK)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(service.snapshot(before?.toLong())))
            } catch (failure: SQLException) {
                autoProcessFailure(failure)
            }
        }
    },
    "/internal/api/auto/runs/{runId}" bind Method.DELETE to { request ->
        username(request)
        val runId = request.path("runId")!!
        val valid =
            try {
                UUID.fromString(runId).toString().equals(runId, ignoreCase = true)
            } catch (failure: IllegalArgumentException) {
                false
            }

        fun error(
            status: Status,
            message: String,
        ) = Response(status).header("Content-Type", "application/json").body(Gson().toJson(mapOf("error" to message)))
        if (!valid) {
            error(Status.BAD_REQUEST, "Expected an Auto run UUID")
        } else {
            try {
                when (service.deleteRun(runId.lowercase())) {
                    AutoRunDeletion.DELETED -> Response(Status.NO_CONTENT)
                    AutoRunDeletion.NOT_FOUND -> error(Status.NOT_FOUND, "Auto run was not found")
                    AutoRunDeletion.PROTECTED -> error(Status.CONFLICT, "The latest or running Auto process cannot be deleted")
                }
            } catch (failure: SQLException) {
                autoProcessFailure(failure)
            }
        }
    },
)

private fun autoProcessFailure(failure: Exception): Response {
    KotlinLogging.logger { }.error(failure) { "Could not access Auto process" }
    return Response(Status.SERVICE_UNAVAILABLE)
        .header("Content-Type", "application/json")
        .body("""{"error":"Could not access Auto process. Reload its status before retrying."}""")
}
