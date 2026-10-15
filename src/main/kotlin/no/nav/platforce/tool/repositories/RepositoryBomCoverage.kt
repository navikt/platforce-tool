package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.security.PlatformBomCoverage
import no.nav.platforce.tool.security.TargetSecurityScan

class RepositoryBomCoverage {
    fun find(
        finding: DependencyFinding,
        inventory: RepositoryDependencyInventory?,
        security: TargetSecurityScan,
    ): Pair<DependencyReference, PlatformBomCoverage>? {
        if (inventory == null || finding.currentVersion != finding.targetVersion) return null
        val platforms = inventory.declarations.filter { it.platformBom }.distinct()
        val platform = platforms.singleOrNull() ?: return null
        val dependencies = inventory.declarations.filter { !it.platformBom && it.key == finding.key }
        if (dependencies.isEmpty() ||
            dependencies.any { it.version != finding.targetVersion || it.configuration != platform.configuration }
        ) {
            return null
        }
        val bom =
            security.platformBoms.singleOrNull {
                it.key == platform.key && it.targetVersion == platform.version &&
                    inventory.platformBoms[it.key] == it.targetVersion
            } ?: return null
        val coverage =
            bom.coverage.singleOrNull {
                it.dependency == finding.key && it.targetVersion == finding.targetVersion &&
                    it.enforcedPlatform == platform.enforcedPlatform
            } ?: return null
        return DependencyReference(DependencyKind.PLATFORM_BOM, bom.key, bom.targetVersion) to coverage
    }
}
