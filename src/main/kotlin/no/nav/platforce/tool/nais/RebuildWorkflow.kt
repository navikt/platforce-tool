package no.nav.platforce.tool.nais

import com.google.gson.JsonParseException
import mu.KotlinLogging
import no.nav.platforce.tool.github.GithubClient
import java.io.IOException

data class RebuildWorkflow(
    val monorepo: Boolean? = null,
    val branch: String? = null,
    val error: String? = null,
)

internal fun RunningDeployments.withRebuildWorkflows(client: GithubClient): RunningDeployments =
    copy(
        rebuildWorkflows =
            applications.mapNotNull { it.repository }.distinct().associateWith { repository ->
                val (owner, repo) = repository.split("/")
                try {
                    val branch = client.getDefaultBranch(owner, repo)
                    RebuildWorkflow(monorepo = !client.fileExists(owner, repo, ".github/workflows/deploy.yml"), branch = branch)
                } catch (failure: IOException) {
                    rebuildWorkflowFailure(repository, failure)
                } catch (failure: IllegalStateException) {
                    rebuildWorkflowFailure(repository, failure)
                } catch (failure: JsonParseException) {
                    rebuildWorkflowFailure(repository, failure)
                }
            },
    )

private fun rebuildWorkflowFailure(
    repository: String,
    failure: Exception,
): RebuildWorkflow {
    KotlinLogging.logger { }.warn(failure) { "Could not check deployment workflow for $repository" }
    return RebuildWorkflow(error = "Could not check deploy.yml. Check GitHub App access and refresh deployments.")
}
