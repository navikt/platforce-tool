package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.security.PlatformBomSecurityResult

data class RepositoryDependencyScan(
    val repository: String,
    val scannedAt: String,
    val findings: List<DependencyFinding>,
    val untrackedDependencies: List<UntrackedDependency> = emptyList(),
    val untrackedPlugins: List<UntrackedPlugin> = emptyList(),
    val untrackedPlatformBoms: List<UntrackedPlatformBom> = emptyList(),
    val declaredDependencyKeys: List<String>? = null,
    val declaredPlatformBomKeys: List<String>? = null,
)

data class DependencyFinding(
    val kind: DependencyKind,
    val key: String,
    val currentVersion: String?,
    val targetVersion: String,
    val status: DependencyStatus,
    // Dependency is safe because another dependency/version overrides
    // the vulnerable transitive version OR added due to parent otherwise has vulnerability
    val relatedTo: List<DependencyReference> = emptyList(),
    val platformBomSecurity: PlatformBomSecurityResult? = null,
    val bomCoverage: List<DependencyReference> = emptyList(),
)

data class DependencyReference(
    val kind: DependencyKind,
    val key: String,
    val version: String,
)

enum class DependencyKind {
    PLUGIN,
    DEPENDENCY,
    PLATFORM_BOM,
    GRADLE,
}

enum class DependencyStatus {
    OK,
    UPDATE,
    AHEAD,
    VULNERABLE, // Enrichment from Security Scan
    OK_OVERRIDDEN, // Enrichment from Security Scan
    OK_WITH_ADD, // Enrichment from Security Scan
    OK_TRANSIENT, // Enrichment from Security Scan
    ADD, // Enrichment from Security Scan
    REMOVE, // Enrichment from Security Scan (on unused transient)
}

data class UntrackedDependency(
    val key: String,
    val version: String,
)

data class UntrackedPlugin(
    val key: String,
    val version: String,
)

data class UntrackedPlatformBom(
    val key: String,
    val version: String,
)
