package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.db.TargetVersion
import no.nav.platforce.tool.db.TargetVersionType
import no.nav.sf.keytool.db.PostgresDatabase
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

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

class TargetVersionsStore(
    private val userId: String = "default",
    private val team: String? = null,
) {
    private val state =
        TargetVersionsState(
            plugins = TargetVersions.plugins.toMutableMap(),
            dependencies = TargetVersions.dependencies.toMutableMap(),
        )

    fun get(): TargetVersionsState {
        val versions = PostgresDatabase.getForUser(userId)

        return TargetVersionsState(
            plugins =
                versions
                    .filter { it.type == TargetVersionType.PLUGIN }
                    .associate { it.key to it.version }
                    .toMutableMap(),
            dependencies =
                versions
                    .filter { it.type == TargetVersionType.DEPENDENCY }
                    .associate { it.key to it.version }
                    .toMutableMap(),
        )
    }

    fun update(state: TargetVersionsState) {
        val now = Instant.now()

        val versions =
            state.plugins.map {
                TargetVersion(
                    userId = userId,
                    team = team,
                    type = TargetVersionType.PLUGIN,
                    key = it.key,
                    version = it.value,
                    createdAt = now,
                    updatedAt = now,
                )
            } +
                state.dependencies.map {
                    TargetVersion(
                        userId = userId,
                        team = team,
                        type = TargetVersionType.DEPENDENCY,
                        key = it.key,
                        version = it.value,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

        PostgresDatabase.replaceForUser(userId, team, versions)
    }

    // old
    fun updatePlugins(map: Map<String, String>) {
        state.plugins.clear()
        state.plugins.putAll(map)
    }

    // old
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

    init {
        transaction(PostgresDatabase.database) {
            val existing = PostgresDatabase.getForUser(userId)

            if (existing.isEmpty()) {
                val seed =
                    TargetVersions.plugins.map { (k, v) ->
                        TargetVersion(
                            userId = userId,
                            team = team,
                            type = TargetVersionType.PLUGIN,
                            key = k,
                            version = v,
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                        )
                    } +
                        TargetVersions.dependencies.map { (k, v) ->
                            TargetVersion(
                                userId = userId,
                                team = team,
                                type = TargetVersionType.DEPENDENCY,
                                key = k,
                                version = v,
                                createdAt = Instant.now(),
                                updatedAt = Instant.now(),
                            )
                        }

                PostgresDatabase.replaceForUser(userId, team, seed)
            }
        }
    }
}
