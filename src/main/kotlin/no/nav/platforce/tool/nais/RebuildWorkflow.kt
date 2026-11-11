package no.nav.platforce.tool.nais

import com.google.gson.JsonParseException
import mu.KotlinLogging
import no.nav.platforce.tool.github.GithubClient
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException

data class RebuildWorkflow(
    val monorepo: Boolean? = null,
    val branch: String? = null,
    val error: String? = null,
    val exists: Boolean? = null,
    val namespace: Boolean = false,
    val fixedApp: Boolean = false,
)

internal fun RunningDeployments.availableRepositories(
    accessible: Set<String>,
    ignored: Set<String>,
): RunningDeployments {
    val allowed = accessible.map { it.lowercase() }.toSet() - ignored.map { it.lowercase() }.toSet()
    val filtered = applications.filter { it.repository?.lowercase() in allowed }
    val repositories = filtered.mapNotNull { it.repository }.toSet()
    return copy(applications = filtered, rebuildWorkflows = rebuildWorkflows.filterKeys { it in repositories })
}

internal fun RunningDeployments.withRebuildWorkflows(client: GithubClient): RunningDeployments =
    copy(
        rebuildWorkflows =
            applications.mapNotNull { it.repository }.distinct().associateWith { repository ->
                rebuildWorkflow(client, repository)
            },
    )

internal fun rebuildWorkflow(
    client: GithubClient,
    repository: String,
): RebuildWorkflow {
    val (owner, repo) = repository.split("/")
    return try {
        val branch = client.getDefaultBranch(owner, repo)
        val files = client.listWorkflowFiles(owner, repo)
        val mono = "deploy.yml" !in files
        val source = if (mono && "workflow.yml" in files) client.getFile(owner, repo, ".github/workflows/workflow.yml") else null
        val namespace = source != null && rebuildWorkflowUsesNamespace(source)
        val fixedApp = namespace && rebuildWorkflowUsesFixedApplication(checkNotNull(source))
        RebuildWorkflow(monorepo = mono, branch = branch, exists = "rebuild.yml" in files, namespace = namespace, fixedApp = fixedApp)
    } catch (failure: IllegalArgumentException) {
        rebuildWorkflowFailure(repository, failure)
    } catch (failure: YAMLException) {
        rebuildWorkflowFailure(repository, failure)
    } catch (failure: IOException) {
        rebuildWorkflowFailure(repository, failure)
    } catch (failure: IllegalStateException) {
        rebuildWorkflowFailure(repository, failure)
    } catch (failure: JsonParseException) {
        rebuildWorkflowFailure(repository, failure)
    }
}

private fun rebuildWorkflowFailure(
    repository: String,
    failure: Exception,
): RebuildWorkflow {
    KotlinLogging.logger { }.warn(failure) { "Could not check deployment workflow for $repository" }
    return RebuildWorkflow(error = "Could not check deploy.yml. Check GitHub App access and refresh deployments.")
}
