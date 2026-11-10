package no.nav.platforce.tool.nais

import com.google.gson.Gson
import com.google.gson.JsonParseException
import mu.KotlinLogging
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun deploymentsRoutes(
    client: NaisConsoleClient,
    githubClient: GithubClient,
): List<org.http4k.routing.RoutingHttpHandler> {
    val visibleRepositories = ConcurrentHashMap<String, Set<String>>()
    return listOf(
        "/internal/api/deployments" bind Method.GET to { request ->
            val context = request.userContext()
            try {
                val deployments =
                    client.getRunningDeployments(context.username).availableRepositories(
                        githubClient.listRepositories().toSet(),
                        context.ignoredRepositoriesStore
                            .get()
                            .repositories
                            .toSet(),
                    )
                visibleRepositories[context.username] = deployments.applications.mapNotNull { app -> app.repository }.toSet()
                Response(Status.OK)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(deployments))
            } catch (failure: IOException) {
                deploymentsFailure(failure)
            } catch (failure: IllegalStateException) {
                deploymentsFailure(failure)
            } catch (failure: JsonParseException) {
                deploymentsFailure(failure)
            }
        },
        "/internal/api/deployments/workflow/{owner}/{repo}" bind Method.GET to { request ->
            val context = request.userContext()
            val repository = "${request.path("owner")}/${request.path("repo")}"
            if (repository !in visibleRepositories[context.username].orEmpty() ||
                context.ignoredRepositoriesStore
                    .get()
                    .repositories
                    .any { it.equals(repository, ignoreCase = true) }
            ) {
                Response(Status.FORBIDDEN)
                    .header("Content-Type", "application/json")
                    .body("""{"error":"Refresh deployments to load accessible repositories."}""")
            } else {
                val workflow = rebuildWorkflow(githubClient, repository)
                Response(Status.OK).header("Content-Type", "application/json").body(Gson().toJson(workflow))
            }
        },
        "/internal/api/deployments/rebuild-pr/{owner}/{repo}" bind Method.POST to { request ->
            val context = request.userContext()
            val owner = request.path("owner").orEmpty()
            val repo = request.path("repo").orEmpty()
            try {
                require("$owner/$repo" == normalizeDeploymentRepository("$owner/$repo")) { "Invalid repository" }
                val deployments =
                    client.getRunningDeployments(context.username).availableRepositories(
                        githubClient.listRepositories().toSet(),
                        context.ignoredRepositoriesStore
                            .get()
                            .repositories
                            .toSet(),
                    )
                if (deployments.applications.none { it.repository == "$owner/$repo" }) {
                    Response(Status.FORBIDDEN).body("Repository is not associated with your running applications.")
                } else {
                    val url = RebuildPullRequestService(githubClient).create(owner, repo)
                    Response(Status.OK).header("Content-Type", "application/json").body(Gson().toJson(mapOf("url" to url)))
                }
            } catch (failure: IllegalArgumentException) {
                Response(Status.CONFLICT)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(mapOf("error" to failure.message)))
            } catch (failure: YAMLException) {
                KotlinLogging.logger { }.warn(failure) { "Could not parse deployment workflow" }
                Response(Status.CONFLICT)
                    .header("Content-Type", "application/json")
                    .body("""{"error":"Could not parse the called workflow. Configure rebuild.yml manually."}""")
            } catch (failure: IOException) {
                rebuildPrFailure(failure)
            } catch (failure: IllegalStateException) {
                rebuildPrFailure(failure)
            } catch (failure: JsonParseException) {
                rebuildPrFailure(failure)
            }
        },
    )
}

private fun rebuildPrFailure(failure: Exception): Response {
    KotlinLogging.logger { }.warn(failure) { "Could not create rebuild workflow PR" }
    return Response(Status.BAD_GATEWAY)
        .header("Content-Type", "application/json")
        .body(
            """{"error":"Could not create rebuild PR. Check GitHub App contents, pull requests and workflows write permissions. A branch or PR may already have been created; check GitHub before retrying."}""",
        )
}

private fun deploymentsFailure(failure: Exception): Response {
    KotlinLogging.logger { }.warn(failure) { "Could not load running deployments from Nais Console" }
    return Response(Status.BAD_GATEWAY)
        .header("Content-Type", "application/json")
        .body(Gson().toJson(mapOf("error" to "Could not load running deployments from Nais Console")))
}
