package no.nav.platforce.tool.node

import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyReference
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.RepositoryDependencyScan
import no.nav.platforce.tool.targets.TargetVersionsState

fun enrichNodeRepositorySecurity(
    scan: RepositoryDependencyScan,
    security: NodeSecurityScan,
    state: TargetVersionsState,
): RepositoryDependencyScan {
    val targets =
        security.targets
            .filter { nodeDependencyStatus(it.targetVersion, state.nodeDependencies[it.key].orEmpty()) == DependencyStatus.OK }
            .associateBy { it.key }
    val additions = mutableListOf<DependencyFinding>()

    fun reference(target: NodeSecurityResult) = DependencyReference(DependencyKind.NODE, target.key, target.targetVersion)

    fun isGlobalOverride(finding: DependencyFinding) = finding.declaration?.removeSuffix(" > .") == "overrides > ${finding.key}"

    fun present(target: NodeSecurityResult): Boolean =
        scan.findings.any { finding ->
            finding.key == target.key &&
                finding.currentVersion != null &&
                nodeDependencyStatus(finding.currentVersion, target.targetVersion) in
                setOf(DependencyStatus.OK, DependencyStatus.UPDATE) &&
                if (target.key in state.nodeTransientDependencies) {
                    isGlobalOverride(finding)
                } else {
                    finding.declaration == "dependencies"
                }
        }

    val findings =
        scan.findings.map { finding ->
            val target = targets[finding.key] ?: return@map finding
            val plannedCoverage =
                finding.status == DependencyStatus.UPDATE && target.relatedTo.isNotEmpty() &&
                    target.status in setOf(NodeSecurityStatus.OK_OVERRIDDEN, NodeSecurityStatus.OK_TRANSIENT)
            val redundantOverride =
                target.status == NodeSecurityStatus.TRANSIENT_UNUSED && isGlobalOverride(finding) &&
                    finding.status in setOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD)
            if (finding.status != DependencyStatus.OK && !plannedCoverage && !redundantOverride) return@map finding
            val override = finding.declaration?.startsWith("overrides") == true
            when (target.status) {
                NodeSecurityStatus.VULNERABLE -> {
                    return@map finding.copy(status = DependencyStatus.VULNERABLE)
                }

                NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED -> {
                    return@map finding.copy(status = DependencyStatus.UNRESOLVED)
                }

                NodeSecurityStatus.TRANSIENT_UNUSED -> {
                    return@map finding.copy(
                        status =
                            if (isGlobalOverride(finding) && finding.key in state.nodeTransientDependencies &&
                                target.replacementDependencies.all { replacement ->
                                    targets[replacement.dependency]?.let {
                                        it.targetVersion == replacement.targetVersion &&
                                            it.status in setOf(NodeSecurityStatus.OK, NodeSecurityStatus.OK_TRANSIENT) && present(it)
                                    } == true
                                }
                            ) {
                                DependencyStatus.REMOVE
                            } else if (override) {
                                DependencyStatus.OK_TRANSIENT
                            } else {
                                DependencyStatus.OK
                            },
                    )
                }

                else -> {
                    Unit
                }
            }

            val providerGroups =
                target.relatedTo.map { reason ->
                    val attributed = targets.values.filter { "${it.key}:${it.targetVersion}" in reason.causedBy }
                    attributed.ifEmpty {
                        listOfNotNull(targets[reason.dependency]?.takeIf { it.targetVersion == reason.targetVersion })
                    }
                }
            val providers = providerGroups.flatten().distinctBy { it.key }
            if ((target.status == NodeSecurityStatus.OK_OVERRIDDEN && providers.isEmpty()) ||
                providerGroups.any { it.isEmpty() } ||
                providers.any { it.status !in setOf(NodeSecurityStatus.OK, NodeSecurityStatus.OK_TRANSIENT) }
            ) {
                return@map finding.copy(status = DependencyStatus.UNRESOLVED)
            }
            val missing = providers.filterNot(::present)
            missing.forEach { provider ->
                additions +=
                    DependencyFinding(
                        kind = DependencyKind.NODE,
                        key = provider.key,
                        currentVersion = null,
                        targetVersion = provider.targetVersion,
                        status = DependencyStatus.ADD,
                        declaration =
                            if (provider.key in state.nodeTransientDependencies) "overrides > ${provider.key}" else "dependencies",
                        relatedTo = listOf(reference(target)),
                    )
            }
            finding.copy(
                status =
                    when {
                        finding.status == DependencyStatus.UPDATE -> DependencyStatus.UPDATE
                        missing.isNotEmpty() -> DependencyStatus.OK_WITH_ADD
                        override -> DependencyStatus.OK_TRANSIENT
                        providers.isNotEmpty() -> DependencyStatus.OK_OVERRIDDEN
                        else -> DependencyStatus.OK
                    },
                relatedTo =
                    if (missing.isNotEmpty() || !override) {
                        providers.map(::reference)
                    } else {
                        target.transientUsage.map { DependencyReference(DependencyKind.NODE, it.dependency, it.targetVersion) }
                    },
            )
        }
    return scan.copy(
        findings =
            findings +
                additions.groupBy { it.key to it.targetVersion }.values.map { group ->
                    group.first().copy(relatedTo = group.flatMap { it.relatedTo }.distinct())
                },
    )
}
