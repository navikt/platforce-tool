package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.github.GithubClient

class DependencyPullRequestService(
    private val githubClient: GithubClient,
    private val cache: DependencyScanCache,
) {
    private val updater = GradleBuildFileUpdater()

    fun createPullRequest(
        owner: String,
        repo: String,
    ): String {
        val fullRepo = "$owner/$repo"

        val scan =
            cache
                .get()
                .singleOrNull { it.repository == fullRepo }
                ?: error("No scan found for $fullRepo")

        val actionable =
            scan.findings
                .filter { it.status != DependencyStatus.OK }

        if (actionable.isEmpty()) {
            return "No changes required"
        }

        val baseBranch = githubClient.getDefaultBranch(owner, repo)
        val branchName = "chore/update-gradle-dependencies"

        val filePath = "build.gradle"

        val currentContent = githubClient.getFile(owner, repo, filePath)

        val updatedContent = updater.apply(currentContent, actionable)

        val sha = githubClient.getFileSha(owner, repo, filePath, baseBranch)

        githubClient.createBranch(
            owner,
            repo,
            branchName,
            sha,
        )

        githubClient.updateFile(
            owner,
            repo,
            filePath,
            updatedContent,
            sha,
            branchName,
            "chore: update Gradle dependencies",
        )

        return githubClient.createPullRequest(
            owner,
            repo,
            title = "chore: update Gradle dependencies",
            body =
                buildString {
                    appendLine("Automated dependency update via platforce-tool app")
                    appendLine()
                    actionable.forEach {
                        appendLine("- ${it.kind} ${it.key}: ${it.currentVersion} → ${it.targetVersion}")
                    }
                },
            head = branchName,
            base = baseBranch,
        )
    }
}
