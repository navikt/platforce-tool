package no.nav.platforce.tool.auto

import com.google.gson.JsonParser
import no.nav.platforce.tool.user.extractPreferredUsername
import no.nav.platforce.tool.user.withUserCacheControl
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.routing.routes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.SQLException
import java.util.Base64

class AutoRepositoriesApiTest {
    private val path = "/internal/api/auto-repositories"

    private fun request(
        method: Method,
        user: String = "alice",
    ): Request {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"preferred_username":"$user"}""".toByteArray())
        return Request(method, path).header("Authorization", "Bearer header.$payload.signature")
    }

    @Test
    fun `opt ins are shared across users survive handler recreation and update independently`() {
        val persisted = mutableSetOf<String>()

        fun handler() =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { listOf("Example/Repo", "example/other") },
                    read = { persisted.toSet() },
                    save = { repository, enabled ->
                        if (enabled) persisted.add(repository) else persisted.remove(repository)
                        Unit
                    },
                    authenticate = {
                        extractPreferredUsername(it.header("Authorization"))
                        Unit
                    },
                ).toTypedArray(),
            ).withUserCacheControl()

        val first = handler()
        assertEquals("""{"repositories":[]}""", first(request(Method.GET)).bodyString())
        val write = first(request(Method.POST).body("""{"repository":"Example/Repo","enabled":true}"""))
        assertEquals(Status.OK, write.status)
        assertEquals("no-store", write.header("Cache-Control"))
        assertEquals("example/repo", JsonParser.parseString(write.bodyString()).asJsonObject["repository"].asString)
        val recreated = handler()
        assertEquals("""{"repositories":["example/repo"]}""", recreated(request(Method.GET, "bob")).bodyString())
        recreated(request(Method.POST, "bob").body("""{"repository":"example/other","enabled":true}"""))
        recreated(request(Method.POST).body("""{"repository":"example/repo","enabled":true}"""))
        assertEquals(setOf("example/repo", "example/other"), persisted)
        recreated(request(Method.POST, "bob").body("""{"repository":"example/repo","enabled":false}"""))
        recreated(request(Method.POST).body("""{"repository":"example/repo","enabled":false}"""))
        assertEquals("""{"repositories":["example/other"]}""", handler()(request(Method.GET)).bodyString())
    }

    @Test
    fun `running Auto rejects opting in and out for every user but permits reads and later changes`() {
        val persisted = mutableSetOf("example/repo")
        var running = true
        val handler =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { listOf("example/repo", "example/other") },
                    read = { persisted.toSet() },
                    save = { repository, enabled ->
                        if (running) throw AutoSettingsLocked()
                        if (enabled) persisted.add(repository) else persisted.remove(repository)
                        Unit
                    },
                    authenticate = {},
                ).toTypedArray(),
            ).withUserCacheControl()
        listOf("alice", "bob").forEach { user ->
            listOf("example/repo" to false, "example/other" to true).forEach { (repository, enabled) ->
                val response = handler(request(Method.POST, user).body("""{"repository":"$repository","enabled":$enabled}"""))
                assertEquals(Status.CONFLICT, response.status)
                assertEquals("no-store", response.header("Cache-Control"))
                assertTrue(response.bodyString().contains("cannot be changed"))
                assertEquals(setOf("example/repo"), persisted)
            }
        }
        assertEquals(Status.OK, handler(request(Method.GET)).status)
        running = false
        assertEquals(Status.OK, handler(request(Method.POST).body("""{"repository":"example/repo","enabled":false}""")).status)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `malformed payloads never reach persistence`() {
        val handler =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { error("Malformed payloads must be rejected before checking access") },
                    read = { emptySet() },
                    save = { _, _ -> error("Invalid settings must not be saved") },
                    authenticate = {
                        extractPreferredUsername(it.header("Authorization"))
                        Unit
                    },
                ).toTypedArray(),
            )
        listOf(
            "",
            "{",
            "null",
            "[]",
            "{}",
            """{"repository":null,"enabled":true}""",
            """{"repository":123,"enabled":true}""",
            """{"repository":"repo","enabled":true}""",
            """{"repository":"example/repo/extra","enabled":true}""",
            """{"repository":"example/repo","enabled":"true"}""",
            """{"repository":"example/repo","enabled":1}""",
            """{"repository":"example/repo"}""",
            """{"repository":"example/${"x".repeat(300)}","enabled":true}""",
        ).forEach { body ->
            val response = handler(request(Method.POST).body(body))
            assertEquals(Status.BAD_REQUEST, response.status, body)
            assertTrue(JsonParser.parseString(response.bodyString()).asJsonObject.has("error"))
        }
    }

    @Test
    fun `database failures are explicit for reads and writes`() {
        val handler =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { listOf("example/repo") },
                    read = { throw SQLException("Synthetic database failure") },
                    save = { _, _ -> throw SQLException("Synthetic database failure") },
                    authenticate = {
                        extractPreferredUsername(it.header("Authorization"))
                        Unit
                    },
                ).toTypedArray(),
            ).withUserCacheControl()
        listOf(Method.GET, Method.POST).forEach { method ->
            val response = handler(request(method).body("""{"repository":"example/repo","enabled":true}"""))
            assertEquals(Status.SERVICE_UNAVAILABLE, response.status)
            assertEquals("no-store", response.header("Cache-Control"))
            assertTrue(JsonParser.parseString(response.bodyString()).asJsonObject.has("error"))
        }
    }

    @Test
    fun `authentication is required even though settings are global`() {
        val handler =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { error("Must not check access without authentication") },
                    read = { error("Must not read without authentication") },
                    save = { _, _ -> error("Must not write without authentication") },
                ).toTypedArray(),
            )
        listOf(Method.GET, Method.POST).forEach { method ->
            assertThrows(RuntimeException::class.java) { handler(Request(method, path)) }
        }
    }

    @Test
    fun `Auto only lists accessible repositories and refuses changes after app access is revoked`() {
        val persisted = mutableSetOf("example/repo", "example/other")
        var installed = listOf("Example/Repo")
        val handler =
            routes(
                *autoRepositoriesRoutes(
                    installedRepositories = { installed },
                    read = { persisted.toSet() },
                    save = { _, _ -> error("Inaccessible repository settings must not be changed") },
                    authenticate = {},
                ).toTypedArray(),
            ).withUserCacheControl()

        assertEquals("""{"repositories":["example/repo"]}""", handler(request(Method.GET)).bodyString())
        listOf(true, false).forEach { enabled ->
            val response = handler(request(Method.POST).body("""{"repository":"example/other","enabled":$enabled}"""))
            assertEquals(Status.FORBIDDEN, response.status)
            assertEquals("no-store", response.header("Cache-Control"))
            assertTrue(
                JsonParser
                    .parseString(response.bodyString())
                    .asJsonObject["error"]
                    .asString
                    .contains("access"),
            )
        }
        installed = emptyList()
        assertEquals("""{"repositories":[]}""", handler(request(Method.GET)).bodyString())
        assertEquals(Status.FORBIDDEN, handler(request(Method.POST).body("""{"repository":"example/repo","enabled":true}""")).status)
        assertEquals(setOf("example/repo", "example/other"), persisted)
    }

    @Test
    fun `GitHub access lookup failures are explicit and never change persisted settings`() {
        listOf(IOException("Offline"), IllegalStateException("GitHub unavailable")).forEach { failure ->
            val handler =
                routes(
                    *autoRepositoriesRoutes(
                        installedRepositories = { throw failure },
                        read = { error("Must not read settings without verifying access") },
                        save = { _, _ -> error("Must not save settings without verifying access") },
                        authenticate = {},
                    ).toTypedArray(),
                )
            listOf(Method.GET, Method.POST).forEach { method ->
                val response = handler(request(method).body("""{"repository":"example/repo","enabled":true}"""))
                assertEquals(Status.BAD_GATEWAY, response.status)
                assertTrue(
                    JsonParser
                        .parseString(response.bodyString())
                        .asJsonObject["error"]
                        .asString
                        .contains("verify"),
                )
            }
        }
    }
}
