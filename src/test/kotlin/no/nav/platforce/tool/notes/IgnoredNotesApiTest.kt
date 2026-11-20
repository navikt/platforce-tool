package no.nav.platforce.tool.notes

import com.google.gson.Gson
import com.google.gson.JsonParser
import no.nav.platforce.tool.user.withUserCacheControl
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.routing.routes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

class IgnoredNotesApiTest {
    private val path = "/internal/api/ignored-notes"

    @Test
    fun `global notes survive handler recreation and remain available without an ignored parent`() {
        val persisted = mutableMapOf<Pair<IgnoredNoteKind, String>, IgnoredNote>()
        val callers = mutableListOf<String>()

        fun handler() =
            routes(
                *ignoredNotesRoutes(
                    read = { persisted.values.toList() },
                    save = {
                        val id = it.kind to it.key
                        if (it.note.isBlank()) persisted.remove(id) else persisted[id] = it
                        Unit
                    },
                    authenticate = { callers.add(requireNotNull(it.header("X-Test-User"))) },
                ).toTypedArray(),
            ).withUserCacheControl()

        fun request(
            method: Method,
            user: String = "alice",
        ) = Request(method, path).header("X-Test-User", user)

        val first = handler()
        assertEquals("[]", first(request(Method.GET)).bodyString())
        val repository = """{"kind":"REPOSITORY","key":"Example/Repo","note":"Keep this repository ignored"}"""
        val response = first(request(Method.POST).body(repository))
        assertEquals(Status.OK, response.status)
        assertEquals("no-store", response.header("Cache-Control"))
        assertEquals("example/repo", JsonParser.parseString(response.bodyString()).asJsonObject["key"].asString)
        val recreated = handler()
        val read = recreated(request(Method.GET, "bob"))
        assertTrue(read.bodyString().contains("Keep this repository ignored"))
        assertEquals("no-store", read.header("Cache-Control"))
        val dependency = """{"kind":"DEPENDENCY","key":"Example:Library","note":"Pinned for compatibility"}"""
        assertEquals(Status.OK, recreated(request(Method.POST, "bob").body(dependency)).status)
        assertEquals(2, persisted.size)
        assertEquals(
            Status.OK,
            recreated(
                request(Method.POST, "bob").body("""{"kind":"REPOSITORY","key":"example/repo","note":"Updated globally"}"""),
            ).status,
        )
        assertTrue(handler()(request(Method.GET)).bodyString().contains("Updated globally"))
        assertEquals(
            Status.OK,
            recreated(request(Method.POST).body("""{"kind":"REPOSITORY","key":"EXAMPLE/REPO","note":"  "}""")).status,
        )
        assertEquals(1, persisted.size)
        assertEquals("Example:Library", persisted.values.single().key)
        assertTrue(callers.containsAll(listOf("alice", "bob")))
        assertThrows(IllegalArgumentException::class.java) { first(Request(Method.GET, path)) }
    }

    @Test
    fun `invalid notes are rejected before persistence and exact length limits are enforced`() {
        val handler =
            routes(
                *ignoredNotesRoutes(
                    read = { emptyList() },
                    save = { error("Invalid notes must not reach persistence") },
                    authenticate = {},
                ).toTypedArray(),
            )
        val invalid =
            listOf(
                "{",
                "null",
                "[]",
                "{}",
                """{"kind":"OTHER","key":"example/repo","note":"text"}""",
                """{"kind":"REPOSITORY","key":"example/repo"}""",
                """{"kind":"REPOSITORY","key":null,"note":"text"}""",
                """{"kind":"REPOSITORY","key":"example/repo","note":false}""",
                """{"kind":"REPOSITORY","key":"example:repo","note":"text"}""",
                """{"kind":"DEPENDENCY","key":"example/library","note":"text"}""",
                """{"kind":"DEPENDENCY","key":"<script>:library","note":"text"}""",
                Gson().toJson(IgnoredNote(IgnoredNoteKind.REPOSITORY, "owner/" + "r".repeat(495), "text")),
                Gson().toJson(IgnoredNote(IgnoredNoteKind.DEPENDENCY, "example:library", "n".repeat(10001))),
            )
        invalid.forEach { body ->
            assertEquals(Status.BAD_REQUEST, handler(Request(Method.POST, path).body(body)).status, body.take(100))
        }
        val maximum = IgnoredNote(IgnoredNoteKind.REPOSITORY, "owner/" + "r".repeat(494), "n".repeat(10000))
        assertEquals(maximum, parseIgnoredNote(Gson().toJson(maximum)))
        assertEquals("", parseIgnoredNote("""{"kind":"DEPENDENCY","key":" example:library ","note":""}""").note)
    }

    @Test
    fun `database failures are explicit and never report a successful read or write`() {
        val handler =
            routes(
                *ignoredNotesRoutes(
                    read = { throw SQLException("Unavailable") },
                    save = { throw SQLException("Unavailable") },
                    authenticate = {},
                ).toTypedArray(),
            )
        val requests =
            listOf(
                Request(Method.GET, path),
                Request(Method.POST, path).body("""{"kind":"REPOSITORY","key":"example/repo","note":"text"}"""),
            )
        requests.forEach {
            val response = handler(it)
            assertEquals(Status.SERVICE_UNAVAILABLE, response.status)
            assertTrue(response.bodyString().contains("Could not access ignored notes"))
        }
    }
}
