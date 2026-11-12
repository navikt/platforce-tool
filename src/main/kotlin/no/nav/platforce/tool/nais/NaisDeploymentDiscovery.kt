package no.nav.platforce.tool.nais

import com.google.gson.JsonObject
import java.time.Instant

data class RunningDeployments(
    val teams: List<String>,
    val applications: List<RunningApplication>,
    val rebuildWorkflows: Map<String, RebuildWorkflow> = emptyMap(),
)

data class RunningApplication(
    val team: String,
    val name: String,
    val environment: String,
    val repository: String?,
    val instances: Int,
    val readyInstances: Int,
    val images: List<RunningImage>,
    val deploymentTime: String?,
    val repositoryDiagnostic: RepositoryDiagnostic? = null,
    val repositorySource: RepositorySource? = null,
    val repositoryAssociationTime: String? = null,
    val lastDeploymentActivityTime: String? = null,
    val workloadType: String = "APPLICATION",
)

enum class RepositorySource {
    SUCCESSFUL_DEPLOYMENT,
    ACTIVITY_LOG,
}

data class RepositoryDiagnostic(
    val reason: String,
    val deploymentsChecked: Int,
    val recentDeployments: List<DeploymentDiagnostic>,
    val latestActivity: ActivityRepositoryDiagnostic? = null,
)

data class ActivityRepositoryDiagnostic(
    val repository: String?,
    val createdAt: String?,
)

data class DeploymentDiagnostic(
    val repository: String?,
    val createdAt: String?,
    val status: String?,
)

data class RunningImage(
    val name: String,
    val tag: String,
    val digest: String?,
)

