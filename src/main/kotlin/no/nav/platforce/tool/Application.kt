package no.nav.platforce.tool

import mu.KotlinLogging
import no.nav.platforce.tool.dependencies.DependencyPullRequestService
import no.nav.platforce.tool.dependencies.DependencyScanCache
import no.nav.platforce.tool.dependencies.DependencyScanner
import no.nav.platforce.tool.dependencies.TargetVersionsStore
import no.nav.platforce.tool.dependencies.dependencyScanRoutes
import no.nav.platforce.tool.dependencies.targetVersionsRoutes
import no.nav.platforce.tool.entra.AuthRouteBuilder
import no.nav.platforce.tool.entra.DefaultTokenValidator
import no.nav.platforce.tool.entra.MockTokenValidator
import no.nav.platforce.tool.github.DefaultGithubAccessTokenHandler
import no.nav.platforce.tool.github.DefaultGithubClient
import no.nav.platforce.tool.github.GithubAppAuthenticator
import no.nav.platforce.tool.notes.RepositoryNotesStore
import no.nav.platforce.tool.notes.repositoryNotesRoutes
import no.nav.sf.keytool.db.PostgresDatabase
import okhttp3.OkHttpClient
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey

class Application {
    private val log = KotlinLogging.logger { }

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    val tokenValidator = if (local) MockTokenValidator() else DefaultTokenValidator()

    val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

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
}
