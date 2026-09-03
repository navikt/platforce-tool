package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.github.GithubClient

class DependencyPullRequestService(
    private val githubClient: GithubClient,
    private val cache: DependencyScanCache,
) {
    private val buildFileUpdater = GradleBuildFileUpdater()
    private val wrapperFileUpdater = GradleWrapperUpdater()

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
                .filter { it.status == DependencyStatus.UPDATE || it.status == DependencyStatus.AHEAD || it.status == DependencyStatus.ADD }

        if (actionable.isEmpty()) {
            return "No changes required "
        }

        val buildGradleFindings =
            actionable.filter { it.kind != DependencyKind.GRADLE }

        val wrapperFindings =
            actionable.filter { it.kind == DependencyKind.GRADLE }

        val baseBranch = githubClient.getDefaultBranch(owner, repo)
        val baseBranchName =
            "chore/update-dependencies-${java.time.LocalDate.now()}"

        val branchName =
            githubClient.resolveBranchName(owner, repo, baseBranchName)

        val branchSha =
            githubClient.getBranchHeadSha(
                owner,
                repo,
                baseBranch,
            )

        githubClient.createBranch(
            owner,
            repo,
            branchName,
            branchSha,
        )

        if (buildGradleFindings.isNotEmpty()) {
            val filePath = "build.gradle"
            val currentContent = githubClient.getFile(owner, repo, filePath)
            val updatedContent = buildFileUpdater.apply(currentContent, actionable)

            val fileSha =
                githubClient.getFileSha(
                    owner,
                    repo,
                    filePath,
                    branchName,
                )

            githubClient.updateFile(
                owner,
                repo,
                filePath,
                updatedContent,
                fileSha,
                branchName,
                "chore: update Gradle dependencies",
            )
        }

        if (wrapperFindings.isNotEmpty()) {
            val filePathWrapper = "gradle/wrapper/gradle-wrapper.properties"
            val currentContentWrapper = githubClient.getFile(owner, repo, filePathWrapper)
            val updatedContentWrapper = wrapperFileUpdater.apply(currentContentWrapper, actionable)

            val wrapperSha =
                githubClient.getFileSha(
                    owner,
                    repo,
                    filePathWrapper,
                    branchName,
                )

            githubClient.updateFile(
                owner,
                repo,
                filePathWrapper,
                updatedContentWrapper,
                wrapperSha,
                branchName,
                "chore: update Gradle version",
            )
        }

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
