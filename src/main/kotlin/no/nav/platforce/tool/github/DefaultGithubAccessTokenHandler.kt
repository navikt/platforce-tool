package no.nav.platforce.tool.github

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class DefaultGithubAccessTokenHandler(
    private val authenticator: GithubAppAuthenticator,
    private val httpClient: OkHttpClient,
) : GithubAccessTokenHandler {
    override val accessToken: String
        get() = fetch()

    private val gson = Gson()

    private val installationId: Long by lazy {
        authenticator.fetchInstallationId()
    }

    private var cachedToken: String? = null
    private var expireAtMillis: Long = 0

    private fun fetch(): String {
        val now = System.currentTimeMillis()

        if (cachedToken != null && now < expireAtMillis) {
            return cachedToken!!
        }

        val jwt = authenticator.createJwt()

        val request =
            Request
                .Builder()
                .url(
                    "https://api.github.com/app/installations/$installationId/access_tokens",
                ).post(
                    "{}".toRequestBody(
                        "application/json".toMediaType(),
                    ),
                ).header("Authorization", "Bearer $jwt")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

        httpClient.newCall(request).execute().use { response ->

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            if (!response.isSuccessful) {
                error("Failed to create access token: $body")
            }

            val parsed =
                gson.fromJson(
                    body,
                    GithubAccessTokenResponse::class.java,
                )

            cachedToken = parsed.token

            expireAtMillis =
                Instant
                    .parse(parsed.expires_at)
                    .minusSeconds(60)
                    .toEpochMilli()

            return parsed.token
        }
    }

    private data class GithubAccessTokenResponse(
        val token: String,
        val expires_at: String,
    )
}
