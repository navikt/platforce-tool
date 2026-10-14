package no.nav.platforce.tool

import com.google.gson.Gson
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.platforce.tool.dependencies.DependencyPullRequestService
import no.nav.platforce.tool.dependencies.DependencyScanCache
import no.nav.platforce.tool.dependencies.DependencyScanViewService
import no.nav.platforce.tool.dependencies.DependencyScanner
import no.nav.platforce.tool.dependencies.GradleTargetResolutionScanner
import no.nav.platforce.tool.dependencies.GradleTargetResolutionService
import no.nav.platforce.tool.dependencies.OsvVulnerabilityService
import no.nav.platforce.tool.dependencies.RepositoryDependencyScan
import no.nav.platforce.tool.dependencies.ResolutionDebugFiles
import no.nav.platforce.tool.dependencies.ResolutionStatus
import no.nav.platforce.tool.dependencies.ResolvedDependency
import no.nav.platforce.tool.dependencies.SecurityScanStatus
import no.nav.platforce.tool.dependencies.TargetSecurityScanner
import no.nav.platforce.tool.dependencies.TargetSecurityService
import no.nav.platforce.tool.dependencies.dependencyScanRoutes
import no.nav.platforce.tool.dependencies.targetVersionsRoutes
import no.nav.platforce.tool.github.DefaultGithubAccessTokenHandler
import no.nav.platforce.tool.github.DefaultGithubClient
import no.nav.platforce.tool.github.GithubAppAuthenticator
import no.nav.platforce.tool.gui.filesHandler
import no.nav.platforce.tool.ignore.ignoredRepositoriesRoutes
import no.nav.platforce.tool.notes.repositoryNotesRoutes
import no.nav.platforce.tool.user.userContext
import no.nav.platforce.tool.user.withUserCacheControl
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

class Application {
    private val log = KotlinLogging.logger { }

    val gson = Gson()

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

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

    val dependencyScanCache = DependencyScanCache()

    private val resolutionDebugFiles = ResolutionDebugFiles()

    val gradleTargetResolutionService = GradleTargetResolutionService(debugFiles = resolutionDebugFiles)

    val gradleTargetResolutionScanner = GradleTargetResolutionScanner(gradleTargetResolutionService::resolve)

    val osvVulnerabilityService = OsvVulnerabilityService(httpClient)

    val targetSecurityService = TargetSecurityService(osvVulnerabilityService)

    val targetSecurityScanner = TargetSecurityScanner(targetSecurityService, gradleTargetResolutionScanner)

    val dependencyScanner = DependencyScanner(githubClient)

    val dependencyScanViewService = DependencyScanViewService(dependencyScanCache, targetSecurityScanner, dependencyScanner)

    val pullRequestService = DependencyPullRequestService(githubClient, dependencyScanViewService)

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to { Response(OK) },
            "/internal/isReady" bind Method.GET to { Response(OK) },
            "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
            "/internal/hello" bind Method.GET to { Response(OK).body("Hello!") },
            "/internal/files" bind Method.GET to userFilesHandler,
            "/internal/files/{path:.*}" bind Method.GET to userFilesHandler,
            "/internal/add-transient" bind Method.GET to { _ ->
                try {
                    PostgresDatabase.expandTargetVersionsWithTransient()

                    Response(Status.OK)
                        .header("Content-Type", "text/plain")
                        .body("target_versions.transient added")
                } catch (e: Exception) {
                    Response(Status.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", "text/plain")
                        .body(
                            "Failed to add target_versions.transient: " +
                                (e.message ?: e.javaClass.simpleName),
                        )
                }
            },
            "/internal/api/target-resolution/start" bind Method.GET to { request ->
                val context = request.userContext()
                val targetState = context.targetVersionsStore.get()
                val snapshot =
                    gradleTargetResolutionScanner.start(
                        username = context.username,
                        targetState = targetState,
                    )
                when (snapshot.status) {
                    ResolutionStatus.READY ->
                        Response(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    ResolutionStatus.RUNNING ->
                        Response(Status.ACCEPTED)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    ResolutionStatus.FAILED ->
                        Response(Status.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    ResolutionStatus.IDLE ->
                        Response(Status.ACCEPTED)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                }
            },
            "/internal/api/target-resolution" bind Method.GET to { request ->
                val context = request.userContext()
                val targetState = context.targetVersionsStore.get()

                val snapshot =
                    gradleTargetResolutionScanner.get(
                        username = context.username,
                        targetState = targetState,
                    )

                val status =
                    when (snapshot.status) {
                        ResolutionStatus.READY -> Status.OK
                        ResolutionStatus.RUNNING -> Status.ACCEPTED
                        ResolutionStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
                        ResolutionStatus.IDLE -> Status.OK
                    }

                Response(status)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(snapshot))
            },
            "/internal/api/target-resolution/security/start" bind Method.GET to { request ->
                val context = request.userContext()
                val targetState = context.targetVersionsStore.get()
                val snapshot =
                    targetSecurityScanner.start(
                        username = context.username,
                        targetState = targetState,
                    )
                when (snapshot.status) {
                    SecurityScanStatus.READY ->
                        Response(Status.OK)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    SecurityScanStatus.RUNNING ->
                        Response(Status.ACCEPTED)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    SecurityScanStatus.FAILED ->
                        Response(Status.INTERNAL_SERVER_ERROR)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                    SecurityScanStatus.IDLE ->
                        Response(Status.ACCEPTED)
                            .header("Content-Type", "application/json")
                            .body(Gson().toJson(snapshot))
                }
            },
            "/internal/api/target-resolution/security" bind Method.GET to { request ->
                val context = request.userContext()
                val targetState = context.targetVersionsStore.get()

                val snapshot =
                    targetSecurityScanner.get(
                        username = context.username,
                        targetState = targetState,
                    )

                log.info {
                    "TARGET SECURITY CACHE: " +
                        "status=${snapshot.status}, " +
                        "fingerprint=${snapshot.targetFingerprint}, " +
                        "targets=${snapshot.result?.targets?.size}"
                }

                val status =
                    when (snapshot.status) {
                        SecurityScanStatus.READY -> Status.OK
                        SecurityScanStatus.RUNNING -> Status.ACCEPTED
                        SecurityScanStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
                        SecurityScanStatus.IDLE -> Status.OK
                    }

                Response(status)
                    .header("Content-Type", "application/json")
                    .body(Gson().toJson(snapshot))
            },
            "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
            "/internal/selectedTeam" bind Method.GET to { request ->
                val context = request.userContext()
                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(
                        gson.toJson(
                            mapOf(
                                "selectedTeam" to context.selectedTeam,
                            ),
                        ),
                    )
            },
            "/internal/selectedTeam" bind Method.POST to { request ->
                val context = request.userContext()
                val body = JsonParser.parseString(request.bodyString()).asJsonObject
                val team = body["selectedTeam"]?.takeIf { !it.isJsonNull }?.asString
                context.selectedTeam = team
                Response(OK)
            },
            "/internal/clearDb" bind Method.GET to clearDbHandler,
            "/internal/initDb" bind Method.GET to initDbHandler,
            *dependencyScanRoutes(
                dependencyScanCache,
                dependencyScanner,
                pullRequestService,
                dependencyScanViewService,
            ).toTypedArray(),
            *targetVersionsRoutes(gradleTargetResolutionScanner, targetSecurityScanner).toTypedArray(),
            *repositoryNotesRoutes().toTypedArray(),
            *ignoredRepositoriesRoutes().toTypedArray(),
            "/internal/repos/view" bind Method.GET to { request ->
                val context = request.userContext()
                val nais = getRepositoriesForUser(context.username)

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
                    buildRepoView(
                        naisRepos,
                        installed,
                        scanned,
                    )

                Response(OK)
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(result))
            },
        ).withUserCacheControl()

