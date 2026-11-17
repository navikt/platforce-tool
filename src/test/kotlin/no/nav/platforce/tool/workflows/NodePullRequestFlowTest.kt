package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.node.NodeCombinedResolution
import no.nav.platforce.tool.node.NodeSecurityScanner
import no.nav.platforce.tool.node.NodeSecurityService
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class NodePullRequestFlowTest {
    private val source =
        """{"name":"example","version":"1.0.0","dependencies":{"parent":"^1.0.0"},"overrides":{"unused":"^3.0.0"},"scripts":{"test":"original"}}"""
    private val files = mutableMapOf("package.json" to source)
    private val github = RecordingGithubClient(files)
    private val cache = DependencyScanCache()
    private val scanner = DependencyScanner(github)
    private val executor = QueuedExecutor()
    private val state =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf(),
            "8.0",
            nodeDependencies = mutableMapOf("parent" to "1.1.0", "cover" to "2.0.0", "unused" to "3.0.0"),
            nodeTransientDependencies = setOf("cover", "unused"),
        )
    private val vulnerabilities =
        object : VulnerabilityService {
            override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                dependencies.associate { dependency ->
                    "${dependency.key}:${dependency.version}" to
                        if (dependency.name == "cover" && dependency.version == "1.0.0") {
                            listOf(Vulnerability("TEST", "Synthetic", emptyList(), emptyList(), listOf("2.0.0")))
                        } else {
                            emptyList()
                        }
                }
        }

    private fun dependency(
        name: String,
        version: String,
    ) = ResolvedDependency("npm", name, version, version, emptyList())

    private val nodeScanner =
        NodeSecurityScanner(
            NodeSecurityService(
                { key, version ->
                    listOf(dependency(key, version)) + if (key == "parent") listOf(dependency("cover", "1.0.0")) else emptyList()
                },
                vulnerabilities,
                { _, _ ->
                    val parent = listOf(dependency("parent", "1.1.0"), dependency("cover", "2.0.0"))
                    NodeCombinedResolution(parent, mapOf("parent" to parent, "cover" to listOf(parent.last()), "unused" to emptyList()))
                },
            ),
            executor,
        )
    private val gradle =
        TargetSecurityScanner(
            TargetSecurityService(EmptyVulnerabilityService()),
            GradleTargetResolutionScanner({ _, _ -> error("Node must not resolve with Gradle") }, QueuedExecutor()),
            QueuedExecutor(),
        )
    private val view = DependencyScanViewService(cache, gradle, scanner, nodeScanner)

    private fun ready(targets: TargetVersionsState = state) {
        val inventory = checkNotNull(scanner.scanRepository("example/repository"))
        cache.update("alice", listOf(inventory))
        cache.update("bob", listOf(inventory))
        nodeScanner.start("alice", targets)
        executor.runNext()
        assertTrue(nodeScanner.ready("alice", targets), nodeScanner.get("alice", targets).error)
        github.calls.clear()
    }

    @Test
    fun `Node PR updates manifest and existing lock then opens the usual dependency PR without Gradle files`() {
        files["package-lock.json"] = "original-lock"
        ready()
        var generations = 0
        val service =
            DependencyPullRequestService(github, view) { manifest, resolution, lock, name ->
                generations++
                assertEquals("original-lock", lock)
                assertEquals("package-lock.json", name)
                val final = JsonParser.parseString(manifest).asJsonObject
                assertEquals("^1.1.0", final.getAsJsonObject("dependencies")["parent"].asString)
                assertEquals("^2.0.0", final.getAsJsonObject("overrides")["cover"].asString)
                assertFalse(final.getAsJsonObject("overrides").has("unused"))
                assertEquals("original", final.getAsJsonObject("scripts")["test"].asString)
                assertEquals(
                    "1.1.0",
                    JsonParser
                        .parseString(resolution)
                        .asJsonObject
                        .getAsJsonObject("dependencies")["parent"]
                        .asString,
                )
                assertFalse(github.calls.any { it.startsWith("createBranch:") })
                "generated-lock"
            }
        var writes = 0
        val url = service.createPullRequest("example", "repository", "alice", state, beforeWrite = { writes++ })
        assertEquals("https://github.com/example/repository/pull/1", url)
        assertEquals(1, generations)
        assertEquals(4, writes)
        assertEquals(setOf("package.json", "package-lock.json"), github.updatedFiles.keys)
        assertEquals("generated-lock", github.updatedFiles["package-lock.json"])
        assertEquals("chore: update Node dependencies", github.pullRequestTitle)
        assertTrue(github.pullRequestBody!!.contains("- DEPENDENCY parent: ^1.0.0 → ^1.1.0"))
        assertTrue(github.pullRequestBody!!.contains("- ADD cover:^2.0.0"))
        assertTrue(github.pullRequestBody!!.contains("- REMOVE unused:^3.0.0"))
        assertTrue(github.calls.filter { it.startsWith("getFileAtRef:") }.all { it.endsWith(":head-sha") })
        assertFalse(github.calls.any { it.contains("build.gradle") || it.contains("gradle-wrapper") })
        assertTrue(
            github.calls.indexOfFirst { it.contains("updateFile:example/repository:package-lock.json") } <
                github.calls.indexOfFirst { it.startsWith("createPullRequest:") },
        )
    }

    @Test
    fun `development PR updates and required additions keep their section through a repository refresh`() {
        files["package.json"] = source.replace("\"dependencies\"", "\"devDependencies\"")
        files["package-lock.json"] = "original-lock"
        val targets = state.copy(nodeTransientDependencies = setOf("unused"))
        ready(targets)
        val service =
            DependencyPullRequestService(github, view) { manifest, resolution, _, _ ->
                val final = JsonParser.parseString(manifest).asJsonObject
                assertFalse(final.has("dependencies"))
                assertFalse(final.has("overrides"))
                assertEquals("^1.1.0", final.getAsJsonObject("devDependencies")["parent"].asString)
                assertEquals("^2.0.0", final.getAsJsonObject("devDependencies")["cover"].asString)
                val exact = JsonParser.parseString(resolution).asJsonObject.getAsJsonObject("devDependencies")
                assertEquals("1.1.0", exact["parent"].asString)
                assertEquals("2.0.0", exact["cover"].asString)
                "generated-dev-lock"
            }
        service.createPullRequest("example", "repository", "alice", targets)
        assertEquals("generated-dev-lock", github.updatedFiles["package-lock.json"])
        assertTrue(github.pullRequestBody!!.contains("- ADD cover:^2.0.0 (required dependency for parent:1.1.0)"))
        files["package.json"] = checkNotNull(github.updatedFiles["package.json"])
        cache.updateRepository("alice", checkNotNull(scanner.scanRepository("example/repository")))
        val merged = view.get("alice", targets).single()
        assertTrue(merged.findings.all { it.declaration == "devDependencies" })
        assertEquals(
            mapOf("parent" to DependencyStatus.OK_OVERRIDDEN, "cover" to DependencyStatus.OK),
            merged.findings.associate { it.key to it.status },
        )
    }

    @Test
    fun `repositories without a lockfile do not gain one and npm shrinkwrap is retained by name`() {
        ready()
        val service = DependencyPullRequestService(github, view) { _, _, _, _ -> error("No lockfile should be generated") }
        service.createPullRequest("example", "repository", "alice", state)
        assertEquals(setOf("package.json"), github.updatedFiles.keys)

        files["npm-shrinkwrap.json"] = "old-shrinkwrap"
        val shrinkwrap =
            DependencyPullRequestService(github, view) { _, _, lock, path ->
                assertEquals("old-shrinkwrap", lock)
                assertEquals("npm-shrinkwrap.json", path)
                "new-shrinkwrap"
            }
        shrinkwrap.createPullRequest("example", "repository", "alice", state)
        assertEquals("new-shrinkwrap", github.updatedFiles["npm-shrinkwrap.json"])
        assertFalse(github.updatedFiles.containsKey("package-lock.json"))
    }

    @Test
    fun `Node PR readiness is user and fingerprint scoped and does not depend on Gradle`() {
        ready()
        val service = DependencyPullRequestService(github, view)
        listOf("bob" to state, "alice" to state.copy(nodeTransientDependencies = emptySet())).forEach { (user, targets) ->
            assertThrows(PullRequestReadinessException::class.java) {
                service.createPullRequest("example", "repository", user, targets)
            }
        }
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `completed Node scans with unresolved targets cannot create a PR`() {
        ready()
        state.nodeDependencies["unsupported"] = "latest"
        nodeScanner.start("alice", state)
        executor.runNext()
        assertFalse(nodeScanner.ready("alice", state))
        assertThrows(PullRequestReadinessException::class.java) {
            DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", state)
        }
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `unsupported lockfiles and package managers fail before creating a branch`() {
        ready()
        val service = DependencyPullRequestService(github, view) { _, _, _, _ -> error("Must not resolve unsupported projects") }
        listOf("yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb").forEach { path ->
            files[path] = "unsupported"
            val failure =
                assertThrows(PullRequestReadinessException::class.java) {
                    service.createPullRequest("example", "repository", "alice", state)
                }
            assertTrue(failure.message!!.contains(path))
            files.remove(path)
        }
        files["package.json"] = source.replace("\"name\":\"example\"", "\"name\":\"example\",\"packageManager\":\"pnpm@9.0.0\"")
        assertThrows(PullRequestReadinessException::class.java) { service.createPullRequest("example", "repository", "alice", state) }
        files["package.json"] = source.dropLast(1) + ""","workspaces":["packages/*"]}"""
        assertThrows(PullRequestReadinessException::class.java) { service.createPullRequest("example", "repository", "alice", state) }
        files["package.json"] = source
        files["package-lock.json"] = "lock"
        files["npm-shrinkwrap.json"] = "shrinkwrap"
        assertThrows(PullRequestReadinessException::class.java) { service.createPullRequest("example", "repository", "alice", state) }
        assertFalse(github.calls.any { it.startsWith("createBranch:") })
    }

    @Test
    fun `lock failures stale manifests and scans invalidated during preparation create no branch or PR`() {
        files["package-lock.json"] = "old-lock"
        ready()
        val failing = DependencyPullRequestService(github, view) { _, _, _, _ -> throw IOException("Synthetic npm failure") }
        val failure =
            assertThrows(PullRequestReadinessException::class.java) {
                failing.createPullRequest("example", "repository", "alice", state)
            }
        assertTrue(failure.message!!.contains("Synthetic npm failure"))
        files["package.json"] = source.replace("^1.0.0", "^1.0.1")
        assertThrows(PullRequestReadinessException::class.java) {
            DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", state)
        }
        files["package.json"] = source
        val invalidated =
            DependencyPullRequestService(github, view) { _, _, _, _ ->
                nodeScanner.invalidate("alice")
                "generated-lock"
            }
        assertThrows(PullRequestReadinessException::class.java) { invalidated.createPullRequest("example", "repository", "alice", state) }
        assertFalse(
            github.calls.any { it.startsWith("createBranch:") || it.startsWith("updateFile:") || it.startsWith("createPullRequest:") },
        )
    }

    @Test
    fun `a successful Node replacement closes the previous app authored dependency PR`() {
        ready()
        github.pullRequests[1] =
            GithubPullRequest(
                1,
                "https://github.com/example/repository/pull/1",
                99,
                "Bot",
                "chore/update-dependencies-2026-11-01",
                "example/repository",
                "main",
                "example/repository",
                "open",
                false,
                "Automated dependency update via platforce-tool app\n\nOld findings",
            )
        val closed = mutableListOf<String>()
        DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", state, onSuperseded = closed::add)
        assertEquals(listOf("https://github.com/example/repository/pull/1"), closed)
        assertEquals("closed", github.pullRequests.getValue(1).state)
        assertEquals("open", github.pullRequests.getValue(2).state)
    }
}
