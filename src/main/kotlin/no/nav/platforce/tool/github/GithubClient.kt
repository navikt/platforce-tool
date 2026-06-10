package no.nav.platforce.tool.github

interface GithubClient {
    fun getFile(
        owner: String,
        repo: String,
        path: String,
    ): String

    fun listRepositories(): List<String>

    fun getDefaultBranch(
        owner: String,
        repo: String,
    ): String

    fun getFileSha(
        owner: String,
        repo: String,
        path: String,
        branch: String,
    ): String

    fun getBranchHeadSha(
        owner: String,
        repo: String,
        branch: String,
    ): String

    fun createBranch(
        owner: String,
        repo: String,
        branch: String,
        sha: String,
    )

    fun updateFile(
        owner: String,
        repo: String,
        path: String,
        content: String,
        sha: String,
        branch: String,
        message: String,
    )

    fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        body: String,
        head: String,
        base: String,
    ): String

    fun resolveBranchName(
        owner: String,
        repo: String,
        baseName: String,
    ): String
}