internal class NaisDeploymentDiscovery(
    private val query: (String, Map<String, Any?>) -> JsonObject,
) {
    fun get(email: String): RunningDeployments {
        val teams =
            pages { cursor ->
                val data = query(load("deployment-teams"), mapOf("email" to email, "after" to cursor))
                checkNotNull(data.getAsJsonObject("user")) { "No user returned" }.getAsJsonObject("teams")
            }.map { it.getAsJsonObject("team")["slug"].asString }.distinct().sorted()
        val applications =
            teams.flatMap { team ->
                pages { cursor ->
                    query(load("deployment-applications"), mapOf("team" to team, "after" to cursor))
                        .getAsJsonObject("team")
                        .getAsJsonObject("applications")
                }.mapNotNull { application ->
                    val id = application["id"].asString
                    val instances =
                        pages { cursor ->
                            query(load("deployment-instances"), mapOf("id" to id, "after" to cursor))
                                .getAsJsonObject("node")
                                .getAsJsonObject("instances")
                        }.filter { it.getAsJsonObject("status")["state"].asString == "RUNNING" }
                    if (instances.isEmpty()) return@mapNotNull null
                    val association = repositoryAssociation(id)
                    RunningApplication(
                        team = team,
                        name = application["name"].asString,
                        environment =
                            application
                                .getAsJsonObject("teamEnvironment")
                                .getAsJsonObject("environment")["name"]
                                .asString,
                        repository = association.repository,
                        instances = instances.size,
                        readyInstances = instances.count { it.getAsJsonObject("status")["ready"].asBoolean },
                        images =
                            instances
                                .map { instance ->
                                    val image = instance.getAsJsonObject("image")
                                    RunningImage(image["name"].asString, image["tag"].asString, image.optionalString("digest"))
                                }.distinct(),
                        deploymentTime = association.createdAt.takeIf { association.source == RepositorySource.SUCCESSFUL_DEPLOYMENT },
                        repositoryDiagnostic = association.diagnostic,
                        repositorySource = association.source,
                        repositoryAssociationTime = association.createdAt,
                        lastDeploymentActivityTime = association.lastDeploymentActivityTime,
                    )
                }
            }
        val jobs =
            teams.flatMap { team ->
                pages { cursor ->
                    query(load("deployment-jobs"), mapOf("team" to team, "after" to cursor))
                        .getAsJsonObject("team")
                        .getAsJsonObject("jobs")
                }.map { job ->
                    val association = repositoryAssociation(job["id"].asString)
                    val image = job.get("image")?.takeUnless { it.isJsonNull }?.asJsonObject
                    RunningApplication(
                        team = team,
                        name = job["name"].asString,
                        environment = job.getAsJsonObject("teamEnvironment").getAsJsonObject("environment")["name"].asString,
                        repository = association.repository,
                        instances = 0,
                        readyInstances = 0,
                        images =
                            image
                                ?.let {
                                    listOf(
                                        RunningImage(
                                            it.optionalString("name").orEmpty(),
                                            it.optionalString("tag").orEmpty(),
                                            it.optionalString("digest"),
                                        ),
                                    )
                                }.orEmpty(),
                        deploymentTime = association.createdAt.takeIf { association.source == RepositorySource.SUCCESSFUL_DEPLOYMENT },
                        repositoryDiagnostic = association.diagnostic,
                        repositorySource = association.source,
                        repositoryAssociationTime = association.createdAt,
                        lastDeploymentActivityTime = association.lastDeploymentActivityTime,
                        workloadType = "JOB",
                    )
                }
            }
        return RunningDeployments(
            teams,
            (applications + jobs).sortedWith(
                compareBy({ it.repository ?: "" }, { it.team }, { it.name }, { it.environment }, { it.workloadType }),
            ),
        )
    }

    private data class RepositoryAssociation(
        val repository: String?,
        val source: RepositorySource?,
        val createdAt: String?,
        val diagnostic: RepositoryDiagnostic?,
        val lastDeploymentActivityTime: String?,
    )

    private fun repositoryAssociation(id: String): RepositoryAssociation {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        val recent = mutableListOf<DeploymentDiagnostic>()
        var checked = 0
        var activity: ActivityRepositoryDiagnostic? = null
        do {
            val node =
                query(load("application-deployments"), mapOf("id" to id, "after" to cursor))
                    .getAsJsonObject("node")
            if (cursor == null) {
                activity =
                    node
                        .getAsJsonObject("activityLog")
                        .getAsJsonArray("nodes")
                        .firstOrNull()
                        ?.asJsonObject
                        ?.let { entry ->
                            ActivityRepositoryDiagnostic(
                                entry
                                    .get("gitHubActorClaims")
                                    ?.takeUnless { it.isJsonNull }
                                    ?.asJsonObject
                                    ?.optionalString("repository"),
                                entry.optionalString("createdAt"),
                            )
                        }
            }
            val connection = node.getAsJsonObject("deployments")
            for (entry in connection.getAsJsonArray("nodes")) {
                val deployment = entry.asJsonObject
                val status =
                    deployment
                        .getAsJsonObject("statuses")
                        .getAsJsonArray("nodes")
                        .firstOrNull()
                        ?.asJsonObject
                        ?.optionalString("state")
                checked++
                if (recent.size < 5) {
                    recent += DeploymentDiagnostic(deployment.optionalString("repository"), deployment.optionalString("createdAt"), status)
                }
                if (status == "SUCCESS") {
                    val raw = deployment.optionalString("repository")
                    if (checked > 5) {
                        recent += DeploymentDiagnostic(raw, deployment.optionalString("createdAt"), status)
                    }
                    val reason =
                        when {
                            raw == null -> {
                                "The latest successful deployment has no repository value."
                            }

                            normalizeDeploymentRepository(raw) == null -> {
                                "The latest successful deployment's repository is not a supported GitHub URL or owner/repository coordinate."
                            }

                            else -> {
                                null
                            }
                        }
                    return selectAssociation(deployment, activity, reason?.let { RepositoryDiagnostic(it, checked, recent.toList()) })
                }
            }
            cursor = nextCursor(connection, seen)
        } while (cursor != null)
        val reason =
            if (checked == 0) {
                "Nais returned no deployment history for this application."
            } else {
                "No deployment had SUCCESS as its latest status. Console may display a deployment with a different or missing status."
            }
        return selectAssociation(null, activity, RepositoryDiagnostic(reason, checked, recent))
    }

    private fun selectAssociation(
        deployment: JsonObject?,
        activity: ActivityRepositoryDiagnostic?,
        diagnostic: RepositoryDiagnostic?,
    ): RepositoryAssociation {
        val deploymentRepository = normalizeDeploymentRepository(deployment?.optionalString("repository"))
        val deploymentTime = deployment?.optionalString("createdAt")
        val activityRepository = normalizeDeploymentRepository(activity?.repository)
        val activityTime = activity?.createdAt
        val lastDeploymentActivityTime = listOfNotNull(deploymentTime, activityTime).maxByOrNull { Instant.parse(it) }
        if (activityRepository != null &&
            (
                deploymentRepository == null || deploymentTime == null ||
                    Instant.parse(checkNotNull(activityTime)).isAfter(Instant.parse(deploymentTime))
            )
        ) {
            return RepositoryAssociation(activityRepository, RepositorySource.ACTIVITY_LOG, activityTime, null, lastDeploymentActivityTime)
        }
        if (deploymentRepository != null) {
            return RepositoryAssociation(
                deploymentRepository,
                RepositorySource.SUCCESSFUL_DEPLOYMENT,
                deploymentTime,
                null,
                lastDeploymentActivityTime,
            )
        }
        val activityReason =
            when {
                activity == null -> "Nais returned no matching application activity."
                activity.repository == null -> "The latest application activity has no GitHub repository claim."
                else -> "The latest application activity's repository is not a supported GitHub URL or owner/repository coordinate."
            }
        return RepositoryAssociation(
            null,
            null,
            null,
            checkNotNull(diagnostic).copy(reason = "${diagnostic.reason} $activityReason", latestActivity = activity),
            lastDeploymentActivityTime,
        )
    }

    private fun pages(fetch: (String?) -> JsonObject): List<JsonObject> {
        val nodes = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val connection = fetch(cursor)
            nodes += connection.getAsJsonArray("nodes").map { it.asJsonObject }
            cursor = nextCursor(connection, seen)
        } while (cursor != null)
        return nodes
    }

    private fun nextCursor(
        connection: JsonObject,
        seen: MutableSet<String>,
    ): String? {
        val info = connection.getAsJsonObject("pageInfo")
        if (!info["hasNextPage"].asBoolean) return null
        val cursor = info.optionalString("endCursor")
        check(!cursor.isNullOrBlank() && seen.add(cursor)) { "Nais Console returned invalid pagination" }
        return cursor
    }

    private fun load(name: String): String = checkNotNull(javaClass.getResource("/graphql/$name.graphql")).readText()
}

private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

internal fun normalizeDeploymentRepository(repository: String?): String? =
    repository
        ?.trim()
        ?.removePrefix("https://github.com/")
        ?.removeSuffix("/")
        ?.removeSuffix(".git")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }
