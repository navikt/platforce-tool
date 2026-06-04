package no.nav.platforce.tool.github

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

class DefaultGithubClient(
    private val tokenHandler: GithubAccessTokenHandler,
    private val httpClient: OkHttpClient,
) : GithubClient {
    private val gson = Gson()

    override fun listRepositories(): List<String> {
        val request =
            authenticatedRequest(
                "https://api.github.com/installation/repositories",
            )

        httpClient.newCall(request).execute().use { response ->

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            if (!response.isSuccessful) {
                error(body)
            }

            val parsed =
                gson.fromJson(
                    body,
                    RepositoryResponse::class.java,
                )

            return parsed.repositories.map { it.full_name }
        }
    }

    override fun getFile(
        owner: String,
        repo: String,
        path: String,
    ): String {
        val request =
            authenticatedRequest(
                "https://api.github.com/repos/$owner/$repo/contents/$path",
            )

        httpClient.newCall(request).execute().use { response ->

            val body =
                response.body?.string()
                    ?: error("Missing response body")

            if (!response.isSuccessful) {
                error(body)
            }

            val parsed =
                gson.fromJson(
                    body,
                    GithubContentResponse::class.java,
                )

            return decodeGitHubBase64(parsed.content)
        }
    }

    private fun authenticatedRequest(url: String): Request =
        Request
            .Builder()
            .url(url)
            .header(
                "Authorization",
                "Bearer ${tokenHandler.accessToken}",
            ).header(
                "Accept",
                "application/vnd.github+json",
            ).header(
                "X-GitHub-Api-Version",
                "2022-11-28",
            ).build()

    private fun decodeGitHubBase64(content: String): String =
        String(
            Base64.getDecoder().decode(
                content.replace("\n", ""),
            ),
            Charsets.UTF_8,
        )

    private data class GithubContentResponse(
        val content: String,
    )

    private data class RepositoryResponse(
        val repositories: List<Repository>,
    )

    private data class Repository(
        val full_name: String,
    )
}
