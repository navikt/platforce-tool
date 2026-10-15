package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.gradle.GradleDependencyParser
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.RepositoryDependencyInventory
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.PlatformBomDependencyResolution
import no.nav.platforce.tool.resolution.PlatformBomResolution
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.TargetSecurityStatus
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformBomSecurityFlowTest {
    private val bomKey = "example:bom"
    private val libraryKey = "example:library"
    private val targets =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf(libraryKey to "1.0", "example:first" to "2.0", "example:second" to "2.0"),
            "8.14.4",
            platformBoms = mutableMapOf(bomKey to "1.0"),
        )
    private val scanner = DependencyScanner(RecordingGithubClient())
    private val queried = mutableSetOf<String>()
    private val advisory = Vulnerability("TEST-BOM-1", "Synthetic advisory", emptyList(), emptyList(), listOf("2.0"))
    private val service =
        TargetSecurityService(
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                    fun flatten(nodes: List<ResolvedDependency>): List<ResolvedDependency> =
                        nodes.flatMap { listOf(it) + flatten(it.dependencies) }
                    return flatten(dependencies).associate {
                        val coordinate = "${it.key}:${it.version}"
                        queried += coordinate
                        coordinate to
                            if (it.version in setOf("1.0", "4.0") &&
                                it.name in setOf("first", "second", "unused")
                            ) {
                                listOf(advisory)
                            } else {
                                emptyList()
                            }
                    }
                }
            },
        )

    private fun node(
        name: String,
        version: String,
        children: List<ResolvedDependency> = emptyList(),
    ) = ResolvedDependency("example", name, version, version, children)

    private fun resolution(
        secondFixed: Boolean = true,
        enforced: Boolean = false,
    ): TargetResolution {
        val standalone = node("library", "1.0", listOf(node("first", "1.0"), node("second", "1.0")))
        val combined = node("library", "1.0", listOf(node("first", "2.0"), node("second", "2.0")))
        val candidate = node("library", "1.0", listOf(node("first", "3.0"), node("second", if (secondFixed) "3.0" else "1.0")))
        return TargetResolution(
            "8.14.4",
            listOf(combined),
            mapOf(
                libraryKey to listOf(standalone),
                "example:first" to listOf(node("first", "2.0")),
                "example:second" to listOf(node("second", "2.0")),
            ),
            mapOf(
                bomKey to
                    PlatformBomResolution(
                        "1.0",
                        listOf(node("first", "3.0"), node("second", "3.0"), node("unused", "1.0")),
                        listOf(PlatformBomDependencyResolution(libraryKey, "1.0", enforced, listOf(candidate))),
                    ),
            ),
        )
    }

    private fun inventory(
        bomVersion: String = "1.0",
        dependencyVersion: String = "1.0",
        configuration: String = "implementation",
        mode: String = "platform",
        extra: String = "",
    ): RepositoryDependencyInventory {
        val source =
            """
            implementation $mode('$bomKey:$bomVersion')
            $configuration '$libraryKey:$dependencyVersion'
            $extra
            """.trimIndent()
        val parser = GradleDependencyParser()
        val parsed = parser.parse(source)
        return RepositoryDependencyInventory(
            "example/repository",
            "now",
            emptyMap(),
            parsed.dependencies,
            "8.14.4",
            parsed.platformBoms,
            parser.declarations(source),
        )
    }

    @Test
    fun `BOM coverage removes redundant ADDs without claiming unused managed module health`() {
        val security = service.scan(resolution(), targets)
        val baseline = security.targets.single { it.key == libraryKey }
        assertEquals(TargetSecurityStatus.OK_OVERRIDDEN, baseline.status)
        val bom = security.platformBoms.single()
        assertEquals(null, bom.status)
        assertEquals("COVERAGE_ONLY", bom.scope)
        assertTrue(bom.vulnerableDependencies.isEmpty())
        assertEquals(0, bom.managedDependencyCount)
        assertTrue(queried.containsAll(listOf("example:first:1.0", "example:first:2.0", "example:first:3.0")))
        assertFalse("example:unused:1.0" in queried)

        val inventory = inventory()
        val enriched = scanner.enrichSecurityScan(scanner.compare(inventory, targets), security, inventory)
        assertFalse(enriched.findings.any { it.status == DependencyStatus.ADD })
        val library = enriched.findings.single { it.key == libraryKey }
        assertEquals(DependencyStatus.OK_OVERRIDDEN, library.status)
        assertEquals(DependencyKind.PLATFORM_BOM, library.relatedTo.single().kind)
        assertEquals(bomKey, library.bomCoverage.single().key)
        assertEquals(DependencyStatus.OK, enriched.findings.single { it.kind == DependencyKind.PLATFORM_BOM }.status)
    }

    @Test
    fun `partial BOM coverage retains the uncovered dependency ADD`() {
        val security = service.scan(resolution(secondFixed = false), targets)
        val inventory = inventory()
        val enriched = scanner.enrichSecurityScan(scanner.compare(inventory, targets), security, inventory)
        assertEquals(listOf("example:second"), enriched.findings.filter { it.status == DependencyStatus.ADD }.map { it.key })
        assertEquals(DependencyStatus.OK_WITH_ADD, enriched.findings.single { it.key == libraryKey }.status)
        assertEquals(
            bomKey,
            enriched.findings
                .single { it.key == libraryKey }
                .bomCoverage
                .single()
                .key,
        )
    }

    @Test
    fun `unscanned mismatched wrong scope and ambiguous declarations never suppress ADDs`() {
        val security = service.scan(resolution(), targets)
        val cases =
            listOf(
                inventory(bomVersion = "0.9"),
                inventory(bomVersion = "2.0"),
                inventory(dependencyVersion = "0.9"),
                inventory(dependencyVersion = "2.0"),
                inventory(configuration = "testImplementation"),
                inventory(mode = "enforcedPlatform"),
                inventory(extra = "implementation platform('example:other-bom:1.0')"),
                inventory().copy(declarations = emptyList()),
                inventory().copy(platformBoms = emptyMap(), declarations = emptyList()),
            )
        cases.forEach { inventory ->
            val result = scanner.enrichSecurityScan(scanner.compare(inventory, targets), security, inventory)
            assertEquals(2, result.findings.count { it.status == DependencyStatus.ADD }, inventory.toString())
        }
    }

    @Test
    fun `enforced platform evidence is separate and version comparison remains available for BOMs`() {
        val security = service.scan(resolution(enforced = true), targets)
        val inventory = inventory(mode = "enforcedPlatform")
        val result = scanner.enrichSecurityScan(scanner.compare(inventory, targets), security, inventory)
        assertFalse(result.findings.any { it.status == DependencyStatus.ADD })
        val older = inventory(bomVersion = "0.9")
        val olderResult = scanner.enrichSecurityScan(scanner.compare(older, targets), security, older)
        val bom = olderResult.findings.single { it.kind == DependencyKind.PLATFORM_BOM }
        assertEquals(DependencyStatus.UPDATE, bom.status)
        assertEquals(null, bom.platformBomSecurity?.status)
    }

    @Test
    fun `missing BOM resolution fails instead of presenting an empty safe catalog`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.scan(resolution().copy(platformBoms = emptyMap()), targets)
        }
    }

    @Test
    fun `coverage introducing another vulnerable module cannot suppress dependency ADDs`() {
        val original = resolution()
        val bom = original.platformBoms.getValue(bomKey)
        val candidate = bom.coverage.single()
        val unsafe =
            bom.copy(
                coverage =
                    listOf(
                        candidate.copy(roots = candidate.roots + node("unused", "1.0")),
                    ),
            )
        val security = service.scan(original.copy(platformBoms = mapOf(bomKey to unsafe)), targets)
        assertTrue(
            security.platformBoms
                .single()
                .coverage
                .single()
                .overrides
                .isEmpty(),
        )
        val inventory = inventory()
        val result = scanner.enrichSecurityScan(scanner.compare(inventory, targets), security, inventory)
        assertEquals(2, result.findings.count { it.status == DependencyStatus.ADD })
    }

    @Test
    fun `coverage introducing a different vulnerable version of an existing module cannot suppress any ADDs`() {
        val normal = resolution().copy(platformBoms = emptyMap())
        val baseline = service.scan(normal, targets.copy(platformBoms = mutableMapOf()))
        val bom = resolution().platformBoms.getValue(bomKey)
        val candidate = bom.coverage.single()
        val unsafe = node("library", "1.0", listOf(node("first", "4.0"), node("second", "3.0")))
        val result =
            service.scanCoverage(
                normal.copy(platformBoms = mapOf(bomKey to bom.copy(coverage = listOf(candidate.copy(roots = listOf(unsafe)))))),
                targets,
                baseline,
            )
        assertTrue("example:first:4.0" in queried)
        assertTrue(
            result.platformBoms
                .single()
                .coverage
                .single()
                .overrides
                .isEmpty(),
        )
    }

    @Test
    fun `BOM only security scan makes no managed module health claim`() {
        val bomOnly = targets.copy(dependencies = mutableMapOf())
        val resolution = resolution().copy(roots = emptyList(), individual = emptyMap())
        val result = service.scan(resolution, bomOnly)
        assertTrue(result.targets.isEmpty())
        assertEquals(null, result.platformBoms.single().status)
        assertFalse("example:unused:1.0" in queried)
    }

    @Test
    fun `ordinary scan excludes BOM work and a new dependency scan invalidates coverage`() {
        val resolutionExecutor = QueuedExecutor()
        val securityExecutor = QueuedExecutor()
        val inputs = mutableListOf<TargetVersionsState>()
        val resolutionScanner =
            GradleTargetResolutionScanner(
                { _, state ->
                    inputs += state
                    if (state.platformBoms.isEmpty()) resolution().copy(platformBoms = emptyMap()) else resolution()
                },
                resolutionExecutor,
                coverageResolve = { _, state, baseline, relevant ->
                    inputs += state
                    assertEquals(setOf(libraryKey), relevant)
                    resolution().copy(roots = baseline.roots, individual = baseline.individual)
                },
            )
        val securityScanner = TargetSecurityScanner(service, resolutionScanner, securityExecutor)

        securityScanner.start("alice", targets)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        assertTrue(inputs.single().platformBoms.isEmpty())
        assertTrue(
            securityScanner
                .get("alice", targets)
                .result!!
                .platformBoms
                .isEmpty(),
        )
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", targets, includeBoms = true).status)

        securityScanner.start("alice", targets, includeBoms = true)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        assertEquals(targets.platformBoms, inputs.last().platformBoms)
        assertEquals(
            bomKey,
            securityScanner
                .get("alice", targets)
                .result!!
                .platformBoms
                .single()
                .key,
        )
        assertTrue(securityScanner.get("bob", targets).result == null)
        queried.clear()
        assertEquals(SecurityScanStatus.READY, securityScanner.start("alice", targets, includeBoms = true).status)
        assertTrue(securityScanner.get("alice", targets).bomReady)
        assertEquals(0, resolutionExecutor.queuedTasks)
        assertEquals(0, securityExecutor.queuedTasks)
        assertTrue(queried.isEmpty())

        securityScanner.start("alice", targets)
        assertEquals(0, resolutionExecutor.queuedTasks)
        assertEquals(SecurityScanStatus.RUNNING, securityScanner.get("alice", targets).status)
        securityExecutor.runNext()
        assertEquals(2, inputs.size)
        assertTrue(
            securityScanner
                .get("alice", targets)
                .result!!
                .platformBoms
                .isEmpty(),
        )
        assertFalse(securityScanner.get("alice", targets).bomReady)

        securityScanner.invalidate("alice")
        resolutionScanner.invalidate("alice")
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", targets).status)
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("alice", targets, includeBoms = true).status)
    }

    @Test
    fun `both BOM start paths fail without a matching normal READY security baseline`() {
        val resolutionExecutor = QueuedExecutor()
        val securityExecutor = QueuedExecutor()
        val resolver = GradleTargetResolutionScanner({ _, _ -> resolution().copy(platformBoms = emptyMap()) }, resolutionExecutor)
        val security = TargetSecurityScanner(service, resolver, securityExecutor)
        assertEquals(no.nav.platforce.tool.resolution.ResolutionStatus.FAILED, resolver.start("alice", targets, true).status)
        assertEquals(SecurityScanStatus.FAILED, security.start("alice", targets, true).status)
        assertEquals(0, resolutionExecutor.queuedTasks)
        assertEquals(0, securityExecutor.queuedTasks)
        security.start("alice", targets)
        assertEquals(SecurityScanStatus.FAILED, security.start("alice", targets, true).status)
        resolutionExecutor.runNext()
        assertEquals(SecurityScanStatus.FAILED, security.start("alice", targets, true).status)
        securityExecutor.runNext()
        assertTrue(security.get("alice", targets).normalReady)
        assertFalse(security.get("alice", targets).bomReady)
        assertEquals(SecurityScanStatus.FAILED, security.start("bob", targets, true).status)
        val changed = targets.copy(dependencies = mutableMapOf(libraryKey to "2.0"))
        assertEquals(SecurityScanStatus.FAILED, security.start("alice", changed, true).status)
    }

    @Test
    fun `coverage reuses baseline security and checks full candidate graphs including changed vulnerable versions`() {
        val normalResolution = resolution().copy(platformBoms = emptyMap())
        val baseline = service.scan(normalResolution, targets.copy(platformBoms = mutableMapOf()))
        queried.clear()
        val bom = resolution().platformBoms.getValue(bomKey)
        val candidate = bom.coverage.single()
        val changedVulnerable =
            bom.copy(
                coverage =
                    listOf(
                        candidate.copy(
                            roots = candidate.roots + node("unused", "1.0", listOf(node("hidden", "3.0", listOf(node("first", "2.0"))))),
                        ),
                    ),
            )
        val result = service.scanCoverage(normalResolution.copy(platformBoms = mapOf(bomKey to changedVulnerable)), targets, baseline)
        assertEquals(baseline.targets, result.targets)
        assertEquals(baseline.dependencies, result.dependencies)
        assertFalse("example:first:1.0" in queried)
        assertFalse("example:first:2.0" in queried)
        assertTrue("example:hidden:3.0" in queried)
        assertTrue(
            result.platformBoms
                .single()
                .coverage
                .single()
                .overrides
                .isEmpty(),
        )
    }

    @Test
    fun `PR gate requires matching BOM READY even for untracked platform inventory`() {
        val github = RecordingGithubClient()
        val resolutionExecutor = QueuedExecutor()
        val securityExecutor = QueuedExecutor()
        val resolver = GradleTargetResolutionScanner({ _, _ -> resolution().copy(platformBoms = emptyMap()) }, resolutionExecutor)
        val security = TargetSecurityScanner(service, resolver, securityExecutor)
        val cache = DependencyScanCache()
        cache.update("alice", listOf(inventory()))
        val view = DependencyScanViewService(cache, security, scanner)
        val pr = DependencyPullRequestService(github, view)
        security.start("alice", targets)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        assertThrows(IllegalStateException::class.java) { pr.createPullRequest("example", "repository", "alice", targets) }
        val untracked = targets.copy(platformBoms = mutableMapOf())
        assertThrows(IllegalStateException::class.java) { pr.createPullRequest("example", "repository", "alice", untracked) }
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `PR retains only uncovered additions and BOM evidence remains user and version isolated`() {
        val source =
            """
            dependencies {
                implementation platform('$bomKey:1.0')
                implementation '$libraryKey:1.0'
            }
            """.trimIndent()
        val github = RecordingGithubClient(mapOf("build.gradle" to source))
        val resolutionExecutor = QueuedExecutor()
        val securityExecutor = QueuedExecutor()
        val resolutionScanner =
            GradleTargetResolutionScanner(
                { _, _ -> resolution(secondFixed = false).copy(platformBoms = emptyMap()) },
                resolutionExecutor,
                coverageResolve = { _, _, baseline, _ ->
                    resolution(secondFixed = false).copy(roots = baseline.roots, individual = baseline.individual)
                },
            )
        val securityScanner = TargetSecurityScanner(service, resolutionScanner, securityExecutor)
        val cache = DependencyScanCache()
        cache.update("alice", listOf(inventory()))
        cache.update("bob", listOf(inventory()))
        val view = DependencyScanViewService(cache, securityScanner, scanner)
        securityScanner.start("alice", targets)
        resolutionExecutor.runNext()
        securityExecutor.runNext()
        securityScanner.start("alice", targets, includeBoms = true)
        resolutionExecutor.runNext()
        securityExecutor.runNext()

        assertEquals(
            1,
            view
                .get("alice", targets)
                .single()
                .findings
                .count { it.status == DependencyStatus.ADD },
        )
        assertEquals(SecurityScanStatus.IDLE, securityScanner.get("bob", targets).status)
        assertTrue(
            view
                .get("bob", targets)
                .single()
                .findings
                .none { it.bomCoverage.isNotEmpty() },
        )
        val changed = targets.copy(platformBoms = mutableMapOf(bomKey to "2.0"))
        assertEquals(SecurityScanStatus.READY, securityScanner.get("alice", changed).status)
        assertFalse(securityScanner.get("alice", changed).bomReady)
        assertTrue(
            view
                .get("alice", changed)
                .single()
                .findings
                .none { it.bomCoverage.isNotEmpty() },
        )
        assertThrows(IllegalStateException::class.java) {
            DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", changed)
        }
        assertThrows(IllegalStateException::class.java) {
            DependencyPullRequestService(github, view).createPullRequest("example", "repository", "bob", targets)
        }
        assertTrue(github.calls.isEmpty())
        assertEquals(SecurityScanStatus.READY, securityScanner.get("alice", targets.copy(platformBoms = mutableMapOf())).status)

        // The PR uses the same enriched view as the GUI; the BOM-covered first module must not reappear.
        DependencyPullRequestService(github, view).createPullRequest("example", "repository", "alice", targets)
        val updated = github.updatedFiles.getValue("build.gradle")
        assertTrue(updated.contains("example:second:2.0"), updated)
        assertFalse(updated.contains("example:first:2.0"), updated)
        assertTrue(updated.contains("platform('$bomKey:1.0')"), updated)
        assertFalse(github.pullRequestBody.orEmpty().contains("ADD example:first"))
    }
}
