package no.nav.platforce.tool.pullrequests

import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.targets.TargetVersionsState

class DependencyPullRequestService(
    private val githubClient: GithubClient,
    private val dependencyScanViewService: DependencyScanViewService,
) {
    private val buildFileUpdater = GradleBuildFileUpdater()
    private val wrapperFileUpdater = GradleWrapperUpdater()

    fun createPullRequest(
        owner: String,
        repo: String,
        username: String,
        targetState: TargetVersionsState,
        beforeWrite: () -> Unit = {},
    ): String {
        val fullRepo = "$owner/$repo"
        dependencyScanViewService.requirePullRequestReady(username, targetState, fullRepo)

        val scan =
            dependencyScanViewService
                .get(username, targetState)
                .singleOrNull { it.repository == fullRepo }
                ?: error("No scan found for $fullRepo")

        val actionable =
            scan.findings
                .filter {
                    it.status == DependencyStatus.UPDATE || it.status == DependencyStatus.AHEAD || it.status == DependencyStatus.ADD ||
                        it.status == DependencyStatus.REMOVE
                }

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

        beforeWrite()
        githubClient.createBranch(
            owner,
            repo,
            branchName,
            branchSha,
        )

        if (buildGradleFindings.isNotEmpty() || wrapperFindings.isNotEmpty()) {
            val (filePath, currentContent) =
                runCatching {
                    "build.gradle" to githubClient.getFile(owner, repo, "build.gradle")
                }.getOrElse {
                    "build.gradle.kts" to githubClient.getFile(owner, repo, "build.gradle.kts")
                }
            val updatedContent = buildFileUpdater.apply(currentContent, actionable)

            if (updatedContent != currentContent) {
                val fileSha =
                    githubClient.getFileSha(
                        owner,
                        repo,
                        filePath,
                        branchName,
                    )

                beforeWrite()
                githubClient.updateFile(
                    owner,
                    repo,
                    filePath,
                    updatedContent,
                    fileSha,
                    branchName,
                    "chore: update Gradle dependencies and compatibility",
                )
            }
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

            beforeWrite()
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

        beforeWrite()
        return githubClient.createPullRequest(
            owner,
            repo,
            title = "chore: update Gradle dependencies",
            body =
                buildString {
                    appendLine("Automated dependency update via platforce-tool app")
                    appendLine()

                    actionable.forEach { finding ->
                        when (finding.status) {
                            DependencyStatus.ADD -> {
                                val related = finding.relatedTo.takeIf { it.isNotEmpty() }

                                if (related != null) {
                                    appendLine(
                                        "- ADD ${finding.key}:${finding.targetVersion} " +
                                            "(transitive dependency override for " +
                                            "${related.joinToString { "${it.key}:${it.version}" }})",
                                    )
                                } else {
                                    appendLine(
                                        "- ADD ${finding.key}:${finding.targetVersion} " +
                                            "(transitive dependency override)",
                                    )
                                }
                            }

                            DependencyStatus.REMOVE -> {
                                appendLine("- REMOVE ${finding.key}:${finding.targetVersion} (deprecated transitive override)")
                            }

                            else -> {
                                appendLine(
                                    "- ${finding.kind} ${finding.key}: " +
                                        "${finding.currentVersion} → ${finding.targetVersion}",
                                )
                            }
                        }
                    }
                },
            head = branchName,
            base = baseBranch,
        )
    }
}
