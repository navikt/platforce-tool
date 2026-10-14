package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.TargetSecurityStatus
import no.nav.platforce.tool.TransientDependencyUsage
import no.nav.platforce.tool.Vulnerability
import no.nav.platforce.tool.VulnerabilityEvent
import no.nav.platforce.tool.VulnerabilityRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetSecurityFlowTest {
    private val vulnerable = dependency("transitive", "1.0.0")
    private val fixed = dependency("transitive", "2.0.0")
    private val standalone = dependency("library", "1.0.0", listOf(vulnerable))
    private val advisory =
        Vulnerability(
            id = "OSV-SYNTHETIC-1",
            summary = "Synthetic vulnerable transitive dependency",
            aliases = emptyList(),
            references = emptyList(),
            fixedVersions = listOf("2.0.0"),
            affectedRanges =
                listOf(
                    VulnerabilityRange(
                        type = "ECOSYSTEM",
                        events = listOf(VulnerabilityEvent("0", null, null), VulnerabilityEvent(null, "2.0.0", null)),
                    ),
                ),
        )
    private val queriedCoordinates = mutableSetOf<String>()
    private val service =
        TargetSecurityService(
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                    val coordinates = dependencies.map { "${it.key}:${it.version}" }
                    queriedCoordinates += coordinates
                    return coordinates.associateWith { coordinate ->
                        if (coordinate == "example:transitive:1.0.0") listOf(advisory) else emptyList()
                    }
                }
            },
        )

    @Test
    fun `unfixed transitive vulnerability makes its parent target vulnerable`() {
        // Supply matching standalone and combined Gradle trees containing a synthetic vulnerable child.
        val targets = targets(includeOverride = false)
        val resolution = TargetResolution("8.1", listOf(standalone), mapOf("example:library" to listOf(standalone)))
        val result = service.scan(resolution, targets).targets.single()

        assertEquals(TargetSecurityStatus.VULNERABLE, result.status)
        assertEquals(listOf(advisory), result.vulnerabilities)
        val child = result.vulnerableDependencies.single()
        assertEquals(vulnerable, child.dependency)
        assertEquals("2.0.0", child.suggestedVersion?.version)
        assertTrue(result.relatedTo.isEmpty())
        // The parent is VULNERABLE, with child version 1.0.0 and suggested fix 2.0.0.
    }

    @Test
    fun `combined resolution can make a vulnerable standalone tree safe through an explicit override`() {
        // Resolve the parent alone with child 1.0.0, but resolve all targets together with child 2.0.0.
        val result = service.scan(overriddenResolution(), targets(includeOverride = true))
        val parent = result.targets.single { it.key == "example:library" }
        val override = parent.relatedTo.single()

        assertEquals(TargetSecurityStatus.OK_OVERRIDDEN, parent.status)
        assertEquals("example:transitive", override.dependency)
        assertEquals("1.0.0", override.vulnerableVersion)
        assertEquals("2.0.0", override.resolvedVersion)
        assertEquals(listOf("example:transitive:2.0.0"), override.causedBy)
        assertEquals(TargetSecurityStatus.OK, result.targets.single { it.key == "example:transitive" }.status)
        assertTrue(queriedCoordinates.containsAll(listOf("example:transitive:1.0.0", "example:transitive:2.0.0")))
        // Both child versions are queried. The parent records why it is safe instead of hiding the standalone vulnerability.
    }

    @Test
    fun `configured transient override records which parent uses it`() {
        // Mark the fixed child as an explicit transient target and scan both dependency trees.
        val result =
            service.scan(
                overriddenResolution(),
                targets(includeOverride = true).copy(transientDependencies = setOf("example:transitive")),
            )
        val transient = result.targets.single { it.key == "example:transitive" }

        assertEquals(TargetSecurityStatus.OK_TRANSIENT, transient.status)
        assertEquals(listOf(TransientDependencyUsage("example:library", "1.0.0")), transient.transientUsage)
        // The override is OK_TRANSIENT and records example:library as its consumer.
    }

    @Test
    fun `transient target with no consuming parent is marked unused`() {
        // Scan a fixed transient target that no other configured target depends on.
        val targets =
            TargetVersionsState(
                mutableMapOf(),
                mutableMapOf("example:transitive" to "2.0.0"),
                "8.1",
                setOf("example:transitive"),
            )
        val resolution = TargetResolution("8.1", listOf(fixed), mapOf("example:transitive" to listOf(fixed)))
        val result = service.scan(resolution, targets).targets.single()

        assertEquals(TargetSecurityStatus.TRANSIENT_UNUSED, result.status)
        assertTrue(result.transientUsage.isEmpty())
        // The result is TRANSIENT_UNUSED, which the repository view can turn into a removal proposal.
    }

    private fun overriddenResolution() =
        TargetResolution(
            gradleVersion = "8.1",
            roots = listOf(standalone.copy(dependencies = listOf(fixed)), fixed),
            individual =
                mapOf(
                    "example:library" to listOf(standalone),
                    "example:transitive" to listOf(fixed),
                ),
        )

    private fun targets(includeOverride: Boolean): TargetVersionsState {
        val dependencies = mutableMapOf("example:library" to "1.0.0")
        if (includeOverride) dependencies["example:transitive"] = "2.0.0"
        return TargetVersionsState(mutableMapOf(), dependencies, "8.1")
    }

    private fun dependency(
        name: String,
        version: String,
        children: List<ResolvedDependency> = emptyList(),
    ) = ResolvedDependency("example", name, version, version, children)
}
