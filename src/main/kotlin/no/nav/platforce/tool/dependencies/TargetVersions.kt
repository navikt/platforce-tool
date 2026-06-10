package no.nav.platforce.tool.dependencies

object TargetVersions {
    val plugins =
        mapOf(
            "org.jetbrains.kotlin.jvm" to "2.3.20",
            "org.jmailen.kotlinter" to "5.2.0",
            "com.gradleup.shadow" to "8.3.1",
        )

    val dependencies =
        mapOf(
            "no.nav.security:token-validation-core" to "5.0.29",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.9.0",
            "io.lettuce:lettuce-core" to "7.0.0.RELEASE",
            "com.google.code.gson:gson" to "2.13.2",
            "org.json:json" to "20250517",
            "org.yaml:snakeyaml" to "2.0",
            "org.http4k:http4k-core" to "6.39.0.0",
            "org.http4k:http4k-server-netty" to "6.39.0.0",
            "org.http4k:http4k-client-okhttp" to "6.39.0.0",
        )
}

data class TargetVersionsState(
    val plugins: MutableMap<String, String>,
    val dependencies: MutableMap<String, String>,
)

class TargetVersionsStore {
    private val state =
        TargetVersionsState(
            plugins = TargetVersions.plugins.toMutableMap(),
            dependencies = TargetVersions.dependencies.toMutableMap(),
        )

    fun get(): TargetVersionsState = state

    fun updatePlugins(map: Map<String, String>) {
        state.plugins.clear()
        state.plugins.putAll(map)
    }

    fun updateDependencies(map: Map<String, String>) {
        state.dependencies.clear()
        state.dependencies.putAll(map)
    }

    fun addPlugin(
        key: String,
        version: String,
    ) {
        state.plugins[key] = version
    }

    fun addDependency(
        key: String,
        version: String,
    ) {
        state.dependencies[key] = version
    }

    fun removePlugin(key: String) {
        state.plugins.remove(key)
    }

    fun removeDependency(key: String) {
        state.dependencies.remove(key)
    }
}
