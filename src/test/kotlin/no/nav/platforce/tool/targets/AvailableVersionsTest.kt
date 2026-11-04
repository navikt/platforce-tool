package no.nav.platforce.tool.targets

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.routing.routes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class AvailableVersionsTest {
    @Test
    fun `plugin upgrades use marker metadata from Plugin Portal and keep the original ID`() {
        val urls = mutableListOf<String>()
        val service =
            AvailableVersionsService(
                client { url ->
                    urls += url
                    200 to metadata("1.0", "1.1", "2.0", "2.1-RC1")
                },
            )
        val result = service.available("alice", "example.plugin", "1.0", "plugin")
        assertEquals("example.plugin", result.key)
        assertEquals(listOf("2.1-RC1", "2.0", "1.1"), result.versions.map { it.version })
        assertEquals(listOf("https://plugins.gradle.org/m2/example/plugin/example.plugin.gradle.plugin/maven-metadata.xml"), urls)
        service.available("alice", "example.plugin", "1.0", "plugin")
        assertEquals(1, urls.size)
        assertThrows(IllegalArgumentException::class.java) {
            service.available("alice", "../example", "1.0", "plugin")
        }
    }

    private fun metadata(vararg versions: String) =
        "<metadata><versioning><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning></metadata>"

    private fun client(reply: (String) -> Pair<Int, String>) =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val (code, body) = reply(chain.request().url.toString())
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Synthetic metadata")
                    .body(body.toResponseBody("application/xml".toMediaType()))
                    .build()
            }.build()

    @Test
    fun `versions use Maven ordering and distinguish prereleases and major upgrades without claiming safety`() {
        val service =
            AvailableVersionsService(
                client {
                    200 to metadata("2.9", "2.9.0", "2.10", "2.10", "2.11-RC1", "3.0", "3.1-beta2", "3.2-M1", "3.3-SNAPSHOT", "3.4-eap-1")
                },
            )
        val result = service.available("alice", "example:library", "2.9")
        assertEquals(
            listOf("3.4-eap-1", "3.3-SNAPSHOT", "3.2-M1", "3.1-beta2", "3.0", "2.11-RC1", "2.10"),
            result.versions.map { it.version },
        )
        assertEquals(listOf("3.0", "2.10"), result.versions.filterNot { it.prerelease }.map { it.version })
        assertTrue(result.versions.first { it.version == "3.0" }.majorUpgrade)
        assertFalse(result.versions.last().majorUpgrade)
        assertTrue(service.available("alice", "example:library", "99.0").versions.isEmpty())
    }

    @Test
    fun `metadata cache expires after five minutes and is isolated by username and coordinate`() {
        var calls = 0
        var time = 0L
        val service =
            AvailableVersionsService(
                client {
                    calls++
                    200 to metadata("2.0")
                },
            ) { time }
        service.available("alice", "example:library", "1.0")
        service.available("alice", "example:library", "1.5")
        assertEquals(1, calls, "Changing the draft version reuses metadata, not a cached comparison")
        service.available("bob", "example:library", "1.0")
        service.available("alice", "example:other", "1.0")
        assertEquals(3, calls)
        time += 300_000
        service.available("alice", "example:library", "1.0")
        assertEquals(4, calls)
    }

    @Test
    fun `Confluent is tried only when Maven Central has no metadata and lookup failures are not cached`() {
        val calls = mutableListOf<String>()
        var fail = true
        val service =
            AvailableVersionsService(
                client { url ->
                    calls += url
                    when {
                        url.contains("repo.maven.apache.org") -> 404 to ""
                        fail -> 503 to ""
                        else -> 200 to metadata("7.0.0")
                    }
                },
            )
        assertThrows(IOException::class.java) { service.available("alice", "io.confluent:kafka-avro-serializer", "6.0.0") }
        fail = false
        assertEquals(
            "7.0.0",
            service
                .available("alice", "io.confluent:kafka-avro-serializer", "6.0.0")
                .versions
                .single()
                .version,
        )
        assertEquals(
            listOf(
                "https://repo.maven.apache.org/maven2/io/confluent/kafka-avro-serializer/maven-metadata.xml",
                "https://packages.confluent.io/maven/io/confluent/kafka-avro-serializer/maven-metadata.xml",
            ),
            calls.take(2),
        )
        assertEquals(4, calls.size)
    }

    @Test
    fun `invalid coordinates and dynamic versions never trigger network calls`() {
        val service = AvailableVersionsService(client { error("Unexpected network call") })
        listOf("https://example.test/x", "example:../../secret", "a..b:c", "a:b/c", "a:b?x=y", "a:b:c").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { service.available("alice", key, "1.0") }
        }
        listOf("", "1.+", "[1,2)", "latest.release").forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { service.available("alice", "example:library", version) }
        }
    }

    @Test
    fun `missing malformed oversized and entity-bearing metadata are explicit failures`() {
        listOf(
            404 to "",
            302 to "",
            200 to "<html>Not metadata</html>",
            200 to "<metadata>",
            200 to """<!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]><metadata>&x;</metadata>""",
            200 to "x".repeat(2 * 1024 * 1024 + 1),
        ).forEach { reply ->
            val service = AvailableVersionsService(client { reply })
            assertThrows(IOException::class.java) { service.available("alice", "example:library", "1.0") }
        }
    }

    @Test
    fun `GUI endpoint requires a user before looking up metadata`() {
        val service = AvailableVersionsService(client { 503 to "" })
        val handler = routes(*availableVersionsRoutes(service).toTypedArray())
        val path = "/internal/api/target-versions/available?key=example:library&currentVersion=1.0"
        assertThrows(RuntimeException::class.java) { handler(Request(Method.GET, path)) }
    }
}
