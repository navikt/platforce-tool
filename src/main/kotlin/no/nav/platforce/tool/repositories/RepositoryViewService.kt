package no.nav.platforce.tool.repositories

class RepositoryViewService {
    fun buildRepoView(
        naisRepos: List<Pair<String, String>>,
        installed: Set<String>,
        scanned: Map<String, RepositoryDependencyScan>,
    ): List<RepoView> =
        naisRepos
            .map { (repo, team) ->

                val scan = scanned[repo]

                val state =
                    when {
                        repo !in installed -> RepoState.NOT_IN_GITHUB
                        scan == null -> RepoState.NOT_SCANNED
                        else -> RepoState.SCANNED
                    }

                RepoView(
                    name = repo,
                    team = team,
                    state = state,
                    scan = scan,
                )
            }.sortedWith(compareBy({ it.team }, { it.name }))
}
