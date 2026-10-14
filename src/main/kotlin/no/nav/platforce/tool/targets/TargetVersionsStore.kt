package no.nav.platforce.tool.targets

import no.nav.platforce.tool.db.PostgresDatabase
import no.nav.platforce.tool.db.TargetVersion
import no.nav.platforce.tool.db.TargetVersionType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class TargetVersionsStore(
    private val userId: String = "default",
    private val team: String? = null,
) {
    fun get(): TargetVersionsState {
        val versions = PostgresDatabase.getForUser(userId)

        return TargetVersionsState(
            plugins =
                versions
                    .filter { it.type == TargetVersionType.PLUGIN }
                    .sortedBy { it.key }
                    .associate { it.key to it.version }
                    .toMutableMap(),
            dependencies =
                versions
                    .filter { it.type == TargetVersionType.DEPENDENCY }
                    .sortedBy { it.key }
                    .associate { it.key to it.version }
                    .toMutableMap(),
            gradleVersion =
                versions
                    .firstOrNull { it.type == TargetVersionType.GRADLE }
                    ?.version ?: "1.0.0-missing",
            transientDependencies =
                versions
                    .filter {
                        it.type == TargetVersionType.DEPENDENCY &&
                            it.transient
                    }.map { it.key }
                    .toSet(),
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
                    transient = false,
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
                        transient = it.key in state.transientDependencies,
                        createdAt = now,
                        updatedAt = now,
                    )
                } +
                TargetVersion(
                    userId = userId,
                    team = team,
                    type = TargetVersionType.GRADLE,
                    key = "gradle-wrapper",
                    version = state.gradleVersion,
                    transient = false,
                    createdAt = now,
                    updatedAt = now,
                )

        PostgresDatabase.replaceForUser(userId, team, versions)
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
                            transient = false,
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
                                transient = false,
                                createdAt = Instant.now(),
                                updatedAt = Instant.now(),
                            )
                        } +
                        TargetVersion(
                            userId = userId,
                            team = team,
                            type = TargetVersionType.GRADLE,
                            key = "gradle-wrapper",
                            version = TargetVersions.gradleVersion,
                            transient = false,
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                        )

                PostgresDatabase.replaceForUser(userId, team, seed)
            }
        }
    }
}
