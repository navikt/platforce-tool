package no.nav.platforce.tool.ignore

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.platforce.tool.db.PostgresDatabase
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind

fun ignoredRepositoriesRoutes() =
    listOf(
        "/internal/api/ignored-repositories" bind Method.GET to { request ->
            val context = request.userContext()
            Response(Status.OK)
                .header("Content-Type", "application/json")
                .body(Gson().toJson(context.ignoredRepositoriesStore.get()))
        },
        "/internal/api/ignored-repositories/update" bind Method.POST to { request ->
            val context = request.userContext()

            val body = request.bodyString()

            val parsed =
                Gson().fromJson(
                    body,
                    IgnoredRepositoriesState::class.java,
                )

            context.ignoredRepositoriesStore.replace(
                team = null,
                repositories = parsed.repositories,
            )

            Response(Status.OK).body("updated")
        },
        "/internal/api/ignored-dependencies/update" bind Method.POST to { request ->
            val context = request.userContext()
            try {
                val dependencies = parseIgnoredDependencies(request.bodyString())
                PostgresDatabase.replaceIgnoredDependencies(context.username, dependencies)
                Response(Status.OK).body("updated")
            } catch (failure: IllegalArgumentException) {
                KotlinLogging.logger { }.warn { "Invalid ignored dependencies: ${failure.message}" }
                Response(Status.BAD_REQUEST).body(failure.message.orEmpty())
            }
        },
    )

internal fun parseIgnoredDependencies(body: String): Set<String> {
    val parsed =
        try {
            JsonParser.parseString(body)
        } catch (failure: JsonParseException) {
            throw IllegalArgumentException("Expected JSON with a dependencies array", failure)
        }
    require(parsed.isJsonObject) { "Expected JSON with a dependencies array" }
    val entries = parsed.asJsonObject.get("dependencies")
    require(entries != null && entries.isJsonArray) { "Expected a dependencies array" }
    require(entries.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) {
        "Dependencies must be group:artifact strings"
    }
    val dependencies = entries.asJsonArray.map { it.asString.trim() }.toSet()
    require(dependencies.all { Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+").matches(it) && it.length <= 500 }) {
        "Dependencies must use group:artifact coordinates"
    }
    return dependencies
}
