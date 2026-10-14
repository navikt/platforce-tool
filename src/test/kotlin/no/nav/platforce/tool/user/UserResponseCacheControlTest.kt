package no.nav.platforce.tool.user

import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UserResponseCacheControlTest {
    private val handler: HttpHandler = { Response(Status.OK).body("user-specific result") }

    @Test
    fun `user API responses cannot be stored in browser or intermediary caches`() {
        val app = handler.withUserCacheControl()
        // Cache isolation also applies to HTTP caches, not just the application's in-memory maps.
        listOf(
            "/internal/api/dependency-scan",
            "/internal/api/dependency-scan/progress",
            "/internal/api/target-resolution/security",
            "/internal/repos/view",
            "/internal/selectedTeam",
        ).forEach { path ->
            val response = app(Request(Method.GET, path))
            assertEquals("no-store", response.header("Cache-Control"))
            assertEquals("user-specific result", response.bodyString())
        }
    }

    @Test
    fun `failed user responses are not cached and operational routes retain their existing headers`() {
        val failing: HttpHandler = { Response(Status.INTERNAL_SERVER_ERROR) }
        assertEquals(
            "no-store",
            failing.withUserCacheControl()(Request(Method.POST, "/internal/api/target-versions/update")).header("Cache-Control"),
        )
        assertNull(handler.withUserCacheControl()(Request(Method.GET, "/internal/isAlive")).header("Cache-Control"))
        assertNull(handler.withUserCacheControl()(Request(Method.GET, "/internal/gui/script.js")).header("Cache-Control"))
    }
}
