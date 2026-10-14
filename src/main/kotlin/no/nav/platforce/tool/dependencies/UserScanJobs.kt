package no.nav.platforce.tool.dependencies

import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

internal enum class ScanJobStatus {
    IDLE,
    RUNNING,
    READY,
    FAILED,
}

internal data class ScanJobSnapshot<T>(
    val status: ScanJobStatus,
    val targetFingerprint: String,
    val result: T? = null,
    val error: String? = null,
)

internal class UserScanJobs<T>(
    private val executor: ExecutorService,
    private val scan: (String, TargetVersionsState) -> T,
) {
    private val log = KotlinLogging.logger { }
    private val snapshots = ConcurrentHashMap<String, AtomicReference<ScanJobSnapshot<T>?>>()

    fun start(
        username: String,
        targetState: TargetVersionsState,
        refreshReady: Boolean = false,
    ): ScanJobSnapshot<T> {
        val state = targetState.snapshot()
        val fingerprint = fingerprint(state)
        val slot = snapshots.computeIfAbsent(username) { AtomicReference() }

        synchronized(slot) {
            val current = slot.get()
            if (
                current?.targetFingerprint == fingerprint &&
                (current.status == ScanJobStatus.RUNNING || (current.status == ScanJobStatus.READY && !refreshReady))
            ) {
                return current
            }

            val running = ScanJobSnapshot<T>(ScanJobStatus.RUNNING, fingerprint)
            slot.set(running)
            try {
                executor.submit {
                    if (slot.get() !== running) return@submit
                    try {
                        val result = scan(username, state)
                        slot.compareAndSet(running, ScanJobSnapshot(ScanJobStatus.READY, fingerprint, result))
                    } catch (e: Exception) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        log.error(e) { "Scan failed for target fingerprint=$fingerprint" }
                        slot.compareAndSet(
                            running,
                            ScanJobSnapshot(ScanJobStatus.FAILED, fingerprint, error = e.message ?: e.javaClass.simpleName),
                        )
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                log.error(e) { "Could not schedule scan for target fingerprint=$fingerprint" }
                slot.compareAndSet(
                    running,
                    ScanJobSnapshot(ScanJobStatus.FAILED, fingerprint, error = e.message ?: e.javaClass.simpleName),
                )
            }
            return checkNotNull(slot.get())
        }
    }

    fun get(
        username: String,
        targetState: TargetVersionsState,
    ): ScanJobSnapshot<T> {
        val fingerprint = fingerprint(targetState)
        return snapshots[username]?.get()?.takeIf { it.targetFingerprint == fingerprint }
            ?: ScanJobSnapshot(ScanJobStatus.IDLE, fingerprint)
    }

    fun invalidate(username: String) {
        snapshots[username]?.let { slot ->
            synchronized(slot) {
                slot.set(null)
            }
        }
    }
}

internal fun scanExecutor(name: String): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
