package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.main
import java.io.IOException

class RecordingGithubClient(
    private val files: Map<String, String> = emptyMap(),
) : GithubClient {
    val calls = mutableListOf<String>()
    val updatedFiles = mutableMapOf<String, String>()
    var pullRequestBody: String? = null
    var pullRequestTitle: String? = null
    var requestedBranchName: String? = null
    val pullRequests = mutableMapOf<Int, GithubPullRequest>()
    val pullRequestComments = mutableMapOf<Int, String>()
    var creationFailure: IOException? = null
    var failClosing = emptySet<Int>()
    var onPullRequestRead: (Int) -> Unit = {}

    override fun listWorkflowFiles(
        owner: String,
        repo: String,
    ): Set<String> =
        files.keys
            .filter { it.startsWith(".github/workflows/") }
            .map { it.substringAfterLast("/") }
            .toSet()

    override fun fileExists(
        owner: String,
        repo: String,
        path: String,
    ): Boolean {
        calls += "fileExists:$owner/$repo:$path"
        return path in files
    }

    override fun getFile(
        owner: String,
        repo: String,
        path: String,
    ): String {
        calls += "getFile:$owner/$repo:$path"
        return files[path] ?: error("Unexpected file request: $path")
    }

    override fun listRepositories(): List<String> = error("Unexpected repository listing")

    override fun getDefaultBranch(
        owner: String,
        repo: String,
    ): String {
        calls += "getDefaultBranch:$owner/$repo"
        return "main"
    }

    override fun getFileSha(
        owner: String,
        repo: String,
        path: String,
        branch: String,
    ): String {
        calls += "getFileSha:$owner/$repo:$path:$branch"
        return "file-sha"
    }

    override fun getBranchHeadSha(
        owner: String,
        repo: String,
        branch: String,
    ): String {
        calls += "getBranchHeadSha:$owner/$repo:$branch"
        return "head-sha"
    }

    override fun createBranch(
        owner: String,
        repo: String,
        branch: String,
        sha: String,
    ) {
        calls += "createBranch:$owner/$repo:$branch:$sha"
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
        calls += "updateFile:$owner/$repo:$path:$sha:$branch"
        updatedFiles[path] = content
    }

    override fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        body: String,
        head: String,
        base: String,
    ): String {
        calls += "createPullRequest:$owner/$repo:$head:$base"
        creationFailure?.let { throw it }
        pullRequestBody = body
        pullRequestTitle = title
        val number = (pullRequests.keys.maxOrNull() ?: 0) + 1
        val url = "https://github.com/$owner/$repo/pull/$number"
        pullRequests[number] = GithubPullRequest(number, url, 99L, "Bot", head, "$owner/$repo", base, "$owner/$repo", "open", false, body)
        return url
    }

    override fun getPullRequest(
        owner: String,
        repo: String,
        number: Int,
    ): GithubPullRequest {
        calls += "getPullRequest:$owner/$repo:$number"
        onPullRequestRead(number)
        return pullRequests.getValue(number)
    }

    override fun listOpenPullRequests(
        owner: String,
        repo: String,
        base: String,
    ): List<GithubPullRequest> {
        calls += "listOpenPullRequests:$owner/$repo:$base"
        return pullRequests.values.toList()
    }

    override fun commentOnPullRequest(
        owner: String,
        repo: String,
        number: Int,
        body: String,
    ) {
        calls += "commentOnPullRequest:$owner/$repo:$number"
        pullRequestComments[number] = body
    }

    override fun closePullRequest(
        owner: String,
        repo: String,
        number: Int,
    ) {
        calls += "closePullRequest:$owner/$repo:$number"
        if (number in failClosing) throw IOException("Synthetic PR closure failure")
        pullRequests[number] = pullRequests.getValue(number).copy(state = "closed")
    }

    override fun resolveBranchName(
        owner: String,
        repo: String,
        baseName: String,
    ): String {
        calls += "resolveBranchName:$owner/$repo"
        requestedBranchName = baseName
        return "test-dependency-update"
    }
}
