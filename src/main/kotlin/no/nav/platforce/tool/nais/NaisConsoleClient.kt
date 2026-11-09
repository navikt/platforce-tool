package no.nav.platforce.tool.nais

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class NaisConsoleClient(
    private val httpClient: OkHttpClient,
    private val gson: Gson = Gson(),
    private val endpoint: String = "https://console.nav.cloud.nais.io/graphql",
    private val serviceToken: () -> String = {
        File(System.getenv("NAIS_SERVICE_ACCOUNT_TOKEN_PATH")).readText(Charsets.UTF_8).trim()
    },
) {
    private val mediaTypeJson = "application/json".toMediaType()

    private fun loadGraphQL(path: String): String =
        object {}
            .javaClass
            .getResource(path)!!
            .readText()

    private fun readServiceToken(): String = serviceToken()

    fun getRunningDeployments(email: String): RunningDeployments = NaisDeploymentDiscovery(::deploymentQuery).get(email)

    private fun deploymentQuery(
        query: String,
        variables: Map<String, Any?>,
    ): JsonObject {
        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${readServiceToken()}")
                .post(gson.toJson(GraphQLRequest(query, variables)).toRequestBody(mediaTypeJson))
                .build()
        return httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Nais Console returned HTTP ${response.code}" }
            val root = JsonParser.parseString(response.body.string()).asJsonObject
            check(root.getAsJsonArray("errors")?.isEmpty != false) { "Nais Console returned GraphQL errors" }
            checkNotNull(root.getAsJsonObject("data")) { "Nais Console returned no data" }
        }
    }

    fun getRepositoriesForUser(email: String): UserRepositoriesResponse {
        val token = readServiceToken()

        val payload =
            GraphQLRequest(
                query = loadGraphQL("/graphql/my-repositories.graphql"),
                variables =
                    mapOf(
                        "email" to email,
                    ),
            )

        val requestBody = gson.toJson(payload)

        val request =
            Request
                .Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mediaTypeJson.toString())
                .post(requestBody.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { response ->

            val root =
                JsonParser
                    .parseString(
                        response.body.string(),
                    ).asJsonObject

            val user =
                root
                    .getAsJsonObject("data")
                    ?.getAsJsonObject("user")
                    ?: throw IllegalStateException("No user returned")

            val teams =
                user
                    .getAsJsonObject("teams")
                    .getAsJsonArray("nodes")

            val result =
                teams
                    .map { node ->

                        val nodeObj = node.asJsonObject

                        val role = nodeObj["role"].asString

                        val teamObj =
                            nodeObj
                                .getAsJsonObject("team")

                        val teamSlug = teamObj["slug"].asString

                        val repositories =
                            teamObj
                                .getAsJsonObject("repositories")
                                .getAsJsonArray("nodes")
                                .map {
                                    it.asJsonObject["name"].asString
                                }.sorted()

                        TeamRepositories(
                            team = teamSlug,
                            role = role,
                            repositories = repositories,
                        )
                    }.sortedBy { it.team }

            return UserRepositoriesResponse(
                email = email,
                teams = result,
            )
        }
    }
}
