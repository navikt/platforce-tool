package no.nav.platforce.tool.github

data class GithubPullRequest(
    val number: Int,
    val url: String,
    val authorId: Long,
    val authorType: String,
    val headBranch: String,
    val headRepository: String?,
    val baseBranch: String,
    val baseRepository: String,
    val state: String,
    val merged: Boolean,
    val body: String?,
)
