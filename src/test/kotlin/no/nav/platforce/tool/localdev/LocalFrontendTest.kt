package no.nav.platforce.tool.localdev

import com.google.gson.JsonParser
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

class LocalFrontendTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `BOM targets start untracked can be saved and accept legacy payloads`() {
        val handler = LocalFrontendApi(directory).handler

        fun scan() =
            JsonParser
                .parseString(handler(Request(Method.GET, "/internal/api/dependency-scan")).bodyString())
                .asJsonArray
                .first()
                .asJsonObject
        val initial = scan()
        assertEquals(
            LocalFrontendFixtures.PLATFORM_BOM,
            initial["untrackedPlatformBoms"]
                .asJsonArray
                .single()
                .asJsonObject["key"]
                .asString,
        )
        val targets =
            JsonParser
                .parseString(handler(Request(Method.GET, "/internal/api/target-versions")).bodyString())
                .asJsonObject
        assertEquals(0, targets["platformBoms"].asJsonObject.size())
        targets["platformBoms"].asJsonObject.addProperty(LocalFrontendFixtures.PLATFORM_BOM, "3.6.0")
        assertEquals(Status.OK, handler(Request(Method.POST, "/internal/api/target-versions/update").body(targets.toString())).status)
        val tracked = scan()
        assertEquals(0, tracked["untrackedPlatformBoms"].asJsonArray.size())
        val bom = tracked["findings"].asJsonArray.single { it.asJsonObject["kind"].asString == "PLATFORM_BOM" }.asJsonObject
        assertEquals("UPDATE", bom["status"].asString)
        assertEquals("3.6.0", bom["targetVersion"].asString)

