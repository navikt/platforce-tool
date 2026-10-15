package no.nav.platforce.tool.workflows

import com.google.gson.Gson
import no.nav.platforce.tool.gradle.GradleDependencyParser
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.pullrequests.GradleBuildFileUpdater
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.UntrackedPlatformBom
import no.nav.platforce.tool.resolution.GradleResolutionBuildFile
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.security.TargetSecurityResult
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.TargetSecurityStatus
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformBomFlowTest {
    private val key = "example:platform"
    private val parser = GradleDependencyParser()
    private val scanner = DependencyScanner(RecordingGithubClient())
    private val declarations =
        listOf(
            """implementation platform('example:platform:1.0')""",
            """implementation(platform("example:platform:1.0"))""",
            """implementation platform("example:platform:1.0")""",
            """api(platform('example:platform:1.0'))""",
            """testImplementation(enforcedPlatform("example:platform:1.0"))""",
            """implementation enforcedPlatform('example:platform:1.0')""",
            """api enforcedPlatform("example:platform:1.0")""",
            """implementation(platform(group = "example", name = "platform", version = "1.0"))""",
            """implementation platform(group: 'example', name: 'platform', version: '1.0')""",
            """api(enforcedPlatform(version = "1.0", name = "platform", group = "example"))""",
            """
            implementation(
                platform(
                    "example:platform:1.0"
                )
            )
            """.trimIndent(),
            """
            implementation /* reason */ enforcedPlatform(
                group: 'example',
                name: 'platform',
                version: '1.0'
            )
            """.trimIndent(),
        )

    @Test
    fun `platform declarations parse and update independently of ordinary dependencies with identical coordinates`() {
        declarations.forEach { declaration ->
            val content =
                """
                dependencies {
                    $declaration
                    implementation("example:platform:1.0")
                    // implementation platform('ignored:line:1.0')
                    /*
                    api(enforcedPlatform("ignored:block:1.0"))
                    */
                }
                """.trimIndent()
            val parsed = parser.parse(content)
            assertEquals(mapOf(key to "1.0"), parsed.platformBoms, declaration)
            assertEquals(mapOf(key to "1.0"), parsed.dependencies, declaration)

            val updated =
                GradleBuildFileUpdater().apply(
                    content,
                    listOf(DependencyFinding(DependencyKind.PLATFORM_BOM, key, "1.0", "2.0", DependencyStatus.UPDATE)),
                )
            assertEquals(content.replace(declaration, declaration.replace("1.0", "2.0")), updated, declaration)
            assertEquals(mapOf(key to "2.0"), parser.parse(updated).platformBoms, declaration)
            assertEquals(parsed.dependencies, parser.parse(updated).dependencies, declaration)

            val dependencyUpdate =
                GradleBuildFileUpdater().apply(
                    content,
                    listOf(finding(key, "1.0", "3.0", DependencyStatus.UPDATE)),
                )
            assertEquals(parsed.platformBoms, parser.parse(dependencyUpdate).platformBoms, declaration)
            assertEquals(mapOf(key to "3.0"), parser.parse(dependencyUpdate).dependencies, declaration)
        }
    }

    @Test
    fun `scanner separates BOMs and ignores dependency security results without BOM evidence`() {
        val inventory =
            inventory(key to "1.0").copy(
                platformBoms = mapOf(key to "1.0", "example:other" to "4.0"),
            )
        val targets = TargetVersionsState(mutableMapOf(), mutableMapOf(key to "1.0"), "8.0")
        assertEquals(
            listOf(UntrackedPlatformBom(key, "1.0"), UntrackedPlatformBom("example:other", "4.0")),
            scanner.compare(inventory, targets).untrackedPlatformBoms,
        )
        val comparisons = listOf("2.0" to DependencyStatus.UPDATE, "1.0" to DependencyStatus.OK, "0.5" to DependencyStatus.AHEAD)
        comparisons.forEach { (version, status) ->
            val tracked = targets.copy(platformBoms = mutableMapOf(key to version, "example:missing" to "1.0"))
            val scan = scanner.compare(inventory, tracked)
            assertEquals(status, scan.findings.single { it.kind == DependencyKind.PLATFORM_BOM }.status)
            assertEquals(listOf(UntrackedPlatformBom("example:other", "4.0")), scan.untrackedPlatformBoms)
            val security =
                TargetSecurityScan(
                    emptyList(),
                    listOf(TargetSecurityResult(key, version, TargetSecurityStatus.VULNERABLE, emptyList(), emptyList(), emptyList())),
                )
            val enriched = scanner.enrichSecurityScan(scan, security)
            assertEquals(status, enriched.findings.single { it.kind == DependencyKind.PLATFORM_BOM }.status)
            assertEquals(DependencyStatus.VULNERABLE, enriched.findings.single { it.kind == DependencyKind.DEPENDENCY }.status)
        }
    }

    @Test
    fun `snapshot normalizes legacy API JSON and makes an independent BOM map`() {
        val legacy =
            Gson()
                .fromJson(
                    """{"plugins":{},"dependencies":{},"gradleVersion":"8.0","transientDependencies":[]}""",
                    TargetVersionsState::class.java,
                ).snapshot()
        assertTrue(legacy.platformBoms.isEmpty())
        assertTrue(Gson().toJson(legacy).contains("\"platformBoms\":{}"))
        legacy.platformBoms[key] = "1.0"
        val snapshot = legacy.snapshot()
        legacy.platformBoms[key] = "2.0"
        assertEquals(mapOf(key to "1.0"), snapshot.platformBoms)
        assertEquals(snapshot, Gson().fromJson(Gson().toJson(snapshot), TargetVersionsState::class.java).snapshot())
    }

    @Test
    fun `BOM targets affect scan fingerprints but leave the dependency baseline separate`() {
        val targets = TargetVersionsState(mutableMapOf(), mutableMapOf("example:library" to "1.0"), "8.0")
        val tracked = targets.copy(platformBoms = mutableMapOf(key to "1.0"))
        assertNotEquals(fingerprint(targets), fingerprint(tracked))
        val build = GradleResolutionBuildFile().create(tracked)
        assertTrue(build.startsWith(GradleResolutionBuildFile().create(targets)))
        assertTrue(build.contains(key))
        assertTrue(build.contains("def configuredPlatformBoms"))
    }

    @Test
    fun `BOM-only PR updates Kotlin build file without changing identical ordinary dependency`() {
        val content = "dependencies {\n    implementation(platform(\"$key:1.0\"))\n    implementation(\"$key:1.0\")\n}"
        val github = RecordingGithubClient(mapOf("build.gradle.kts" to content))
        val resolution = GradleTargetResolutionScanner({ _, _ -> error("BOM must not cause resolution") })
        val security = TargetSecurityScanner(TargetSecurityService(EmptyVulnerabilityService()), resolution)
        val cache = DependencyScanCache()
        val scan = DependencyScanner(github)
        cache.update("alice", listOf(scan.scanRepository("example/repository")!!.copy(gradleVersion = "8.0")))
        val targets = TargetVersionsState(mutableMapOf(), mutableMapOf(), "8.0", platformBoms = mutableMapOf(key to "2.0"))
        val service = DependencyPullRequestService(github, DependencyScanViewService(cache, security, scan))

        service.createPullRequest("example", "repository", "alice", targets)

        val updated = parser.parse(github.updatedFiles.getValue("build.gradle.kts"))
        assertEquals(mapOf(key to "2.0"), updated.platformBoms)
        assertEquals(mapOf(key to "1.0"), updated.dependencies)
        assertTrue(github.pullRequestBody.orEmpty().contains(key))
    }
}
