package no.nav.platforce.tool.targets

data class TargetVersionsState(
    val plugins: MutableMap<String, String>,
    val dependencies: MutableMap<String, String>,
    val gradleVersion: String,
    val transientDependencies: Set<String> = emptySet(),
)

fun TargetVersionsState.snapshot(): TargetVersionsState =
    copy(
        plugins = plugins.toMutableMap(),
        dependencies = dependencies.toMutableMap(),
        transientDependencies = transientDependencies.toSet(),
    )
