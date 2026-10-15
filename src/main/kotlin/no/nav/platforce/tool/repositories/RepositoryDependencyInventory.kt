package no.nav.platforce.tool.repositories

data class RepositoryDependencyInventory(
    val repository: String,
    val scannedAt: String,
    val plugins: Map<String, String>,
    val dependencies: Map<String, String>,
    val gradleVersion: String?,
    val platformBoms: Map<String, String> = emptyMap(),
)
