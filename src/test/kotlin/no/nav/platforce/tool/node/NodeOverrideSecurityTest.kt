package no.nav.platforce.tool.node

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.workflows.QueuedExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections

class NodeOverrideSecurityTest {
    private fun node(
        name: String,
        version: String,
    ) = ResolvedDependency("npm", name, version, version, emptyList())

    private val advisory = Vulnerability("TEST", "Synthetic", emptyList(), emptyList(), emptyList())
    private val osv =
        object : VulnerabilityService {
            override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                dependencies.associate {
                    "${it.key}:${it.version}" to
                        if (it.name in setOf("leaf", "other") && it.version == "1.0.0") listOf(advisory) else emptyList()
                }
        }

    private fun state(
        vararg targets: Pair<String, String>,
        covers: Set<String>,
    ) = TargetVersionsState(
        mutableMapOf(),
        mutableMapOf(),
        "9.0",
        nodeDependencies = targets.toMap().toMutableMap(),
        nodeTransientDependencies = covers,
    )

    private fun service(
        lookup: VulnerabilityService = osv,
        graph: (String, String, Map<String, String>) -> List<ResolvedDependency>,
    ) = NodeSecurityService(
        { key, version -> graph(key, version, emptyMap()) },
        lookup,
        { dependencies, overrides ->
            val roots = dependencies.mapValues { (key, version) -> graph(key, version, overrides) }
            val packages = roots.values.flatten().distinct()
            val covers =
                overrides.mapValues { (key, version) ->
                    if (packages.any { it.name == key && it.version == version }) {
                        graph(key, version, overrides).filter { it in packages }
                    } else {
                        emptyList()
                    }
                }
            NodeCombinedResolution(packages, roots + covers)
        },
    )

    private fun tree(
        key: String,
        version: String,
        overrides: Map<String, String>,
    ): List<ResolvedDependency> =
        listOf(node(key, version)) + if (key == "parent") listOf(node("leaf", overrides["leaf"] ?: "1.0.0")) else emptyList()

    @Test
    fun `changing a Node bandage invalidates old jobs and remains isolated between users`() {
        val queue = QueuedExecutor()
        val scanner = NodeSecurityScanner(service(graph = ::tree), queue)
        val normal = state("leaf" to "2.0.0", covers = emptySet())
        val cover = normal.copy(nodeTransientDependencies = setOf("leaf"))
        scanner.start("alice", normal)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", cover).status)
        scanner.start("alice", cover)
        queue.runNext()
        assertEquals(SecurityScanStatus.RUNNING, scanner.get("alice", cover).status)
        queue.runNext()
        assertEquals(
            NodeSecurityStatus.TRANSIENT_UNUSED,
            scanner
                .get("alice", cover)
                .result!!
                .targets
                .single()
                .status,
        )
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", normal).status)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("bob", cover).status)
    }

