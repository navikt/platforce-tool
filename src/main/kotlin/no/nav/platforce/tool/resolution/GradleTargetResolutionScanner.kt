package no.nav.platforce.tool.resolution

import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.ScanJobStatus
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.targets.TargetVersionsState
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService

class GradleTargetResolutionScanner(
    resolve: (String, TargetVersionsState) -> TargetResolution,
    executor: ExecutorService = scanExecutor("gradle-target-resolution"),
) {
    private val jobs = UserScanJobs(executor, resolve)

    fun start(
        username: String,
        targetState: TargetVersionsState,
    ): TargetResolutionSnapshot = jobs.start(username, targetState).toResolutionSnapshot()

    fun get(
        username: String,
        targetState: TargetVersionsState,
    ): TargetResolutionSnapshot = jobs.get(username, targetState).toResolutionSnapshot()

    fun invalidate(username: String) = jobs.invalidate(username)

    fun waitForResolution(
        username: String,
        targetState: TargetVersionsState,
    ): TargetResolution {
        val started = jobs.get(username, targetState)
        while (true) {
            val current = jobs.get(username, targetState)
            when (current.status) {
                ScanJobStatus.READY -> return checkNotNull(current.result)
                ScanJobStatus.FAILED -> error("Gradle target resolution failed: ${current.error}")
                ScanJobStatus.IDLE -> throw CancellationException("Target resolution was invalidated")
                ScanJobStatus.RUNNING -> {
                    if (current !== started) throw CancellationException("Target resolution was replaced")
                    Thread.sleep(250)
                }
            }
        }
    }

    private fun ScanJobSnapshot<TargetResolution>.toResolutionSnapshot() =
        TargetResolutionSnapshot(
            status =
                when (status) {
                    ScanJobStatus.IDLE -> ResolutionStatus.IDLE
                    ScanJobStatus.RUNNING -> ResolutionStatus.RUNNING
                    ScanJobStatus.READY -> ResolutionStatus.READY
                    ScanJobStatus.FAILED -> ResolutionStatus.FAILED
                },
            targetFingerprint = targetFingerprint,
            result = result,
            error = error,
        )
}
