package no.nav.platforce.tool.github

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import mu.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

class DefaultGithubClient(
    private val tokenHandler: GithubAccessTokenHandler,
    private val httpClient: OkHttpClient,
) : GithubClient {
    private val gson = Gson()

    private val log = KotlinLogging.logger { }

    override fun listWorkflowFiles(
        owner: String,
        repo: String,
    ): Set<String> {
        val request = authenticatedRequest("https://api.github.com/repos/$owner/$repo/contents/.github/workflows")
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) {
                val root = authenticatedRequest("https://api.github.com/repos/$owner/$repo/contents")
                httpClient.newCall(root).execute().use { contents ->
                    check(contents.isSuccessful) { "Could not verify GitHub contents access: HTTP ${contents.code}" }
                }
                return emptySet()
            }
            check(response.isSuccessful) { "Could not list workflow files: HTTP ${response.code}" }
            return com.google.gson.JsonParser
                .parseString(response.body.string())
                .asJsonArray
                .filter { it.asJsonObject["type"].asString == "file" }
                .map { it.asJsonObject["name"].asString }
                .toSet()
        }
    }

    override fun fileExists(
        owner: String,
        repo: String,
        path: String,
    ): Boolean {
        val request = authenticatedRequest("https://api.github.com/repos/$owner/$repo/contents/$path")
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) {
                val root = authenticatedRequest("https://api.github.com/repos/$owner/$repo/contents")
                httpClient.newCall(root).execute().use { contents ->
                    check(contents.isSuccessful) { "Could not verify GitHub contents access: HTTP ${contents.code}" }
                }
                return false
            }
            check(response.isSuccessful) { "Could not check GitHub file: HTTP ${response.code}" }
            return true
        }
    }

    private data class RepoResponse(
        val default_branch: String,
    )

    private data class PullRequestResponse(
        val html_url: String,
    )

    override fun listRepositories(): List<String> {
        val repositories = mutableListOf<String>()
        var page = 1
        do {
            val request = authenticatedRequest("https://api.github.com/installation/repositories?per_page=100&page=$page")
            val entries =
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Could not list GitHub App repositories: HTTP ${response.code}" }
                    gson.fromJson(response.body.string(), RepositoryResponse::class.java).repositories
                }
            repositories += entries.map { it.full_name }
            page++
        } while (entries.size == 100)
        return repositories.distinct()
    }

    override fun getDefaultBranch(
        owner: String,
        repo: String,
    ): String {
        log.info { "Get default branch owner $owner/$repo" }
        val request = authenticatedRequest("https://api.github.com/repos/$owner/$repo")

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to get repo info: $body")
            }

            val parsed = gson.fromJson(body, RepoResponse::class.java)
            return parsed.default_branch
        }
    }

    override fun getFileSha(
        owner: String,
        repo: String,
        path: String,
        branch: String,
    ): String {
        log.info { "Fetching file sha with owner $owner/$repo at $path with branch $branch" }
        val request =
            authenticatedRequest(
                "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch",
            )

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to get file sha: $body")
            }

            val parsed = gson.fromJson(body, GithubContentResponse::class.java)
            return parsed.sha
        }
    }

    override fun getBranchHeadSha(
        owner: String,
        repo: String,
        branch: String,
    ): String {
        log.info(
            "Fetching branch head sha owner $owner/$repo branch $branch",
        )

        val request =
            authenticatedRequest(
                "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch",
            )

        httpClient.newCall(request).execute().use { response ->

            val body = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to fetch branch head sha: $body")
            }

            return gson
                .fromJson(body, GitRefResponse::class.java)
                .gitObject
                .sha
        }
    }

    override fun createBranch(
        owner: String,
        repo: String,
        branch: String,
        sha: String,
    ) {
        log.info { "Create Branch with owner $owner/$repo at with branch $branch, sha $sha" }

        val payload =
            mapOf(
                "ref" to "refs/heads/$branch",
                "sha" to sha,
            )

        val request =
            authenticatedRequest("https://api.github.com/repos/$owner/$repo/git/refs")
                .newBuilder()
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to create branch: $body")
            }
        }
    }

    override fun updateFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        sha: String,
        branch: String,
        message: String,
    ) {
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(content.toByteArray(Charsets.UTF_8))

        val payload =
            mapOf(
                "message" to message,
                "content" to encoded,
                "branch" to branch,
            ) + if (sha.isBlank()) emptyMap() else mapOf("sha" to sha)

        val request =
            authenticatedRequest("https://api.github.com/repos/$owner/$repo/contents/$path")
                .newBuilder()
                .put(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to update file: $body")
            }
        }
    }

    override fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        body: String,
        head: String,
        base: String,
    ): String {
        val payload =
            mapOf(
                "title" to title,
                "body" to body,
                "head" to head,
                "base" to base,
            )

        val request =
            authenticatedRequest("https://api.github.com/repos/$owner/$repo/pulls")
                .newBuilder()
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()

            if (!response.isSuccessful) {
                error("Failed to create PR: $bodyStr")
            }

            val parsed = gson.fromJson(bodyStr, PullRequestResponse::class.java)
            return parsed.html_url
        }
    }

    override fun getPullRequest(
        owner: String,
        repo: String,
        number: Int,
    ): GithubPullRequest {
        val request = authenticatedRequest("https://api.github.com/repos/$owner/$repo/pulls/$number")
        return httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not read pull request #$number: HTTP ${response.code}" }
            parsePullRequest(JsonParser.parseString(response.body.string()).asJsonObject)
        }
    }

    override fun listOpenPullRequests(
        owner: String,
        repo: String,
        base: String,
    ): List<GithubPullRequest> {
        val results = mutableListOf<GithubPullRequest>()
        var page = 1
        do {
            val url =
                "https://api.github.com/repos/$owner/$repo/pulls"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("state", "open")
                    .addQueryParameter("base", base)
                    .addQueryParameter("sort", "created")
                    .addQueryParameter("direction", "asc")
                    .addQueryParameter("per_page", "100")
                    .addQueryParameter("page", page.toString())
                    .build()
            val entries =
                httpClient.newCall(authenticatedRequest(url.toString())).execute().use { response ->
                    check(response.isSuccessful) { "Could not list open pull requests: HTTP ${response.code}" }
                    JsonParser.parseString(response.body.string()).asJsonArray.map { parsePullRequest(it.asJsonObject) }
                }
            results += entries
            page++
        } while (entries.size == 100)
        return results.distinctBy { it.number }
    }

    override fun commentOnPullRequest(
        owner: String,
        repo: String,
        number: Int,
        body: String,
    ) {
        val request =
            authenticatedRequest("https://api.github.com/repos/$owner/$repo/issues/$number/comments")
                .newBuilder()
                .post(gson.toJson(mapOf("body" to body)).toRequestBody("application/json".toMediaType()))
                .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not comment on superseded pull request #$number: HTTP ${response.code}" }
        }
    }

    override fun closePullRequest(
        owner: String,
        repo: String,
        number: Int,
    ) {
        val request =
            authenticatedRequest("https://api.github.com/repos/$owner/$repo/pulls/$number")
                .newBuilder()
                .patch("""{"state":"closed"}""".toRequestBody("application/json".toMediaType()))
                .build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not close superseded pull request #$number: HTTP ${response.code}" }
            val result = JsonParser.parseString(response.body.string()).asJsonObject
            check(
                result["state"]?.takeIf { it.isJsonPrimitive }?.asString == "closed" &&
                    result["number"]?.takeIf { it.isJsonPrimitive }?.asInt == number,
            ) { "GitHub did not confirm closure of pull request #$number" }
        }
    }

    private fun parsePullRequest(json: JsonObject): GithubPullRequest {
        fun objectValue(
            parent: JsonObject,
            key: String,
        ): JsonObject {
            val value = checkNotNull(parent[key])
            check(value.isJsonObject) { "Invalid pull request object: $key" }
            return value.asJsonObject
        }

        fun string(
            objectValue: JsonObject,
            key: String,
        ): String {
            val value = checkNotNull(objectValue[key])
            check(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid pull request field: $key" }
            return value.asString
        }

        fun positiveNumber(
            parent: JsonObject,
            key: String,
        ): Long {
            val value = checkNotNull(parent[key])
            check(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid pull request number: $key" }
            val number = checkNotNull(value.asString.toLongOrNull())
            check(number > 0) { "Invalid pull request identity: $key" }
            return number
        }
        val author = objectValue(json, "user")
        val head = objectValue(json, "head")
        val base = objectValue(json, "base")
        val mergedAt = checkNotNull(json["merged_at"])
        val number = positiveNumber(json, "number")
        check(number <= Int.MAX_VALUE) { "Pull request number exceeds supported range" }
        return GithubPullRequest(
            number.toInt(),
            string(json, "html_url"),
            positiveNumber(author, "id"),
            string(author, "type"),
            string(head, "ref"),
            head["repo"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { string(it, "full_name") },
            string(base, "ref"),
            string(objectValue(base, "repo"), "full_name"),
            string(json, "state"),
            !mergedAt.isJsonNull,
            json["body"]?.takeUnless { it.isJsonNull }?.asString,
        )
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

            val body = response.body.string()

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

    override fun resolveBranchName(
        owner: String,
        repo: String,
        baseName: String,
    ): String {
        fun exists(branch: String): Boolean {
            val url =
                "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch"

            val request = authenticatedRequest(url)

            httpClient.newCall(request).execute().use { response ->
                return response.isSuccessful
            }
        }

        var candidate = baseName
        var counter = 2

        while (exists(candidate)) {
            candidate = "$baseName-$counter"
            counter++
        }

        return candidate
    }

    private data class GithubContentResponse(
        val content: String,
        val sha: String,
    )

    private data class RepositoryResponse(
        val repositories: List<Repository>,
    )

    private data class Repository(
        val full_name: String,
    )

    private data class GitRefResponse(
        @SerializedName("object")
        val gitObject: GitObject,
    )

    private data class GitObject(
        val sha: String,
    )
}
