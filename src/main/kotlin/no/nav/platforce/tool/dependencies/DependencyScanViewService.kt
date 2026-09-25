package no.nav.platforce.tool.dependencies

import mu.KotlinLogging

class DependencyScanViewService(
    private val dependencyScanCache: DependencyScanCache,
    private val targetSecurityScanner: TargetSecurityScanner,
    private val dependencyScanner: DependencyScanner,
) {
    val log = KotlinLogging.logger { }

    fun get(targetState: TargetVersionsState): List<RepositoryDependencyScan> {
        val securitySnapshot =
            targetSecurityScanner.get(targetState)

        log.info {
            "DEPENDENCY VIEW SECURITY: " +
                "status=${securitySnapshot.status}, " +
                "hasResult=${securitySnapshot.result != null}"
        }

        val securityResult =
            targetSecurityScanner
                .get(targetState)
                .takeIf { it.status == SecurityScanStatus.READY }
                ?.result

        return dependencyScanCache.get().map { scan ->
            if (securityResult != null) {
                dependencyScanner.enrichSecurityScan(
                    scan = scan,
                    securityResult = securityResult,
                )
            } else {
                scan
            }
        }
    }
}
