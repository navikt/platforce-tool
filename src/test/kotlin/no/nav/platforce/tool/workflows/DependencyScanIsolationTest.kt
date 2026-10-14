package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.ScanProgress
import no.nav.platforce.tool.repositories.UntrackedDependency
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DependencyScanIsolationTest {
    private val cache = DependencyScanCache()

    @Test
    fun `repository inventories and progress are isolated by username`() {
        val alice = inventory("example:library" to "1.0.0")
        val bob = inventory("example:library" to "2.0.0")
        cache.update("alice", listOf(alice))
        cache.setProgress("alice", ScanProgress(total = 10, done = 3, running = true))
        assertTrue(cache.get("bob").isEmpty())
        assertEquals(ScanProgress(), cache.getProgress("bob"))

        cache.updateRepository("bob", bob)
        cache.setProgress("bob", ScanProgress(total = 1, done = 1))
        assertEquals(listOf(alice), cache.get("alice"))
        assertEquals(listOf(bob), cache.get("bob"))
        assertEquals(3, cache.getProgress("alice").done)
        assertEquals(1, cache.getProgress("bob").done)
    }

    @Test
    fun `concurrent repository updates for one user do not lose results`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                (1..30).map { index ->
                    executor.submit {
                        cache.updateRepository("alice", inventory().copy(repository = "example/repository-$index"))
                    }
                }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((1..30).map { "example/repository-$it" }.toSet(), cache.get("alice").map { it.repository }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `refresh lock serializes one users refreshes without blocking another user`() {
        val executor = Executors.newFixedThreadPool(3)
        val releaseAlice = CountDownLatch(1)
        val aliceStarted = CountDownLatch(1)
        val secondAliceAttempted = CountDownLatch(1)
        val secondAliceEntered = CountDownLatch(1)
        try {
            val alice =
                executor.submit {
                    cache.withRefreshLock("alice") {
                        aliceStarted.countDown()
                        check(releaseAlice.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(aliceStarted.await(5, TimeUnit.SECONDS))
            val secondAlice =
                executor.submit {
                    secondAliceAttempted.countDown()
                    cache.withRefreshLock("alice") { secondAliceEntered.countDown() }
                }
            assertTrue(secondAliceAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(secondAliceEntered.await(100, TimeUnit.MILLISECONDS))
            // Bob's refresh can proceed while Alice's refresh is holding its lock.
            val bob = executor.submit<String> { cache.withRefreshLock("bob") { "bob result" } }
            assertEquals("bob result", bob.get(5, TimeUnit.SECONDS))
            releaseAlice.countDown()
            alice.get(5, TimeUnit.SECONDS)
            secondAlice.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondAliceEntered.count)
        } finally {
            releaseAlice.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `inventory is compared against current targets without fetching GitHub again`() {
        val github = RecordingGithubClient()
        val scanner = DependencyScanner(github)
        val resolution = GradleTargetResolutionScanner({ _, _ -> error("Unexpected Gradle call") })
        val security = TargetSecurityScanner(TargetSecurityService(EmptyVulnerabilityService()), resolution)
        val view = DependencyScanViewService(cache, security, scanner)
        cache.update("alice", listOf(inventory("example:library" to "1.0.0", "example:other" to "3.0.0")))
        val original = TargetVersionsState(mutableMapOf(), mutableMapOf("example:library" to "1.0.0"), "8.0")
        val before = view.get("alice", original).single()
        assertEquals(DependencyStatus.OK, before.findings.first().status)
        assertEquals(listOf(UntrackedDependency("example:other", "3.0.0")), before.untrackedDependencies)

        // Saving different targets recomputes both comparisons and the untracked list from the same inventory.
        val changed =
            original.copy(dependencies = mutableMapOf("example:library" to "2.0.0", "example:other" to "3.0.0"))
        val after = view.get("alice", changed).single()
        assertEquals(DependencyStatus.UPDATE, after.findings.first().status)
        assertTrue(after.untrackedDependencies.isEmpty())
        assertEquals(before.scannedAt, after.scannedAt)
        assertTrue(view.get("bob", changed).isEmpty())
        assertTrue(github.calls.isEmpty())
    }

    @Test
    fun `failed repository refresh clears running state and records an error for only that user`() {
        val scanner = DependencyScanner(RecordingGithubClient())
        cache.setProgress("bob", ScanProgress(total = 3, done = 1, running = true))
        assertThrows(IllegalStateException::class.java) {
            scanner.scanAllRepositoriesWithProgress(cache, "alice")
        }
        assertEquals(false, cache.getProgress("alice").running)
        assertEquals("Unexpected repository listing", cache.getProgress("alice").error)
        assertEquals(ScanProgress(total = 3, done = 1, running = true), cache.getProgress("bob"))
    }
}
