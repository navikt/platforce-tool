package no.nav.platforce.tool.dependencies

import mu.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class GradleTargetResolutionScanner(
    private val resolutionService: GradleTargetResolutionService,
) {
    private val log = KotlinLogging.logger { }

    private val executor =
        Executors.newSingleThreadExecutor {
            Thread(it, "gradle-target-resolution").apply {
                isDaemon = true
            }
        }

    private val snapshot =
        AtomicReference(
            TargetResolutionSnapshot(
                status = ResolutionStatus.IDLE,
                targetFingerprint = null,
                result = null,
                error = null,
            ),
        )

    fun start(targetState: TargetVersionsState): TargetResolutionSnapshot {
        val fingerprint = fingerprint(targetState)

        synchronized(this) {
            val current = snapshot.get()

            if (
                current.status == ResolutionStatus.READY &&
                current.targetFingerprint == fingerprint
            ) {
                log.info {
                    "Target resolution already available for fingerprint=$fingerprint"
                }

                return current
            }

            if (
                current.status == ResolutionStatus.RUNNING &&
                current.targetFingerprint == fingerprint
            ) {
                log.info {
                    "Target resolution already running for fingerprint=$fingerprint"
                }

                return current
            }

            log.info {
                "Starting target resolution for fingerprint=$fingerprint"
            }

            snapshot.set(
                TargetResolutionSnapshot(
                    status = ResolutionStatus.RUNNING,
                    targetFingerprint = fingerprint,
                    result = null,
                    error = null,
                ),
            )

            executor.submit {
                resolveAsync(
                    targetState = targetState,
                    fingerprint = fingerprint,
                )
            }

            return snapshot.get()
        }
    }

    fun get(targetState: TargetVersionsState): TargetResolutionSnapshot {
        val fingerprint = fingerprint(targetState)

        val current = snapshot.get()

        if (current.targetFingerprint != fingerprint) {
            return TargetResolutionSnapshot(
                status = ResolutionStatus.IDLE,
                targetFingerprint = fingerprint,
                result = null,
                error = null,
            )
        }

        log.info {
            "Returning cached target resolution: " +
                "status=${current.status}, " +
                "fingerprint=${current.targetFingerprint}, " +
                "hasResult=${current.result != null}"
        }

        return current
    }

    fun invalidate() {
        synchronized(this) {
            snapshot.set(
                TargetResolutionSnapshot(
                    status = ResolutionStatus.IDLE,
                    targetFingerprint = null,
                    result = null,
                    error = null,
                ),
            )
        }
    }

    private fun resolveAsync(
        targetState: TargetVersionsState,
        fingerprint: String,
    ) {
        try {
            log.info {
                "Running Gradle target resolution for fingerprint=$fingerprint"
            }

            val result =
                resolutionService.resolve(targetState)

            synchronized(this) {
                val current = snapshot.get()

                /*
                 * Target versions might have changed while Gradle was running.
                 * Never put a stale result into the cache.
                 */
                if (
                    current.status != ResolutionStatus.RUNNING ||
                    current.targetFingerprint != fingerprint
                ) {
                    log.info {
                        "Discarding stale resolution result for fingerprint=$fingerprint"
                    }

                    return
                }

                snapshot.set(
                    TargetResolutionSnapshot(
                        status = ResolutionStatus.READY,
                        targetFingerprint = fingerprint,
                        result = result,
                        error = null,
                    ),
                )
            }

            log.info {
                "Gradle target resolution completed for fingerprint=$fingerprint"
            }
        } catch (e: Exception) {
            log.error(e) {
                "Gradle target resolution failed for fingerprint=$fingerprint"
            }

            synchronized(this) {
                val current = snapshot.get()

                if (
                    current.status == ResolutionStatus.RUNNING &&
                    current.targetFingerprint == fingerprint
                ) {
                    snapshot.set(
                        TargetResolutionSnapshot(
                            status = ResolutionStatus.FAILED,
                            targetFingerprint = fingerprint,
                            result = null,
                            error = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun fingerprint(targetState: TargetVersionsState): String {
        val canonical =
            buildString {
                append("gradle=")
                append(targetState.gradleVersion)
                append('\n')

                targetState.plugins
                    .toSortedMap()
                    .forEach { (key, version) ->
                        append("plugin:")
                        append(key)
                        append('=')
                        append(version)
                        append('\n')
                    }

                targetState.dependencies
                    .toSortedMap()
                    .forEach { (key, version) ->
                        append("dependency:")
                        append(key)
                        append('=')
                        append(version)
                        append('\n')
                    }
            }

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray())

        return digest.joinToString("") {
            "%02x".format(it)
        }
    }
}
