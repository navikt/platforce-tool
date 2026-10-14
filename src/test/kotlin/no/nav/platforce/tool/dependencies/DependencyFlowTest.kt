package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.OverrideReason
import no.nav.platforce.tool.TargetSecurityResult
import no.nav.platforce.tool.TargetSecurityStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DependencyFlowTest {
    private val github = RecordingGithubClient()
    private val securityScanner = TargetSecurityScanner(TargetSecurityService(EmptyVulnerabilityService()))
    private val scanner = DependencyScanner(github, securityScanner)

    @Test
    fun `parse build file and distinguish matching older newer and missing dependencies`() {
        // Parse a Groovy build with a versioned plugin and both dependency declaration styles.
        val parsed =
            GradleDependencyParser().parse(
                """
                plugins {
                    id 'example.plugin' version '2.0.0'
                }
                dependencies {
                    implementation "example:library:1.9.0"
                    testImplementation("example:test-library:3.0.0")
                }
                """.trimIndent(),
            )

        assertEquals(mapOf("example.plugin" to "2.0.0"), parsed.plugins)
        assertEquals(mapOf("example:library" to "1.9.0", "example:test-library" to "3.0.0"), parsed.dependencies)
        // Compare parsed versions with target 2.0.0: OK, UPDATE, AHEAD and ADD.
        assertEquals(DependencyStatus.OK, scanner.dependencyStatus(parsed.plugins["example.plugin"], "2.0.0"))
        assertEquals(DependencyStatus.UPDATE, scanner.dependencyStatus(parsed.dependencies["example:library"], "2.0.0"))
        assertEquals(DependencyStatus.AHEAD, scanner.dependencyStatus(parsed.dependencies["example:test-library"], "2.0.0"))
        assertEquals(DependencyStatus.ADD, scanner.dependencyStatus(parsed.dependencies["example:missing"], "2.0.0"))
    }

    @Test
    fun `missing transitive override produces an add finding without modifying the cached scan`() {
        val raw = scan(finding("example:library"))
        val cache = DependencyScanCache().apply { update(listOf(raw)) }
        // Enrich a cached repository whose target is safe only with example:override:2.0.0.
        val enriched = scanner.enrichSecurityScan(cache.get().single(), overrideSecurity())

        assertEquals(DependencyStatus.OK_WITH_ADD, enriched.findings.first().status)
        val addition = enriched.findings.single { it.status == DependencyStatus.ADD }
        assertEquals("example:override", addition.key)
        assertEquals("2.0.0", addition.targetVersion)
        assertEquals(listOf(DependencyReference(DependencyKind.DEPENDENCY, "example:library", "1.0.0")), addition.relatedTo)
        assertEquals(listOf(raw), cache.get())
        // The view proposes ADD, while the original cached finding remains OK.

        val updated =
            GradleBuildFileUpdater().apply(
                "dependencies {\n    implementation \"example:library:1.0.0\"\n}",
                enriched.findings,
            )
        assertEquals(
            mapOf("example:library" to "1.0.0", "example:override" to "2.0.0"),
            GradleDependencyParser().parse(updated).dependencies,
        )
        // Apply the proposal to build.gradle and parse it again to verify the override was added.
    }

    @Test
    fun `present override avoids an unnecessary add finding`() {
        // Enrich a repository that already declares the required override at its target version.
        val enriched =
            scanner.enrichSecurityScan(
                scan(finding("example:library"), finding("example:override", version = "2.0.0")),
                overrideSecurity(),
            )

        assertEquals(DependencyStatus.OK_OVERRIDDEN, enriched.findings.first().status)
        assertTrue(enriched.findings.none { it.status == DependencyStatus.ADD })
    }

    @Test
    fun `vulnerable matching target is marked vulnerable but older and newer versions retain comparison status`() {
        val security =
            TargetSecurityScan(
                dependencies = emptyList(),
                targets = listOf(securityTarget(TargetSecurityStatus.VULNERABLE)),
            )
        // Apply a vulnerable target result to matching, older and newer repository versions.
        listOf(
            "1.0.0" to DependencyStatus.VULNERABLE,
            "0.9.0" to DependencyStatus.UPDATE,
            "1.1.0" to DependencyStatus.AHEAD,
        ).forEach { (current, expected) ->
            val raw = scan(finding("example:library").copy(currentVersion = current))
            assertEquals(
                expected,
                scanner
                    .enrichSecurityScan(raw, security)
                    .findings
                    .single()
                    .status,
            )
        }
    }

    @Test
    fun `unused transient override is removed from the proposed build file`() {
        // An explicitly configured but unused transient dependency yields REMOVE.
        val security =
            TargetSecurityScan(
                dependencies = emptyList(),
                targets = listOf(securityTarget(TargetSecurityStatus.TRANSIENT_UNUSED)),
            )
        val enriched = scanner.enrichSecurityScan(scan(finding("example:library")), security)
        assertEquals(DependencyStatus.REMOVE, enriched.findings.single().status)

        val updated =
            GradleBuildFileUpdater().apply(
                "dependencies {\n    implementation \"example:library:1.0.0\"\n    implementation \"example:keep:1.0.0\"\n}",
                enriched.findings,
            )
        assertEquals(mapOf("example:keep" to "1.0.0"), GradleDependencyParser().parse(updated).dependencies)
        // Only the unused override is removed; an unrelated dependency remains.
    }

    private fun overrideSecurity() =
        TargetSecurityScan(
            dependencies = emptyList(),
            targets =
                listOf(
                    securityTarget(TargetSecurityStatus.OK_OVERRIDDEN).copy(
                        relatedTo = listOf(OverrideReason("example:override", "2.0.0", "2.0.0")),
                    ),
                ),
        )

    private fun securityTarget(status: TargetSecurityStatus) =
        TargetSecurityResult("example:library", "1.0.0", status, emptyList(), emptyList(), emptyList())
}

fun finding(
    key: String,
    version: String = "1.0.0",
    target: String = version,
    status: DependencyStatus = DependencyStatus.OK,
) = DependencyFinding(DependencyKind.DEPENDENCY, key, version, target, status)

fun scan(vararg findings: DependencyFinding) = RepositoryDependencyScan("example/repository", "2026-01-01T00:00:00Z", findings.toList())

class EmptyVulnerabilityService : VulnerabilityService {
    override fun find(dependencies: List<ResolvedDependency>) = emptyMap<String, List<no.nav.platforce.tool.Vulnerability>>()
}
