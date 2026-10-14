package no.nav.platforce.tool.dependencies

class DependencyScanViewService(
    private val dependencyScanCache: DependencyScanCache,
    private val targetSecurityScanner: TargetSecurityScanner,
    private val dependencyScanner: DependencyScanner,
) {
    fun get(targetState: TargetVersionsState): List<RepositoryDependencyScan> {
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
