package no.nav.platforce.tool.security

import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.ScanJobStatus
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.targets.TargetVersionsState
import java.util.concurrent.ExecutorService

class TargetSecurityScanner(
    targetSecurityService: TargetSecurityService,
    private val resolutionScanner: GradleTargetResolutionScanner,
    executor: ExecutorService = scanExecutor("target-security"),
) {
    private val jobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val resolution = resolutionScanner.waitForResolution(username, state)
            targetSecurityService.scan(resolution, state)
        }

    fun start(
        username: String,
        targetState: TargetVersionsState,
    ): SecurityScanSnapshot {
        resolutionScanner.start(username, targetState)
        return jobs.start(username, targetState, refreshReady = true).toSecuritySnapshot()
    }

    fun get(
        username: String,
        targetState: TargetVersionsState,
    ): SecurityScanSnapshot = jobs.get(username, targetState).toSecuritySnapshot()

    fun invalidate(username: String) = jobs.invalidate(username)

    private fun ScanJobSnapshot<TargetSecurityScan>.toSecuritySnapshot() =
        SecurityScanSnapshot(
            status =
                when (status) {
                    ScanJobStatus.IDLE -> SecurityScanStatus.IDLE
                    ScanJobStatus.RUNNING -> SecurityScanStatus.RUNNING
                    ScanJobStatus.READY -> SecurityScanStatus.READY
                    ScanJobStatus.FAILED -> SecurityScanStatus.FAILED
                },
            targetFingerprint = targetFingerprint,
            result = result,
            error = error,
        )
}
