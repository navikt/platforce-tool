package no.nav.platforce.tool.ignore

import no.nav.sf.keytool.db.PostgresDatabase

data class IgnoredRepositoriesState(
    val repositories: MutableList<String>,
)

class IgnoredRepositoriesStore(
    private val userId: String = "default",
) {
    fun get(): IgnoredRepositoriesState =
        IgnoredRepositoriesState(
            repositories =
                PostgresDatabase
                    .getIgnoredRepositories(userId)
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
