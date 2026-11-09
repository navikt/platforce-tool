package no.nav.platforce.tool.nais

import com.google.gson.JsonObject

data class RunningDeployments(
    val teams: List<String>,
    val applications: List<RunningApplication>,
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
                    val deployment = latestSuccessfulDeployment(id)
                    RunningApplication(
                        team = team,
                        name = application["name"].asString,
                        environment =
                            application
                                .getAsJsonObject("teamEnvironment")
                                .getAsJsonObject("environment")["name"]
                                .asString,
                        repository = normalizeDeploymentRepository(deployment?.optionalString("repository")),
                        instances = instances.size,
                        readyInstances = instances.count { it.getAsJsonObject("status")["ready"].asBoolean },
                        images =
                            instances
                                .map { instance ->
                                    val image = instance.getAsJsonObject("image")
                                    RunningImage(image["name"].asString, image["tag"].asString, image.optionalString("digest"))
                                }.distinct(),
                        deploymentTime = deployment?.optionalString("createdAt"),
                    )
                }
            }
        return RunningDeployments(
            teams,
            applications.sortedWith(compareBy({ it.repository ?: "" }, { it.team }, { it.name }, { it.environment })),
        )
    }

    private fun latestSuccessfulDeployment(id: String): JsonObject? {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val connection =
                query(load("application-deployments"), mapOf("id" to id, "after" to cursor))
                    .getAsJsonObject("node")
                    .getAsJsonObject("deployments")
            connection
                .getAsJsonArray("nodes")
                .map { it.asJsonObject }
                .firstOrNull {
                    it
                        .getAsJsonObject("statuses")
                        .getAsJsonArray("nodes")
                        .firstOrNull()
                        ?.asJsonObject
                        ?.get("state")
                        ?.asString == "SUCCESS"
                }?.let { return it }
            cursor = nextCursor(connection, seen)
        } while (cursor != null)
        return null
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
