package no.nav.platforce.tool.security

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.targets.TargetVersionsState
import org.apache.maven.artifact.versioning.ComparableVersion
import kotlin.collections.isNotEmpty

class TargetSecurityService(
    private val vulnerabilityService: VulnerabilityService,
) {
    fun scan(
        resolution: TargetResolution,
        targetState: TargetVersionsState,
    ): TargetSecurityScan {
        val allDependencies =
            allDependencies(resolution)
        require(targetState.platformBoms.all { (key, version) -> resolution.platformBoms[key]?.version == version }) {
            "BOM resolution evidence is missing or does not match saved target versions"
        }
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
        val enrichedTargets =
            applyTransientStatus(
                targets = targets,
                targetState = targetState,
            )

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
            targets = enrichedTargets,
            platformBoms =
                targetState.platformBoms.toSortedMap().map { (key, _) ->
                    PlatformBomSecurity().evaluate(key, resolution.platformBoms.getValue(key), resolution, vulnerabilityMap)
                },
        )
    }

    private fun applyTransientStatus(
        targets: List<TargetSecurityResult>,
        targetState: TargetVersionsState,
    ): List<TargetSecurityResult> {
        val safeProviders = targets.filter { it.status == TargetSecurityStatus.OK }.associateBy { it.key }

        fun provides(
            provider: TargetSecurityResult,
            reason: OverrideReason,
        ): Boolean = "${provider.key}:${provider.targetVersion}" in reason.causedBy

        val requirements = targets.flatMap { consumer -> consumer.relatedTo.map { consumer to it } }
        val transientUsage =
            targetState.transientDependencies
                .associateWith { transientKey ->
                    val provider = safeProviders[transientKey]
                    requirements
                        .filter { (consumer, reason) ->
                            consumer.key != transientKey && provider != null && provides(provider, reason)
                        }.map { it.first }
                        .distinctBy { it.key }
                }
        val retained = safeProviders.toMutableMap()
        val replacements = mutableMapOf<String, List<TransientDependencyUsage>>()
        val transientCandidates =
            safeProviders.values
                .filter { it.key in targetState.transientDependencies }
                .sortedWith(
                    compareBy<TargetSecurityResult> { provider -> requirements.count { provides(provider, it.second) } }
                        .thenBy { it.key },
                )
        transientCandidates.forEach { candidate ->
            val covered = requirements.filter { provides(candidate, it.second) }
            val alternatives =
                covered.map { (_, reason) ->
                    retained.values.firstOrNull { it.key != candidate.key && provides(it, reason) }
                }
            if (alternatives.all { it != null }) {
                retained.remove(candidate.key)
                replacements[candidate.key] =
                    alternatives.filterNotNull().map { TransientDependencyUsage(it.key, it.targetVersion) }.distinct()
            }
        }

        fun retainedReplacements(key: String): List<TransientDependencyUsage> =
            replacements[key]
                .orEmpty()
                .flatMap { replacement ->
                    if (replacement.dependency in replacements) retainedReplacements(replacement.dependency) else listOf(replacement)
                }.distinct()

        return targets.map { target ->
            if (target.key !in targetState.transientDependencies) {
                return@map target
            }

            val protectedTargets =
                transientUsage[target.key].orEmpty()

            // Detecting vulnerability does preceed other judgement
            if (target.status == TargetSecurityStatus.VULNERABLE) {
                target.copy(
                    transientUsage =
                        protectedTargets.map {
                            TransientDependencyUsage(
                                dependency = it.key,
                                targetVersion = it.targetVersion,
                            )
                        },
                )
            } else if (target.key in replacements) {
                target.copy(
                    status = TargetSecurityStatus.TRANSIENT_UNUSED,
                    transientUsage = protectedTargets.map { TransientDependencyUsage(it.key, it.targetVersion) },
                    replacementDependencies = retainedReplacements(target.key),
                )
            } else if (protectedTargets.isNotEmpty() || target.status == TargetSecurityStatus.OK_OVERRIDDEN) {
                target.copy(
                    status = TargetSecurityStatus.OK_TRANSIENT,
                    transientUsage =
                        protectedTargets.map {
                            TransientDependencyUsage(
                                dependency = it.key,
                                targetVersion = it.targetVersion,
                            )
                        },
                )
            } else {
                target.copy(
                    status = TargetSecurityStatus.TRANSIENT_UNUSED,
                )
            }
        }
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
                .distinctBy {
                    coordinate(
                        it.group,
                        it.name,
                        it.version,
                    )
                }.mapNotNull { dependency ->
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
                        val suggestedVersion =
                            vulnerabilities
                                .flatMap { it.fixedVersions }
                                .filter {
                                    ComparableVersion(it)
                                        .compareTo(
                                            ComparableVersion(dependency.version),
                                        ) > 0
                                }.distinct()
                                .sortedWith(compareBy { ComparableVersion(it) })
                                .firstOrNull { candidate ->
                                    vulnerabilities.none { vulnerability ->
                                        isAffectedByVulnerability(
                                            version = candidate,
                                            vulnerability = vulnerability,
                                        )
                                    }
                                }?.let { version ->
                                    VersionSuggestion(
                                        dependency =
                                            coordinateWithoutVersion(
                                                dependency.group,
                                                dependency.name,
                                            ),
                                        version = version,
                                    )
                                }
                        VulnerableDependency(
                            dependency = dependency,
                            vulnerabilities = vulnerabilities,
                            suggestedVersion = suggestedVersion,
                        )
                    }
                }

        if (vulnerableDependencies.isEmpty()) {
            return TargetSecurityResult(
                key = targetKey,
                targetVersion = targetVersion,
                status = TargetSecurityStatus.OK,
                vulnerabilities = emptyList(),
                vulnerableDependencies = emptyList(),
                relatedTo = emptyList(),
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
                            val causedBy =
                                targetState.dependencies
                                    .filter { (otherTargetKey, _) ->
                                        otherTargetKey != targetKey
                                    }.filter { (otherTargetKey, _) ->
                                        flatten(
                                            resolution.individual[otherTargetKey]
                                                ?: emptyList(),
                                        ).any { otherDependency ->
                                            coordinateWithoutVersion(
                                                otherDependency.group,
                                                otherDependency.name,
                                            ) == dependencyKey &&
                                                otherDependency.version ==
                                                resolvedDependency.version
                                        }
                                    }.map { (otherTargetKey, otherTargetVersion) ->
                                        "$otherTargetKey:$otherTargetVersion"
                                    }

                            val targetDependencyVersion =
                                targetState.dependencies[dependencyKey]
                                    ?: resolvedDependency.version

                            listOf(
                                OverrideReason(
                                    dependency = dependencyKey,
                                    targetVersion = targetDependencyVersion,
                                    resolvedVersion =
                                        resolvedDependency.version,
                                    vulnerableVersion =
                                        dependency.version,
                                    causedBy = causedBy,
                                    suggestedVersion =
                                        if (
                                            ComparableVersion(resolvedDependency.version)
                                                .compareTo(ComparableVersion(targetDependencyVersion)) > 0
                                        ) {
                                            resolvedDependency.version
                                        } else {
                                            null
                                        },
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    }
                }.distinctBy {
                    "${it.dependency}:${it.targetVersion}:${it.resolvedVersion}:${it.vulnerableVersion}:${it.causedBy}"
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
                        vulnerableDependencies = unresolvedVulnerabilities,
                        relatedTo = overrides,
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
                        vulnerableDependencies = vulnerableDependencies,
                        relatedTo = overrides,
                    )
            }
        return result
    }

    private fun isAffectedByVulnerability(
        version: String,
        vulnerability: Vulnerability,
    ): Boolean {
        val candidate = ComparableVersion(version)

        return vulnerability.affectedRanges.any { range ->
            var affected = false

            range.events.forEach { event ->
                when {
                    event.introduced != null -> {
                        val introduced = event.introduced

                        affected =
                            introduced == "0" ||
                            candidate.compareTo(
                                ComparableVersion(introduced),
                            ) >= 0
                    }

                    event.fixed != null -> {
                        if (affected) {
                            affected =
                                candidate.compareTo(
                                    ComparableVersion(event.fixed),
                                ) < 0
                        }
                    }

                    event.lastAffected != null -> {
                        if (affected) {
                            affected =
                                candidate.compareTo(
                                    ComparableVersion(event.lastAffected),
                                ) <= 0
                        }
                    }
                }
            }

            affected
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

    private fun allDependencies(resolution: TargetResolution): List<ResolvedDependency> =
        (
            resolution.individual.values.flatten() + resolution.roots +
                resolution.platformBoms.values.flatMap { bom -> bom.managed + bom.coverage.flatMap { it.roots } }
        ).let(::flatten)

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
}
