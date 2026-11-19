package no.nav.platforce.tool.auto

import com.google.gson.JsonParser
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.node.NodeCombinedResolution
import no.nav.platforce.tool.node.NodeSecurityScanner
import no.nav.platforce.tool.node.NodeSecurityService
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.PullRequestReadinessException
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
        var vulnerableVersion: String? = null
        var unresolved = false
        var missingBom = false
        var listingFailure: IOException? = null
        var nodeResolutionFailure = false
        var nodeLookupFailure = false
        var nodeChild: ResolvedDependency? = null
        var lockGenerations = 0
        var lockFailure = false
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
        val nodeQueue = QueuedExecutor()
        val osv =
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                    if (nodeLookupFailure && dependencies.any { it.group == "npm" }) throw IOException("OSV unavailable")
                    return dependencies.associate { node ->
                        "${node.key}:${node.version}" to
                            if (node.name == vulnerableName && (vulnerableVersion == null || node.version == vulnerableVersion)) {
                                listOf(Vulnerability("TEST-AUTO", "Synthetic", emptyList(), emptyList(), emptyList()))
                            } else {
                                emptyList()
                            }
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

        private fun npmTree(
            key: String,
            version: String,
            versions: Map<String, String> = emptyMap(),
        ): List<ResolvedDependency> =
            listOf(ResolvedDependency("npm", key, version, version, emptyList())) +
                if (key == "parent") {
                    listOfNotNull(nodeChild?.let { it.copy(version = versions[it.name] ?: it.version) })
                } else {
                    emptyList()
                }

        val nodeSecurity =
            NodeSecurityScanner(
                NodeSecurityService(
                    { key, version ->
                        check(!nodeResolutionFailure) { "npm resolution failed" }
                        npmTree(key, version)
                    },
                    osv,
                    { normal, overrides ->
                        val packages = normal.flatMap { (key, version) -> npmTree(key, version, normal + overrides) }
                        NodeCombinedResolution(
                            packages,
                            (normal + overrides).mapValues { (key, version) ->
                                if (key in normal) npmTree(key, version, normal + overrides) else packages.filter { it.name == key }
                            },
                        )
                    },
                ),
                nodeQueue,
            )
        val view = DependencyScanViewService(cache, security, dependencyScanner, nodeSecurity)
        val operations =
            DefaultAutoOperations(
                AvailableVersionsService(OkHttpClient()),
                TargetVersionsUpdater(resolution, security, plugins, nodeSecurityScanner = nodeSecurity),
                security,
                resolution,
                plugins,
                github,
                dependencyScanner,
                cache,
                view,
                DependencyPullRequestService(github, view) { _, resolution, _, _ ->
                    lockGenerations++
                    check(JsonParser.parseString(resolution).asJsonObject.has("dependencies"))
                    if (lockFailure) throw IOException("npm lock refresh failed")
                    "generated-lock"
                },
                nodeSecurity,
                readTargets = { state.snapshot() },
                writeTargets = { _, expected, updated ->
                    check(state == expected)
                    state = updated.snapshot()
                    security.invalidate("alice")
                    resolution.invalidate("alice")
                    plugins.invalidate("alice")
                    nodeSecurity.invalidate("alice")
                },
                users = { listOf("alice", "bob") },
                optedIn = { optedIn },
                ignoredRepositories = { ignored },
            )

        fun tick() {
            if (resolveQueue.queuedTasks > 0) resolveQueue.runNext()
            if (securityQueue.queuedTasks > 0) securityQueue.runNext()
            if (pluginQueue.queuedTasks > 0) pluginQueue.runNext()
            if (nodeQueue.queuedTasks > 0) nodeQueue.runNext()
        }

        fun scans() {
            operations.scanDependencies("alice", state, ::tick)
            operations.scanBoms("alice", state, ::tick)
            operations.scanPlugins("alice", state, ::tick)
            operations.scanNode("alice", state, ::tick)
        }
    }

    @Test
    fun `Node Auto requires matching user scoped security results before GitHub writes`() {
        val fixture = Fixture()
        fixture.files.clear()
        fixture.files["package.json"] = """{"dependencies":{"express":"^4.21.2"}}"""
        fixture.state.nodeDependencies["express"] = "5.0.0"
        assertThrows(PullRequestReadinessException::class.java) {
            fixture.operations.createPullRequest("alice", fixture.state, "example/repo") {}
        }
        fixture.operations.scanNode("alice", fixture.state, fixture::tick)
        assertThrows(PullRequestReadinessException::class.java) {
            fixture.operations.createPullRequest("bob", fixture.state, "example/repo") {}
        }
        val original = fixture.state.snapshot()
        fixture.state.nodeDependencies["express"] = "5.1.0"
        assertThrows(PullRequestReadinessException::class.java) {
            fixture.operations.createPullRequest("alice", fixture.state, "example/repo") {}
        }
        fixture.state = original
        assertFalse(fixture.recording.calls.any { it.startsWith("create") || it.startsWith("update") })
        assertEquals(0, fixture.resolveQueue.queuedTasks)
        assertEquals(0, fixture.securityQueue.queuedTasks)
    }

    @Test
    fun `Node Auto refreshes existing npm lockfiles and creates the usual guarded pull request`() {
        val fixture = Fixture()
        fixture.files.clear()
        fixture.files["package.json"] = """{"dependencies":{"parent":"^1.0.0"},"devDependencies":{"dev-only":"^1.0.0"}}"""
        fixture.files["package-lock.json"] = "old-lock"
        fixture.state.nodeDependencies["parent"] = "1.1.0"
        fixture.operations.scanNode("alice", fixture.state, fixture::tick)
        val snapshot = fixture.nodeSecurity.get("alice", fixture.state)
        val report = fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
        assertEquals("https://github.com/example/repo/pull/1", report.url)
        assertEquals("chore: update Node dependencies", fixture.recording.pullRequestTitle)
        assertEquals(setOf("package.json", "package-lock.json"), fixture.recording.updatedFiles.keys)
        val manifest = JsonParser.parseString(fixture.recording.updatedFiles.getValue("package.json")).asJsonObject
        assertEquals("^1.1.0", manifest.getAsJsonObject("dependencies")["parent"].asString)
        assertEquals("^1.0.0", manifest.getAsJsonObject("devDependencies")["dev-only"].asString)
        assertEquals("generated-lock", fixture.recording.updatedFiles["package-lock.json"])
        assertEquals(1, fixture.lockGenerations)
        assertEquals(snapshot, fixture.nodeSecurity.get("alice", fixture.state))
        assertEquals(0, fixture.nodeQueue.queuedTasks)
        assertEquals(0, fixture.resolveQueue.queuedTasks)
        assertFalse(fixture.recording.calls.any { it.contains("getFile:example/repo:build.gradle") })
    }

    @Test
    fun `Node Auto stops on transitive vulnerabilities unresolved packages and failed OSV queries`() {
        for (failure in listOf("vulnerable", "unresolved", "unsupported", "osv")) {
            val fixture = Fixture()
            fixture.state.nodeDependencies["parent"] = "1.1.0"
            fixture.nodeChild = ResolvedDependency("npm", "child", "1.0.0", "1.0.0", emptyList())
            when (failure) {
                "vulnerable" -> fixture.vulnerableName = "child"
                "unresolved" -> fixture.nodeResolutionFailure = true
                "unsupported" -> fixture.state.nodeDependencies["parent"] = "latest"
                "osv" -> fixture.nodeLookupFailure = true
            }
            val stopped =
                assertThrows(AutoStopped::class.java) {
                    fixture.operations.scanNode("alice", fixture.state, fixture::tick)
                }
            assertEquals(failure == "vulnerable", stopped.vulnerabilitiesFound)
            assertTrue(stopped.report.message.contains("unfinished"))
            assertFalse(fixture.recording.calls.any { it.startsWith("create") || it.startsWith("update") })
        }
    }

    @Test
    fun `Node Auto applies scanned transitive overrides and removes unused overrides through the shared PR service`() {
        val fixture = Fixture()
        fixture.files.clear()
        fixture.files["package.json"] = """{"dependencies":{"parent":"^1.0.0"},"overrides":{"unused":"^3.0.0"}}"""
        fixture.state =
            fixture.state.copy(
                nodeDependencies = mutableMapOf("parent" to "1.1.0", "cover" to "2.0.0", "unused" to "3.0.0"),
                nodeTransientDependencies = setOf("cover", "unused"),
            )
        fixture.nodeChild = ResolvedDependency("npm", "cover", "1.0.0", "1.0.0", emptyList())
        fixture.vulnerableName = "cover"
        fixture.vulnerableVersion = "1.0.0"
        val scan = fixture.operations.scanNode("alice", fixture.state, fixture::tick)
        assertTrue(scan.details!!.contains("REMOVE npm:unused:3.0.0"))
        val report = fixture.operations.createPullRequest("alice", fixture.state, "example/repo", fixture::tick)
        assertEquals("https://github.com/example/repo/pull/1", report.url)
        val manifest = JsonParser.parseString(fixture.recording.updatedFiles.getValue("package.json")).asJsonObject
        assertEquals("^2.0.0", manifest.getAsJsonObject("overrides")["cover"].asString)
        assertFalse(manifest.getAsJsonObject("overrides").has("unused"))
        assertFalse(fixture.recording.updatedFiles.containsKey("package-lock.json"))
    }

    @Test
    fun `Node Auto stops if saved targets or process ownership change while scanning`() {
        for (changeTargets in listOf(false, true)) {
            val fixture = Fixture()
            fixture.state.nodeDependencies["parent"] = "1.1.0"
            val original = fixture.state.snapshot()
            assertThrows(AutoInterrupted::class.java) {
                fixture.operations.scanNode("alice", original) {
                    if (fixture.nodeQueue.queuedTasks > 0) {
                        if (changeTargets) {
                            fixture.state.nodeDependencies["parent"] = "1.2.0"
                        } else {
                            throw AutoInterrupted("Auto ownership was lost")
                        }
                    }
                    fixture.tick()
                }
            }
            assertFalse(fixture.recording.calls.any { it.startsWith("create") || it.startsWith("update") })
        }
    }

    @Test
    fun `Node Auto keeps opt in access ignored repository and write time guards`() {
        for (guard in listOf("opt-out", "access", "ignored", "target-change", "write-opt-out", "lock-failure", "unsupported-lock")) {
            val fixture = Fixture()
            fixture.files.clear()
            fixture.files["package.json"] = """{"dependencies":{"parent":"^1.0.0"}}"""
            fixture.files["package-lock.json"] = "old-lock"
            fixture.state.nodeDependencies["parent"] = "1.1.0"
            fixture.operations.scanNode("alice", fixture.state, fixture::tick)
            when (guard) {
                "opt-out" -> fixture.optedIn = emptySet()
                "access" -> fixture.installed = emptyList()
                "ignored" -> fixture.ignored = listOf("example/repo")
                "lock-failure" -> fixture.lockFailure = true
                "unsupported-lock" -> fixture.files["pnpm-lock.yaml"] = "lockfileVersion: '9.0'"
            }
            val action = {
                fixture.operations.createPullRequest("alice", fixture.state.snapshot(), "example/repo") {
                    fixture.tick()
                    if (fixture.recording.calls.any { it.startsWith("createBranch") }) {
                        if (guard == "target-change") fixture.state.nodeDependencies["parent"] = "1.2.0"
                        if (guard == "write-opt-out") fixture.optedIn = emptySet()
                    }
                }
            }
            when (guard) {
                "target-change", "write-opt-out" -> assertThrows(AutoInterrupted::class.java) { action() }
                "lock-failure", "unsupported-lock" -> assertThrows(PullRequestReadinessException::class.java) { action() }
                else -> assertTrue(action().message.startsWith("Skipped"))
            }
            assertFalse(fixture.recording.calls.any { it.startsWith("updateFile") || it.startsWith("createPullRequest") })
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
        assertTrue(
            assertThrows(AutoStopped::class.java) {
                vulnerable.operations.scanDependencies("alice", vulnerable.state, vulnerable::tick)
            }.vulnerabilitiesFound,
        )
        val unresolved = Fixture().apply { unresolved = true }
        assertFalse(
            assertThrows(AutoStopped::class.java) {
                unresolved.operations.scanDependencies("alice", unresolved.state, unresolved::tick)
            }.vulnerabilitiesFound,
        )
        val plugin = Fixture().apply { vulnerableName = "plugin-impl" }
        plugin.operations.scanDependencies("alice", plugin.state, plugin::tick)
        plugin.operations.scanBoms("alice", plugin.state, plugin::tick)
        val stopped = assertThrows(AutoStopped::class.java) { plugin.operations.scanPlugins("alice", plugin.state, plugin::tick) }
        assertTrue(stopped.report.details!!.contains("VULNERABLE"))
        assertTrue(stopped.vulnerabilitiesFound)
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
        assertFalse(
            assertThrows(AutoStopped::class.java) {
                fixture.operations.scanBoms("alice", fixture.state, fixture::tick)
            }.vulnerabilitiesFound,
        )
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
