package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.gradle.GradleDependencyParser
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class OverrideAdditionFlowTest {
    private val encoder = "net.logstash.logback:logstash-logback-encoder"
    private val databind = "tools.jackson.core:jackson-databind"
    private val core = "tools.jackson.core:jackson-core"

    private fun node(
        key: String,
        version: String,
        children: List<ResolvedDependency> = emptyList(),
    ) = ResolvedDependency(key.substringBefore(":"), key.substringAfter(":"), version, version, children)

    @Test
    fun `databind alone covers both vulnerable Jackson modules in repository findings and PR`() {
        runFlow(databindIncludesCore = true)
    }

    @Test
    fun `independent overrides retain both ADDs when neither covers the other`() {
        runFlow(databindIncludesCore = false)
    }

    private fun runFlow(databindIncludesCore: Boolean) {
        val source = "dependencies {\n    implementation '$encoder:9.0'\n}"
        val github = RecordingGithubClient(mapOf("build.gradle" to source))
        val scanner = DependencyScanner(github)
        val cache = DependencyScanCache()
        cache.update("alice", listOf(scanner.scanRepository("example/repository")!!.copy(gradleVersion = "8.14.4")))
        val targets =
            TargetVersionsState(
                mutableMapOf(),
                mutableMapOf(encoder to "9.0", core to "3.1.7", databind to "3.1.7"),
                "8.14.4",
                setOf(core, databind),
            )
        val vulnerableCore = node(core, "3.0.1")
        val vulnerableDatabind = node(databind, "3.0.1", listOf(vulnerableCore))
        val standalone = node(encoder, "9.0", listOf(vulnerableDatabind))
        val fixedCore = node(core, "3.1.7")
        val fixedDatabind = node(databind, "3.1.7", if (databindIncludesCore) listOf(fixedCore) else emptyList())
        val resolutionExecutor = QueuedExecutor()
        val securityExecutor = QueuedExecutor()
        val resolutionScanner =
            GradleTargetResolutionScanner(
                { _, _ ->
                    TargetResolution(
                        "8.14.4",
                        listOf(node(encoder, "9.0", listOf(fixedDatabind, fixedCore))),
                        mapOf(encoder to listOf(standalone), databind to listOf(fixedDatabind), core to listOf(fixedCore)),
                    )
                },
                resolutionExecutor,
            )
        val security =
            TargetSecurityScanner(
                TargetSecurityService(
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                            dependencies.associate { dependency ->
                                "${dependency.key}:${dependency.version}" to
                                    if (dependency.version == "3.0.1") {
                                        listOf(Vulnerability("TEST-${dependency.name}", null, emptyList(), emptyList(), listOf("3.1.7")))
                                    } else {
                                        emptyList()
                                    }
                            }
                    },
                ),
                resolutionScanner,
                securityExecutor,
            )
        security.start("alice", targets)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        val view = DependencyScanViewService(cache, security, scanner)
        val findings = view.get("alice", targets).single().findings
        val expected = if (databindIncludesCore) setOf(databind) else setOf(databind, core)
        // No bandaids are present in the repository. Databind's individual graph proves whether it also fixes core.
        assertEquals(expected, findings.filter { it.status == DependencyStatus.ADD }.map { it.key }.toSet())
        val parent = findings.single { it.key == encoder }
        assertEquals(DependencyStatus.OK_WITH_ADD, parent.status)
        assertEquals(expected, parent.relatedTo.map { it.key }.toSet())

        DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", targets)
        val updated = GradleDependencyParser().parse(github.updatedFiles.getValue("build.gradle"))
        assertEquals(expected + encoder, updated.dependencies.keys)
        if (databindIncludesCore) {
            assertFalse(github.pullRequestBody.orEmpty().contains("ADD $core:"))
        }
    }
}
