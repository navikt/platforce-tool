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
        val standaloneTree =
            resolution.individual[targetKey]
                ?: emptyList()

        val standaloneDependencies =
            flatten(standaloneTree)

        /*
         * The individual tree tells us what this target originally requested.
         *
         * The combined tree tells us what Gradle actually resolved for the
         * application.
         */
        val combinedDependencies =
            flatten(resolution.roots)

        val combinedByDependency =
            combinedDependencies
                .filter { it.group != null }
                .groupBy {
                    coordinateWithoutVersion(
                        it.group,
                        it.name,
                    )
                }

        /*
         * Find vulnerabilities in the versions requested by this target.
         *
         * These are NOT automatically vulnerabilities in the final application.
         * We still have to check what Gradle resolved in the combined graph.
         */
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
            log.info {
                "SECURITY RESULT target=$targetKey:$targetVersion status=OK vulnerableDependencies=0"
            }
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
                    val dependency =
                        vulnerable.dependency

                    val dependencyKey =
                        coordinateWithoutVersion(
                            dependency.group,
                            dependency.name,
                        )

                    val resolvedDependency =
                        combinedByDependency[dependencyKey]
                            ?.firstOrNull()

                    if (resolvedDependency == null) {
                        emptyList()
                    } else {
                        val resolvedVulnerabilities =
                            vulnerabilityMap[
                                coordinate(
                                    resolvedDependency.group,
                                    resolvedDependency.name,
                                    resolvedDependency.version,
                                ),
                            ].orEmpty()

                        if (
                            dependency.version != resolvedDependency.version &&
                            resolvedVulnerabilities.isEmpty()
                        ) {
                            listOf(
                                OverrideReason(
                                    dependency = dependencyKey,
                                    targetVersion =
                                        targetState.dependencies[
                                            dependencyKey,
                                        ] ?: resolvedDependency.version,
                                    resolvedVersion =
                                        resolvedDependency.version,
                                    vulnerableVersion =
                                        dependency.version,
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }
                }.distinctBy {
                    "${it.dependency}:${it.targetVersion}:${it.resolvedVersion}:${it.vulnerableVersion}"
                }

        val unresolvedVulnerabilities =
            vulnerableDependencies.filter { vulnerable ->
                !hasOverride(
                    vulnerable = vulnerable,
                    overrides = overrides,
                )
            }

        val result =
            when {
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
        log.info {
            """
            SECURITY RESULT
              target=$targetKey:$targetVersion
              status=${result.status}
              vulnerableDependencies=${
                vulnerableDependencies.joinToString {
                    "${it.dependency.group}:${it.dependency.name}:${it.dependency.version}" +
                        " vulnerabilities=${it.vulnerabilities.map { vulnerability -> vulnerability.id }}"
                }
            }
              overrides=${
                overrides.joinToString {
                    "${it.dependency} " +
                        "targetVersion=${it.targetVersion} " +
                        "resolvedVersion=${it.resolvedVersion} " +
                        "vulnerableVersion=${it.vulnerableVersion}"
                }
            }
              unresolved=${
                unresolvedVulnerabilities.joinToString {
                    "${it.dependency.group}:${it.dependency.name}:${it.dependency.version}"
                }
            }
            """.trimIndent()
        }
        return result
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