    fun start() {
        log.info { "Starting in cluster $cluster" }
        val dir = File("/tmp/files")
        dir.mkdirs()
        apiServer(8080).start()
    }

    private val userFilesHandler: HttpHandler = { request ->
        val context = request.userContext()
        filesHandler(resolutionDebugFiles.latestDirectory(context.username).toFile())(request)
            .header("Cache-Control", "no-store")
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
        PostgresDatabase.createIgnoredRepositoriesTable(true)
        Response(OK).body("Tables recreated")
    }

    private val initDbHandler: HttpHandler = {
        // PostgresDatabase.createTargetVersionsTable(false)
        // PostgresDatabase.createRepositoryNotesTable(false)
        PostgresDatabase.createIgnoredRepositoriesTable(false)
        Response(OK).body("Tables created")
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

    fun readServiceToken(): String =
        File(System.getenv("NAIS_SERVICE_ACCOUNT_TOKEN_PATH"))
            .readText(Charsets.UTF_8)
            .trim()

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
        naisRepos: List<Pair<String, String>>,
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

data class Vulnerability(
    val id: String,
    val summary: String?,
    val aliases: List<String>,
    val references: List<String>,
    val fixedVersions: List<String>,
    val affectedRanges: List<VulnerabilityRange> = emptyList(),
)

data class VulnerabilityRange(
    val type: String?,
    val events: List<VulnerabilityEvent>,
)

data class VulnerabilityEvent(
    val introduced: String?,
    val fixed: String?,
    val lastAffected: String?,
)

data class ResolvedDependencySecurity(
    val group: String?,
    val name: String,
    val requestedVersion: String?,
    val resolvedVersion: String,
    val vulnerabilities: List<Vulnerability>,
)

data class VersionSuggestion(
    val dependency: String, // group:name
    val version: String,
)

data class VulnerableDependency(
    val dependency: ResolvedDependency,
    val vulnerabilities: List<Vulnerability>,
    val suggestedVersion: VersionSuggestion? = null,
)

enum class TargetSecurityStatus {
    OK,
    OK_OVERRIDDEN,
    VULNERABLE,

    // Explicitly configured as a transitive dependency
    // and still required to override a vulnerability.
    OK_TRANSIENT,

    // Explicitly configured as transitive, but no longer
    // referenced by an active override.
    TRANSIENT_UNUSED,
}

data class TransientDependencyUsage(
    val dependency: String,
    val targetVersion: String,
)

data class TargetSecurityResult(
    val key: String,
    val targetVersion: String,
    val status: TargetSecurityStatus,
    val vulnerabilities: List<Vulnerability>,
    val vulnerableDependencies: List<VulnerableDependency>,
    val relatedTo: List<OverrideReason>,
    val transientUsage: List<TransientDependencyUsage> = emptyList(),
)

data class OverrideReason(
    val dependency: String,
    val targetVersion: String,
    val resolvedVersion: String,
    val vulnerableVersion: String? = null,
    val causedBy: List<String> = emptyList(),
    val suggestedVersion: String? = null,
)
