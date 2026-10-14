package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.github.GithubClient

class RecordingGithubClient(
    private val files: Map<String, String> = emptyMap(),
) : GithubClient {
    val calls = mutableListOf<String>()
    val updatedFiles = mutableMapOf<String, String>()
    var pullRequestBody: String? = null

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
        pullRequestBody = body
        return "https://github.example.test/$owner/$repo/pull/1"
    }

    override fun resolveBranchName(
        owner: String,
        repo: String,
        baseName: String,
    ): String {
        calls += "resolveBranchName:$owner/$repo"
        return "test-dependency-update"
    }
}
