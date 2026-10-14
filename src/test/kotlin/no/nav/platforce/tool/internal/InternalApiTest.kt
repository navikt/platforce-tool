package no.nav.platforce.tool.internal

import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class InternalApiTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `extracted probes retain their paths and responses without initializing the application`() {
        val handler = routes(*internalRoutes(ResolutionDebugFiles(directory)).toTypedArray())
        // Probe handlers do not require GitHub secrets, NAIS authentication or database initialization.
        assertEquals(Status.OK, handler(Request(Method.GET, "/internal/isAlive")).status)
        assertEquals(Status.OK, handler(Request(Method.GET, "/internal/isReady")).status)
        assertEquals("Hello!", handler(Request(Method.GET, "/internal/hello")).bodyString())
    }

    @Test
    fun `every split GUI script is served through the existing static route`() {
        val handler = routes("/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")))
        val page = handler(Request(Method.GET, "/internal/gui/index.html"))
        assertEquals(Status.OK, page.status)
        val scripts = Regex("""<script\s+src="([^"]+)"""").findAll(page.bodyString()).map { it.groupValues[1] }.toList()
        assertTrue(scripts.isNotEmpty())
        scripts.forEach { url ->
            val script = handler(Request(Method.GET, url))
            assertEquals(Status.OK, script.status, url)
            assertTrue(script.bodyString().isNotBlank(), url)
        }
    }
}
