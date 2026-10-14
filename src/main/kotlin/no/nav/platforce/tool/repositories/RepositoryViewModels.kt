package no.nav.platforce.tool.repositories

enum class RepoState {
    NOT_IN_GITHUB, // exists in Nais, but not installed
    NOT_SCANNED, // installed but no scan yet
    SCANNED, // has scan data
}

data class RepoView(
    val name: String,
    val team: String,
    val state: RepoState,
    val scan: RepositoryDependencyScan? = null,
)
