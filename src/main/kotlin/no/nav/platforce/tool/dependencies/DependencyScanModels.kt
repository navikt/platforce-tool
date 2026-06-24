package no.nav.platforce.tool.dependencies

data class RepositoryDependencyScan(
    val repository: String,
    val scannedAt: String,
    val findings: List<DependencyFinding>,
    val untrackedDependencies: List<UntrackedDependency> = emptyList(),
    val untrackedPlugins: List<UntrackedPlugin> = emptyList(),
)

data class DependencyFinding(
    val kind: DependencyKind,
    val key: String,
    val currentVersion: String,
    val targetVersion: String,
    val status: DependencyStatus,
)

enum class DependencyKind {
    PLUGIN,
    DEPENDENCY,
}

enum class DependencyStatus {
    OK,
    UPDATE,
    AHEAD,
}

data class UntrackedDependency(
    val key: String,
    val version: String,
)

data class UntrackedPlugin(
    val key: String,
    val version: String,
)