        targets.remove("platformBoms")
        assertEquals(Status.OK, handler(Request(Method.POST, "/internal/api/target-versions/update").body(targets.toString())).status)
        assertEquals(1, scan()["untrackedPlatformBoms"].asJsonArray.size())
        targets.addProperty("platformBoms", "invalid")
        assertEquals(
            Status.BAD_REQUEST,
            handler(Request(Method.POST, "/internal/api/target-versions/update").body(targets.toString())).status,
        )
    }

    @Test
    fun `local server serves GUI assets and every startup API without credentials or database`() {
        val server = startLocalFrontend(0, Path.of("src/main/resources/gui").toAbsolutePath())
        try {
            assertTrue(server.address.address.isLoopbackAddress)
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.address.port}"

            fun get(path: String): HttpResponse<String> =
                client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString())

            val page = get("/internal/gui")
            assertEquals(200, page.statusCode())
            assertTrue(page.body().contains("id=\"container\""))
            val scripts = Regex("""<script\s+src="([^"]+)"""").findAll(page.body()).map { it.groupValues[1] }.toList()
            assertEquals(9, scripts.size)
            scripts.forEach { asset ->
                assertEquals(200, get(asset).statusCode(), asset)
            }
            listOf(
                "/internal/selectedTeam",
                "/internal/api/target-versions",
                "/internal/repos/view",
                "/internal/api/dependency-scan",
                "/internal/api/ignored-repositories",
                "/internal/api/repository-notes",
                "/internal/api/target-resolution/security",
                "/internal/api/dependency-scan/progress",
            ).forEach { path ->
                val response = get(path)
                assertEquals(200, response.statusCode(), path)
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow())
                JsonParser.parseString(response.body())
            }
            assertEquals(404, get("/internal/clearDb").statusCode())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `GUI edits are visible on next request without rebuilding`() {
        Files.writeString(directory.resolve("index.html"), "<h1>Before</h1>")
        val handler = LocalFrontendApi(directory).handler
        assertEquals("<h1>Before</h1>", handler(Request(Method.GET, "/internal/gui")).bodyString())
        Files.writeString(directory.resolve("index.html"), "<h1>After</h1>")
        assertEquals("<h1>After</h1>", handler(Request(Method.GET, "/internal/gui")).bodyString())
    }

    @Test
    fun `mock data demonstrates access scanning version and security states using production enrichment`() {
        val handler = LocalFrontendApi(directory).handler
        val repos = JsonParser.parseString(handler(Request(Method.GET, "/internal/repos/view")).bodyString()).asJsonArray
        assertEquals(setOf("SCANNED", "NOT_SCANNED", "NOT_IN_GITHUB"), repos.map { it.asJsonObject["state"].asString }.toSet())
        val scans = JsonParser.parseString(handler(Request(Method.GET, "/internal/api/dependency-scan")).bodyString()).asJsonArray
        val statuses =
            scans
                .flatMap { scan ->
                    scan.asJsonObject["findings"].asJsonArray.map { it.asJsonObject["status"].asString }
                }.toSet()
        assertTrue(
            statuses.containsAll(
                setOf("OK", "UPDATE", "AHEAD", "VULNERABLE", "ADD", "REMOVE", "OK_WITH_ADD", "OK_OVERRIDDEN", "OK_TRANSIENT"),
            ),
            statuses.toString(),
        )
    }

    @Test
    fun `saving targets invalidates security then simulated scans progress from running to ready`() {
        var time = 10_000L
        val handler = LocalFrontendApi(directory) { time }.handler
        val targetResponse = handler(Request(Method.GET, "/internal/api/target-versions"))
        val targets = JsonParser.parseString(targetResponse.bodyString()).asJsonObject
        targets["dependencies"].asJsonObject.addProperty(LocalFrontendFixtures.LEGACY, "2.0.0")
        assertEquals(Status.OK, handler(Request(Method.POST, "/internal/api/target-versions/update").body(targets.toString())).status)
        val idle =
            JsonParser
                .parseString(
                    handler(Request(Method.GET, "/internal/api/target-resolution/security")).bodyString(),
                ).asJsonObject
        assertEquals("IDLE", idle["status"].asString)
        val running = handler(Request(Method.GET, "/internal/api/target-resolution/start"))
        assertEquals(Status.ACCEPTED, running.status)
        assertEquals("RUNNING", JsonParser.parseString(running.bodyString()).asJsonObject["status"].asString)
        time += 2000
        val resolved = JsonParser.parseString(handler(Request(Method.GET, "/internal/api/target-resolution")).bodyString()).asJsonObject
        assertEquals("READY", resolved["status"].asString)
        assertEquals(Status.ACCEPTED, handler(Request(Method.GET, "/internal/api/target-resolution/security/start")).status)
        time += 2000
        val ready =
            JsonParser
                .parseString(
                    handler(Request(Method.GET, "/internal/api/target-resolution/security")).bodyString(),
                ).asJsonObject
        assertEquals("READY", ready["status"].asString)
        assertEquals(resolved["targetFingerprint"], ready["targetFingerprint"])
        val results = ready["result"].asJsonObject["targets"].asJsonArray
        assertEquals(
            "OK",
            results.first { it.asJsonObject["key"].asString == LocalFrontendFixtures.LEGACY }.asJsonObject["status"].asString,
        )
    }

    @Test
    fun `notes ignores selection and simulated PR work locally and reset in a new instance`() {
        val handler = LocalFrontendApi(directory).handler
        assertEquals(
            Status.OK,
            handler(
                Request(Method.POST, "/internal/api/repository-notes").body("""{"repository":"demo/orders-api","note":"Edited locally"}"""),
            ).status,
        )
        assertTrue(handler(Request(Method.GET, "/internal/api/repository-notes")).bodyString().contains("Edited locally"))
        assertEquals(
            Status.OK,
            handler(
                Request(Method.POST, "/internal/api/ignored-repositories/update").body("""{"repositories":["demo/no-app-access"]}"""),
            ).status,
        )
        assertEquals(
            """{"repositories":["demo/no-app-access"]}""",
            handler(Request(Method.GET, "/internal/api/ignored-repositories")).bodyString(),
        )
        handler(Request(Method.POST, "/internal/selectedTeam").body("""{"selectedTeam":"demo-platform"}"""))
        assertTrue(handler(Request(Method.GET, "/internal/selectedTeam")).bodyString().contains("demo-platform"))
        val pr = handler(Request(Method.POST, "/internal/api/dependency-scan/pr/demo/orders-api"))
        assertEquals("/internal/local-demo/pr", pr.bodyString())
        assertTrue(handler(Request(Method.GET, pr.bodyString())).bodyString().contains("no pull request was created"))
        assertEquals(Status.NOT_FOUND, handler(Request(Method.POST, "/internal/api/dependency-scan/pr/demo/unknown")).status)
        assertEquals(Status.OK, handler(Request(Method.POST, "/internal/api/dependency-scan/refresh/demo/new-service")).status)
        assertTrue(handler(Request(Method.GET, "/internal/api/dependency-scan")).bodyString().contains("demo/new-service"))
        val fresh = LocalFrontendApi(directory).handler
        assertFalse(fresh(Request(Method.GET, "/internal/api/repository-notes")).bodyString().contains("Edited locally"))
        assertFalse(fresh(Request(Method.GET, "/internal/api/dependency-scan")).bodyString().contains("demo/new-service"))
        assertEquals(Status.OK, fresh(Request(Method.POST, "/internal/api/dependency-scan/refresh")).status)
        assertTrue(fresh(Request(Method.GET, "/internal/api/dependency-scan")).bodyString().contains("demo/new-service"))
    }

    @Test
    fun `malformed writes fail visibly and do not change existing state`() {
        val handler = LocalFrontendApi(directory).handler
        val before = handler(Request(Method.GET, "/internal/api/target-versions")).bodyString()
        listOf("{", "null", """{"plugins":{},"dependencies":{},"gradleVersion":"8"}""").forEach { invalid ->
            assertEquals(Status.BAD_REQUEST, handler(Request(Method.POST, "/internal/api/target-versions/update").body(invalid)).status)
            assertEquals(before, handler(Request(Method.GET, "/internal/api/target-versions")).bodyString())
        }
        assertEquals(
            Status.BAD_REQUEST,
            handler(Request(Method.POST, "/internal/api/repository-notes").body("""{"repository":"demo/orders-api"}""")).status,
        )
        assertEquals(
            Status.BAD_REQUEST,
            handler(Request(Method.POST, "/internal/api/ignored-repositories/update").body("""{"repositories":[null]}""")).status,
        )
    }
}
