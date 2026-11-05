package no.nav.platforce.tool.targets

data class TargetVersionsState(
    val plugins: MutableMap<String, String>,
    val dependencies: MutableMap<String, String>,
    val gradleVersion: String,
    val transientDependencies: Set<String> = emptySet(),
    val platformBoms: MutableMap<String, String> = mutableMapOf(),
    val ignoredDependencies: Set<String> = emptySet(),
)

fun TargetVersionsState.snapshot(): TargetVersionsState =
    copy(
        plugins = plugins.toMutableMap(),
        dependencies = dependencies.toMutableMap(),
        transientDependencies = transientDependencies.toSet(),
        // Gson bypasses constructor defaults when reading older JSON payloads.
        platformBoms = (platformBoms as Map<String, String>?)?.toMutableMap() ?: mutableMapOf(),
        ignoredDependencies = (ignoredDependencies as Set<String>?)?.toSet() ?: emptySet(),
    )

fun TargetVersionsState.withoutIgnoredDependencies(): TargetVersionsState =
    copy(
        dependencies = dependencies.filterKeys { it !in ignoredDependencies }.toMutableMap(),
        transientDependencies = transientDependencies - ignoredDependencies,
    )

fun TargetVersionsState.preservingIgnoredDependencies(stored: TargetVersionsState): TargetVersionsState =
    copy(
        dependencies =
            (
                dependencies.filterKeys { it !in stored.ignoredDependencies } +
                    stored.dependencies.filterKeys { it in stored.ignoredDependencies }
            ).toMutableMap(),
        transientDependencies =
            (transientDependencies - stored.ignoredDependencies) +
                stored.transientDependencies.intersect(stored.ignoredDependencies),
        ignoredDependencies = stored.ignoredDependencies,
    )
