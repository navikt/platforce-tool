package no.nav.platforce.tool.auto

import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.github.GithubPullRequest
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
import java.io.IOException

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
        var listingFailure: IOException? = null
        val files =
            mutableMapOf(
                "build.gradle" to "dependencies {\n    implementation 'example:library:1.0'\n}",
                "gradle/wrapper/gradle-wrapper.properties" to
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.0-bin.zip",
            )
        val recording = RecordingGithubClient(files)
        val github =
            object : GithubClient by recording {
                override fun listRepositories(): List<String> {
                    listingFailure?.let { throw it }
                    return installed
                }

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
    fun `full refresh replaces caller inventories with current GitHub data for every accessible repository`() {
        val fixture = Fixture()
        val stale = checkNotNull(fixture.dependencyScanner.scanRepository("example/repo"))
        fixture.cache.update("alice", listOf(stale))
        fixture.cache.update("bob", listOf(stale))
        fixture.installed = listOf("example/repo", "example/not-opted-in")
        fixture.files["build.gradle"] = "dependencies {\n    implementation 'example:library:1.1'\n}"
        val report = fixture.operations.refreshRepositories("alice", fixture.state, fixture::tick)
        val refreshed = fixture.cache.get("alice")
        assertEquals(fixture.installed.toSet(), refreshed.map { it.repository }.toSet())
        assertTrue(refreshed.all { it.dependencies["example:library"] == "1.1" })
        assertEquals(listOf(stale), fixture.cache.get("bob"))
        assertEquals(2, fixture.cache.getProgress("alice").total)
        assertEquals(2, fixture.cache.getProgress("alice").done)
        assertFalse(fixture.cache.getProgress("alice").running)
        assertEquals(null, fixture.cache.getProgress("alice").error)
        assertTrue(report.message.contains("2 repository inventories refreshed from GitHub"))
        assertFalse(fixture.recording.calls.any { it.startsWith("createPullRequest") })
    }

    @Test
    fun `failed full refresh retains prior inventories and records a stopped progress error`() {
        val fixture = Fixture()
        val stale = checkNotNull(fixture.dependencyScanner.scanRepository("example/repo"))
        fixture.cache.update("alice", listOf(stale))
        fixture.listingFailure = IOException("GitHub unavailable")
        assertThrows(IOException::class.java) {
            fixture.operations.refreshRepositories("alice", fixture.state, fixture::tick)
        }
        assertEquals(listOf(stale), fixture.cache.get("alice"))
        assertFalse(fixture.cache.getProgress("alice").running)
        assertEquals("GitHub unavailable", fixture.cache.getProgress("alice").error)
        assertFalse(fixture.recording.calls.any { it.startsWith("createPullRequest") })
    }

    @Test
    fun `changed targets or lost ownership interrupt full refresh without publishing partial inventories`() {
        listOf(true, false).forEach { changeTargets ->
            val fixture = Fixture()
            val stale = checkNotNull(fixture.dependencyScanner.scanRepository("example/previous"))
            fixture.cache.update("alice", listOf(stale))
            fixture.installed = listOf("example/first", "example/second")
            assertThrows(AutoInterrupted::class.java) {
                fixture.operations.refreshRepositories("alice", fixture.state.snapshot()) {
                    if (fixture.cache.getProgress("alice").done == 1) {
                        if (changeTargets) {
                            fixture.state.dependencies["example:library"] = "1.3"
                        } else {
                            throw AutoInterrupted(
                                "Lost ownership",
                            )
                        }
                    }
                }
            }
            assertEquals(listOf(stale), fixture.cache.get("alice"))
            assertFalse(fixture.cache.getProgress("alice").running)
            assertTrue(fixture.cache.getProgress("alice").error != null)
            assertFalse(fixture.recording.calls.any { it.contains("example/second") })
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
    fun `Auto reports closed superseded PRs and retains the replacement link if cleanup fails`() {
        listOf(false, true).forEach { failCleanup ->
            val fixture = Fixture()
            fixture.scans()
            fixture.recording.pullRequests[1] =
                GithubPullRequest(
                    1,
                    "https://github.com/example/repo/pull/1",
                    99L,
                    "Bot",
                    "chore/update-dependencies-2026-11-01",
                    "example/repo",
                    "main",
                    "example/repo",
                    "open",
                    false,
                    "Automated dependency update via platforce-tool app",
                )
            if (failCleanup) fixture.recording.failClosing = setOf(1)
            if (failCleanup) {
                val stopped =
                    assertThrows(AutoStopped::class.java) {
                        fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
                    }
                assertEquals("https://github.com/example/repo/pull/2", stopped.report.url)
                assertTrue(stopped.report.message.contains("cleanup is unfinished"))
                assertTrue(stopped.report.details!!.contains("Review existing PRs before retrying"))
                assertEquals(
                    "open",
                    fixture.recording.pullRequests
                        .getValue(1)
                        .state,
                )
            } else {
                val report = fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
                assertEquals("https://github.com/example/repo/pull/2", report.url)
                assertTrue(report.details!!.contains("Closed superseded PRs:\nhttps://github.com/example/repo/pull/1"))
                assertEquals(
                    "closed",
                    fixture.recording.pullRequests
                        .getValue(1)
                        .state,
                )
            }
            assertEquals(
                "open",
                fixture.recording.pullRequests
                    .getValue(2)
                    .state,
            )
        }
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
    fun `routine scan reports are compact and redundant overrides are non blocking REMOVE findings`() {
        val fixture = Fixture()
        val dependencies = fixture.operations.scanDependencies("alice", fixture.state, fixture::tick)
        assertTrue(dependencies.message.contains("1 targets checked"))
        assertEquals(null, dependencies.details)
        val boms = fixture.operations.scanBoms("alice", fixture.state, fixture::tick)
        assertEquals("BOM scan finished: 1 saved BOMs checked for coverage", boms.message)
        assertEquals(null, boms.details)
        assertEquals(null, fixture.operations.scanPlugins("alice", fixture.state, fixture::tick).details)

        fixture.state = fixture.state.copy(transientDependencies = setOf("example:library"))
        val redundant = fixture.operations.scanDependencies("alice", fixture.state, fixture::tick)
        assertTrue(redundant.details!!.startsWith("REMOVE example:library:1.2: redundant transient override."))
        assertTrue(redundant.details.contains("review repository declarations"))
        fixture.operations.scanBoms("alice", fixture.state, fixture::tick)
        fixture.operations.scanPlugins("alice", fixture.state, fixture::tick)
        val report = fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
        assertTrue(report.details!!.startsWith("REMOVE example:library:"))
        assertEquals("https://github.com/example/repo/pull/1", report.url)
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
