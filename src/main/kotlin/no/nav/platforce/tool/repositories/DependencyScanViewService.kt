package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.node.NodeSecurityScanner
import no.nav.platforce.tool.node.enrichNodeRepositorySecurity
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.withoutIgnoredDependencies

class PullRequestReadinessException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class DependencyScanViewService(
    private val dependencyScanCache: DependencyScanCache,
    private val targetSecurityScanner: TargetSecurityScanner,
    private val dependencyScanner: DependencyScanner,
    private val nodeSecurityScanner: NodeSecurityScanner? = null,
) {
    fun requirePullRequestReady(
        username: String,
        targetState: TargetVersionsState,
        repository: String,
    ) {
        val inventory =
            dependencyScanCache.get(username).singleOrNull { it.repository == repository }
                ?: error("No scan found for $repository")
        if (inventory.projectType == RepositoryProjectType.SALESFORCE &&
            get(username, targetState).single { it.repository == repository }.findings.none {
                it.kind == DependencyKind.NODE &&
                    it.status in setOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD, DependencyStatus.ADD, DependencyStatus.REMOVE)
            }
        ) {
            return
        }
        if (inventory.projectType in setOf(RepositoryProjectType.NODE, RepositoryProjectType.SALESFORCE)) {
            if (nodeSecurityScanner?.ready(username, targetState) != true) {
                throw PullRequestReadinessException(
                    "Run a matching Node security scan with no unresolved targets before creating a pull request",
                )
            }
            return
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
        val nodeSecurityResult =
            nodeSecurityScanner
                ?.get(username, targetState)
                ?.takeIf { it.status == SecurityScanStatus.READY }
                ?.result

        return dependencyScanCache.get(username).map { cached ->
            val inventory = cached.withoutIgnoredDependencies(targetState.ignoredDependencies)
            val scan =
                dependencyScanner.compare(inventory, targetState.withoutIgnoredDependencies()).copy(
                    declaredDependencyKeys = cached.dependencies.keys.sorted(),
                )
            if (inventory.projectType in setOf(RepositoryProjectType.NODE, RepositoryProjectType.SALESFORCE) &&
                nodeSecurityResult != null
            ) {
                enrichNodeRepositorySecurity(
                    scan,
                    nodeSecurityResult,
                    targetState.withoutIgnoredDependencies(),
                    excludedDevDependencies =
                        inventory.nodeDependencies
                            .filter { it.declaration == "devDependencies" && it.key !in scan.declaredNodeDependencyKeys.orEmpty() }
                            .map { it.key }
                            .toSet(),
                )
            } else if (securityResult != null && inventory.projectType == RepositoryProjectType.GRADLE) {
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
