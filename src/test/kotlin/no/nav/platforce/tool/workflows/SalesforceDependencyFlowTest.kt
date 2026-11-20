package no.nav.platforce.tool.workflows

import com.google.gson.Gson
import com.google.gson.JsonParser
import no.nav.platforce.tool.node.NodeCombinedResolution
import no.nav.platforce.tool.node.NodeSecurityScanner
import no.nav.platforce.tool.node.NodeSecurityService
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException
import no.nav.platforce.tool.repositories.RepositoryProjectType
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.salesforce.SALESFORCE_MANIFEST
import no.nav.platforce.tool.salesforce.parseSalesforceProject
import no.nav.platforce.tool.salesforce.salesforceDependencyStatus
import no.nav.platforce.tool.salesforce.updateSalesforceProject
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.snapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SalesforceDependencyFlowTest {
    private val source =
        """
        {
          "sourceApiVersion": "61.0",
          "packageDirectories": [
            {"path":"src/model","package":"local-model","versionNumber":"0.1.0.NEXT","dependencies":[
              {"package":"crm-platform-base","versionNumber":"0.253.0.LATEST"}
            ]},
            {"path":"src/audit","versionNumber":"0.2.0.NEXT","dependencies":[
              {"package":"crm-platform-base","versionNumber":"0.251.0.LATEST"},
              {"package":"pinned-package"}
            ]}
          ],
          "packageAliases":{"crm-platform-base":"0Ho2o0000008ORmCAM","pinned-package":"04t000000000001AAA"}
        }
        """.trimIndent()
    private val files =
        mutableMapOf(
            SALESFORCE_MANIFEST to source,
            "package.json" to """{"name":"sf-demo","devDependencies":{"tool":"1.0.0"},"scripts":{"test":"original"}}""",
        )
    private val github = RecordingGithubClient(files)
    private val scanner = DependencyScanner(github)
    private val cache = DependencyScanCache()
    private val executor = QueuedExecutor()
    private val node =
        NodeSecurityScanner(
            NodeSecurityService(
                { key, version -> listOf(ResolvedDependency("npm", key, version, version, emptyList())) },
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                        dependencies.associate { "${it.key}:${it.version}" to emptyList() }
                },
                { state, _ ->
                    val roots = state.map { (key, version) -> ResolvedDependency("npm", key, version, version, emptyList()) }
                    NodeCombinedResolution(roots, roots.associate { it.name to listOf(it) })
                },
            ),
            executor,
        )
    private val gradle =
        TargetSecurityScanner(
            TargetSecurityService(EmptyVulnerabilityService()),
            GradleTargetResolutionScanner({ _, _ -> error("Salesforce must not resolve with Gradle") }, QueuedExecutor()),
            QueuedExecutor(),
        )
    private val view = DependencyScanViewService(cache, gradle, scanner, node)
    private val state =
        TargetVersionsState(
            mutableMapOf(),
            mutableMapOf(),
            "8.0",
            salesforceDependencies = mutableMapOf("crm-platform-base" to "0.254.0.LATEST"),
        )

    private fun scan() {
        cache.update("alice", listOf(checkNotNull(scanner.scanRepository("navikt/sf-shared"))))
        github.calls.clear()
    }

    @Test
    fun `Salesforce inventories include both manifests and every package directory but not own package versions`() {
        val inventory = checkNotNull(scanner.scanRepository("navikt/sf-shared"))
        assertEquals(RepositoryProjectType.SALESFORCE, inventory.projectType)
        assertEquals("tool", inventory.nodeDependencies.single().key)
        assertEquals(3, inventory.salesforceDependencies.size)
        assertEquals(
            listOf("src/model", "src/audit"),
            inventory.salesforceDependencies
                .filter {
                    it.key == "crm-platform-base"
                }.map { it.declaration },
        )
        assertTrue(inventory.salesforceDependencies.none { it.key == "local-model" })
        val scan = scanner.compare(inventory, state)
        assertEquals(2, scan.findings.size)
        assertTrue(
            scan.findings.all {
                it.kind == DependencyKind.SALESFORCE && it.status == DependencyStatus.UPDATE &&
                    it.sourceFile == SALESFORCE_MANIFEST
            },
        )
        assertEquals(listOf("crm-platform-base", "pinned-package"), scan.declaredSalesforceDependencyKeys)
        assertEquals("04t000000000001AAA", scan.untrackedSalesforceDependencies.single().version)
        assertEquals("tool", scan.untrackedNodeDependencies.single().key)
        files.remove("package.json")
        assertTrue(checkNotNull(scanner.scanRepository("navikt/sf-shared")).nodeDependencies.isEmpty())
    }

    @Test
    fun `Salesforce-only PR changes sfdx versions without touching npm files aliases source API or own versions`() {
        files["package.json"] = """{"workspaces":["packages/*"],"packageManager":"yarn@4.0.0"}"""
        files["yarn.lock"] = "unchanged"
        scan()
        val service = DependencyPullRequestService(github, view) { _, _, _, _ -> error("Salesforce must not generate npm locks") }
        service.createPullRequest("navikt", "sf-shared", "alice", state)
        assertEquals(setOf(SALESFORCE_MANIFEST), github.updatedFiles.keys)
        val root = JsonParser.parseString(github.updatedFiles.getValue(SALESFORCE_MANIFEST)).asJsonObject
        assertEquals("61.0", root["sourceApiVersion"].asString)
        assertEquals(JsonParser.parseString(source).asJsonObject["packageAliases"], root["packageAliases"])
        val directories = root.getAsJsonArray("packageDirectories")
        assertEquals("0.1.0.NEXT", directories[0].asJsonObject["versionNumber"].asString)
        assertEquals("0.2.0.NEXT", directories[1].asJsonObject["versionNumber"].asString)
        directories.forEach { directory ->
            assertEquals(
                "0.254.0.LATEST",
                directory.asJsonObject
                    .getAsJsonArray("dependencies")[0]
                    .asJsonObject["versionNumber"]
                    .asString,
            )
        }
        assertEquals("chore: update Salesforce dependencies", github.pullRequestTitle)
        assertTrue(github.pullRequestBody.orEmpty().contains("$SALESFORCE_MANIFEST / src/audit"))
        assertFalse(github.calls.any { it.contains(":package.json:") || it.contains(":yarn.lock:") || it.contains("build.gradle") })
        assertThrows(IllegalStateException::class.java) { view.requirePullRequestReady("bob", state, "navikt/sf-shared") }
    }

    @Test
    fun `mixed npm and Salesforce PR needs Node readiness and updates both files and only the existing npm lock`() {
        val mixed = state.copy(nodeDependencies = mutableMapOf("tool" to "1.1.0"))
        files["package-lock.json"] = "old-lock"
        scan()
        assertThrows(PullRequestReadinessException::class.java) { view.requirePullRequestReady("alice", mixed, "navikt/sf-shared") }
        node.start("alice", mixed)
        executor.runNext()
        var generations = 0
        val service =
            DependencyPullRequestService(github, view) { manifest, _, lock, _ ->
                generations++
                assertEquals("old-lock", lock)
                assertEquals(
                    "^1.1.0",
                    JsonParser
                        .parseString(manifest)
                        .asJsonObject
                        .getAsJsonObject("devDependencies")["tool"]
                        .asString,
                )
                "new-lock"
            }
        service.createPullRequest("navikt", "sf-shared", "alice", mixed)
        assertEquals(1, generations)
        assertEquals(setOf(SALESFORCE_MANIFEST, "package.json", "package-lock.json"), github.updatedFiles.keys)
        assertEquals("chore: update Node and Salesforce dependencies", github.pullRequestTitle)
        assertTrue(github.pullRequestBody.orEmpty().contains("0.253.0.LATEST → 0.254.0.LATEST"))
        assertFalse(github.pullRequestBody.orEmpty().contains("^0.254"))
    }

    @Test
    fun `npm security enrichment cannot change Salesforce findings with the same package name`() {
        files["package.json"] = """{"dependencies":{"crm-platform-base":"0.9.0"}}"""
        val mixed = state.copy(nodeDependencies = mutableMapOf("crm-platform-base" to "1.0.0"))
        scan()
        node.start("alice", mixed)
        executor.runNext()
        val findings = view.get("alice", mixed).single().findings
        assertEquals(DependencyStatus.UPDATE, findings.single { it.kind == DependencyKind.NODE }.status)
        val salesforce = findings.filter { it.kind == DependencyKind.SALESFORCE }
        assertEquals(2, salesforce.size)
        assertTrue(salesforce.all { it.status == DependencyStatus.UPDATE && it.targetVersion == "0.254.0.LATEST" })
    }

    @Test
    fun `stale Salesforce manifest fails before creating a branch and directory reordering preserves update identity`() {
        scan()
        files[SALESFORCE_MANIFEST] = source.replace("0.253.0.LATEST", "0.255.0.LATEST")
        val service = DependencyPullRequestService(github, view)
        assertThrows(PullRequestReadinessException::class.java) { service.createPullRequest("navikt", "sf-shared", "alice", state) }
        assertFalse(github.calls.any { it.startsWith("createBranch:") })
        val root = JsonParser.parseString(source).asJsonObject
        val dirs = root.getAsJsonArray("packageDirectories")
        val first = dirs.remove(0)
        dirs.add(first)
        files[SALESFORCE_MANIFEST] = Gson().toJson(root)
        service.createPullRequest("navikt", "sf-shared", "alice", state)
        assertTrue(github.updatedFiles.getValue(SALESFORCE_MANIFEST).contains("0.254.0.LATEST"))
    }

    @Test
    fun `Salesforce comparisons use numeric release and build values without inventing LATEST build numbers`() {
        assertEquals(DependencyStatus.UPDATE, salesforceDependencyStatus("0.9.0.LATEST", "0.10.0.LATEST"))
        assertEquals(DependencyStatus.UPDATE, salesforceDependencyStatus("1.0.0.9", "1.0.0.10"))
        assertEquals(DependencyStatus.AHEAD, salesforceDependencyStatus("1.1.0.LATEST", "1.0.0.LATEST"))
        assertEquals(DependencyStatus.OK, salesforceDependencyStatus("0.253.0.LATEST", "0.253.0.LATEST"))
        assertEquals(DependencyStatus.UNRESOLVED, salesforceDependencyStatus("1.0.0.1", "1.0.0.LATEST"))
        assertEquals(DependencyStatus.UNRESOLVED, salesforceDependencyStatus("04t000000000001AAA", "1.0.0.LATEST"))
        assertEquals(DependencyStatus.UNRESOLVED, salesforceDependencyStatus("1.0.0.NEXT", "1.0.0.LATEST"))
    }

    @Test
    fun `Salesforce targets round trip independently with safe legacy defaults and cache fingerprints`() {
        val roundTrip = Gson().fromJson(Gson().toJson(state), TargetVersionsState::class.java).snapshot()
        assertEquals(state.salesforceDependencies, roundTrip.salesforceDependencies)
        assertTrue(roundTrip.nodeDependencies.isEmpty())
        assertTrue(
            Gson()
                .fromJson(
                    """{"plugins":{},"dependencies":{},"gradleVersion":"8.0","transientDependencies":[]}""",
                    TargetVersionsState::class.java,
                ).snapshot()
                .salesforceDependencies
                .isEmpty(),
        )
        val snapshot = state.snapshot()
        snapshot.salesforceDependencies["crm-platform-base"] = "0.255.0.LATEST"
        assertNotEquals(fingerprint(state), fingerprint(snapshot))
        assertEquals("0.254.0.LATEST", state.salesforceDependencies["crm-platform-base"])
        assertThrows(
            IllegalArgumentException::class.java,
        ) { state.copy(salesforceDependencies = mutableMapOf("bad" to "^1.0.0")).snapshot() }
    }

    @Test
    fun `malformed or ambiguous Salesforce declarations fail explicitly and alias pins remain untouched`() {
        for (manifest in listOf(
            "[]",
            """{"packageDirectories":{}}""",
            """{"packageDirectories":[{"path":"src","dependencies":[{"package":"a","versionNumber":null}]}]}""",
            """{"packageDirectories":[{"path":"src"},{"path":"src"}]}""",
            """{"packageDirectories":[{"path":"src","dependencies":[{"package":"a"},{"package":"a"}]}]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { parseSalesforceProject(manifest) }
        }
        val pinned =
            scanner.compare(
                checkNotNull(scanner.scanRepository("navikt/sf-shared")),
                state.copy(salesforceDependencies = mutableMapOf("pinned-package" to "1.0.0.LATEST")),
            )
        assertEquals(DependencyStatus.UNRESOLVED, pinned.findings.single().status)
        assertEquals(source, updateSalesforceProject(source, pinned.findings))
    }
}
