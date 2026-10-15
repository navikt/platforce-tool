package no.nav.platforce.tool.security

import no.nav.platforce.tool.resolution.BOM_PREREQUISITE
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
            targetSecurityService.scanCoverage(resolution, state, checkNotNull(normalResult(username, state)) { BOM_PREREQUISITE })
        }

    init {
        resolutionScanner.useSecurityBaseline(::normalResult)
    }

    private fun normalResult(
        username: String,
        state: TargetVersionsState,
    ): TargetSecurityScan? =
        jobs.get(username, state.copy(platformBoms = mutableMapOf())).takeIf { it.status == ScanJobStatus.READY }?.result

    fun normalReady(
        username: String,
        state: TargetVersionsState,
    ): Boolean = normalResult(username, state) != null

    fun start(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): SecurityScanSnapshot = withReadiness(startSnapshot(username, targetState, includeBoms), username, targetState)

    private fun startSnapshot(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean,
    ): SecurityScanSnapshot {
        if (includeBoms && !resolutionScanner.canScanCoverage(username, targetState)) {
            return bomJobs.fail(username, targetState, BOM_PREREQUISITE).toSecuritySnapshot()
        }
        resolutionScanner.start(username, targetState, includeBoms)
        return if (includeBoms) {
            bomJobs.start(username, targetState).toSecuritySnapshot()
        } else {
            bomJobs.invalidate(username)
            resolutionScanner.invalidateCoverage(username)
            jobs.start(username, targetState.copy(platformBoms = mutableMapOf()), refreshReady = true).toSecuritySnapshot()
        }
    }

    fun get(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): SecurityScanSnapshot = withReadiness(getSnapshot(username, targetState, includeBoms), username, targetState)

    private fun withReadiness(
        snapshot: SecurityScanSnapshot,
        username: String,
        state: TargetVersionsState,
    ): SecurityScanSnapshot =
        snapshot.copy(
            normalReady = normalReady(username, state),
            bomReady = normalReady(username, state) && bomJobs.get(username, state).status == ScanJobStatus.READY,
        )

    private fun getSnapshot(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean,
    ): SecurityScanSnapshot {
        val bom = bomJobs.get(username, targetState).toSecuritySnapshot()
        if (includeBoms) return bom
        val baseline = jobs.get(username, targetState.copy(platformBoms = mutableMapOf())).toSecuritySnapshot()
        if (bom.status != SecurityScanStatus.READY) return baseline
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
