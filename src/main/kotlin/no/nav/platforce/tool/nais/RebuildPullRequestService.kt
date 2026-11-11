package no.nav.platforce.tool.nais

import no.nav.platforce.tool.github.GithubClient
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import java.io.StringReader
import java.time.LocalDate

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
        val namespace = mono && rebuildWorkflowUsesNamespace(original)
        val fixedApp = namespace && rebuildWorkflowUsesFixedApplication(original)
        val plan = rebuildWorkflowPlan(original, mono, base)
        val date = LocalDate.now()
        val branch = github.resolveBranchName(owner, repo, "chore/add-rebuild-workflow-$date")
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
            "chore: add manual rebuild workflow $date",
            "Adds a manual rebuild entry point using the existing build and deploy workflow.\n\n" +
                "Review deployment inputs and production behavior before merging. " +
                "Rebuilding does not guarantee vulnerability remediation.\n\n" +
                if (fixedApp) {
                    "Selected targets include the cluster and namespace; the existing workflow selects the application."
                } else if (namespace) {
                    "Selected targets include the application, cluster, and namespace and are rebuilt separately."
                } else if (mono) {
                    "Kafka defaults: FLAG_SEEK=false, SEEK_OFFSET=0, NUMBER_OF_SAMPLES=0, FLAG_NO_POST=false, FLAG_ALT_ID=false. " +
                        "Selected targets are rebuilt separately."
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
        if (mono && name in setOf("DEPLOY_APP", "DEPLOY_CLUSTER", "DEPLOY_NAMESPACE")) {
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
        require(
            inputs?.field("DEPLOY_CLUSTER") != null &&
                (inputs.field("DEPLOY_APP") != null || inputs.field("DEPLOY_NAMESPACE") != null),
        ) {
            "Monorepo workflow.yml must declare DEPLOY_CLUSTER and either DEPLOY_APP or DEPLOY_NAMESPACE in workflow_call.inputs."
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
    val template =
        checkNotNull(RebuildPullRequestService::class.java.getResource("/workflows/rebuild-${if (mono) "monorepo" else "normal"}.yml"))
    val namespace = mono && inputs?.field("DEPLOY_NAMESPACE") != null
    val fixedApp = namespace && inputs?.field("DEPLOY_APP") == null
    if (namespace) extra += "      DEPLOY_NAMESPACE: \${{ matrix.target.namespace }}"
    val wrapper =
        template
            .readText()
            .replace("__DEFAULT_BRANCH__", branch)
            .replace("__EXTRA_INPUTS__", extra.joinToString("\n"))
            .replace(
                "__TARGET_FORMAT__",
                if (fixedApp) {
                    "cluster:namespace pairs"
                } else if (namespace) {
                    "app:cluster:namespace triples"
                } else {
                    "app:cluster pairs"
                },
            ).replace("__APP_PATTERN__", if (fixedApp) "" else "[a-z0-9][a-z0-9-]*:")
            .replace("__NAMESPACE_PATTERN__", if (namespace) ":[a-z0-9][a-z0-9-]*" else "")
            .replace(
                "__TARGET_FIELDS__",
                if (fixedApp) {
                    "cluster, namespace"
                } else if (namespace) {
                    "app, cluster, namespace"
                } else {
                    "app, cluster"
                },
            ).replace("__MANIFEST_PREFIX__", if (namespace) "{namespace}/" else "")
            .replace("__MANIFEST_APP__", if (fixedApp) "" else "{app}/")
            .replace("__APP_ENTRY__", if (fixedApp) "" else """"app": app, """)
            .replace("__APP_INPUT__", if (fixedApp) "" else "      DEPLOY_APP: \${{ matrix.target.app }}\n")
            .replace(
                "__NAMESPACE_GUARD__",
                if (namespace) {
                    """              if namespace == "default":
                raise SystemExit("The default namespace cannot be deployed through this workflow")
"""
                } else {
                    ""
                },
            ).replace("__NAMESPACE_ENTRY__", if (namespace) """, "namespace": namespace""" else "")
    return RebuildWorkflowPlan(called, wrapper)
}

internal fun rebuildWorkflowUsesNamespace(source: String): Boolean {
    val root = Yaml().compose(StringReader(source)) as? MappingNode ?: error("Workflow must be a YAML mapping")
    val inputs = ((root.field("on") as? MappingNode)?.field("workflow_call") as? MappingNode)?.field("inputs") as? MappingNode
    val namespace = inputs?.field("DEPLOY_NAMESPACE") as? MappingNode ?: return false
    require((namespace.field("type") as? ScalarNode)?.value == "string") { "DEPLOY_NAMESPACE must be a string input" }
    return true
}

internal fun rebuildWorkflowUsesFixedApplication(source: String): Boolean {
    val root = Yaml().compose(StringReader(source)) as? MappingNode ?: error("Workflow must be a YAML mapping")
    val inputs = ((root.field("on") as? MappingNode)?.field("workflow_call") as? MappingNode)?.field("inputs") as? MappingNode
    return inputs?.field("DEPLOY_NAMESPACE") != null && inputs.field("DEPLOY_APP") == null
}

private fun MappingNode.field(key: String): Node? = value.singleOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode
