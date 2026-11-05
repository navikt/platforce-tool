package no.nav.platforce.tool.ignore

import no.nav.platforce.tool.db.PostgresDatabase

data class IgnoredRepositoriesState(
    val repositories: MutableList<String>,
    val dependencies: Set<String> = emptySet(),
)

class IgnoredRepositoriesStore(
    private val userId: String = "default",
) {
    fun get(): IgnoredRepositoriesState =
        IgnoredRepositoriesState(
            dependencies = PostgresDatabase.getIgnoredDependencies(userId),
            repositories =
                PostgresDatabase
                    .getIgnoredRepositories(userId)
                    .sorted()
                    .toMutableList(),
        )

    fun replace(
        team: String?,
        repositories: List<String>,
    ) {
        PostgresDatabase.replaceIgnoredRepositories(
            userId,
            team,
            repositories,
        )
    }
}
