package no.nav.platforce.tool.github

interface GithubClient {
    fun getFile(
        owner: String,
        repo: String,
        path: String,
    ): String

    fun listRepositories(): List<String>
}
