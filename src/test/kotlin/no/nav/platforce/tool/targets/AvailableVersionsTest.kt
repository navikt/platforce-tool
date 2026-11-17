package no.nav.platforce.tool.targets

import com.google.gson.Gson
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.TimeUnit

class AvailableVersionsTest {
    @Test
    fun `npm upgrades use registry semver publication times and user scoped expiring metadata`() {
        var time = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()
        val requests = mutableListOf<String>()
        val metadata =
            Gson().toJson(
                mapOf(
                    "versions" to
                        listOf(
                            "1.0.0",
                            "1.0.0+build.1",
                            "1.2.0",
                            "1.10.0",
                            "2.0.0",
                            "2.1.0-test.1",
                            "invalid",
                        ).associateWith { emptyMap<String, String>() },
                    "time" to
                        mapOf(
                            "1.2.0" to "2026-11-10T12:00:00Z",
                            "1.10.0" to "2026-11-10T12:00:01Z",
                            "2.0.0" to "2024-01-01T00:00:00Z",
                            "2.1.0-test.1" to "2024-01-01T00:00:00Z",
                        ),
                ),
            )
        val service =
            AvailableVersionsService(
                client { url ->
                    requests += url
                    200 to metadata
                },
            ) { time }
        val result = service.available("alice", "@slack/bolt", "^1.0.0", "node")
        assertEquals("@slack/bolt", result.key)
        assertEquals("1.0.0", result.currentVersion)
        assertEquals(listOf("2.1.0-test.1", "2.0.0", "1.10.0", "1.2.0"), result.versions.map { it.version })
        assertTrue(result.versions.first().prerelease)
        assertEquals(listOf("2.1.0-test.1", "2.0.0"), result.versions.filter { it.majorUpgrade }.map { it.version })
        assertEquals(listOf("1.10.0"), result.versions.filter { it.young }.map { it.version })
        assertEquals("2026-11-10T12:00:00Z", result.versions.last().releaseDate)
        assertEquals(listOf("https://registry.npmjs.org/@slack%2Fbolt"), requests)
        service.available("alice", "@slack/bolt", "~1.2.0", "node")
        assertEquals(1, requests.size)
        service.available("bob", "@slack/bolt", "1.0.0", "node")
        assertEquals(2, requests.size)
        time += TimeUnit.MINUTES.toMillis(5)
        service.available("alice", "@slack/bolt", "1.0.0", "node")
        assertEquals(3, requests.size)
    }

    @Test
    fun `npm missing and invalid release dates stay visible and failed metadata can be retried`() {
        var response = 503 to ""
        val service = AvailableVersionsService(client { response })
        assertThrows(IOException::class.java) { service.available("alice", "express", "1.0.0", "node") }
        response =
            200 to """{"versions":{"1.1.0":{},"1.2.0":{},"1.3.0":{}},"time":{"1.2.0":"invalid","1.3.0":"+999999999-01-01T00:00:00Z"}}"""
        val versions = service.available("alice", "express", "1.0.0", "node").versions
        assertEquals(3, versions.size)
        assertTrue(versions.all { it.releaseDate == null && !it.young && it.releaseDateError!!.startsWith("Release date unavailable:") })
    }

    @Test
    fun `npm rejects nonpublic names ranges malformed metadata and redirects`() {
        val service = AvailableVersionsService(client { error("Invalid input must not request metadata") })
        listOf("../secret", "@scope/../../secret", "https://example.test", "a:b", "a?query").forEach {
            assertThrows(IllegalArgumentException::class.java) { service.available("alice", it, "1.0.0", "node") }
        }
        listOf("latest", ">=1.0.0", "workspace:*", "1.0", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { service.available("alice", "express", it, "node") }
        }
        listOf(
            302 to "",
            404 to "",
            200 to "{",
            200 to "[]",
            200 to """{"versions":[]}""",
            200 to """{"versions":{"1.1.0":null}}""",
            200 to "x".repeat(16 * 1024 * 1024 + 1),
        ).forEach { reply ->
            assertThrows(IOException::class.java) {
                AvailableVersionsService(client { reply }).available("alice", "express", "1.0.0", "node")
            }
        }
    }

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

