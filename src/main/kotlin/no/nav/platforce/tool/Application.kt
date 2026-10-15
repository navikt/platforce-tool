package no.nav.platforce.tool

import mu.KotlinLogging
import no.nav.platforce.tool.config.env
import no.nav.platforce.tool.config.env_NAIS_CLUSTER_NAME
import no.nav.platforce.tool.config.secret_PLATFORCE_TOOLING_APP_ID
import no.nav.platforce.tool.config.secret_PLATFORCE_TOOLING_PRIVATE_KEY
import no.nav.platforce.tool.github.DefaultGithubAccessTokenHandler
import no.nav.platforce.tool.github.DefaultGithubClient
import no.nav.platforce.tool.github.GithubAppAuthenticator
import no.nav.platforce.tool.github.parsePrivateKey
import no.nav.platforce.tool.ignore.ignoredRepositoriesRoutes
import no.nav.platforce.tool.internal.databaseAdminRoutes
import no.nav.platforce.tool.internal.internalRoutes
import no.nav.platforce.tool.nais.NaisConsoleClient
import no.nav.platforce.tool.notes.repositoryNotesRoutes
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.RepositoryViewService
import no.nav.platforce.tool.repositories.dependencyScanRoutes
import no.nav.platforce.tool.repositories.repositoryViewRoutes
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.GradleTargetResolutionService
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import no.nav.platforce.tool.resolution.targetResolutionRoutes
import no.nav.platforce.tool.security.OsvVulnerabilityService
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityService
import no.nav.platforce.tool.security.targetSecurityRoutes
import no.nav.platforce.tool.targets.targetVersionsRoutes
import no.nav.platforce.tool.user.selectedTeamRoutes
import no.nav.platforce.tool.user.withUserCacheControl
import okhttp3.OkHttpClient
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.File

class Application {
    private val log = KotlinLogging.logger { }

    private val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    private val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    private val githubAuthenticator =
        GithubAppAuthenticator(
            appId = env(secret_PLATFORCE_TOOLING_APP_ID),
            privateKey = parsePrivateKey(env(secret_PLATFORCE_TOOLING_PRIVATE_KEY)),
            httpClient = httpClient,
        )

    private val githubTokenHandler =
        DefaultGithubAccessTokenHandler(
            authenticator = githubAuthenticator,
            httpClient = httpClient,
        )

    private val githubClient =
        DefaultGithubClient(
            tokenHandler = githubTokenHandler,
            httpClient = httpClient,
        )

    private val dependencyScanCache = DependencyScanCache()

    private val resolutionDebugFiles = ResolutionDebugFiles()

    private val gradleTargetResolutionService = GradleTargetResolutionService(debugFiles = resolutionDebugFiles)

    private val gradleTargetResolutionScanner =
        GradleTargetResolutionScanner(
            gradleTargetResolutionService::resolve,
            coverageResolve = gradleTargetResolutionService::resolveCoverage,
        )

    private val osvVulnerabilityService = OsvVulnerabilityService(httpClient)

    private val targetSecurityService = TargetSecurityService(osvVulnerabilityService)

    private val targetSecurityScanner = TargetSecurityScanner(targetSecurityService, gradleTargetResolutionScanner)

    private val dependencyScanner = DependencyScanner(githubClient)

    private val dependencyScanViewService = DependencyScanViewService(dependencyScanCache, targetSecurityScanner, dependencyScanner)

    private val pullRequestService = DependencyPullRequestService(githubClient, dependencyScanViewService)

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    private val naisClient = NaisConsoleClient(httpClient)
    private val repositoryViewService = RepositoryViewService()

    fun api(): HttpHandler =
        routes(
            *internalRoutes(resolutionDebugFiles).toTypedArray(),
            *databaseAdminRoutes().toTypedArray(),
            *targetResolutionRoutes(gradleTargetResolutionScanner).toTypedArray(),
            *targetSecurityRoutes(targetSecurityScanner).toTypedArray(),
            "/internal/gui" bind Method.GET to static(ResourceLoader.Classpath("gui")),
            *selectedTeamRoutes().toTypedArray(),
            *dependencyScanRoutes(dependencyScanCache, dependencyScanner, pullRequestService, dependencyScanViewService).toTypedArray(),
            *targetVersionsRoutes(gradleTargetResolutionScanner, targetSecurityScanner).toTypedArray(),
            *repositoryNotesRoutes().toTypedArray(),
            *ignoredRepositoriesRoutes().toTypedArray(),
            *repositoryViewRoutes(naisClient, githubClient, dependencyScanViewService, repositoryViewService).toTypedArray(),
        ).withUserCacheControl()

    fun start() {
        log.info { "Starting in cluster $cluster" }
        val dir = File("/tmp/files")
        dir.mkdirs()
        apiServer(8080).start()
    }
}
