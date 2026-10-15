package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.targets.TargetVersionsState

class PullRequestReadinessException(
    message: String,
) : IllegalStateException(message)

class DependencyScanViewService(
    private val dependencyScanCache: DependencyScanCache,
    private val targetSecurityScanner: TargetSecurityScanner,
    private val dependencyScanner: DependencyScanner,
) {
    fun requirePullRequestReady(
        username: String,
        targetState: TargetVersionsState,
        repository: String,
    ) {
        if (!targetSecurityScanner.normalReady(username, targetState)) {
            throw PullRequestReadinessException("A matching normal security scan must be READY before creating a pull request")
        }
        val inventory =
            dependencyScanCache.get(username).singleOrNull { it.repository == repository }
                ?: error("No scan found for $repository")
        if (inventory.platformBoms.isNotEmpty() || inventory.declarations.any { it.platformBom }) {
            if (!targetSecurityScanner.get(username, targetState, includeBoms = true).bomReady) {
                throw PullRequestReadinessException(
                    "A matching BOM coverage scan must be READY before creating a pull request for a repository with platform BOMs",
                )
            }
        }
    }

    fun get(
        username: String,
        targetState: TargetVersionsState,
    ): List<RepositoryDependencyScan> {
        val securityResult =
            targetSecurityScanner
                .get(username, targetState)
                .takeIf { it.status == SecurityScanStatus.READY }
                ?.result

        return dependencyScanCache.get(username).map { inventory ->
            val scan = dependencyScanner.compare(inventory, targetState)
            if (securityResult != null) {
                dependencyScanner.enrichSecurityScan(
                    scan = scan,
                    securityResult = securityResult,
                    inventory = inventory,
                )
            } else {
                scan
            }
        }
    }
}
