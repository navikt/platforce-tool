package no.nav.platforce.tool

import com.google.gson.Gson
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.platforce.tool.dependencies.DependencyPullRequestService
import no.nav.platforce.tool.dependencies.DependencyScanCache
import no.nav.platforce.tool.dependencies.DependencyScanner
import no.nav.platforce.tool.dependencies.RepositoryDependencyScan
import no.nav.platforce.tool.dependencies.TargetVersionsStore
import no.nav.platforce.tool.dependencies.dependencyScanRoutes
import no.nav.platforce.tool.dependencies.targetVersionsRoutes
import no.nav.platforce.tool.entra.AuthRouteBuilder
import no.nav.platforce.tool.entra.DefaultTokenValidator
import no.nav.platforce.tool.entra.MockTokenValidator
import no.nav.platforce.tool.github.DefaultGithubAccessTokenHandler
import no.nav.platforce.tool.github.DefaultGithubClient
import no.nav.platforce.tool.github.GithubAppAuthenticator
import no.nav.platforce.tool.ignore.IgnoredRepositoriesStore
import no.nav.platforce.tool.ignore.ignoredRepositoriesRoutes
import no.nav.platforce.tool.notes.RepositoryNotesStore
import no.nav.platforce.tool.notes.repositoryNotesRoutes
import no.nav.sf.keytool.db.PostgresDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.UNAUTHORIZED
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.File
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.util.Base64

class Application {
    private val log = KotlinLogging.logger { }

    val gson = Gson()

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    val tokenValidator = if (local) MockTokenValidator() else DefaultTokenValidator()

    val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    val mediaTypeJson = "application/json".toMediaType()

    val githubAuthenticator =
        GithubAppAuthenticator(
            appId = env(secret_PLATFORCE_TOOLING_APP_ID),
            privateKey = parsePrivateKey(env(secret_PLATFORCE_TOOLING_PRIVATE_KEY)),
            httpClient = httpClient,
        )

    val githubTokenHandler =
        DefaultGithubAccessTokenHandler(
            authenticator = githubAuthenticator,
            httpClient = httpClient,
        )

    val githubClient =
        DefaultGithubClient(
            tokenHandler = githubTokenHandler,
            httpClient = httpClient,
        )

    private val dependencyScanCache = DependencyScanCache()

    private val targetVersionsStore = TargetVersionsStore()

    private val dependencyScanner = DependencyScanner(githubClient, targetVersionsStore)

    private val pullRequestService = DependencyPullRequestService(githubClient, dependencyScanCache)

    val repositoryNotesStore = RepositoryNotesStore()

