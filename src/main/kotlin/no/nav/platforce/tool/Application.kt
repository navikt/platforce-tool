package no.nav.platforce.tool

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.token.AuthRouteBuilder
import no.nav.platforce.tool.token.DefaultTokenValidator
import no.nav.platforce.tool.token.MockTokenValidator
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.time.Duration
import java.util.concurrent.TimeUnit

class Application {
    private val log = KotlinLogging.logger { }

    val local: Boolean = System.getenv(env_NAIS_CLUSTER_NAME) == null

    val tokenValidator = if (local) MockTokenValidator() else DefaultTokenValidator()

    val cluster = if (local) "local" else env(env_NAIS_CLUSTER_NAME)

    fun apiServer(port: Int): Http4kServer = api().asServer(Netty(port))

    fun api(): HttpHandler =
        routes(
            "/internal/isAlive" bind Method.GET to { Response(OK) },
            "/internal/isReady" bind Method.GET to { Response(OK) },
            "/internal/metrics" bind Method.GET to Metrics.metricsHttpHandler,
            "/internal/hello" bind Method.GET to { Response(OK).body("Hello!") },
            "/internal/secrethello" authbind Method.GET to { Response(OK).body("Secret Hello") },
            "/internal/list" bind Method.GET to {
                val jwtFactory = GithubJwtFactory()
                val jwt = jwtFactory.createJwt()
                Response(OK).body(listInstallations(jwt))
            },
            "/internal/repos" bind Method.GET to {
                val installationId = 137708755L

                val jwtFactory = GithubJwtFactory()
                val jwt = jwtFactory.createJwt()

                val token =
                    createInstallationToken(
                        installationId = installationId,
                        jwt = jwt,
                    )

                val request =
                    Request
                        .Builder()
                        .url("https://api.github.com/installation/repositories")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .build()

                var result = ""
                httpClient.newCall(request).execute().use {
                    result = it.body.string()
                }

                Response(OK).body(result)
            },
        )

    private val httpClient: OkHttpClient =
        OkHttpClient
            .Builder()
//            .connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
//            .connectTimeout(Duration.ofSeconds(60))
//            .readTimeout(Duration.ofSeconds(60))
//            .writeTimeout(Duration.ofSeconds(60))
//            .retryOnConnectionFailure(false)
            .build()

    /**
     * authbind: a variant of bind that takes care of authentication with use of tokenValidator
     */
    infix fun String.authbind(method: Method) = AuthRouteBuilder(this, method, tokenValidator)

    fun start() {
        log.info { "Starting in cluster $cluster" }
        apiServer(8080).start()
    }

    val gson = Gson()

    fun getInstallationId(
        owner: String,
        repo: String,
        jwt: String,
    ): Long {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/repos/$owner/$repo/installation")
                .header("Authorization", "Bearer $jwt")
                .header("Accept", "application/vnd.github+json")
                .build()

        httpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw RuntimeException(
                    "Failed to get installation. " +
                        "HTTP ${response.code}: ${response.body?.string()}",
                )
            }

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            return gson
                .fromJson(
                    body,
                    InstallationResponse::class.java,
                ).id
        }
    }

    data class AccessTokenResponse(
        val token: String,
    )

    fun createInstallationToken(
        installationId: Long,
        jwt: String,
    ): String {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/app/installations/$installationId/access_tokens")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $jwt")
                .header("Accept", "application/vnd.github+json")
                .build()

        httpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                throw RuntimeException(
                    "Failed to create installation token. " +
                        "HTTP ${response.code}: ${response.body?.string()}",
                )
            }

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            return gson
                .fromJson(
                    body,
                    AccessTokenResponse::class.java,
                ).token
        }
    }

    fun listInstallations(jwt: String): String {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/app/installations")
                .header("Authorization", "Bearer $jwt")
                .header("Accept", "application/vnd.github+json")
                .build()

        httpClient.newCall(request).execute().use { response ->
            return response.body.string()
        }
    }
}
