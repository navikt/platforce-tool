package no.nav.platforce.tool.nais

import no.nav.platforce.tool.github.GithubClient
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import java.io.StringReader

class RebuildPullRequestService(
    private val github: GithubClient,
) {
    fun create(
        owner: String,
        repo: String,
    ): String {
        require(!github.fileExists(owner, repo, ".github/workflows/rebuild.yml")) { "rebuild.yml already exists. Refresh deployments." }
        val base = github.getDefaultBranch(owner, repo)
        require(base.matches(Regex("[A-Za-z0-9_./-]+"))) { "Unsupported default branch name" }
        val mono = !github.fileExists(owner, repo, ".github/workflows/deploy.yml")
        val calledPath = ".github/workflows/${if (mono) "workflow" else "deploy"}.yml"
        require(github.fileExists(owner, repo, calledPath)) { "$calledPath is missing; cannot generate a compatible rebuild workflow." }
        val original = github.getFile(owner, repo, calledPath)
        val plan = rebuildWorkflowPlan(original, mono, base)
        val branch = github.resolveBranchName(owner, repo, "chore/add-rebuild-workflow")
        github.createBranch(owner, repo, branch, github.getBranchHeadSha(owner, repo, base))
        if (plan.called != original) {
            github.updateFile(
                owner,
                repo,
                calledPath,
                plan.called,
                github.getFileSha(owner, repo, calledPath, branch),
                branch,
                "chore: support reusable rebuild workflow",
            )
        }
        github.updateFile(owner, repo, ".github/workflows/rebuild.yml", plan.wrapper, "", branch, "chore: add rebuild workflow")
        return github.createPullRequest(
            owner,
            repo,
            "chore: add manual rebuild workflow",
            "Adds a manual rebuild entry point using the existing build and deploy workflow.\n\n" +
                "Review deployment inputs and production behavior before merging. " +
                "Rebuilding does not guarantee vulnerability remediation.\n\n" +
                if (mono) {
                    "Kafka defaults: FLAG_SEEK=false, SEEK_OFFSET=0, NUMBER_OF_SAMPLES=0, FLAG_NO_POST=false, FLAG_ALT_ID=false. " +
                        "Selected targets are rebuilt separately; imageUrl artifact names are made target-specific."
                } else {
                    "The called workflow is enabled for workflow_call while preserving existing triggers."
                },
            branch,
            base,
        )
    }
}

internal data class RebuildWorkflowPlan(
    val called: String,
    val wrapper: String,
)

internal fun rebuildWorkflowPlan(
    source: String,
    mono: Boolean,
    branch: String,
): RebuildWorkflowPlan {
    val root = Yaml().compose(StringReader(source)) as? MappingNode ?: error("Workflow must be a YAML mapping")
    val triggers =
        root.field("on") as? MappingNode
            ?: throw IllegalArgumentException("Only block-style on: workflow triggers are supported for generation.")
    val call = triggers.field("workflow_call")
    val inputs = (call as? MappingNode)?.field("inputs") as? MappingNode
    val defaults =
        mapOf(
            "FLAG_SEEK" to ("boolean" to "false"),
            "SEEK_OFFSET" to ("string" to "'0'"),
            "NUMBER_OF_SAMPLES" to ("string" to "'0'"),
            "FLAG_NO_POST" to ("boolean" to "false"),
            "FLAG_ALT_ID" to ("boolean" to "false"),
        )
    val extra = mutableListOf<String>()
    inputs?.value?.forEach { tuple ->
        val name = (tuple.keyNode as ScalarNode).value
        val config = tuple.valueNode as? MappingNode ?: error("Invalid workflow input $name")
        val type = (config.field("type") as? ScalarNode)?.value
        if (mono && name in setOf("DEPLOY_APP", "DEPLOY_CLUSTER")) {
            require(type == "string") { "$name must be a string input" }
        } else if (mono && name in defaults) {
            val (expectedType, value) = defaults.getValue(name)
            require(type == expectedType) { "Unsupported type for $name" }
            extra += "      $name: $value"
        } else {
            require((config.field("required") as? ScalarNode)?.value != "true" || config.field("default") != null) {
                "Unknown required workflow input: $name. Configure a rebuild workflow manually."
            }
        }
    }
    if (mono) {
        require(inputs?.field("DEPLOY_APP") != null && inputs.field("DEPLOY_CLUSTER") != null) {
            "Monorepo workflow.yml must declare DEPLOY_APP and DEPLOY_CLUSTER in workflow_call.inputs."
        }
    }
    var called = source
    if (call == null) {
        val lines = source.lines().toMutableList()
        val on =
            root.value
                .single { (it.keyNode as? ScalarNode)?.value == "on" }
                .keyNode.startMark
                .line
        require(lines[on].matches(Regex("""['"]?on['"]?:\s*(#.*)?"""))) { "Only block-style on: triggers are supported." }
        lines.add(on + 1, "  workflow_call:")
        called = lines.joinToString("\n")
    }
    if (mono) {
        called =
            Regex("""(?m)^([ \t]*name:[ \t]*)imageUrl[ \t]*$""").replace(called) { match ->
                "${match.groupValues[1]}\${{ github.workflow == 'Rebuild and deploy selected applications' && " +
                    "format('imageUrl-{0}-{1}', inputs.DEPLOY_APP, inputs.DEPLOY_CLUSTER) || 'imageUrl' }}"
            }
    }
    val template =
        checkNotNull(RebuildPullRequestService::class.java.getResource("/workflows/rebuild-${if (mono) "monorepo" else "normal"}.yml"))
    val wrapper = template.readText().replace("__DEFAULT_BRANCH__", branch).replace("__EXTRA_INPUTS__", extra.joinToString("\n"))
    return RebuildWorkflowPlan(called, wrapper)
}

private fun MappingNode.field(key: String): Node? = value.singleOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode
