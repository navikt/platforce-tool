package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.gradle.GradleDependencyParser
import no.nav.platforce.tool.gradle.GradleWrapperParser
import no.nav.platforce.tool.main
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.pullrequests.SupersededPullRequestCleanupException
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class DependencyPullRequestFlowTest {
    private val wrapperPath = "gradle/wrapper/gradle-wrapper.properties"
    private val github =
        RecordingGithubClient(
            mapOf(
                "build.gradle" to "dependencies {\n    implementation \"example:library:1.0.0\"\n}",
                wrapperPath to "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.0-bin.zip\n",
            ),
        )
    private val cache = DependencyScanCache()
    private val resolutionExecutor = QueuedExecutor()
    private val securityExecutor = QueuedExecutor()
    private val resolutionScanner =
        GradleTargetResolutionScanner({ _, state -> TargetResolution(state.gradleVersion, emptyList(), emptyMap()) }, resolutionExecutor)
    private val securityScanner =
        TargetSecurityScanner(TargetSecurityService(EmptyVulnerabilityService()), resolutionScanner, securityExecutor)
    private val scanner = DependencyScanner(github)
    private val view = DependencyScanViewService(cache, securityScanner, scanner)
    private val service = DependencyPullRequestService(github, view)
    private val targets = TargetVersionsState(mutableMapOf(), mutableMapOf("example:library" to "2.0.0"), "8.1")

    private fun ready() {
        securityScanner.start("alice", targets)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
    }

    @Test
    fun `PR flow creates branch updates build and wrapper then opens pull request`() {
        cache.update(
            "alice",
            listOf(
                inventory("example:library" to "1.0.0"),
            ),
        )
        ready()
        val url = service.createPullRequest("example", "repository", "alice", targets)

        assertEquals("https://github.com/example/repository/pull/1", url)
        assertEquals(
            listOf(
                "getDefaultBranch:example/repository",
                "resolveBranchName:example/repository",
                "getBranchHeadSha:example/repository:main",
                "createBranch:example/repository:test-dependency-update:head-sha",
                "getFile:example/repository:build.gradle",
                "getFileSha:example/repository:build.gradle:test-dependency-update",
                "updateFile:example/repository:build.gradle:file-sha:test-dependency-update",
                "getFile:example/repository:$wrapperPath",
                "getFileSha:example/repository:$wrapperPath:test-dependency-update",
                "updateFile:example/repository:$wrapperPath:file-sha:test-dependency-update",
                "createPullRequest:example/repository:test-dependency-update:main",
                "getPullRequest:example/repository:1",
                "listOpenPullRequests:example/repository:main",
            ),
            github.calls,
        )
        assertEquals(
            mapOf("example:library" to "2.0.0"),
            GradleDependencyParser().parse(github.updatedFiles.getValue("build.gradle")).dependencies,
        )
        assertEquals("8.1", GradleWrapperParser().parse(github.updatedFiles.getValue(wrapperPath)))
        assertTrue(github.pullRequestBody.orEmpty().contains("example:library"))
        // Verified every GitHub call in order and parsed both proposed files; no real GitHub calls were made.
    }

    private fun oldPullRequest(number: Int) =
        GithubPullRequest(
            number,
            "https://github.com/example/repository/pull/$number",
            99L,
            "Bot",
            "chore/update-dependencies-2026-11-01",
            "example/repository",
            "main",
            "example/repository",
            "open",
            false,
            "Automated dependency update via platforce-tool app\n\nOld findings",
        )

    @Test
    fun `successful replacement closes only app authored older dependency PRs on the same repository and base`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        val candidates =
            listOf(
                oldPullRequest(1),
                oldPullRequest(2).copy(authorId = 123),
                oldPullRequest(3).copy(authorType = "User"),
                oldPullRequest(4).copy(headBranch = "chore/add-rebuild"),
                oldPullRequest(5).copy(baseBranch = "release"),
                oldPullRequest(6).copy(headRepository = "other/fork"),
                oldPullRequest(7).copy(state = "closed"),
                oldPullRequest(8).copy(merged = true),
                oldPullRequest(9).copy(body = "Human-authored update"),
                oldPullRequest(10).copy(baseRepository = "other/repository"),
                oldPullRequest(11).copy(headRepository = null),
                oldPullRequest(12).copy(headBranch = "chore/update-dependencies-not-a-date"),
                oldPullRequest(13).copy(headBranch = "chore/update-dependencies-2026-11-01-2"),
            )
        candidates.forEach { github.pullRequests[it.number] = it }
        val closed = mutableListOf<String>()
        val url = service.createPullRequest("example", "repository", "alice", targets, onSuperseded = { closed += it })
        assertEquals("https://github.com/example/repository/pull/14", url)
        assertEquals(listOf(candidates.first().url, candidates.last().url), closed)
        assertEquals(listOf(1, 13), github.pullRequestComments.keys.toList())
        assertTrue(github.pullRequestComments.values.all { it.contains(url) })
        assertTrue(
            github.calls.indexOfFirst { it.startsWith("createPullRequest:") } <
                github.calls.indexOfFirst { it.startsWith("closePullRequest:") },
        )
        assertEquals("open", github.pullRequests.getValue(14).state)
        candidates.filter { it.number !in setOf(1, 13) }.forEach { assertEquals(it, github.pullRequests[it.number]) }
    }

    @Test
    fun `replacement creation failure never comments on or closes an existing PR`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        github.pullRequests[1] = oldPullRequest(1)
        github.creationFailure = IOException("Synthetic creation failure")
        assertThrows(IOException::class.java) { service.createPullRequest("example", "repository", "alice", targets) }
        assertEquals("open", github.pullRequests.getValue(1).state)
        assertTrue(github.pullRequestComments.isEmpty())
        assertFalse(github.calls.any { it.startsWith("listOpenPullRequests") || it.startsWith("closePullRequest") })
    }

    @Test
    fun `closed or retargeted PRs are rechecked and concurrent newer PRs are not superseded`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        github.pullRequests[1] = oldPullRequest(1)
        github.pullRequests[2] = oldPullRequest(2)
        github.onPullRequestRead = { number ->
            when (number) {
                3 -> github.pullRequests[4] = oldPullRequest(4)
                1 -> github.pullRequests[1] = oldPullRequest(1).copy(state = "closed", merged = true)
                2 -> github.pullRequests[2] = oldPullRequest(2).copy(baseBranch = "release")
            }
        }
        service.createPullRequest("example", "repository", "alice", targets)
        assertTrue(github.pullRequestComments.isEmpty())
        assertFalse(github.calls.any { it.startsWith("closePullRequest") })
        assertEquals("open", github.pullRequests.getValue(4).state)
    }

    @Test
    fun `cleanup failures explicitly report the created replacement and already closed PRs`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        github.pullRequests[1] = oldPullRequest(1)
        github.pullRequests[2] = oldPullRequest(2)
        github.failClosing = setOf(2)
        val failure =
            assertThrows(SupersededPullRequestCleanupException::class.java) {
                service.createPullRequest("example", "repository", "alice", targets)
            }
        assertEquals("https://github.com/example/repository/pull/3", failure.pullRequestUrl)
        assertEquals(listOf("https://github.com/example/repository/pull/1"), failure.closedUrls)
        assertTrue(failure.message!!.contains("Review existing PRs before retrying"))
        assertEquals("closed", github.pullRequests.getValue(1).state)
        assertEquals("open", github.pullRequests.getValue(2).state)
        assertEquals("open", github.pullRequests.getValue(3).state)
    }

    @Test
    fun `replacement metadata must still describe the open PR created by the app before any cleanup`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        listOf("closed", "User").forEach { changed ->
            github.pullRequests.clear()
            github.pullRequests[1] = oldPullRequest(1)
            github.onPullRequestRead = { number ->
                if (number == 2) {
                    val replacement = github.pullRequests.getValue(2)
                    github.pullRequests[2] =
                        if (changed == "closed") replacement.copy(state = "closed") else replacement.copy(authorType = "User")
                }
            }
            assertThrows(SupersededPullRequestCleanupException::class.java) {
                service.createPullRequest("example", "repository", "alice", targets)
            }
            assertEquals("open", github.pullRequests.getValue(1).state)
            assertTrue(github.pullRequestComments.isEmpty())
            assertFalse(github.calls.any { it.startsWith("closePullRequest") })
        }
    }

    @Test
    fun `write fencing still applies to comments and closure after replacement creation`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        ready()
        github.pullRequests[1] = oldPullRequest(1)
        assertThrows(SupersededPullRequestCleanupException::class.java) {
            service.createPullRequest("example", "repository", "alice", targets, beforeWrite = {
                if (github.pullRequestComments.isNotEmpty()) error("Lost write ownership")
            })
        }
        assertTrue(github.pullRequestComments.getValue(1).contains("/pull/2"))
        assertFalse(github.calls.any { it.startsWith("closePullRequest") })
        assertEquals("open", github.pullRequests.getValue(1).state)
    }

    @Test
    fun `wrapper-only PR also modernizes application and JUnit configuration`() {
        val client =
            RecordingGithubClient(
                mapOf(
                    "build.gradle" to
                        """
                        application {
                            mainClassName = 'example.Main'
                        }
                        dependencies {
                            testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.4'
                        }
                        """.trimIndent(),
                    wrapperPath to "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.0-bin.zip\n",
                ),
            )
        cache.update("alice", listOf(inventory("example:library" to "2.0.0")))
        ready()
        DependencyPullRequestService(client, view).createPullRequest("example", "repository", "alice", targets)
        val build = client.updatedFiles.getValue("build.gradle")
        assertTrue(build.contains("mainClass = 'example.Main'"))
        assertTrue(build.contains("testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'"))
        assertEquals("8.1", GradleWrapperParser().parse(client.updatedFiles.getValue(wrapperPath)))
    }

    @Test
    fun `matching dependencies do not create a branch or pull request`() {
        cache.update("alice", listOf(inventory("example:library" to "2.0.0").copy(gradleVersion = "8.1")))
        ready()
        // Invoke PR creation when all findings are already OK.
        service.createPullRequest("example", "repository", "alice", targets)
        assertTrue(github.calls.isEmpty())
        // No GitHub operation is performed.
    }

    @Test
    fun `missing repository scan fails before any GitHub operation`() {
        ready()
        // Invoke PR creation before a repository has been scanned.
        val error =
            assertThrows(IllegalStateException::class.java) {
                service.createPullRequest("example", "repository", "alice", targets)
            }
        assertEquals("No scan found for example/repository", error.message)
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `another users cached inventory cannot be used to create a PR`() {
        cache.update("bob", listOf(inventory("example:library" to "1.0.0")))
        // Alice must scan the repository herself before creating a PR, even if Bob already scanned it.
        assertThrows(IllegalStateException::class.java) {
            service.createPullRequest("example", "repository", "alice", targets)
        }
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `PR requires matching normal READY security before any GitHub call`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        assertThrows(IllegalStateException::class.java) { service.createPullRequest("example", "repository", "alice", targets) }
        securityScanner.start("alice", targets)
        assertThrows(IllegalStateException::class.java) { service.createPullRequest("example", "repository", "alice", targets) }
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        val changed = targets.copy(dependencies = mutableMapOf("example:library" to "3.0.0"))
        assertThrows(IllegalStateException::class.java) { service.createPullRequest("example", "repository", "alice", changed) }
        assertTrue(github.calls.isEmpty())
    }
}
