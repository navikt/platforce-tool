package no.nav.platforce.tool.auto

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.platforce.tool.db.PostgresDatabase
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.io.IOException
import java.sql.SQLException
import java.util.Locale

data class AutoRepositorySetting(
    val repository: String,
    val enabled: Boolean,
)

internal fun parseAutoRepositorySetting(body: String): AutoRepositorySetting {
    val element = JsonParser.parseString(body)
    require(element.isJsonObject) { "Expected a JSON object" }
    val repository = element.asJsonObject["repository"]
    val enabled = element.asJsonObject["enabled"]
    require(repository != null && repository.isJsonPrimitive && repository.asJsonPrimitive.isString) {
        "Expected repository as owner/name"
    }
    val name = repository.asString
    require(name.length <= 300 && name.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+"))) {
        "Expected repository as owner/name"
    }
    require(enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean) { "Expected enabled as a boolean" }
    return AutoRepositorySetting(name.lowercase(Locale.ROOT), enabled.asBoolean)
}

fun autoRepositoriesRoutes(
    installedRepositories: () -> List<String>,
    read: () -> Set<String> = PostgresDatabase::getAutoRepositories,
    save: (String, Boolean) -> Unit = PostgresDatabase::saveAutoRepository,
    authenticate: (Request) -> Unit = {
        it.userContext()
        Unit
    },
) = listOf(Method.GET, Method.POST).map { method ->
    "/internal/api/auto-repositories" bind method to handler@{ request ->
        authenticate(request)

        fun json(
            value: Any,
            status: Status = Status.OK,
        ) = Response(status).header("Content-Type", "application/json").body(Gson().toJson(value))

        val setting =
            if (method == Method.POST) {
                try {
                    parseAutoRepositorySetting(request.bodyString())
                } catch (failure: JsonParseException) {
                    return@handler json(mapOf("error" to "Invalid JSON"), Status.BAD_REQUEST)
                } catch (failure: IllegalArgumentException) {
                    return@handler json(mapOf("error" to failure.message), Status.BAD_REQUEST)
                }
            } else {
                null
            }

        try {
            val accessible = installedRepositories().map { it.lowercase(Locale.ROOT) }.toSet()
            if (setting == null) {
                json(mapOf("repositories" to read().filter { it in accessible }.sorted()))
            } else if (setting.repository !in accessible) {
                json(mapOf("error" to "GitHub App does not have access to this repository"), Status.FORBIDDEN)
            } else {
                save(setting.repository, setting.enabled)
                json(setting)
            }
        } catch (failure: SQLException) {
            KotlinLogging.logger { }.error(failure) { "Could not access Auto repository settings" }
            json(mapOf("error" to "Could not access Auto repository settings"), Status.SERVICE_UNAVAILABLE)
        } catch (failure: IOException) {
            autoRepositoryAccessFailure(failure)
        } catch (failure: IllegalStateException) {
            autoRepositoryAccessFailure(failure)
        } catch (failure: JsonParseException) {
            autoRepositoryAccessFailure(failure)
        }
    }
}

private fun autoRepositoryAccessFailure(failure: Exception): Response {
    KotlinLogging.logger { }.warn(failure) { "Could not verify GitHub App repository access for Auto settings" }
    return Response(Status.BAD_GATEWAY)
        .header("Content-Type", "application/json")
        .body("""{"error":"Could not verify GitHub App repository access. Retry loading Auto settings."}""")
}
