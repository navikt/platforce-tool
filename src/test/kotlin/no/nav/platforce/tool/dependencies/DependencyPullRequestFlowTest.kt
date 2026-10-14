package no.nav.platforce.tool.dependencies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    private val resolutionScanner = GradleTargetResolutionScanner({ error("Unexpected Gradle resolution") })
    private val securityScanner = TargetSecurityScanner(TargetSecurityService(EmptyVulnerabilityService()), resolutionScanner)
    private val scanner = DependencyScanner(github)
    private val view = DependencyScanViewService(cache, securityScanner, scanner)
    private val service = DependencyPullRequestService(github, view)
    private val targets = TargetVersionsState(mutableMapOf(), mutableMapOf("example:library" to "2.0.0"), "8.1")

    @Test
    fun `PR flow creates branch updates build and wrapper then opens pull request`() {
        cache.update(
            "alice",
            listOf(
                inventory("example:library" to "1.0.0"),
            ),
        )
        // Invoke PR creation with an existing scan and no available security snapshot.
        val url = service.createPullRequest("example", "repository", "alice", targets)

        assertEquals("https://github.example.test/example/repository/pull/1", url)
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

    @Test
    fun `matching dependencies do not create a branch or pull request`() {
        cache.update("alice", listOf(inventory("example:library" to "2.0.0").copy(gradleVersion = "8.1")))
        // Invoke PR creation when all findings are already OK.
        service.createPullRequest("example", "repository", "alice", targets)
        assertTrue(github.calls.isEmpty())
        // No GitHub operation is performed.
    }

    @Test
    fun `missing repository scan fails before any GitHub operation`() {
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
}
