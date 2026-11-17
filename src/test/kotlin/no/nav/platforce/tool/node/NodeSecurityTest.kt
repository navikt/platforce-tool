package no.nav.platforce.tool.node

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.workflows.QueuedExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NodeSecurityTest {
    private val advisory = Vulnerability("TEST-NPM", "Synthetic", emptyList(), emptyList(), listOf("2.0.0"))
    private val queried = mutableListOf<ResolvedDependency>()
    private val osv =
        object : VulnerabilityService {
            override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                queried += dependencies
                return dependencies.associate {
                    "${it.key}:${it.version}" to if (it.name == "transitive" && it.version == "1.0.0") listOf(advisory) else emptyList()
                }
            }
        }

    @Test
    fun `caret and tilde targets resolve exactly and independent transitive vulnerabilities cannot be overridden by another target`() {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val service =
            service(
                { key, version ->
                    calls += key to version
                    listOf(node(key, version), node("transitive", if (key == "vulnerable") "1.0.0" else "2.0.0"))
                },
                osv,
                { dependencies, _ ->
                    val roots =
                        dependencies.mapValues { (key, version) ->
                            listOf(node(key, version), node("transitive", if (key == "vulnerable") "1.0.0" else "2.0.0"))
                        }
                    NodeCombinedResolution(roots.values.flatten().distinct(), roots)
                },
            )
        val result = service.scan(state("vulnerable" to "^1.2.3", "@scope/safe" to "~2.0.0", "transitive" to "2.0.0"))
        assertTrue(calls.containsAll(listOf("vulnerable" to "1.2.3", "@scope/safe" to "2.0.0")))
        val vulnerable = result.targets.single { it.key == "vulnerable" }
        assertEquals("1.2.3", vulnerable.targetVersion)
        assertEquals(NodeSecurityStatus.VULNERABLE, vulnerable.status)
        assertEquals(
            "transitive",
            vulnerable.vulnerableDependencies
                .single()
                .dependency.name,
        )
        assertEquals(listOf(advisory), vulnerable.vulnerableDependencies.single().vulnerabilities)
        assertEquals(
            "2.0.0",
            vulnerable.vulnerableDependencies
                .single()
                .suggestedVersion!!
                .version,
        )
        assertEquals(
            "transitive",
            vulnerable.vulnerableDependencies
                .single()
                .suggestedVersion!!
                .dependency,
        )
        assertTrue(result.targets.filter { it.key != "vulnerable" }.all { it.status == NodeSecurityStatus.OK })
        assertTrue(queried.all { it.group == "npm" })
        assertEquals(queried.distinctBy { it.name to it.version }, queried)
    }

    @Test
    fun `fix suggestions choose the lowest stable newer version with a clean OSV lookup`() {
        val lookedUp = mutableListOf<String>()
        val issues =
            advisory.copy(fixedVersions = listOf("1.2.0", "1.10.0", "2.0.0", "0.9.0", "1.3.0-beta.1", "invalid"))
        val service =
            service(
                { key, version -> listOf(node(key, version)) },
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                        dependencies.associate {
                            lookedUp += it.version
                            "${it.key}:${it.version}" to if (it.version in setOf("1.0.0", "1.2.0")) listOf(issues) else emptyList()
                        }
                },
            )
        val result = service.scan(state("@scope/root" to "1.0.0")).targets.single()
        val fix = result.vulnerableDependencies.single().suggestedVersion!!
        assertEquals("@scope/root", fix.dependency)
        assertEquals("1.10.0", fix.version)
        assertEquals(listOf("1.0.0", "1.2.0", "1.10.0", "2.0.0"), lookedUp)
    }

    @Test
    fun `missing fix lookups fail explicitly and still vulnerable candidates have no suggestion`() {
        listOf(true, false).forEach { missing ->
            val service =
                service(
                    { key, version -> listOf(node(key, version)) },
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                            dependencies
                                .filterNot { missing && it.version == "2.0.0" }
                                .associate { "${it.key}:${it.version}" to listOf(advisory) }
                    },
                )
            if (missing) {
                assertThrows(IllegalStateException::class.java) { service.scan(state("root" to "1.0.0")) }
            } else {
                val result = service.scan(state("root" to "1.0.0")).targets.single()
                assertEquals(NodeSecurityStatus.VULNERABLE, result.status)
                assertNull(result.vulnerableDependencies.single().suggestedVersion)
            }
        }
    }

    @Test
    fun `running scan phases distinguish resolution from OSV lookups`() {
        val queue = QueuedExecutor()
        val state = state("safe" to "1.0.0")
        lateinit var scanner: NodeSecurityScanner
        val observed = mutableListOf<NodeScanPhase?>()
        scanner =
            NodeSecurityScanner(
                service(
                    { key, version ->
                        observed += scanner.get("alice", state).phase
                        listOf(node(key, version))
                    },
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                            observed += scanner.get("alice", state).phase
                            return dependencies.associate { "${it.key}:${it.version}" to emptyList() }
                        }
                    },
                ),
                queue,
            )
        assertEquals(NodeScanPhase.RESOLUTION, scanner.start("alice", state).phase)
        queue.runNext()
        assertEquals(listOf(NodeScanPhase.RESOLUTION, NodeScanPhase.OSV), observed)
        assertNull(scanner.get("alice", state).phase)
    }

    @Test
    fun `slow independent resolutions run four at a time and publish isolated progress until ready`() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val progress = CopyOnWriteArrayList<NodeScanProgress>()
        val state = state(*(1..8).map { "target-$it" to "1.0.0" }.toTypedArray())
        val service =
            service(
                { key, version ->
                    val running = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, running) }
                    started.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                        listOf(node(key, version))
                    } finally {
                        active.decrementAndGet()
                    }
                },
                osv,
            )
        Executors.newSingleThreadExecutor().use { executor ->
            val scanner = NodeSecurityScanner(service, executor)
            scanner.start("alice", state)
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS), "Expected four concurrent resolutions")
                val running = scanner.get("alice", state)
                assertEquals(SecurityScanStatus.RUNNING, running.status)
                assertNull(running.result)
                assertEquals(NodeScanPhase.RESOLUTION, running.phase)
                assertEquals(0, running.completedResolutions)
                assertEquals(9, running.totalResolutions)
                val otherUser = scanner.get("bob", state)
                assertEquals(SecurityScanStatus.IDLE, otherUser.status)
                assertNull(otherUser.phase)
                assertEquals(0, otherUser.totalResolutions)
                assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", state("changed" to "1.0.0")).status)
            } finally {
                release.countDown()
            }
            executor.submit {}.get(10, TimeUnit.SECONDS)
            val ready = scanner.get("alice", state)
            assertEquals(SecurityScanStatus.READY, ready.status)
            assertEquals(8, ready.result!!.targets.size)
            assertNull(ready.phase)
            assertEquals(0, ready.totalResolutions)
        }
        assertEquals(4, maximum.get())
        service.scan(state, progress::add)
        assertTrue(progress.zipWithNext().all { (before, after) -> before.completedResolutions <= after.completedResolutions })
        assertEquals(NodeScanProgress(NodeScanPhase.COVERAGE, 9, 9), progress.last())
    }

    @Test
    fun `invalid names and non exact specifications are unsupported without executing a resolver`() {
        val service = service({ _, _ -> error("Must not execute") }, osv)
        val invalid =
            listOf(
                "safe" to ">=1.0.0",
                "safe" to "1.x",
                "safe" to "latest",
                "safe" to "npm:other@1.0.0",
                "safe" to "file:../path",
                "safe" to "git+https://example.test/repo",
                "safe" to "^^1.0.0",
                "safe" to "1.0.0;touch /tmp/trap",
                "../path" to "1.0.0",
                "@bad" to "1.0.0",
            )
        invalid.forEach { (key, version) ->
            val result = service.scan(state(key to version)).targets.single()
            assertEquals(NodeSecurityStatus.UNSUPPORTED, result.status)
            assertNotNull(result.error)
        }
        assertTrue(queried.isEmpty())
    }

    @Test
    fun `failed or incomplete resolutions cannot turn green and do not hide other targets`() {
        val service =
            service(
                { key, version ->
                    when (key) {
                        "failed" -> throw IOException("Registry unavailable")
                        "empty" -> emptyList()
                        "wrong" -> listOf(node(key, "9.0.0"))
                        "incomplete" -> listOf(node(key, version), node("transitive", "<unresolved>"))
                        else -> listOf(node(key, version))
                    }
                },
                osv,
            )
        val result = service.scan(state(*listOf("failed", "empty", "wrong", "incomplete", "safe").map { it to "1.0.0" }.toTypedArray()))
        assertEquals(NodeSecurityStatus.OK, result.targets.single { it.key == "safe" }.status)
        assertTrue(result.targets.filter { it.key != "safe" }.all { it.status == NodeSecurityStatus.UNRESOLVED && it.error != null })
        assertTrue(queried.any { it.name == "safe" })
        assertTrue(queried.all { validNodeVersion(it.version) })
    }

    @Test
    fun `lookup failures and missing OSV results fail the scan instead of reporting safe targets`() {
        listOf(true, false).forEach { fail ->
            val service =
                service(
                    { key, version -> listOf(node(key, version)) },
                    object : VulnerabilityService {
                        override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                            if (fail) error("OSV unavailable")
                            return emptyMap()
                        }
                    },
                )
            val queue = QueuedExecutor()
            val scanner = NodeSecurityScanner(service, queue)
            val state = state("safe" to "1.0.0")
            assertEquals(SecurityScanStatus.RUNNING, scanner.start("alice", state).status)
            queue.runNext()
            assertEquals(SecurityScanStatus.FAILED, scanner.get("alice", state).status)
            assertNotNull(scanner.get("alice", state).error)
            assertNull(scanner.get("alice", state).result)
        }
    }

    @Test
    fun `jobs isolate users and fingerprints refresh ready scans and discard invalidated work`() {
        val queue = QueuedExecutor()
        val service = service({ key, version -> listOf(node(key, version)) }, osv)
        val scanner = NodeSecurityScanner(service, queue)
        val original = state("safe" to "1.0.0")
        val changed = state("safe" to "2.0.0")
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", original).status)
        scanner.start("alice", original)
        scanner.start("alice", original)
        assertEquals(1, queue.queuedTasks)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("bob", original).status)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", changed).status)
        queue.runNext()
        assertEquals(SecurityScanStatus.READY, scanner.get("alice", original).status)
        scanner.start("alice", original)
        assertEquals(1, queue.queuedTasks)
        scanner.invalidate("alice")
        queue.runNext()
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", original).status)
        scanner.start("alice", original)
        scanner.start("alice", changed)
        queue.runNext()
        queue.runNext()
        assertEquals(
            "2.0.0",
            scanner
                .get("alice", changed)
                .result!!
                .targets
                .single()
                .targetVersion,
        )
    }

    @Test
    fun `caret and exact forms have identical saved target semantics and scan fingerprints`() {
        val queue = QueuedExecutor()
        val scanner = NodeSecurityScanner(service({ key, version -> listOf(node(key, version)) }, osv), queue)
        val caret = state("safe" to "^1.2.3")
        val exact = state("safe" to "1.2.3")
        scanner.start("alice", caret)
        queue.runNext()
        assertEquals(SecurityScanStatus.READY, scanner.get("alice", caret).status)
        assertEquals(scanner.get("alice", exact), scanner.get("alice", caret))
        assertEquals(
            "1.2.3",
            scanner
                .get("alice", caret)
                .result!!
                .targets
                .single()
                .targetVersion,
        )
    }

    @Test
    fun `interruption propagates instead of continuing with more targets`() {
        val service = service({ _, _ -> throw InterruptedException("Cancelled") }, osv)
        try {
            assertThrows(InterruptedException::class.java) { service.scan(state("safe" to "1.0.0")) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupted resolution cancels and joins other workers before returning`() {
        val started = CountDownLatch(3)
        val stopped = CountDownLatch(3)
        val service =
            service(
                { key, _ ->
                    if (key == "cancel") {
                        check(started.await(5, TimeUnit.SECONDS))
                        throw InterruptedException("Cancelled")
                    }
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                        error("Expected cancellation")
                    } finally {
                        stopped.countDown()
                    }
                },
                osv,
            )
        try {
            assertThrows(InterruptedException::class.java) {
                service.scan(state("cancel" to "1.0.0", "first" to "1.0.0", "second" to "1.0.0", "third" to "1.0.0"))
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(0L, stopped.count)
        } finally {
            Thread.interrupted()
        }
    }

    private fun service(
        resolve: (String, String) -> List<ResolvedDependency>,
        lookup: VulnerabilityService = osv,
        combined: (Map<String, String>, Map<String, String>) -> NodeCombinedResolution = { dependencies, overrides ->
            val roots = (dependencies + overrides).mapValues { (key, version) -> listOf(node(key, version)) }
            NodeCombinedResolution(roots.values.flatten().distinct(), roots)
        },
    ) = NodeSecurityService(resolve, lookup, combined)

    private fun state(vararg dependencies: Pair<String, String>) =
        TargetVersionsState(mutableMapOf(), mutableMapOf(), "8.0", nodeDependencies = dependencies.toMap().toMutableMap())

    private fun node(
        name: String,
        version: String,
    ) = ResolvedDependency("npm", name, version, version, emptyList())
}
