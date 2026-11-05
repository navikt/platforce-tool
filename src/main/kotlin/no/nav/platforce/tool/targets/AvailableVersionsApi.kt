package no.nav.platforce.tool.targets

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.io.IOException

fun availableVersionsRoutes(service: AvailableVersionsService) =
    listOf(
        "/internal/api/target-versions/available" bind Method.GET to { request ->
            val context = request.userContext()
            val log = KotlinLogging.logger { }
            try {
                require(
                    request.query("kind") == "plugin" ||
                        request.query("key") !in context.targetVersionsStore.get().ignoredDependencies,
                ) { "Dependency is ignored" }
                val result =
                    service.available(
                        context.username,
                        request.query("key").orEmpty(),
                        request.query("currentVersion").orEmpty(),
                        request.query("kind") ?: "dependency",
                    )
                Response(Status.OK).header("Content-Type", "application/json").body(Gson().toJson(result))
            } catch (failure: IllegalArgumentException) {
                log.warn { "Invalid version lookup request: ${failure.message}" }
                Response(
                    Status.BAD_REQUEST,
                ).header("Content-Type", "application/json").body(Gson().toJson(mapOf("error" to failure.message)))
            } catch (failure: IOException) {
                log.warn(failure) { "Published version lookup failed" }
                Response(
                    Status.BAD_GATEWAY,
                ).header("Content-Type", "application/json").body(Gson().toJson(mapOf("error" to failure.message)))
            }
        },
    )
