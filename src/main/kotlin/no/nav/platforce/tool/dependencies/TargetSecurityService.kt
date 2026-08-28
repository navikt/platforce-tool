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
        val allDependencies =
            allDependencies(resolution)
        val vulnerabilityMap =
            vulnerabilityService.find(
                allDependencies,
            )
        val targets =
            targetState.dependencies
                .toSortedMap()
                .map { (targetKey, targetVersion) ->
                    evaluateTarget(
                        targetKey = targetKey,
                        targetVersion = targetVersion,
                        resolution = resolution,
                        targetState = targetState,
                        vulnerabilityMap = vulnerabilityMap,
                    )
                }
        val securityDependencies =
            allDependencies
                .distinctBy {
                    "${it.group}:${it.name}:${it.version}"
                }.map { dependency ->
                    ResolvedDependencySecurity(
                        group = dependency.group,
                        name = dependency.name,
                        requestedVersion = dependency.requestedVersion,
                        resolvedVersion = dependency.version,
                        vulnerabilities =
                            vulnerabilityMap[
                                "${dependency.group}:${dependency.name}:${dependency.version}",
                            ].orEmpty(),
                    )
                }
        return TargetSecurityScan(
            dependencies = securityDependencies,
            targets = targets,
        )
    }

    private fun evaluateTarget(
        targetKey: String,
        targetVersion: String,
        resolution: TargetResolution,
        targetState: TargetVersionsState,
        vulnerabilityMap: Map<String, List<Vulnerability>>,
    ): TargetSecurityResult {
        log.info {
            """
            SECURITY DEBUG target=$targetKey:$targetVersion

            INDIVIDUAL:
            ${
                flatten(
                    resolution.individual[targetKey] ?: emptyList(),
                ).joinToString("\n") {
                    "  ${it.group}:${it.name}:${it.version} (requested=${it.requestedVersion})"
                }
            }

            COMBINED:
            ${
                resolution.roots.joinToString("\n") {
                    "${it.group}:${it.name}:${it.version}"
                }
            }
            """.trimIndent()
        }
        val standaloneTree =
            resolution.individual[targetKey]
                ?: emptyList()
        val standaloneDependencies =
            flatten(standaloneTree)
        val vulnerableDependencies =
            standaloneDependencies
                .mapNotNull { dependency ->
                    val vulnerabilities =
                        vulnerabilityMap[
                            coordinate(
                                dependency.group,
                                dependency.name,
                                dependency.version,
                            ),
                        ].orEmpty()
                    if (vulnerabilities.isEmpty()) {
                        null
                    } else {
                        VulnerableDependency(
                            dependency = dependency,
                            vulnerabilities = vulnerabilities,
                        )
                    }
                }
        if (vulnerableDependencies.isEmpty()) {
            return TargetSecurityResult(
                key = targetKey,
                targetVersion = targetVersion,
                status = TargetSecurityStatus.OK,
                vulnerabilities = emptyList(),
                overriddenBy = emptyList(),
            )
        }
        val overrides =
            vulnerableDependencies
                .flatMap { vulnerable ->
                    findOverrides(
                        vulnerable = vulnerable,
                        targetKey = targetKey,
                        targetState = targetState,
                        resolution = resolution,
                        vulnerabilityMap = vulnerabilityMap,
                    )
                }.distinctBy {
                    "${it.dependency}:${it.targetVersion}:${it.resolvedVersion}"
                }
        val unresolvedVulnerabilities =
            vulnerableDependencies.filter { vulnerable ->
                !hasOverride(
                    vulnerable = vulnerable,
                    overrides = overrides,
                )
            }
        return when {
            unresolvedVulnerabilities.isNotEmpty() ->
                TargetSecurityResult(
                    key = targetKey,
                    targetVersion = targetVersion,
                    status = TargetSecurityStatus.VULNERABLE,
                    vulnerabilities =
                        unresolvedVulnerabilities
                            .flatMap { it.vulnerabilities }
                            .distinctBy { it.id },
                    overriddenBy = overrides,
                )
            else ->
                TargetSecurityResult(
                    key = targetKey,
                    targetVersion = targetVersion,
                    status = TargetSecurityStatus.OK_OVERRIDDEN,
                    vulnerabilities =
                        vulnerableDependencies
                            .flatMap { it.vulnerabilities }
                            .distinctBy { it.id },
                    overriddenBy = overrides,
                )
        }
    }

    private fun findOverrides(
        vulnerable: VulnerableDependency,
        targetKey: String,
        targetState: TargetVersionsState,
        resolution: TargetResolution,
        vulnerabilityMap: Map<String, List<Vulnerability>>,
    ): List<OverrideReason> {
        val dependency = vulnerable.dependency
        val dependencyKey =
            coordinateWithoutVersion(
                dependency.group,
                dependency.name,
            )
        return targetState.dependencies
            .filter { (otherKey, _) ->
                otherKey != targetKey &&
                    otherKey == dependencyKey
            }.mapNotNull { (otherKey, otherTargetVersion) ->
                val vulnerabilities =
                    vulnerabilityMap[
                        coordinate(
                            dependency.group,
                            dependency.name,
                            otherTargetVersion,
                        ),
                    ].orEmpty()
                if (vulnerabilities.isNotEmpty()) {
                    return@mapNotNull null
                }
                val combinedVersion =
                    findResolvedVersion(
                        resolution.roots,
                        dependency.group,
                        dependency.name,
                    )
                if (combinedVersion != otherTargetVersion) {
                    return@mapNotNull null
                }
                OverrideReason(
                    dependency = otherKey,
                    targetVersion = otherTargetVersion,
                    resolvedVersion = combinedVersion,
                )
            }
    }

    private fun hasOverride(
        vulnerable: VulnerableDependency,
        overrides: List<OverrideReason>,
    ): Boolean {
        val key =
            coordinateWithoutVersion(
                vulnerable.dependency.group,
                vulnerable.dependency.name,
            )
        return overrides.any {
            it.dependency == key
        }
    }

    private fun findResolvedVersion(
        roots: List<ResolvedDependency>,
        group: String?,
        name: String,
    ): String? =
        flatten(roots)
            .firstOrNull {
                it.group == group &&
                    it.name == name
            }?.version

    private fun allDependencies(resolution: TargetResolution): List<ResolvedDependency> =
        resolution.individual.values
            .flatten()
            .let(::flatten)

    private fun flatten(roots: List<ResolvedDependency>): List<ResolvedDependency> {
        val result = mutableListOf<ResolvedDependency>()

        fun visit(node: ResolvedDependency) {
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

    private data class VulnerableDependency(
        val dependency: ResolvedDependency,
        val vulnerabilities: List<Vulnerability>,
    )
}
