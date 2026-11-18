package no.nav.platforce.tool.resolution

import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.ScanJobStatus
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.security.TargetSecurityScan
import no.nav.platforce.tool.targets.TargetVersionsState
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService

const val BOM_PREREQUISITE = "A matching normal security scan must be READY before scanning BOM coverage"

class GradleTargetResolutionScanner(
    resolve: (String, TargetVersionsState) -> TargetResolution,
    executor: ExecutorService = scanExecutor("gradle-target-resolution"),
    coverageResolve: ((String, TargetVersionsState, TargetResolution, Set<String>) -> TargetResolution)? = null,
    private val progress: (String, TargetVersionsState, Boolean) -> GradleResolutionProgress? = { _, _, _ -> null },
) {
    private val jobs = UserScanJobs(executor, resolve)
    private var securityBaseline: (String, TargetVersionsState) -> TargetSecurityScan? = { _, _ -> null }
    private val bomJobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val baseline = checkNotNull(securityBaseline(username, state)) { BOM_PREREQUISITE }
            val resolution = checkNotNull(jobs.get(username, state.copy(platformBoms = mutableMapOf())).result) { BOM_PREREQUISITE }
            val relevant =
                baseline.targets
                    .filter { it.vulnerableDependencies.isNotEmpty() }
                    .map { it.key }
                    .toSet()
            checkNotNull(coverageResolve) { "Coverage-only Gradle resolver is not configured" }(username, state, resolution, relevant)
        }

    fun useSecurityBaseline(provider: (String, TargetVersionsState) -> TargetSecurityScan?) {
        securityBaseline = provider
    }

    fun canScanCoverage(
        username: String,
        state: TargetVersionsState,
    ): Boolean =
        securityBaseline(username, state) != null &&
            jobs.get(username, state.copy(platformBoms = mutableMapOf())).status == ScanJobStatus.READY

    fun start(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): TargetResolutionSnapshot =
        if (includeBoms) {
            if (canScanCoverage(username, targetState)) {
                bomJobs.start(username, targetState).toResolutionSnapshot(username, targetState, true)
            } else {
                bomJobs.fail(username, targetState, BOM_PREREQUISITE).toResolutionSnapshot(username, targetState, true)
            }
        } else {
            val state = targetState.copy(platformBoms = mutableMapOf())
            jobs.start(username, state).toResolutionSnapshot(username, state, false)
        }

    fun get(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): TargetResolutionSnapshot =
        if (includeBoms) {
            bomJobs.get(username, targetState).toResolutionSnapshot(username, targetState, true)
        } else {
            val state = targetState.copy(platformBoms = mutableMapOf())
            jobs.get(username, state).toResolutionSnapshot(username, state, false)
        }

    fun invalidate(username: String) {
        jobs.invalidate(username)
        bomJobs.invalidate(username)
    }

    fun invalidateCoverage(username: String) = bomJobs.invalidate(username)

    fun waitForResolution(
        username: String,
        targetState: TargetVersionsState,
        includeBoms: Boolean = false,
    ): TargetResolution {
        val selectedJobs = if (includeBoms) bomJobs else jobs
        val state = if (includeBoms) targetState else targetState.copy(platformBoms = mutableMapOf())
        val started = selectedJobs.get(username, state)
        while (true) {
            val current = selectedJobs.get(username, state)
            when (current.status) {
                ScanJobStatus.READY -> {
                    return checkNotNull(current.result)
                }

                ScanJobStatus.FAILED -> {
                    error("Gradle target resolution failed: ${current.error}")
                }

                ScanJobStatus.IDLE -> {
                    throw CancellationException("Target resolution was invalidated")
                }

                ScanJobStatus.RUNNING -> {
                    if (current !== started) throw CancellationException("Target resolution was replaced")
                    Thread.sleep(250)
                }
            }
        }
    }

    private fun ScanJobSnapshot<TargetResolution>.toResolutionSnapshot(
        username: String,
        state: TargetVersionsState,
        includeBoms: Boolean,
    ): TargetResolutionSnapshot {
        val current = if (status == ScanJobStatus.RUNNING) progress(username, state, includeBoms) else null
        val total =
            current?.totalResolutions ?: if (includeBoms) {
                result?.platformBoms?.values?.sumOf { it.coverage.size }
                    ?: (
                        state.platformBoms.size *
                            securityBaseline(username, state)?.targets.orEmpty().count { it.vulnerableDependencies.isNotEmpty() }
                    )
            } else {
                state.dependencies.size + 1
            }
        return TargetResolutionSnapshot(
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
            completedResolutions = if (status == ScanJobStatus.READY) total else current?.completedResolutions ?: 0,
            totalResolutions = if (status in setOf(ScanJobStatus.RUNNING, ScanJobStatus.READY)) total else 0,
        )
    }
}
