package no.nav.platforce.tool.auto

import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.PlatformBomResolution
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.PluginSecurityScanner
import no.nav.platforce.tool.security.PluginSecurityService
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.AvailableVersionsService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.TargetVersionsUpdater
import no.nav.platforce.tool.targets.snapshot
import no.nav.platforce.tool.workflows.QueuedExecutor
import no.nav.platforce.tool.workflows.RecordingGithubClient
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultAutoOperationsTest {
    private class Fixture {
        var state =
            TargetVersionsState(
                mutableMapOf("example.plugin" to "2.0"),
                mutableMapOf("example:library" to "1.2"),
                "8.0",
                platformBoms = mutableMapOf("example:bom" to "1.0"),
            )
        var installed = listOf("example/repo")
        var optedIn = setOf("example/repo")
        var ignored = emptyList<String>()
        var vulnerableName: String? = null
        var unresolved = false
        var missingBom = false
        val recording =
            RecordingGithubClient(
                mapOf(
                    "build.gradle" to "dependencies {\n    implementation 'example:library:1.0'\n}",
                    "gradle/wrapper/gradle-wrapper.properties" to
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.0-bin.zip",
                ),
            )
        val github =
            object : GithubClient by recording {
                override fun listRepositories(): List<String> = installed

                override fun createPullRequest(
                    owner: String,
                    repo: String,
                    title: String,
                    body: String,
                    head: String,
                    base: String,
                ): String =
                    recording
                        .createPullRequest(
                            owner,
                            repo,
                            title,
                            body,
                            head,
                            base,
                        ).replace("https://github.example.test", "https://github.com")
            }
        val cache = DependencyScanCache()
        val dependencyScanner = DependencyScanner(github)
        val resolveQueue = QueuedExecutor()
        val securityQueue = QueuedExecutor()
        val pluginQueue = QueuedExecutor()
        val osv =
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                    dependencies.associate { node ->
                        "${node.key}:${node.version}" to
                            if (node.name == vulnerableName) {
                                listOf(Vulnerability("TEST-AUTO", "Synthetic", emptyList(), emptyList(), emptyList()))
                            } else {
                                emptyList()
                            }
                    }
            }
        val resolution =
            GradleTargetResolutionScanner(
                resolve = { _, targets ->
                    val roots =
                        targets.dependencies.map { (key, version) ->
                            ResolvedDependency(
                                key.substringBefore(":"),
                                key.substringAfter(":"),
                                if (unresolved) "<unresolved>" else version,
                                version,
                                emptyList(),
                            )
                        }
                    TargetResolution(targets.gradleVersion, roots, roots.associate { it.key to listOf(it) })
                },
                executor = resolveQueue,
                coverageResolve = { _, targets, baseline, _ ->
                    baseline.copy(
                        platformBoms =
                            if (missingBom) {
                                emptyMap()
                            } else {
                                targets.platformBoms.mapValues {
                                    PlatformBomResolution(
                                        it.value,
                                        emptyList(),
                                        emptyList(),
                                    )
                                }
                            },
                    )
                },
            )
        val security = TargetSecurityScanner(TargetSecurityService(osv), resolution, securityQueue)
        val plugins =
            PluginSecurityScanner(
                resolve = { _, targets ->
                    TargetResolution(
                        targets.gradleVersion,
                        emptyList(),
                        targets.plugins.mapValues { (id, version) ->
                            listOf(
                                ResolvedDependency(
                                    id,
                                    "$id.gradle.plugin",
                                    version,
                                    version,
                                    listOf(ResolvedDependency("example", "plugin-impl", version, version, emptyList())),
                                ),
                            )
                        },
                    )
                },
                service = PluginSecurityService(osv),
                executor = pluginQueue,
            )
        val view = DependencyScanViewService(cache, security, dependencyScanner)
        val operations =
            DefaultAutoOperations(
                AvailableVersionsService(OkHttpClient()),
                TargetVersionsUpdater(resolution, security, plugins),
                security,
                resolution,
                plugins,
                github,
                dependencyScanner,
                cache,
                view,
                DependencyPullRequestService(github, view),
                readTargets = { state.snapshot() },
                writeTargets = { _, expected, updated ->
                    check(state == expected)
                    state = updated.snapshot()
                    security.invalidate("alice")
                    resolution.invalidate("alice")
                    plugins.invalidate("alice")
                },
                users = { listOf("alice", "bob") },
                optedIn = { optedIn },
                ignoredRepositories = { ignored },
            )

        fun tick() {
            if (resolveQueue.queuedTasks > 0) resolveQueue.runNext()
            if (securityQueue.queuedTasks > 0) securityQueue.runNext()
            if (pluginQueue.queuedTasks > 0) pluginQueue.runNext()
        }

        fun scans() {
            operations.scanDependencies("alice", state, ::tick)
            operations.scanBoms("alice", state, ::tick)
            operations.scanPlugins("alice", state, ::tick)
        }
    }

    @Test
    fun `production scan and PR services create a report link only for opted in accessible repositories needing updates`() {
        val fixture = Fixture()
        fixture.scans()
        assertEquals(listOf("example/repo"), fixture.operations.repositories())
        val report = fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
        assertEquals("https://github.com/example/repo/pull/1", report.url)
        assertTrue(report.details!!.contains("example:library"))
        assertTrue(
            fixture.recording.updatedFiles
                .getValue("build.gradle")
                .contains("1.2"),
        )
        assertTrue(fixture.cache.get("alice").isNotEmpty())
        assertTrue(fixture.cache.get("bob").isEmpty())
        fixture.optedIn = emptySet()
        assertTrue(
            fixture.operations
                .createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
                .message
                .startsWith("Skipped"),
        )
        fixture.optedIn = setOf("example/repo")
        fixture.installed = emptyList()
        assertTrue(fixture.operations.repositories().isEmpty())
    }

    @Test
    fun `ignored and current repositories do not create PRs`() {
        val fixture = Fixture()
        fixture.state.dependencies["example:library"] = "1.0"
        fixture.scans()
        assertTrue(
            fixture.operations
                .createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
                .message
                .contains("disabled"),
        )
        fixture.ignored = listOf("example/repo")
        assertTrue(
            fixture.operations
                .createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
                .message
                .contains("ignored"),
        )
        assertFalse(fixture.recording.calls.any { it.startsWith("createPullRequest") })
    }

    @Test
    fun `real security results stop vulnerable dependencies unresolved metadata and vulnerable plugins`() {
        val vulnerable = Fixture().apply { vulnerableName = "library" }
        assertThrows(AutoStopped::class.java) { vulnerable.operations.scanDependencies("alice", vulnerable.state, vulnerable::tick) }
        val unresolved = Fixture().apply { unresolved = true }
        assertThrows(AutoStopped::class.java) { unresolved.operations.scanDependencies("alice", unresolved.state, unresolved::tick) }
        val plugin = Fixture().apply { vulnerableName = "plugin-impl" }
        plugin.operations.scanDependencies("alice", plugin.state, plugin::tick)
        plugin.operations.scanBoms("alice", plugin.state, plugin::tick)
        val stopped = assertThrows(AutoStopped::class.java) { plugin.operations.scanPlugins("alice", plugin.state, plugin::tick) }
        assertTrue(stopped.report.details!!.contains("VULNERABLE"))
    }

    @Test
    fun `changed target inputs interrupt rather than using another fingerprint or overwriting saved work`() {
        val fixture = Fixture()
        val original = fixture.state.snapshot()
        fixture.state.dependencies["example:library"] = "1.3"
        assertThrows(AutoInterrupted::class.java) { fixture.operations.scanDependencies("alice", original, fixture::tick) }
        assertFalse(fixture.recording.calls.any { it.startsWith("createPullRequest") })
    }

    @Test
    fun `missing BOM evidence stops automation explicitly`() {
        val fixture = Fixture().apply { missingBom = true }
        fixture.operations.scanDependencies("alice", fixture.state, fixture::tick)
        assertThrows(AutoStopped::class.java) { fixture.operations.scanBoms("alice", fixture.state, fixture::tick) }
    }

    @Test
    fun `target changes or opting out during PR creation prevent further GitHub writes`() {
        listOf(true, false).forEach { changeTargets ->
            val fixture = Fixture()
            fixture.scans()
            assertThrows(AutoInterrupted::class.java) {
                fixture.operations.createPullRequest("alice", fixture.state.snapshot(), "example/repo") {
                    fixture.tick()
                    if (fixture.recording.calls.any { it.startsWith("createBranch") }) {
                        if (changeTargets) fixture.state.dependencies["example:library"] = "1.4" else fixture.optedIn = emptySet()
                    }
                }
            }
            assertFalse(fixture.recording.calls.any { it.startsWith("updateFile") || it.startsWith("createPullRequest") })
        }
    }
}
