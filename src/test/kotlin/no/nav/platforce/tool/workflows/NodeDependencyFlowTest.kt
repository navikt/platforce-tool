package no.nav.platforce.tool.workflows

import com.google.gson.Gson
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.node.NodeDependencyDeclaration
import no.nav.platforce.tool.node.PackageJsonParser
import no.nav.platforce.tool.node.nodeDependencyStatus
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.pullrequests.NodePackageJsonUpdater
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException
import no.nav.platforce.tool.repositories.RepositoryProjectType
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class NodeDependencyFlowTest {
    private val source =
        """
        {
          "name": "alpine",
          "engines": {"node": ">=18.15.0"},
          "dependencies": {"@slack/bolt": "^3.22.0", "axios": "^1.16.0", "glob": "^9.0.0"},
          "devDependencies": {"jest": "^29.5.0"},
          "overrides": {"form-data": "2.5.6", "glob": "^10.5.0", "ws": "^7.5.11"}
        }
        """.trimIndent()

    @Test
    fun `fallback inventories include production and development dependencies and overrides but no engines or Gradle`() {
        val github = RecordingGithubClient(mapOf("package.json" to source))
        val scanner = DependencyScanner(github)
        val inventory = checkNotNull(scanner.scanRepository("navikt/on-behalf"))
        assertEquals(RepositoryProjectType.NODE, inventory.projectType)
        assertNull(inventory.gradleVersion)
        assertTrue(inventory.dependencies.isEmpty())
        assertTrue(inventory.plugins.isEmpty())
        assertTrue(inventory.declarations.isEmpty())
        assertEquals(7, inventory.nodeDependencies.size)
        assertTrue(inventory.nodeDependencies.none { it.key == "node" })
        assertEquals(
            NodeDependencyDeclaration("jest", "^29.5.0", "devDependencies", listOf("devDependencies", "jest")),
            inventory.nodeDependencies.single { it.key == "jest" },
        )
        assertEquals(listOf("^9.0.0", "^10.5.0"), inventory.nodeDependencies.filter { it.key == "glob" }.map { it.version })
        assertTrue(github.calls.none { it.contains("gradle/wrapper") })

        val scan = scanner.compare(inventory, targets())
        assertTrue(scan.findings.all { it.kind == DependencyKind.NODE })
        assertEquals(listOf(DependencyStatus.UPDATE, DependencyStatus.UPDATE), scan.findings.map { it.status })
        assertEquals(listOf("dependencies", "overrides > glob"), scan.findings.map { it.declaration })
        assertEquals(listOf("@slack/bolt", "axios", "form-data", "glob", "jest", "ws"), scan.declaredNodeDependencyKeys)
        assertEquals(setOf("@slack/bolt", "axios", "form-data", "jest", "ws"), scan.untrackedNodeDependencies.map { it.key }.toSet())
        assertTrue(scan.declaredDependencyKeys!!.isEmpty())
        assertEquals(inventory.nodeDependencies, PackageJsonParser().parse(source))
    }

    @Test
    fun `shared saved targets retain each repository production or development declaration through upgrades`() {
        val state = targets().copy(nodeDependencies = mutableMapOf("tool" to "1.1.0"))
        val saved = Gson().fromJson(Gson().toJson(state), TargetVersionsState::class.java).snapshot()
        listOf("dependencies", "devDependencies").forEach { section ->
            val source = """{"$section":{"tool":"^1.0.0"}}"""
            val scanner = DependencyScanner(RecordingGithubClient(mapOf("package.json" to source)))
            val inventory = checkNotNull(scanner.scanRepository("example/$section"))
            val untracked = scanner.compare(inventory, targets()).untrackedNodeDependencies.single()
            assertEquals(section, untracked.declaration)
            val findings = scanner.compare(inventory, saved).findings
            assertEquals(section, findings.single().declaration)
            assertEquals(DependencyStatus.UPDATE, findings.single().status)
            val update = NodePackageJsonUpdater().apply(source, findings)
            val declarations = PackageJsonParser().parse(update.manifest)
            assertEquals(listOf(NodeDependencyDeclaration("tool", "^1.1.0", section, listOf(section, "tool"))), declarations)
            val merged = scanner.compare(inventory.copy(nodeDependencies = declarations), saved).findings.single()
            assertEquals(DependencyStatus.OK, merged.status)
            assertEquals(section, merged.declaration)
        }
    }

    @Test
    fun `development declarations retain duplicate package roles and resolve override references`() {
        val parsed =
            PackageJsonParser().parse(
                """
                {"dependencies":{"shared":"^1.0.0"},
                 "devDependencies":{"shared":"~2.0.0","cypress":"^13.17.0"},
                 "overrides":{"shared":"${'$'}shared","cypress":"${'$'}cypress"}}
                """.trimIndent(),
            )
        assertEquals(listOf("^1.0.0", "~2.0.0", "~2.0.0"), parsed.filter { it.key == "shared" }.map { it.version })
        assertEquals(
            listOf("dependencies", "devDependencies", "overrides > shared"),
            parsed.filter { it.key == "shared" }.map { it.declaration },
        )
        assertEquals("^13.17.0", parsed.last().version)
        assertEquals(listOf("overrides", "cypress"), parsed.last().path)
    }

    @Test
    fun `Gradle takes precedence over package json and unsupported repositories return no inventory`() {
        listOf("build.gradle", "build.gradle.kts").forEach { path ->
            val github = RecordingGithubClient(mapOf(path to "", "package.json" to source))
            val inventory = checkNotNull(DependencyScanner(github).scanRepository("example/mixed"))
            assertEquals(RepositoryProjectType.GRADLE, inventory.projectType)
            assertTrue(inventory.nodeDependencies.isEmpty())
            assertFalse(github.calls.any { it.contains("package.json") })
        }
        assertNull(DependencyScanner(RecordingGithubClient()).scanRepository("example/unsupported"))
        assertEquals(emptyList<NodeDependencyDeclaration>(), PackageJsonParser().parse("""{"private":true}"""))
    }

    @Test
    fun `nested overrides retain package names selectors contexts and references`() {
        val parsed =
            PackageJsonParser().parse(
                """
                {"dependencies":{"foo":"^1.0.0"},"overrides":{
                  "@slack/bolt@^3":{".":"3.22.1","ws":"^7.5.11"},
                  "foo":{"@scope/bar@2":{".":"2.1.0"},"glob":"${'$'}foo"},
                  "@scope/plain":"1.0.0"
                }}
                """.trimIndent(),
            )
        assertEquals(listOf("foo", "@slack/bolt", "ws", "@scope/bar", "glob", "@scope/plain"), parsed.map { it.key })
        assertEquals("overrides > @slack/bolt@^3 > .", parsed[1].declaration)
        assertEquals("overrides > foo > @scope/bar@2 > .", parsed[3].declaration)
        assertEquals("^1.0.0", parsed[4].version)
    }

    @Test
    fun `malformed manifests fail instead of silently appearing empty`() {
        listOf(
            "{",
            "null",
            "[]",
            """{"dependencies":null}""",
            """{"dependencies":{"foo":42}}""",
            """{"dependencies":{"foo":""}}""",
            """{"devDependencies":null}""",
            """{"devDependencies":[]}""",
            """{"devDependencies":{"foo":42}}""",
            """{"devDependencies":{"foo":""}}""",
            """{"overrides":{"foo":null}}""",
            """{"overrides":{".":"1.0.0"}}""",
            """{"overrides":{"foo":{".":{}}}}""",
        ).forEach { invalid ->
            assertThrows(Exception::class.java, { PackageJsonParser().parse(invalid) }, invalid)
        }
    }

    @Test
    fun `GitHub access errors do not fall back to another ecosystem`() {
        val github =
            object : GithubClient by RecordingGithubClient(mapOf("package.json" to source)) {
                override fun fileExists(
                    owner: String,
                    repo: String,
                    path: String,
                ): Boolean = throw IOException("GitHub unavailable")
            }
        assertThrows(IOException::class.java) { DependencyScanner(github).scanRepository("example/repo") }
    }

    @Test
    fun `simple declaration comparisons use lower bounds and complex specifications require review`() {
        assertEquals(DependencyStatus.OK, nodeDependencyStatus("^3.22.0", "3.22.0"))
        assertEquals(DependencyStatus.UPDATE, nodeDependencyStatus("~3.22.0", "^3.23.0"))
        assertEquals(DependencyStatus.AHEAD, nodeDependencyStatus("4.0.0", "3.23.0"))
        listOf(">=3.0.0", "1.x", "1.0.0 || 2.0.0", "npm:foo@1.0.0", "workspace:*", "file:../foo", "${'$'}foo", "latest", "1.0.0-rc.1")
            .forEach { assertEquals(DependencyStatus.UNRESOLVED, nodeDependencyStatus(it, "2.0.0"), it) }
        assertEquals(DependencyStatus.UNRESOLVED, nodeDependencyStatus("1.0.0", "*"))
    }

    @Test
    fun `Node targets survive old payloads snapshots and JSON round trips and affect fingerprints`() {
        val old =
            Gson()
                .fromJson(
                    """{"plugins":{},"dependencies":{},"gradleVersion":"8.0","transientDependencies":[]}""",
                    TargetVersionsState::class.java,
                ).snapshot()
        assertTrue(old.nodeDependencies.isEmpty())
        assertTrue(old.nodeTransientDependencies.isEmpty())
        val state = targets()
        val snapshot = state.snapshot()
        state.nodeDependencies["glob"] = "11.0.0"
        assertEquals("10.6.0", snapshot.nodeDependencies["glob"])
        assertNotEquals(fingerprint(state), fingerprint(snapshot))
        assertEquals(snapshot, Gson().fromJson(Gson().toJson(snapshot), TargetVersionsState::class.java).snapshot())
        assertEquals(fingerprint(snapshot), fingerprint(snapshot.copy(nodeDependencies = snapshot.nodeDependencies.toSortedMap())))
        val cover = snapshot.copy(nodeTransientDependencies = setOf("glob"))
        assertNotEquals(fingerprint(snapshot), fingerprint(cover))
        assertEquals(cover, Gson().fromJson(Gson().toJson(cover), TargetVersionsState::class.java).snapshot())
        assertTrue(
            cover
                .copy(nodeDependencies = mutableMapOf())
                .snapshot()
                .nodeTransientDependencies
                .isEmpty(),
        )
    }

    @Test
    fun `full and single repository refreshes isolate users and Node PR creation requires a matching Node scan`() {
        val github =
            object : GithubClient by RecordingGithubClient(mapOf("package.json" to source)) {
                override fun listRepositories(): List<String> = listOf("navikt/on-behalf")
            }
        val scanner = DependencyScanner(github)
        val cache = DependencyScanCache()
        cache.update("alice", scanner.scanAllRepositoriesWithProgress(cache, "alice"))
        assertTrue(cache.get("bob").isEmpty())
        assertFalse(cache.getProgress("alice").running)
        assertEquals(1, cache.getProgress("alice").done)
        val inventory = cache.get("alice").single()
        cache.updateRepository("bob", inventory)
        val resolution = GradleTargetResolutionScanner({ _, _ -> error("Node must not resolve with Gradle") }, QueuedExecutor())
        val security =
            TargetSecurityScanner(
                TargetSecurityService(
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Nothing =
                            error("Node must not use Maven security checks")
                    },
                ),
                resolution,
                QueuedExecutor(),
            )
        val view = DependencyScanViewService(cache, security, scanner)
        assertEquals(
            2,
            view
                .get("alice", targets())
                .single()
                .findings.size,
        )
        assertTrue(
            view
                .get("bob", targets().copy(nodeDependencies = mutableMapOf()))
                .single()
                .findings
                .isEmpty(),
        )
        assertEquals(inventory, cache.get("alice").single())
        val failure =
            assertThrows(PullRequestReadinessException::class.java) {
                DependencyPullRequestService(github, view).createPullRequest("navikt", "on-behalf", "alice", targets())
            }
        assertTrue(failure.message!!.contains("Node security scan"))
    }

    private fun targets() =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf("glob" to "999"),
            "8.0",
            nodeDependencies = mutableMapOf("glob" to "10.6.0"),
        )
}
