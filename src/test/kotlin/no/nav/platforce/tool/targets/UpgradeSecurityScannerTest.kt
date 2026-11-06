package no.nav.platforce.tool.targets

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.workflows.QueuedExecutor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpgradeSecurityScannerTest {
    private val versions =
        AvailableVersionsService(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            "<metadata><versioning><versions><version>1.1</version><version>1.2</version><version>2.0-RC1</version></versions></versioning></metadata>"
                                .toResponseBody("application/xml".toMediaType()),
                        ).build()
                }.build(),
        )
    private val state = TargetVersionsState(mutableMapOf(), mutableMapOf(), "  8.14.4")

    private fun node(
        name: String,
        version: String,
        children: List<ResolvedDependency> = emptyList(),
    ) = ResolvedDependency(group = "example", name = name, version = version, requestedVersion = version, dependencies = children)

    @Test
    fun `all stable candidates resolve once in separate trees and transitive advisories are counted distinctly`() {
        val executor = QueuedExecutor()
        var resolutions = 0
        var lookups = 0
        val scanner =
            UpgradeSecurityScanner(
                versions,
                { _, saved, modules ->
                    resolutions++
                    assertEquals(state.gradleVersion, saved.gradleVersion)
                    assertEquals(mapOf("1.2" to "example:library:1.2", "1.1" to "example:library:1.1"), modules)
                    TargetResolution(
                        saved.gradleVersion,
                        emptyList(),
                        mapOf(
                            "1.1" to listOf(node("library", "1.1", listOf(node("unsafe", "1.0")))),
                            "1.2" to listOf(node("library", "1.2", listOf(node("unsafe", "2.0")))),
                        ),
                    )
                },
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                        lookups++
                        return dependencies.associate {
                            "${it.key}:${it.version}" to
                                if (it.name == "unsafe" &&
                                    it.version == "1.0"
                                ) {
                                    listOf(Vulnerability("TEST", "Test", emptyList(), emptyList(), emptyList()))
                                } else {
                                    emptyList()
                                }
                        }
                    }
                },
                executor,
            )

        fun get(
            user: String = "alice",
            start: Boolean = false,
        ) = scanner.snapshot(user, state, "dependency", "example:library", "1.0", start)
        assertEquals("IDLE", get().status)
        get(start = true)
        get(start = true)
        assertEquals(1, executor.queuedTasks)
        executor.runNext()
        val result = get().result!!
        assertEquals(listOf("OK", "VULNERABLE"), result.map { it.status })
        assertEquals(listOf(0, 1), result.map { it.vulnerabilityCount })
        get(start = true)
        assertEquals(1, resolutions)
        assertEquals(1, lookups)
        assertEquals("IDLE", get("bob").status)
        scanner.invalidate("alice")
        assertEquals("IDLE", get().status)
        get(start = true)
        scanner.invalidate("alice")
        executor.runNext()
        assertEquals(1, resolutions, "Saving discards queued scans")
    }

    @Test
    fun `missing resolution or OSV evidence is unresolved and saved targets invalidate running work`() {
        val executor = QueuedExecutor()
        lateinit var scanner: UpgradeSecurityScanner
        scanner =
            UpgradeSecurityScanner(
                versions,
                { _, saved, _ ->
                    TargetResolution(saved.gradleVersion, emptyList(), mapOf("1.1" to listOf(node("library", "1.1"))))
                },
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>) = emptyMap<String, List<Vulnerability>>()
                },
                executor,
            )
        scanner.snapshot("alice", state, "dependency", "example:library", "1.0", true)
        executor.runNext()
        val result = scanner.snapshot("alice", state, "dependency", "example:library", "1.0", false).result!!
        assertTrue(result.all { it.status == "UNRESOLVED" && !it.error.isNullOrEmpty() })
        assertEquals(
            "IDLE",
            scanner.snapshot("alice", state.copy(gradleVersion = "9.0"), "dependency", "example:library", "1.0", false).status,
        )
    }
}
