package no.nav.platforce.tool.repositories

import com.google.gson.Gson
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.nais.NaisConsoleClient
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind

fun repositoryViewRoutes(
    naisClient: NaisConsoleClient,
    githubClient: GithubClient,
    dependencyScanViewService: DependencyScanViewService,
    repositoryViewService: RepositoryViewService,
) = listOf(
    "/internal/repos/view" bind Method.GET to { request ->
        val context = request.userContext()
        val nais = naisClient.getRepositoriesForUser(context.username)

        val naisRepos =
            nais.teams
                .flatMap { team ->
                    team.repositories.map { repo ->
                        repo to team.team
                    }
                }

        val installed = githubClient.listRepositories().toSet()
        val scanned: Map<String, RepositoryDependencyScan> =
            dependencyScanViewService
                .get(context.username, context.targetVersionsStore.get())
                .associateBy { it.repository }

        val result =
            repositoryViewService.buildRepoView(
                naisRepos,
                installed,
                scanned,
            )

        Response(OK)
            .header("Content-Type", "application/json")
            .body(Gson().toJson(result))
    },
)