    @Test
    fun `only bandaged targets apply as npm overrides and necessary covers expose matching statuses and reasons`() {
        val state = state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf"))
        val covered = service(graph = ::tree).scan(state).targets.associateBy { it.key }
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, covered.getValue("parent").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, covered.getValue("leaf").status)
        assertEquals(
            "parent",
            covered
                .getValue("leaf")
                .transientUsage
                .single()
                .dependency,
        )
        val reason = covered.getValue("parent").relatedTo.single()
        assertEquals("leaf", reason.dependency)
        assertEquals("1.0.0", reason.vulnerableVersion)
        assertEquals("2.0.0", reason.resolvedVersion)
        assertEquals(listOf("leaf:2.0.0"), reason.causedBy)
        assertEquals(
            NodeSecurityStatus.VULNERABLE,
            service(graph = ::tree)
                .scan(state.copy(nodeTransientDependencies = emptySet()))
                .targets
                .single {
                    it.key ==
                        "parent"
                }.status,
        )
    }

    @Test
    fun `one combined resolution and one solo per target finish before OSV and coverage inference`() {
        val progress = mutableListOf<NodeScanProgress>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val lookupPhases = mutableListOf<NodeScanPhase>()
        val lookup =
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                    lookupPhases += progress.last().phase
                    return osv.find(dependencies)
                }
            }
        val results =
            NodeSecurityService(
                { key, version ->
                    calls += key
                    tree(key, version, emptyMap())
                },
                lookup,
                { dependencies, overrides ->
                    calls += "combined"
                    assertEquals(mapOf("parent" to "1.0.0"), dependencies)
                    assertEquals(mapOf("leaf" to "2.0.0"), overrides)
                    val packages = listOf(node("parent", "1.0.0"), node("leaf", "2.0.0"), node("new-transitive", "1.0.0"))
                    NodeCombinedResolution(packages, mapOf("parent" to packages, "leaf" to listOf(node("leaf", "2.0.0"))))
                },
            ).scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf")), progress::add)
        assertEquals(listOf(NodeScanPhase.RESOLUTION, NodeScanPhase.OSV, NodeScanPhase.COVERAGE), progress.map { it.phase }.distinct())
        assertTrue(progress.zipWithNext().all { (before, after) -> before.phase.ordinal <= after.phase.ordinal })
        assertTrue(progress.zipWithNext().all { (before, after) -> before.completedResolutions <= after.completedResolutions })
        assertEquals(listOf(NodeScanPhase.OSV), lookupPhases)
        assertEquals(3, calls.size)
        assertEquals(calls.size, calls.distinct().size)
        assertEquals(NodeScanProgress(NodeScanPhase.COVERAGE, 3, 3), progress.last())
        assertTrue(progress.filter { it.phase != NodeScanPhase.RESOLUTION }.all { it.completedResolutions == 3 })
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, results.targets.single { it.key == "parent" }.status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.targets.single { it.key == "leaf" }.status)
    }

    @Test
    fun `twenty dependencies and six overrides require exactly twenty seven npm resolutions and one OSV batch`() {
        val dependencies = (1..20).associate { "dependency-$it" to "^1.0.0" }
        val overrides = (1..6).associate { "cover-$it" to "~2.0.0" }
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val progress = mutableListOf<NodeScanProgress>()
        var lookups = 0

        fun cover(key: String) = "cover-${(key.substringAfterLast('-').toInt() - 1) % 6 + 1}"
        val service =
            NodeSecurityService(
                { key, version ->
                    calls += key
                    listOf(node(key, version)) + if (key in dependencies) listOf(node(cover(key), "1.0.0")) else emptyList()
                },
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                        lookups++
                        assertEquals(NodeScanPhase.OSV, progress.last().phase)
                        return dependencies.associate {
                            "${it.key}:${it.version}" to
                                if (it.name.startsWith("cover-") && it.version == "1.0.0") listOf(advisory) else emptyList()
                        }
                    }
                },
                { normal, covers ->
                    calls += "combined"
                    assertEquals(dependencies.mapValues { "1.0.0" }, normal)
                    assertEquals(overrides.mapValues { "2.0.0" }, covers)
                    val roots =
                        normal.mapValues { (key, version) -> listOf(node(key, version), node(cover(key), "2.0.0")) } +
                            covers.mapValues { (key, version) -> listOf(node(key, version)) }
                    NodeCombinedResolution(roots.values.flatten().distinct(), roots)
                },
            )
        val result =
            service.scan(
                state(*(dependencies + overrides).toList().toTypedArray(), covers = overrides.keys),
                progress::add,
            )
        assertEquals(27, calls.size)
        assertEquals(27, calls.distinct().size)
        assertEquals(1, lookups)
        assertTrue(progress.all { it.totalResolutions == 27 })
        assertEquals(NodeScanProgress(NodeScanPhase.COVERAGE, 27, 27), progress.last())
        assertTrue(result.targets.filter { it.key in dependencies }.all { it.status == NodeSecurityStatus.OK_OVERRIDDEN })
        assertTrue(result.targets.filter { it.key in overrides }.all { it.status == NodeSecurityStatus.OK_TRANSIENT })
    }

    @Test
    fun `a normal target can provide coverage when npm actually selects its safe version in the consumer tree`() {
        val result =
            NodeSecurityService(
                { key, version -> tree(key, version, emptyMap()) },
                osv,
                { dependencies, overrides ->
                    assertEquals(mapOf("parent" to "1.0.0", "leaf" to "2.0.0"), dependencies)
                    assertTrue(overrides.isEmpty())
                    val packages = listOf(node("parent", "1.0.0"), node("leaf", "2.0.0"))
                    NodeCombinedResolution(packages, mapOf("parent" to packages, "leaf" to listOf(node("leaf", "2.0.0"))))
                },
            ).scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = emptySet())).targets.associateBy { it.key }
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, result.getValue("parent").status)
        assertEquals(
            listOf("leaf:2.0.0"),
            result
                .getValue("parent")
                .relatedTo
                .single()
                .causedBy,
        )
        assertEquals(NodeSecurityStatus.OK, result.getValue("leaf").status)
    }

    @Test
    fun `safe but unused overrides are redundant while vulnerable overrides stay vulnerable`() {
        val safe = state("parent" to "1.0.0", "unrelated" to "2.0.0", covers = setOf("unrelated"))
        assertEquals(
            NodeSecurityStatus.TRANSIENT_UNUSED,
            service(graph = ::tree)
                .scan(safe)
                .targets
                .single { it.key == "unrelated" }
                .status,
        )
        val unused = state("leaf" to "2.0.0", covers = setOf("leaf"))
        assertEquals(
            NodeSecurityStatus.TRANSIENT_UNUSED,
            service(graph = ::tree)
                .scan(unused)
                .targets
                .single()
                .status,
        )
        val vulnerable = state("leaf" to "1.0.0", covers = setOf("leaf"))
        assertEquals(
            NodeSecurityStatus.VULNERABLE,
            service(graph = ::tree)
                .scan(vulnerable)
                .targets
                .single()
                .status,
        )
    }

    @Test
    fun `direct override is retained rather than mistaking another branch providing the same version for a replacement`() {
        val state = state("parent" to "1.0.0", "leaf" to "2.0.0", "alternative" to "2.0.0", covers = setOf("leaf", "alternative"))
        val results =
            service { key, version, overrides ->
                when (key) {
                    "parent" -> {
                        listOf(
                            node(key, version),
                            node("alternative", overrides["alternative"] ?: "1.0.0"),
                            node("leaf", if (overrides.isEmpty()) "1.0.0" else "2.0.0"),
                        )
                    }

                    "alternative" -> {
                        listOf(node(key, version), node("leaf", "2.0.0"))
                    }

                    else -> {
                        tree(key, version, overrides)
                    }
                }
            }.scan(state).targets.associateBy { it.key }
        assertEquals(NodeSecurityStatus.TRANSIENT_UNUSED, results.getValue("alternative").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("leaf").status)
        assertTrue(results.getValue("alternative").replacementDependencies.isEmpty())
        assertEquals(
            listOf("leaf:2.0.0"),
            results
                .getValue("parent")
                .relatedTo
                .single()
                .causedBy,
        )
    }

    @Test
    fun `complementary overrides are both required when only their combination covers a vulnerability`() {
        val results =
            service { key, version, overrides ->
                if (key == "parent") {
                    listOf(
                        node(key, version),
                        node("first", overrides["first"] ?: "1.0.0"),
                        node("second", overrides["second"] ?: "1.0.0"),
                        node("leaf", if (overrides.keys.containsAll(setOf("first", "second"))) "2.0.0" else "1.0.0"),
                    )
                } else {
                    listOf(node(key, version))
                }
            }.scan(state("parent" to "1.0.0", "first" to "2.0.0", "second" to "2.0.0", covers = setOf("first", "second")))
                .targets
                .associateBy { it.key }
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, results.getValue("parent").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("first").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("second").status)
        assertEquals(
            setOf("first:2.0.0", "second:2.0.0"),
            results
                .getValue("parent")
                .relatedTo
                .single()
                .causedBy
                .toSet(),
        )
    }

    @Test
    fun `an override transitive vulnerability can itself be covered by another global override`() {
        val results =
            service { key, version, overrides ->
                listOf(node(key, version)) +
                    when (key) {
                        "parent" -> listOf(node("other", overrides["other"] ?: "1.0.0"), node("leaf", overrides["leaf"] ?: "1.0.0"))
                        "other" -> listOf(node("leaf", overrides["leaf"] ?: "1.0.0"))
                        else -> emptyList()
                    }
            }.scan(state("parent" to "1.0.0", "other" to "2.0.0", "leaf" to "2.0.0", covers = setOf("other", "leaf")))
                .targets
                .associateBy { it.key }
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, results.getValue("parent").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("other").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("leaf").status)
        val reasons = results.getValue("parent").relatedTo.associateBy { it.dependency }
        assertEquals(listOf("other:2.0.0"), reasons.getValue("other").causedBy)
        assertEquals(listOf("leaf:2.0.0"), reasons.getValue("leaf").causedBy)
    }

    @Test
    fun `an override may cover one vulnerability while its parent remains vulnerable to another`() {
        val results =
            service { key, version, overrides ->
                tree(key, version, overrides) + if (key == "parent") listOf(node("other", "1.0.0")) else emptyList()
            }.scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf"))).targets.associateBy { it.key }
        assertEquals(NodeSecurityStatus.VULNERABLE, results.getValue("parent").status)
        assertEquals(listOf("other"), results.getValue("parent").vulnerableDependencies.map { it.dependency.name })
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("leaf").status)
        assertEquals(
            "leaf",
            results
                .getValue("parent")
                .relatedTo
                .single()
                .dependency,
        )
    }

    @Test
    fun `override conflicts and incomplete normal targets never produce coverage or redundant covers`() {
        for (baselineFails in listOf(false, true)) {
            val results =
                service { key, version, overrides ->
                    if (key == "parent" && (baselineFails || overrides.isNotEmpty())) throw IOException("EOVERRIDE synthetic conflict")
                    tree(key, version, overrides)
                }.scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf"))).targets.associateBy { it.key }
            assertEquals(NodeSecurityStatus.UNRESOLVED, results.getValue("parent").status)
            assertTrue(results.getValue("parent").error!!.contains("EOVERRIDE"))
            assertTrue(results.getValue("parent").relatedTo.isEmpty())
            assertEquals(NodeSecurityStatus.UNRESOLVED, results.getValue("leaf").status)
        }
    }

    @Test
    fun `coverage inference never runs leave-one-out override comparisons`() {
        val results =
            service { key, version, overrides ->
                if (key == "parent" && overrides.keys == setOf("other")) throw IOException("Comparison failed")
                tree(key, version, overrides) +
                    if (key == "second") listOf(node("other", overrides["other"] ?: "1.0.0")) else emptyList()
            }.scan(state("parent" to "1.0.0", "second" to "1.0.0", "leaf" to "2.0.0", "other" to "2.0.0", covers = setOf("leaf", "other")))
                .targets
                .associateBy { it.key }
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("leaf").status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, results.getValue("other").status)
        assertFalse(results.values.any { it.status == NodeSecurityStatus.TRANSIENT_UNUSED })
    }

    @Test
    fun `overrides introducing vulnerabilities are vulnerable even when their standalone trees are safe`() {
        val results =
            service { key, version, overrides ->
                tree(key, version, overrides) +
                    if (key in setOf("parent", "leaf") && overrides.isNotEmpty()) listOf(node("other", "1.0.0")) else emptyList()
            }.scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf"))).targets.associateBy { it.key }
        assertEquals(NodeSecurityStatus.VULNERABLE, results.getValue("parent").status)
        assertEquals(NodeSecurityStatus.VULNERABLE, results.getValue("leaf").status)
        assertEquals(
            "other",
            results
                .getValue("leaf")
                .vulnerableDependencies
                .single()
                .dependency.name,
        )
    }

    @Test
    fun `missing vulnerability results in overridden trees fail the whole scan`() {
        val missing =
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                    osv.find(dependencies).filterKeys { !it.contains(":missing:") }
            }
        assertThrows(IllegalStateException::class.java) {
            service(missing) { key, version, overrides ->
                tree(key, version, overrides) +
                    if (key == "parent" && overrides.isNotEmpty()) listOf(node("missing", "1.0.0")) else emptyList()
            }.scan(state("parent" to "1.0.0", "leaf" to "2.0.0", covers = setOf("leaf")))
        }
    }
}
