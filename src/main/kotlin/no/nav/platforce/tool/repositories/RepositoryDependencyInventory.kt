package no.nav.platforce.tool.repositories

import no.nav.platforce.tool.gradle.GradleDependencyDeclaration
import no.nav.platforce.tool.node.NodeDependencyDeclaration

enum class RepositoryProjectType {
    GRADLE,
    NODE,
}

data class RepositoryDependencyInventory(
    val repository: String,
    val scannedAt: String,
    val plugins: Map<String, String>,
    val dependencies: Map<String, String>,
    val gradleVersion: String?,
    val platformBoms: Map<String, String> = emptyMap(),
    val declarations: List<GradleDependencyDeclaration> = emptyList(),
    val projectType: RepositoryProjectType = RepositoryProjectType.GRADLE,
    val nodeDependencies: List<NodeDependencyDeclaration> = emptyList(),
)

fun RepositoryDependencyInventory.withoutIgnoredDependencies(ignored: Set<String>): RepositoryDependencyInventory =
    copy(
        dependencies = dependencies.filterKeys { it !in ignored },
        declarations = declarations.filter { it.platformBom || it.key !in ignored },
    )
