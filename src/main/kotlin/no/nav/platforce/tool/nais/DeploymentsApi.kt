package no.nav.platforce.tool.nais

import com.google.gson.Gson
import com.google.gson.JsonParseException
import mu.KotlinLogging
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.io.IOException

fun deploymentsRoutes(client: NaisConsoleClient) =
    listOf(
        "/internal/api/deployments" bind Method.GET to { request ->
            val context = request.userContext()
            try {
                Response(Status.OK)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(client.getRunningDeployments(context.username)))
            } catch (failure: IOException) {
                deploymentsFailure(failure)
            } catch (failure: IllegalStateException) {
                deploymentsFailure(failure)
            } catch (failure: JsonParseException) {
                deploymentsFailure(failure)
            }
        },
    )

private fun deploymentsFailure(failure: Exception): Response {
    KotlinLogging.logger { }.warn(failure) { "Could not load running deployments from Nais Console" }
    return Response(Status.BAD_GATEWAY)
        .header("Content-Type", "application/json")
        .body(Gson().toJson(mapOf("error" to "Could not load running deployments from Nais Console")))
}
