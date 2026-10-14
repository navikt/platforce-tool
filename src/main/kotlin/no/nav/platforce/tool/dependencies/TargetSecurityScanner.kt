package no.nav.platforce.tool.dependencies

import java.util.concurrent.ExecutorService

enum class SecurityScanStatus {
    IDLE,
    RUNNING,
    READY,
    FAILED,
}

data class SecurityScanSnapshot(
    val status: SecurityScanStatus,
    val targetFingerprint: String?,
    val result: TargetSecurityScan?,
    val error: String?,
)

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
