package no.nav.platforce.tool.dependencies

import java.time.Instant

data class RepositoryDependencyScan(
    val repository: String,
    val scannedAt: String,
    val findings: List<DependencyFinding>,
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