    private fun client(
        release: (String) -> Pair<Int, String?> = { 200 to "Mon, 01 Jan 2024 00:00:00 GMT" },
        reply: (String) -> Pair<Int, String>,
    ) = OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            val head = chain.request().method == "HEAD"
            val url = chain.request().url.toString()
            val (code, body) = if (head) release(url) else reply(url)
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Synthetic metadata")
                .apply { if (head && body != null) header("Last-Modified", body) }
                .body((if (head) "" else body.orEmpty()).toResponseBody("application/xml".toMediaType()))
                .build()
        }.build()

    @Test
    fun `release dates distinguish young versions at the exact seven day boundary and age cached results`() {
        var time = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()
        val initial = time
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val service =
            AvailableVersionsService(
                client(release = { url ->
                    requested += url
                    val age =
                        when {
                            "/1.1/" in url -> TimeUnit.DAYS.toMillis(7) + 1
                            "/1.2/" in url -> TimeUnit.DAYS.toMillis(7)
                            "/1.3/" in url -> TimeUnit.DAYS.toMillis(7) - 1000
                            else -> -TimeUnit.DAYS.toMillis(1)
                        }
                    200 to DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(initial - age).atZone(ZoneOffset.UTC))
                }) { 200 to metadata("1.0", "1.1", "1.2", "1.3", "1.4") },
            ) { time }
        val versions = service.available("alice", "example:library", "1.0").versions
        assertEquals(listOf("1.4", "1.3"), versions.filter { it.young }.map { it.version })
        assertEquals("2026-11-09T12:00:00Z", versions.first { it.version == "1.2" }.releaseDate)
        assertTrue(versions.all { it.releaseDateError == null })
        assertTrue(requested.all { it.startsWith("https://repo.maven.apache.org/maven2/example/library/") && it.endsWith(".pom") })
        time += 1000
        assertFalse(
            service
                .available("alice", "example:library", "1.2")
                .versions
                .first { it.version == "1.3" }
                .young,
        )
        assertEquals(4, requested.size, "Changing the draft and aging versions reuse cached release dates")
        service.available("bob", "example:library", "1.2")
        assertEquals(6, requested.size, "Release date caches are user scoped")
        time += TimeUnit.MINUTES.toMillis(5)
        service.available("alice", "example:library", "1.2")
        assertEquals(8, requested.size, "Release dates are refreshed after cache expiry")
    }

    @Test
    fun `unverifiable release dates remain explicit without hiding available upgrades`() {
        listOf(200 to null, 200 to "invalid", 404 to null, 503 to null, 302 to null).forEach { response ->
            val service = AvailableVersionsService(client(release = { response }) { 200 to metadata("1.1") })
            val version = service.available("alice", "example:library", "1.0").versions.single()
            assertEquals(null, version.releaseDate)
            assertFalse(version.young)
            assertTrue(version.releaseDateError!!.startsWith("Release date unavailable:"))
        }
        val service =
            AvailableVersionsService(
                client(release = { throw IOException("Timeout") }) { 200 to metadata("1.1") },
            )
        assertTrue(
            service
                .available("alice", "example:library", "1.0")
                .versions
                .single()
                .releaseDateError!!
                .contains("Timeout"),
        )
    }

    @Test
    fun `release dates use the repository that supplied metadata including plugin markers and Confluent`() {
        listOf(
            Triple(
                "example.plugin",
                "plugin",
                "https://plugins.gradle.org/m2/example/plugin/example.plugin.gradle.plugin/1.1/example.plugin.gradle.plugin-1.1.pom",
            ),
            Triple("io.confluent:library", "dependency", "https://packages.confluent.io/maven/io/confluent/library/1.1/library-1.1.pom"),
        ).forEach { (key, kind, expected) ->
            val requested = mutableListOf<String>()
            val service =
                AvailableVersionsService(
                    client(release = { url ->
                        requested += url
                        200 to "Mon, 01 Jan 2024 00:00:00 GMT"
                    }) { url -> if ("repo.maven.apache.org" in url) 404 to "" else 200 to metadata("1.1") },
                )
            assertEquals(
                "2024-01-01T00:00:00Z",
                service
                    .available("alice", key, "1.0", kind)
                    .versions
                    .single()
                    .releaseDate,
            )
            assertEquals(listOf(expected), requested)
        }
    }

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