    val ignoredRepositoriesStore = IgnoredRepositoriesStore()

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to { Response(OK) },
            "/internal/isReady" bind Method.GET to { Response(OK) },
            "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
            "/internal/hello" bind Method.GET to { Response(OK).body("Hello!") },
            "/internal/secrethello" authbind Method.GET to { Response(OK).body("Secret Hello!") },
            "/internal/repos" bind Method.GET to {
                Response(OK).body(githubClient.listRepositories().joinToString("\n"))
            },
            "/internal/filetest" bind Method.GET to {
                val file =
                    githubClient.getFile(
                        owner = "navikt",
                        repo = "simtest",
                        path = "build.gradle",
                    )
                Response(OK).header("Content-Type", "text/plain").body(file)
            },
            "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
            "/internal/clearDb" bind Method.GET to clearDbHandler,
            "/internal/initDb" bind Method.GET to initDbHandler,
            *dependencyScanRoutes(dependencyScanCache, dependencyScanner, pullRequestService).toTypedArray(),
            *targetVersionsRoutes(targetVersionsStore).toTypedArray(),
            *repositoryNotesRoutes(repositoryNotesStore).toTypedArray(),
            *ignoredRepositoriesRoutes(ignoredRepositoriesStore).toTypedArray(),
            "/internal/all-teams" bind Method.GET to { _ ->
                val teams = getAllTeams()

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(teams))
            },
            "/internal/my-teams" bind Method.GET to { request ->

                val authHeader =
                    request.header("Authorization")
                        ?: return@to Response(Status.UNAUTHORIZED).body("Missing Authorization header")

                val email =
                    try {
                        extractPreferredUsername(authHeader)
                    } catch (e: Exception) {
                        return@to Response(Status.UNAUTHORIZED).body("Invalid token: ${e.message}")
                    }

                val userTeams = getUserTeams(email)

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(userTeams))
            },
            "/internal/my-apps" bind Method.GET to { request ->
                val authHeader =
                    request.header("Authorization")
                        ?: return@to Response(Status.UNAUTHORIZED).body("Missing Authorization")

                val email = extractPreferredUsername(authHeader)

                val userTeams = getUserTeams(email) // Set<String>

                val result =
                    userTeams.map { teamSlug ->

                        val apps = fetchTeamApps(teamSlug)

                        TeamAppsGroup(
                            team = teamSlug,
                            apps = apps,
                        )
                    }

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(result))
            },
            "/internal/my-repos" bind Method.GET to { request ->

                val authHeader =
                    request.header("Authorization")
                        ?: return@to Response(UNAUTHORIZED)

                val email = extractPreferredUsername(authHeader)

                val repos = getRepositoriesForUser(email)

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(repos))
            },
            "/internal/repos/view" bind Method.GET to { request ->

                val auth =
                    request.header("Authorization")
                        ?: return@to Response(Status.UNAUTHORIZED)

                val email = extractPreferredUsername(auth)

                val nais = getRepositoriesForUser(email)

                val naisRepoMap =
                    nais.teams
                        .flatMap { team ->
                            team.repositories.map { repo ->
                                repo to team.team
                            }
                        }.toMap()

                val installed = githubClient.listRepositories().toSet()
                val scanned: Map<String, RepositoryDependencyScan> =
                    dependencyScanCache
                        .get()
                        .associateBy { it.repository }

                val result =
                    buildRepoView(
                        naisRepoMap,
                        installed,
                        scanned,
                    )

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(result))
            },
        )

    /**
     * authbind: a variant of bind that takes care of authentication with use of tokenValidator
     */
    infix fun String.authbind(method: Method) = AuthRouteBuilder(this, method, tokenValidator)

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
    }

    fun parsePrivateKey(pem: String): RSAPrivateKey {
        val reader = PEMParser(StringReader(pem))
        val converter = JcaPEMKeyConverter()

        val keyPair =
            converter.getKeyPair(
                reader.readObject() as PEMKeyPair,
            )

        return keyPair.private as RSAPrivateKey
    }

    private val clearDbHandler: HttpHandler = {
        PostgresDatabase.createTargetVersionsTable(true)
        PostgresDatabase.createRepositoryNotesTable(true)
        Response(OK).body("Tables recreated")
    }

    private val initDbHandler: HttpHandler = {
        // PostgresDatabase.createTargetVersionsTable(false)
        PostgresDatabase.createRepositoryNotesTable(false)
        Response(OK).body("Tables created")
    }

    fun callNaisGraphql(authHeader: String?): String {
        val query =
            """
            {
              me {
                __typename
              }
            }
            """.trimIndent()

        val body =
            """
            {
              "query": ${query.trim().let { "\"\"\"$it\"\"\"" }}
            }
            """.trimIndent()

        val request =
            Request
                .Builder()
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (authHeader != null) {
                        addHeader("Authorization", authHeader)
                    }
                }.post(body.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { resp ->
            return resp.body.string()
        }
    }

    fun callNaisApi(): String {
        val token = File(env(env_NAIS_SERVICE_ACCOUNT_TOKEN_PATH)).readText(Charsets.UTF_8)

        val query =
            """
            {
              me {
                __typename
              }
            }
            """.trimIndent()

        val payload =
            mapOf(
                "query" to query,
            )

        val bodyJson = gson.toJson(payload)

        val request =
            Request
                .Builder()
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { resp ->
            return resp.body.string()
        }
    }

    fun callNaisTeams(): String {
        val token =
            File(System.getenv("NAIS_SERVICE_ACCOUNT_TOKEN_PATH"))
                .readText(Charsets.UTF_8)
                .trim()

        val query = loadGraphQL("/graphql/team-information.graphql")

        val payload =
            GraphQLRequest(
                query = query,
                variables =
                    mapOf(
                        "teamFirst" to 200,
                        "teamAfter" to null,
                    ),
            )

        val bodyJson = gson.toJson(payload)

        val request =
            okhttp3.Request
                .Builder()
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

        httpClient.newCall(request).execute().use { response ->
            return response.body.string() ?: "empty response"
        }
    }

    fun loadGraphQL(path: String): String =
        object {}
            .javaClass
            .getResource(path)!!
            .readText()

    data class GraphQLRequest(
        val query: String,
        val variables: Map<String, Any?> = emptyMap(),
    )

    data class TeamInfo(
        val slug: String,
        val slackChannel: String?,
    )

    fun readServiceToken(): String =
        File(System.getenv("NAIS_SERVICE_ACCOUNT_TOKEN_PATH"))
            .readText(Charsets.UTF_8)
            .trim()

    fun getAllTeams(): List<TeamInfo> {
        val allTeams = mutableListOf<TeamInfo>()

        var cursor: String? = null
        var hasNextPage = true

        val token = readServiceToken()

        val query = loadGraphQL("/graphql/team-information.graphql")

        while (hasNextPage) {
            val payload =
                GraphQLRequest(
                    query = query,
                    variables =
                        mapOf(
                            "teamFirst" to 200,
                            "teamAfter" to cursor,
                        ),
                )

            val bodyJson = gson.toJson(payload)

            val request =
                Request
                    .Builder()
                    .url("https://console.nav.cloud.nais.io/graphql")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", mediaTypeJson.toString())
                    .post(bodyJson.toRequestBody(mediaTypeJson))
                    .build()

            httpClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${response.message}")
                }

                val root = JsonParser.parseString(response.body.string()).asJsonObject

                // GraphQL errors check
                if (root.has("errors")) {
                    throw RuntimeException(root["errors"].toString())
                }

                val data = root["data"].asJsonObject
                val teams = data["teams"].asJsonObject
                val nodes = teams["nodes"].asJsonArray
                val pageInfo = teams["pageInfo"].asJsonObject

                for (node in nodes) {
                    val obj = node.asJsonObject

                    allTeams.add(
                        TeamInfo(
                            slug = obj["slug"].asString,
                            slackChannel = obj["slackChannel"]?.takeIf { !it.isJsonNull }?.asString,
                        ),
                    )
                }

                hasNextPage = pageInfo["hasNextPage"].asBoolean
                cursor = pageInfo["endCursor"]?.takeIf { !it.isJsonNull }?.asString
            }
        }

        return allTeams
    }

    fun getUserTeams(email: String): Set<String> {
        val token = readServiceToken()

        val query = loadGraphQL("/graphql/team-memberships-for-user.graphql")

        val payload =
            GraphQLRequest(
                query = query,
                variables = mapOf("email" to email),
            )

        val bodyJson = gson.toJson(payload)

        val request =
            Request
                .Builder()
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mediaTypeJson.toString())
                .post(bodyJson.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { response ->

            val root = JsonParser.parseString(response.body.string()).asJsonObject

            if (root.has("errors")) {
                throw RuntimeException(root["errors"].toString())
            }

            val nodes =
                root["data"]
                    .asJsonObject["user"]
                    .asJsonObject["teams"]
                    .asJsonObject["nodes"]
                    .asJsonArray

            return nodes
                .map {
                    it.asJsonObject["team"].asJsonObject["slug"].asString
                }.toSet()
        }
    }

    fun extractPreferredUsername(authHeader: String?): String {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw RuntimeException("Missing Bearer token")
        }

        val token = authHeader.removePrefix("Bearer ").trim()

        val parts = token.split(".")

        if (parts.size < 2) {
            throw RuntimeException("Invalid JWT token")
        }

        val payloadJson =
            String(
                Base64.getUrlDecoder().decode(parts[1]),
            )

        val payload = JsonParser.parseString(payloadJson).asJsonObject

        return payload["preferred_username"]?.asString
            ?: throw RuntimeException("preferred_username not found in token")
    }

    data class AppInfo(
        val name: String,
    )

    data class TeamAppsGroup(
        val team: String,
        val apps: List<AppInfo>,
    )

    fun fetchTeamApps(teamSlug: String): List<AppInfo> {
        val token = readServiceToken()
        val query = loadGraphQL("/graphql/team-apps.graphql")

        val payload =
            GraphQLRequest(
                query = query,
                variables = mapOf("slug" to teamSlug),
            )

        val bodyJson = gson.toJson(payload)

        val request =
            Request
                .Builder()
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mediaTypeJson.toString())
                .post(bodyJson.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { response ->

            val root =
                JsonParser
                    .parseString(
                        response.body?.string() ?: return emptyList(),
                    ).asJsonObject

            val data =
                root["data"]?.takeIf { !it.isJsonNull }?.asJsonObject
                    ?: return emptyList()

            val team =
                data["team"]?.takeIf { !it.isJsonNull }?.asJsonObject
                    ?: return emptyList()

            val workloads =
                team["workloads"]?.takeIf { !it.isJsonNull }?.asJsonObject
                    ?: return emptyList()

            val nodes =
                workloads["nodes"]?.takeIf { !it.isJsonNull }?.asJsonArray
                    ?: return emptyList()

            return nodes.mapNotNull { node ->
                val obj = node.asJsonObject
                val name = obj["name"]?.takeIf { !it.isJsonNull }?.asString
                name?.let { AppInfo(it) }
            }
        }
    }

    data class TeamRepositories(
        val team: String,
        val role: String,
        val repositories: List<String>,
    )

    data class UserRepositoriesResponse(
        val email: String,
        val teams: List<TeamRepositories>,
    )

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
                .url("https://console.nav.cloud.nais.io/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", mediaTypeJson.toString())
                .post(requestBody.toRequestBody(mediaTypeJson))
                .build()

        httpClient.newCall(request).execute().use { response ->

            val root =
                JsonParser
                    .parseString(
                        response.body?.string() ?: "{}",
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

    enum class RepoState {
        NOT_IN_GITHUB, // exists in Nais, but not installed
        NOT_SCANNED, // installed but no scan yet
        SCANNED, // has scan data
    }

    data class RepoView(
        val name: String,
        val team: String,
        val state: RepoState,
        val scan: RepositoryDependencyScan? = null,
    )

    fun buildRepoView(
        naisRepos: Map<String, String>,
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
