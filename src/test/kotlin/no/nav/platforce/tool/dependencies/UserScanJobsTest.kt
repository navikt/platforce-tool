package no.nav.platforce.tool.dependencies

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class UserScanJobsTest {
    private val executor = QueuedExecutor()
    private val targets = TargetVersionsState(mutableMapOf(), mutableMapOf("example:library" to "1.0.0"), "8.1")

    @Test
    fun `identical targets for two users have separate jobs results and invalidation`() {
        val jobs = UserScanJobs(executor) { username, _: TargetVersionsState -> "result for $username" }
        // Even identical target fingerprints must not let Bob see Alice's running or finished scan.
        jobs.start("alice", targets)
        assertEquals(ScanJobStatus.IDLE, jobs.get("bob", targets).status)
        jobs.start("bob", targets)
        assertEquals(2, executor.queuedTasks)
        executor.runNext()
        assertEquals("result for alice", jobs.get("alice", targets).result)
        assertEquals(ScanJobStatus.RUNNING, jobs.get("bob", targets).status)
        executor.runNext()
        assertEquals("result for bob", jobs.get("bob", targets).result)

        jobs.invalidate("alice")
        assertEquals(ScanJobStatus.IDLE, jobs.get("alice", targets).status)
        assertEquals("result for bob", jobs.get("bob", targets).result)
    }

    @Test
    fun `repeated start during a scan does not schedule duplicate work`() {
        val jobs = UserScanJobs(executor) { _: String, _: TargetVersionsState -> "result" }
        val running = jobs.start("alice", targets)
        assertSame(running, jobs.start("alice", targets))
        assertEquals(1, executor.queuedTasks)
        executor.runNext()
        val ready = jobs.get("alice", targets)
        assertSame(ready, jobs.start("alice", targets))
        assertEquals(0, executor.queuedTasks)
    }

    @Test
    fun `scan inputs are captured before mutable target maps change`() {
        val jobs = UserScanJobs(executor) { _: String, state: TargetVersionsState -> state.dependencies.getValue("example:library") }
        val running = jobs.start("alice", targets)
        val original = targets.snapshot()
        targets.dependencies["example:library"] = "2.0.0"
        executor.runNext()

        assertEquals("1.0.0", jobs.get("alice", original).result)
        assertEquals(running.targetFingerprint, jobs.get("alice", original).targetFingerprint)
        assertEquals(ScanJobStatus.IDLE, jobs.get("alice", targets).status)
    }

    @Test
    fun `obsolete job cannot overwrite a new job even when the fingerprint is unchanged`() {
        lateinit var jobs: UserScanJobs<String>
        var calls = 0
        jobs =
            UserScanJobs(executor) { _: String, _: TargetVersionsState ->
                calls++
                if (calls == 1) {
                    // Simulate invalidation and a replacement scan while the old scan is still executing.
                    jobs.invalidate("alice")
                    jobs.start("alice", targets)
                    "old result"
                } else {
                    "new result"
                }
            }
        jobs.start("alice", targets)
        executor.runNext()
        assertEquals(ScanJobStatus.RUNNING, jobs.get("alice", targets).status)
        assertNull(jobs.get("alice", targets).result)
        executor.runNext()
        assertEquals("new result", jobs.get("alice", targets).result)
    }

    @Test
    fun `obsolete failing job cannot replace a newer running job with failure`() {
        lateinit var jobs: UserScanJobs<String>
        var calls = 0
        jobs =
            UserScanJobs(executor) { _: String, _: TargetVersionsState ->
                calls++
                if (calls == 1) {
                    jobs.invalidate("alice")
                    jobs.start("alice", targets)
                    error("Old job failed")
                }
                "new result"
            }
        jobs.start("alice", targets)
        executor.runNext()
        assertEquals(ScanJobStatus.RUNNING, jobs.get("alice", targets).status)
        assertNull(jobs.get("alice", targets).error)
        executor.runNext()
        assertEquals("new result", jobs.get("alice", targets).result)
    }

    @Test
    fun `queued obsolete job is skipped and changed targets produce their own result`() {
        var calls = 0
        val jobs =
            UserScanJobs(executor) { _: String, state: TargetVersionsState ->
                calls++
                state.dependencies.getValue("example:library")
            }
        val old = jobs.start("alice", targets)
        val changed = targets.copy(dependencies = mutableMapOf("example:library" to "2.0.0"))
        val new = jobs.start("alice", changed)
        assertNotEquals(old.targetFingerprint, new.targetFingerprint)
        executor.runNext()
        assertEquals(0, calls)
        assertEquals(ScanJobStatus.RUNNING, jobs.get("alice", changed).status)
        executor.runNext()
        assertEquals("2.0.0", jobs.get("alice", changed).result)
        assertEquals(ScanJobStatus.IDLE, jobs.get("alice", targets).status)
    }

    @Test
    fun `scan failure and scheduling failure are exposed as failed snapshots`() {
        val jobs = UserScanJobs<String>(executor) { _, _ -> error("Synthetic scan failure") }
        jobs.start("alice", targets)
        executor.runNext()
        assertEquals(ScanJobStatus.FAILED, jobs.get("alice", targets).status)
        assertEquals("Synthetic scan failure", jobs.get("alice", targets).error)

        executor.shutdown()
        val rejected = jobs.start("bob", targets)
        assertEquals(ScanJobStatus.FAILED, rejected.status)
        assertEquals("Test executor is shut down", rejected.error)
    }
}
