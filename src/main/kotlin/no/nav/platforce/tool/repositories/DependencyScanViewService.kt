package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.withoutIgnoredDependencies

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
        val inventory =
            dependencyScanCache.get(username).singleOrNull { it.repository == repository }
                ?: error("No scan found for $repository")
        if (inventory.projectType == RepositoryProjectType.NODE) {
            throw PullRequestReadinessException("Node.js pull request updates and security scans are not supported yet")
        }
        if (!targetSecurityScanner.normalReady(username, targetState)) {
            throw PullRequestReadinessException("A matching normal security scan must be READY before creating a pull request")
        }
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

        return dependencyScanCache.get(username).map { cached ->
            val inventory = cached.withoutIgnoredDependencies(targetState.ignoredDependencies)
            val scan = dependencyScanner.compare(inventory, targetState.withoutIgnoredDependencies())
            if (securityResult != null && inventory.projectType != RepositoryProjectType.NODE) {
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
