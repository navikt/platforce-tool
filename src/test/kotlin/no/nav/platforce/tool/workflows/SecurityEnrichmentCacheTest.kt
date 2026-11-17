package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.node.NodeCombinedResolution
import no.nav.platforce.tool.node.NodeDependencyDeclaration
import no.nav.platforce.tool.node.NodeSecurityScanner
import no.nav.platforce.tool.node.NodeSecurityService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.RepositoryProjectType
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolutionStatus
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityEnrichmentCacheTest {
    private val resolutionExecutor = QueuedExecutor()
    private val securityExecutor = QueuedExecutor()
    private val targets =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf("example:library" to "1.0.0", "example:override" to "2.0.0"),
            "8.0",
        )
    private val vulnerable = ResolvedDependency("example", "override", "1.0.0", "1.0.0", emptyList())
    private val fixed = vulnerable.copy(version = "2.0.0")
    private val standalone = ResolvedDependency("example", "library", "1.0.0", "1.0.0", listOf(vulnerable))
    private var vulnerabilityQueries = 0
    private val resolutionScanner =
        GradleTargetResolutionScanner(
            { _, _ ->
                TargetResolution(
                    "8.0",
                    listOf(standalone.copy(dependencies = listOf(fixed)), fixed),
                    mapOf("example:library" to listOf(standalone), "example:override" to listOf(fixed)),
                )
            },
            resolutionExecutor,
        )
    private val securityScanner =
        TargetSecurityScanner(
            TargetSecurityService(
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                        vulnerabilityQueries++
                        return mapOf(
                            "example:override:1.0.0" to
                                listOf(Vulnerability("OSV-SYNTHETIC", null, emptyList(), emptyList(), listOf("2.0.0"))),
                        )
                    }
                },
            ),
            resolutionScanner,
            securityExecutor,
        )
    private val cache = DependencyScanCache()
    private val github = RecordingGithubClient()
    private val scanner = DependencyScanner(github)
    private val view = DependencyScanViewService(cache, securityScanner, scanner)

    @Test
    fun `completed security scan immediately enriches an existing repo inventory without refresh scan`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        val before = view.get("alice", targets).single()
        assertEquals(DependencyStatus.OK, before.findings.first().status)

        // Finish target scanning only. There is no repository refresh or GitHub file fetch between these reads.
        completeSecurityScan("alice")
        val after = view.get("alice", targets).single()
        assertEquals(DependencyStatus.OK_WITH_ADD, after.findings.first().status)
        assertEquals("example:override", after.findings.single { it.status == DependencyStatus.ADD }.key)
        assertEquals(before.scannedAt, after.scannedAt)
        assertEquals(mapOf("example:library" to "1.0.0"), cache.get("alice").single().dependencies)
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `security enrichment is not shared even when users have identical targets and repo inventories`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        cache.update("bob", listOf(inventory("example:library" to "1.0.0")))
        completeSecurityScan("alice")
        assertEquals(
            DependencyStatus.OK_WITH_ADD,
            view
                .get("alice", targets)
                .single()
                .findings
                .first()
                .status,
        )
        assertEquals(
            DependencyStatus.OK,
            view
                .get("bob", targets)
                .single()
                .findings
                .first()
                .status,
        )
        assertEquals(ResolutionStatus.IDLE, resolutionScanner.get("bob", targets).status)
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("bob", targets).status)

        completeSecurityScan("bob")
        resolutionScanner.invalidate("alice")
        securityScanner.invalidate("alice")
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", targets).status)
        assertEquals(SecurityScanStatus.READY, securityScanner.get("bob", targets).status)
        assertEquals(ResolutionStatus.READY, resolutionScanner.get("bob", targets).status)
        assertEquals(
            DependencyStatus.OK_WITH_ADD,
            view
                .get("bob", targets)
                .single()
                .findings
                .first()
                .status,
        )
    }

    @Test
    fun `changing targets or transient flags hides old security results without removing repo inventory`() {
        cache.update("alice", listOf(inventory("example:library" to "1.0.0")))
        completeSecurityScan("alice")
        val changed = targets.copy(dependencies = mutableMapOf("example:library" to "2.0.0"))
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", changed).status)
        assertEquals(
            DependencyStatus.UPDATE,
            view
                .get("alice", changed)
                .single()
                .findings
                .first()
                .status,
        )
        assertTrue(
            view
                .get("alice", changed)
                .single()
                .findings
                .none { it.status == DependencyStatus.ADD },
        )

        val transient = targets.copy(transientDependencies = setOf("example:override"))
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", transient).status)
        assertEquals(
            DependencyStatus.OK,
            view
                .get("alice", transient)
                .single()
                .findings
                .first()
                .status,
        )
        assertEquals(1, cache.get("alice").size)
    }

    @Test
    fun `explicit security rescan refreshes vulnerabilities while reads and resolution reuse cached results`() {
        completeSecurityScan("alice")
        repeat(3) { securityScanner.get("alice", targets) }
        assertEquals(1, vulnerabilityQueries)
        assertEquals(ResolutionStatus.READY, resolutionScanner.start("alice", targets).status)
        assertEquals(0, resolutionExecutor.queuedTasks)

        // SCAN performs a new vulnerability lookup even if versions are unchanged; polling does not.
        assertEquals(SecurityScanStatus.RUNNING, securityScanner.start("alice", targets).status)
        assertEquals(SecurityScanStatus.RUNNING, securityScanner.start("alice", targets).status)
        assertEquals(1, securityExecutor.queuedTasks)
        securityExecutor.runNext()
        assertEquals(2, vulnerabilityQueries)
        assertEquals(SecurityScanStatus.READY, securityScanner.get("alice", targets).status)
    }

    @Test
    fun `Node security enriches cached inventories only for the matching user and target fingerprint`() {
        val executor = QueuedExecutor()
        val nodeScanner =
            NodeSecurityScanner(
                NodeSecurityService(
                    { key, version -> listOf(ResolvedDependency("npm", key, version, version, emptyList())) },
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                            dependencies.associate { "${it.key}:${it.version}" to emptyList() }
                    },
                    { _, _ -> NodeCombinedResolution(emptyList(), mapOf("uuid" to emptyList())) },
                ),
                executor,
            )
        val nodeTargets =
            targets.copy(nodeDependencies = mutableMapOf("uuid" to "11.1.1"), nodeTransientDependencies = setOf("uuid"))
        val nodeInventory =
            inventory("example:library" to "1.0.0").copy(
                projectType = RepositoryProjectType.NODE,
                nodeDependencies = listOf(NodeDependencyDeclaration("uuid", "^11.1.1", "overrides > uuid")),
            )
        cache.update("alice", listOf(nodeInventory))
        cache.update("bob", listOf(nodeInventory))
        val nodeView = DependencyScanViewService(cache, securityScanner, scanner, nodeScanner)
        assertEquals(
            DependencyStatus.OK,
            nodeView
                .get("alice", nodeTargets)
                .single()
                .findings
                .single()
                .status,
        )
        nodeScanner.start("alice", nodeTargets)
        executor.runNext()
        assertEquals(SecurityScanStatus.READY, nodeScanner.get("alice", nodeTargets).status)
        assertEquals(
            DependencyStatus.REMOVE,
            nodeView
                .get("alice", nodeTargets)
                .single()
                .findings
                .single()
                .status,
        )
        assertEquals(
            DependencyStatus.OK,
            nodeView
                .get("bob", nodeTargets)
                .single()
                .findings
                .single()
                .status,
        )
        val changed = nodeTargets.copy(nodeTransientDependencies = emptySet())
        assertEquals(
            DependencyStatus.OK,
            nodeView
                .get("alice", changed)
                .single()
                .findings
                .single()
                .status,
        )
        assertEquals(nodeInventory, cache.get("alice").single())
        assertTrue(github.calls.isEmpty())
    }

    private fun completeSecurityScan(username: String) {
        resolutionScanner.start(username, targets)
        resolutionExecutor.runNext()
        securityScanner.start(username, targets)
        securityExecutor.runNext()
        assertEquals(SecurityScanStatus.READY, securityScanner.get(username, targets).status)
    }
}
