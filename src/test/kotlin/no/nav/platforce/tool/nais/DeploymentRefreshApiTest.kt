package no.nav.platforce.tool.nais

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
import java.sql.SQLException
import java.time.Instant
import java.util.Base64

class DeploymentRefreshApiTest {
    private fun request(
        method: Method,
        user: String = "alice",
    ): Request {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"preferred_username":"$user"}""".toByteArray())
        return Request(method, "/internal/api/deployments/refresh-marker").header("Authorization", "Bearer header.$payload.signature")
    }

    @Test
    fun `marker survives handler recreation advances on demand and is isolated by authenticated user`() {
        val persisted = mutableMapOf<String, Instant>()
        var time = Instant.parse("2026-11-09T13:00:00Z")

        fun handler() =
            routes(
                *deploymentRefreshRoutes(
                    read = { persisted[it] },
                    save = { user, after -> if (after == null) persisted.remove(user) else persisted[user] = after },
                    now = { time },
                    username = { extractPreferredUsername(it.header("Authorization")) },
                ).toTypedArray(),
            ).withUserCacheControl()
        val first = handler()
        assertTrue(JsonParser.parseString(first(request(Method.GET)).bodyString()).asJsonObject["after"].isJsonNull)
        val marked = first(request(Method.POST).body("""{"after":"2099-01-01T00:00:00Z"}"""))
        assertEquals(Status.OK, marked.status)
        assertEquals(time.toString(), JsonParser.parseString(marked.bodyString()).asJsonObject["after"].asString)
        assertEquals("no-store", marked.header("Cache-Control"))
        val recreated = handler()
        assertEquals(marked.bodyString(), recreated(request(Method.GET)).bodyString())
        assertTrue(JsonParser.parseString(recreated(request(Method.GET, "bob")).bodyString()).asJsonObject["after"].isJsonNull)
        time = time.plusSeconds(60)
        recreated(request(Method.POST, "bob"))
        time = time.plusSeconds(60)
        recreated(request(Method.POST))
        assertEquals(time, persisted["alice"])
        assertEquals(time.minusSeconds(60), persisted["bob"])
        assertEquals(Status.OK, recreated(request(Method.DELETE)).status)
        assertTrue(JsonParser.parseString(handler()(request(Method.GET)).bodyString()).asJsonObject["after"].isJsonNull)
        assertEquals(time.minusSeconds(60), persisted["bob"])
    }

    @Test
    fun `database failures return explicit errors for reads writes and resets`() {
        val handler =
            routes(
                *deploymentRefreshRoutes(
                    read = { throw SQLException("Synthetic database failure") },
                    save = { _, _ -> throw SQLException("Synthetic database failure") },
                    username = { extractPreferredUsername(it.header("Authorization")) },
                ).toTypedArray(),
            ).withUserCacheControl()
        listOf(Method.GET, Method.POST, Method.DELETE).forEach { method ->
            val response = handler(request(method))
            assertEquals(Status.SERVICE_UNAVAILABLE, response.status)
            assertTrue(
                JsonParser
                    .parseString(response.bodyString())
                    .asJsonObject["error"]
                    .asString
                    .contains("refresh marker"),
            )
            assertEquals("no-store", response.header("Cache-Control"))
        }
    }

    @Test
    fun `missing authentication is rejected before marker access`() {
        val handler =
            routes(
                *deploymentRefreshRoutes(
                    read = { error("Must not read without a user") },
                    save = { _, _ -> error("Must not save without a user") },
                ).toTypedArray(),
            )
        assertThrows(RuntimeException::class.java) {
            handler(Request(Method.POST, "/internal/api/deployments/refresh-marker"))
        }
    }
}
