package no.nav.platforce.tool.targets

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun upgradeSecurityRoutes(scanner: UpgradeSecurityScanner) =
    listOf(false, true).map { start ->
        ("/internal/api/target-versions/upgrade-security" + if (start) "/start" else "") bind Method.GET to { request ->
            val context = request.userContext()
            try {
                val state = context.targetVersionsStore.get()
                require(request.query("kind") == "plugin" || request.query("key") !in state.ignoredDependencies) {
                    "Dependency is ignored"
                }
                val snapshot =
                    scanner.snapshot(
                        context.username,
                        state,
                        request.query("kind") ?: "dependency",
                        request.query("key").orEmpty(),
                        request.query("currentVersion").orEmpty(),
                        start,
                    )
                val status =
                    when (snapshot.status) {
                        "RUNNING" -> Status.ACCEPTED
                        "FAILED" -> Status.INTERNAL_SERVER_ERROR
                        else -> Status.OK
                    }
                Response(status).header("Content-Type", "application/json").body(Gson().toJson(snapshot))
            } catch (failure: IllegalArgumentException) {
                KotlinLogging.logger { }.warn { "Invalid upgrade scan request: ${failure.message}" }
                Response(
                    Status.BAD_REQUEST,
                ).header("Content-Type", "application/json").body(Gson().toJson(mapOf("error" to failure.message)))
            }
        }
    }
