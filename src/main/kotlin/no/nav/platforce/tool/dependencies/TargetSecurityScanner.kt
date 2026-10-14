package no.nav.platforce.tool.dependencies

import mu.KotlinLogging
import no.nav.platforce.tool.application
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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
    private val targetSecurityService: TargetSecurityService,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val snapshot =
        AtomicReference(
            SecurityScanSnapshot(
                status = SecurityScanStatus.IDLE,
                targetFingerprint = null,
                result = null,
                error = null,
            ),
        )

    fun start(targetState: TargetVersionsState): SecurityScanSnapshot {
        val fingerprint = fingerprint(targetState)
        synchronized(this) {
            val current = snapshot.get()
            if (
                current.targetFingerprint == fingerprint &&
                current.status == SecurityScanStatus.READY
            ) {
                return current
            }
            if (
                current.targetFingerprint == fingerprint &&
                current.status == SecurityScanStatus.RUNNING
            ) {
                return current
            }
            snapshot.set(
                SecurityScanSnapshot(
                    status = SecurityScanStatus.RUNNING,
                    targetFingerprint = fingerprint,
                    result = null,
                    error = null,
                ),
            )
            executor.submit {
                try {
                    val resolution =
                        application.waitForResolution(
                            targetState = targetState,
                        )
                    val result =
                        targetSecurityService.scan(
                            resolution = resolution,
                            targetState = targetState,
                        )
                    snapshot.set(
                        SecurityScanSnapshot(
                            status = SecurityScanStatus.READY,
                            targetFingerprint = fingerprint,
                            result = result,
                            error = null,
                        ),
                    )
                } catch (e: Exception) {
                    snapshot.set(
                        SecurityScanSnapshot(
                            status = SecurityScanStatus.FAILED,
                            targetFingerprint = fingerprint,
                            result = null,
                            error = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
            return snapshot.get()
        }
    }

    private val log = KotlinLogging.logger { }

    fun get(targetState: TargetVersionsState): SecurityScanSnapshot {
        val fingerprint = fingerprint(targetState)
        log.info {
            "TARGET SECURITY GET: " +
                "fingerprint=$fingerprint"
        }
        val current = snapshot.get()

        log.info {
            "TARGET SECURITY CACHE: " +
                "status=${current.status}, " +
                "fingerprint=${current.targetFingerprint}, " +
                "targets=${current.result?.targets?.size}"
        }

        if (current.targetFingerprint != fingerprint) {
            log.info {
                "TARGET SECURITY CACHE MISS: " +
                    "requestedFingerprint=$fingerprint, " +
                    "cachedFingerprint=${current.targetFingerprint}"
            }
            return SecurityScanSnapshot(
                status = SecurityScanStatus.IDLE,
                targetFingerprint = fingerprint,
                result = null,
                error = null,
            )
        }

        log.info {
            "TARGET SECURITY CACHE HIT: " +
                "status=${current.status}, " +
                "targets=${current.result?.targets?.size}"
        }
        return current
    }

    fun invalidate() {
        snapshot.set(
            SecurityScanSnapshot(
                status = SecurityScanStatus.IDLE,
                targetFingerprint = null,
                result = null,
                error = null,
            ),
        )
    }
}
