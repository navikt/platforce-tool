package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.gradle.GradleDependencyDeclaration

data class RepositoryDependencyInventory(
    val repository: String,
    val scannedAt: String,
    val plugins: Map<String, String>,
    val dependencies: Map<String, String>,
    val gradleVersion: String?,
    val platformBoms: Map<String, String> = emptyMap(),
    val declarations: List<GradleDependencyDeclaration> = emptyList(),
)
