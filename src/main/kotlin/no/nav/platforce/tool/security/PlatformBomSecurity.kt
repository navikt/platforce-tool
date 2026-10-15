package no.nav.platforce.tool.security

import no.nav.platforce.tool.resolution.PlatformBomResolution
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution

class PlatformBomSecurity {
    fun evaluate(
        key: String,
        bom: PlatformBomResolution,
        resolution: TargetResolution,
        vulnerabilities: Map<String, List<Vulnerability>>,
    ): PlatformBomSecurityResult {
        val vulnerableManaged =
            bom.managed.mapNotNull { dependency ->
                vulnerabilities[coordinate(dependency)].orEmpty().takeIf { it.isNotEmpty() }?.let {
                    VulnerableDependency(dependency, it)
                }
            }
        val coverage =
            bom.coverage.map { candidate ->
                val standalone = flatten(resolution.individual[candidate.dependency].orEmpty())
                val selected = flatten(candidate.roots).groupBy { it.key }
                val originalVulnerableKeys =
                    standalone.filter { vulnerabilities[coordinate(it)].orEmpty().isNotEmpty() }.map { it.key }.toSet()
                val introducesVulnerability =
                    selected.values.flatten().any {
                        it.key !in originalVulnerableKeys && vulnerabilities[coordinate(it)].orEmpty().isNotEmpty()
                    }
                PlatformBomCoverage(
                    dependency = candidate.dependency,
                    targetVersion = candidate.targetVersion,
                    enforcedPlatform = candidate.enforcedPlatform,
                    overrides =
                        if (introducesVulnerability) {
                            emptyList()
                        } else {
                            standalone
                                .filter { vulnerabilities[coordinate(it)].orEmpty().isNotEmpty() }
                                .mapNotNull { vulnerable ->
                                    val replacements = selected[vulnerable.key].orEmpty()
                                    val safe =
                                        replacements
                                            .takeIf {
                                                it.isNotEmpty() &&
                                                    it.all { replacement ->
                                                        replacement.version != vulnerable.version &&
                                                            vulnerabilities[coordinate(replacement)].orEmpty().isEmpty()
                                                    }
                                            }?.firstOrNull() ?: return@mapNotNull null
                                    OverrideReason(
                                        dependency = vulnerable.key,
                                        targetVersion = safe.version,
                                        resolvedVersion = safe.version,
                                        vulnerableVersion = vulnerable.version,
                                        causedBy = listOf("$key:${bom.version}"),
                                    )
                                }.distinct()
                        },
                )
            }
        return PlatformBomSecurityResult(
            key = key,
            targetVersion = bom.version,
            status = if (vulnerableManaged.isEmpty()) TargetSecurityStatus.OK else TargetSecurityStatus.VULNERABLE,
            vulnerableDependencies = vulnerableManaged,
            managedDependencyCount = bom.managed.size,
            coverage = coverage,
        )
    }

    private fun coordinate(dependency: ResolvedDependency): String = "${dependency.key}:${dependency.version}"

    private fun flatten(roots: List<ResolvedDependency>): List<ResolvedDependency> = roots.flatMap { listOf(it) + flatten(it.dependencies) }
}
