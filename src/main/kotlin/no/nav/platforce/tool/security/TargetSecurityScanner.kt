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
    private val bomJobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val resolution = resolutionScanner.waitForResolution(username, state, includeBoms = true)
            targetSecurityService.scan(resolution, state)
        }

    fun start(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): SecurityScanSnapshot {
        resolutionScanner.start(username, targetState, includeBoms)
        return if (includeBoms) {
            bomJobs.start(username, targetState, refreshReady = true).toSecuritySnapshot()
        } else {
            jobs.start(username, targetState.copy(platformBoms = mutableMapOf()), refreshReady = true).toSecuritySnapshot()
        }
    }

    fun get(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): SecurityScanSnapshot {
        val bom = bomJobs.get(username, targetState).toSecuritySnapshot()
        if (includeBoms) return bom
        val baseline = jobs.get(username, targetState.copy(platformBoms = mutableMapOf())).toSecuritySnapshot()
        if (bom.status != SecurityScanStatus.READY) return baseline
        if (baseline.status == SecurityScanStatus.IDLE) return bom
        if (baseline.status != SecurityScanStatus.READY) return baseline
        return baseline.copy(result = baseline.result?.copy(platformBoms = bom.result?.platformBoms.orEmpty()))
    }

    fun invalidate(username: String) {
        jobs.invalidate(username)
        bomJobs.invalidate(username)
    }

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
