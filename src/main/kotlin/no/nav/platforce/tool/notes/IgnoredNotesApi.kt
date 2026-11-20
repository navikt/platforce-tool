package no.nav.platforce.tool.notes

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
import java.sql.SQLException
import java.util.Locale

enum class IgnoredNoteKind {
    REPOSITORY,
    DEPENDENCY,
}

data class IgnoredNote(
    val kind: IgnoredNoteKind,
    val key: String,
    val note: String,
)

internal fun parseIgnoredNote(body: String): IgnoredNote {
    val parsed =
        try {
            JsonParser.parseString(body)
        } catch (failure: JsonParseException) {
            throw IllegalArgumentException("Expected JSON with kind, key and note strings", failure)
        }
    require(parsed.isJsonObject) { "Expected JSON with kind, key and note strings" }
    val fields =
        listOf("kind", "key", "note").map { field ->
            val value = parsed.asJsonObject.get(field)
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$field must be a string" }
            value.asString
        }
    val kind = IgnoredNoteKind.entries.find { it.name == fields[0] }
    require(kind != null) { "kind must be REPOSITORY or DEPENDENCY" }
    val key = fields[1].trim()
    val pattern =
        when (kind) {
            IgnoredNoteKind.REPOSITORY -> Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
            IgnoredNoteKind.DEPENDENCY -> Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
        }
    require(key.length <= 500 && pattern.matches(key)) { "Invalid ignored item key" }
    require(fields[2].length <= 10000) { "Notes must not exceed 10000 characters" }
    return IgnoredNote(kind, if (kind == IgnoredNoteKind.REPOSITORY) key.lowercase(Locale.ROOT) else key, fields[2])
}

fun ignoredNotesRoutes(
    read: () -> List<IgnoredNote> = PostgresDatabase::getIgnoredNotes,
    save: (IgnoredNote) -> Unit = PostgresDatabase::saveIgnoredNote,
    authenticate: (Request) -> Unit = {
        it.userContext()
        Unit
    },
) = listOf(Method.GET, Method.POST).map { method ->
    "/internal/api/ignored-notes" bind method to handler@{ request ->
        authenticate(request)

        fun json(
            value: Any,
            status: Status = Status.OK,
        ) = Response(status).header("Content-Type", "application/json").body(Gson().toJson(value))

        val note =
            if (method == Method.POST) {
                try {
                    parseIgnoredNote(request.bodyString())
                } catch (failure: IllegalArgumentException) {
                    return@handler json(mapOf("error" to failure.message), Status.BAD_REQUEST)
                }
            } else {
                null
            }
        try {
            if (note == null) {
                json(read().sortedWith(compareBy({ it.kind.name }, { it.key })))
            } else {
                save(note)
                json(note)
            }
        } catch (failure: SQLException) {
            KotlinLogging.logger { }.error(failure) { "Could not access global ignored notes" }
            json(mapOf("error" to "Could not access ignored notes"), Status.SERVICE_UNAVAILABLE)
        }
    }
}
