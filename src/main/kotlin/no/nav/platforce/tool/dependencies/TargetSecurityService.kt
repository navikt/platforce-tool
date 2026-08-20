package no.nav.platforce.tool.dependencies
import mu.KotlinLogging
import no.nav.platforce.tool.OverrideReason
import no.nav.platforce.tool.ResolvedDependencySecurity
import no.nav.platforce.tool.TargetSecurityResult
import no.nav.platforce.tool.TargetSecurityStatus
import no.nav.platforce.tool.Vulnerability
import kotlin.collections.isNotEmpty

class TargetSecurityService(
    private val vulnerabilityService: VulnerabilityService,
) {
    private val log = KotlinLogging.logger { }

    fun scan(
        resolution: TargetResolution,
        targetState: TargetVersionsState,
    ): TargetSecurityScan {
        log.info {
            "Starting target security scan for " +
                "${resolution.roots.size} target dependencies"
        }
        val vulnerabilityMap =
            vulnerabilityService.find(
                resolution.roots,
            )
        log.info {
            "Vulnerability lookup completed for " +
                "${vulnerabilityMap.size} dependency versions"
        }
        val targetResults =
            resolution.roots.map { root ->
                evaluateTarget(
                    root = root,
                    roots = resolution.roots,
                    targetState = targetState,
                    vulnerabilityMap = vulnerabilityMap,
                )
            }
        val allResolvedDependencies =
            flatten(resolution.roots)
                .map { dependency ->
                    ResolvedDependencySecurity(
                        group = dependency.group,
                        name = dependency.name,
                        requestedVersion = dependency.requestedVersion,
                        resolvedVersion = dependency.version,
                        vulnerabilities =
                            vulnerabilityMap[
                                coordinate(
                                    dependency.group,
                                    dependency.name,
                                    dependency.version,
                                ),
                            ].orEmpty(),
                    )
                }.distinctBy {
                    coordinate(
                        it.group,
                        it.name,
                        it.resolvedVersion,
                    )
                }
        return TargetSecurityScan(
            dependencies = allResolvedDependencies,
            targets = targetResults,
        )
    }

    private fun evaluateTarget(
        root: ResolvedDependency,
        roots: List<ResolvedDependency>,
        targetState: TargetVersionsState,
        vulnerabilityMap: Map<String, List<Vulnerability>>,
    ): TargetSecurityResult {
        val targetKey =
            coordinateWithoutVersion(
                root.group,
                root.name,
            )
        val targetVersion =
            targetState.dependencies[targetKey]
                ?: root.version
        val subtree =
            flatten(
                listOf(root),
            )
        val resolvedVulnerabilities =
            subtree
                .flatMap { dependency ->
                    vulnerabilityMap[
                        coordinate(
                            dependency.group,
                            dependency.name,
                            dependency.version,
                        ),
                    ].orEmpty()
                }.distinctBy { it.id }
        /*
         * Look for dependency requests which were vulnerable at the
         * requested version but became safe because Gradle selected
         * another version.
         */
        val overridden =
            findOverrides(
                root = root,
                roots = roots,
                vulnerabilityMap = vulnerabilityMap,
            )
        val status =
            when {
                resolvedVulnerabilities.isNotEmpty() ->
                    TargetSecurityStatus.VULNERABLE
                overridden.isNotEmpty() ->
                    TargetSecurityStatus.OK_OVERRIDDEN
                else ->
                    TargetSecurityStatus.OK
            }
        log.info {
            "Target $targetKey:$targetVersion -> $status " +
                "(vulnerabilities=${resolvedVulnerabilities.size}, " +
                "overrides=${overridden.size})"
        }
        return TargetSecurityResult(
            key = targetKey,
            targetVersion = targetVersion,
            status = status,
            vulnerabilities = resolvedVulnerabilities,
            overriddenBy = overridden,
        )
    }

    private fun findOverrides(
        root: ResolvedDependency,
        roots: List<ResolvedDependency>,
        vulnerabilityMap: Map<String, List<Vulnerability>>,
    ): List<OverrideReason> {
        val result = mutableListOf<OverrideReason>()
        val subtree =
            flatten(
                listOf(root),
            )
        subtree.forEach { dependency ->
            val requestedVersion =
                dependency.requestedVersion
                    ?: return@forEach
            if (requestedVersion == dependency.version) {
                return@forEach
            }
            val requestedKey =
                coordinate(
                    dependency.group,
                    dependency.name,
                    requestedVersion,
                )
            val resolvedKey =
                coordinate(
                    dependency.group,
                    dependency.name,
                    dependency.version,
                )
            val requestedVulnerabilities =
                vulnerabilityMap[requestedKey].orEmpty()
            val resolvedVulnerabilities =
                vulnerabilityMap[resolvedKey].orEmpty()
            /*
             * We only consider this an override when:
             *
             *   requested version = vulnerable
             *   resolved version  = safe
             *
             * This means the target would have had a vulnerable
             * dependency request, but the combined target set caused
             * Gradle to select a safe version.
             */
            if (
                requestedVulnerabilities.isEmpty() ||
                resolvedVulnerabilities.isNotEmpty()
            ) {
                return@forEach
            }
            val overridingTargets =
                roots
                    .filter { otherRoot ->
                        !sameDependency(
                            root,
                            otherRoot,
                        )
                    }.filter { otherRoot ->
                        containsResolvedVersion(
                            root = otherRoot,
                            group = dependency.group,
                            name = dependency.name,
                            version = dependency.version,
                        )
                    }
            overridingTargets.forEach { overridingRoot ->
                result +=
                    OverrideReason(
                        dependency =
                            coordinateWithoutVersion(
                                overridingRoot.group,
                                overridingRoot.name,
                            ),
                        targetVersion =
                            overridingRoot.version,
                        resolvedVersion =
                            dependency.version,
                    )
            }
        }
        return result.distinctBy {
            "${it.dependency}:${it.targetVersion}:${it.resolvedVersion}"
        }
    }

    private fun containsResolvedVersion(
        root: ResolvedDependency,
        group: String?,
        name: String,
        version: String,
    ): Boolean =
        flatten(listOf(root)).any { dependency ->
            dependency.group == group &&
                dependency.name == name &&
                dependency.version == version
        }

    private fun sameDependency(
        left: ResolvedDependency,
        right: ResolvedDependency,
    ): Boolean =
        left.group == right.group &&
            left.name == right.name

    private fun flatten(roots: List<ResolvedDependency>): List<ResolvedDependency> {
        val result = mutableListOf<ResolvedDependency>()
        val visited = mutableSetOf<String>()

        fun visit(node: ResolvedDependency) {
            val key =
                coordinate(
                    node.group,
                    node.name,
                    node.version,
                )
            if (!visited.add(key)) {
                return
            }
            result += node
            node.dependencies.forEach(::visit)
        }
        roots.forEach(::visit)
        return result
    }

    private fun coordinate(
        group: String?,
        name: String,
        version: String,
    ): String = "${group ?: ""}:$name:$version"

    private fun coordinateWithoutVersion(
        group: String?,
        name: String,
    ): String = "${group ?: ""}:$name"
}
