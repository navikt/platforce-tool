package no.nav.platforce.tool.github
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

class GithubAppAuthenticator(
    private val appId: String,
    private val privateKey: RSAPrivateKey,
    private val httpClient: OkHttpClient,
) {
    private val gson = Gson()

    fun createJwt(): String {
        val now = Instant.now()

        return JWT
            .create()
            .withIssuedAt(Date.from(now.minusSeconds(60)))
            .withExpiresAt(Date.from(now.plusSeconds(600)))
            .withIssuer(appId)
            .sign(Algorithm.RSA256(null, privateKey))
    }

    fun fetchInstallationId(): Long {
        val jwt = createJwt()

        val request =
            Request
                .Builder()
                .url("https://api.github.com/app/installations")
                .header("Authorization", "Bearer $jwt")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

        httpClient.newCall(request).execute().use { response ->

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            if (!response.isSuccessful) {
                error("Failed to fetch installations: $body")
            }

            val installations =
                gson.fromJson(
                    body,
                    Array<GithubInstallation>::class.java,
                )

            return installations.single().id
        }
    }

    private data class GithubInstallation(
        val id: Long,
    )
}
