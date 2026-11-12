package no.nav.platforce.tool.pullrequests

import com.google.gson.JsonParseException
import mu.KotlinLogging
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.targets.TargetVersionsState
import java.io.IOException

private const val DEPENDENCY_PR_MARKER = "Automated dependency update via platforce-tool app"

class DependencyPullRequestService(
    private val githubClient: GithubClient,
    private val dependencyScanViewService: DependencyScanViewService,
) {
    private val buildFileUpdater = GradleBuildFileUpdater()
    private val wrapperFileUpdater = GradleWrapperUpdater()
    private val log = KotlinLogging.logger { }

    fun createPullRequest(
        owner: String,
        repo: String,
        username: String,
        targetState: TargetVersionsState,
        beforeWrite: () -> Unit = {},
        onSuperseded: (String) -> Unit = {},
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
        val url =
            githubClient.createPullRequest(
                owner,
                repo,
                title = "chore: update Gradle dependencies",
                body =
                    buildString {
                        appendLine(DEPENDENCY_PR_MARKER)
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
        closeSuperseded(owner, repo, url, branchName, baseBranch, beforeWrite, onSuperseded)
        return url
    }

    private fun closeSuperseded(
        owner: String,
        repo: String,
        replacementUrl: String,
        head: String,
        base: String,
        beforeWrite: () -> Unit,
        onSuperseded: (String) -> Unit,
    ) {
        val closed = mutableListOf<String>()

        fun failed(cause: Exception): Nothing {
            val failure = SupersededPullRequestCleanupException(replacementUrl, closed.toList(), cause)
            log.error(failure) { "Dependency PR created but superseded PR cleanup is unfinished" }
            throw failure
        }
        try {
            val number =
                Regex(
                    "^https://github\\.com/${Regex.escape(owner)}/${Regex.escape(repo)}/pull/([1-9][0-9]*)$",
                    RegexOption.IGNORE_CASE,
                ).matchEntire(replacementUrl)?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("GitHub did not return a valid replacement PR link")
            val replacement = githubClient.getPullRequest(owner, repo, number)
            val repository = "$owner/$repo"
            check(
                replacement.number == number && replacement.authorType == "Bot" && replacement.authorId > 0 &&
                    replacement.state == "open" && !replacement.merged && replacement.headBranch == head &&
                    replacement.baseBranch == base && replacement.baseRepository.equals(repository, ignoreCase = true) &&
                    replacement.headRepository.equals(repository, ignoreCase = true),
            ) { "Replacement PR is no longer an open app-authored PR on the expected branches" }

            fun eligible(pr: GithubPullRequest): Boolean =
                pr.number < replacement.number && pr.state == "open" && !pr.merged &&
                    pr.authorType == "Bot" && pr.authorId == replacement.authorId &&
                    pr.baseBranch == base && pr.baseRepository.equals(repository, ignoreCase = true) &&
                    pr.headRepository.equals(repository, ignoreCase = true) && pr.headBranch != head &&
                    pr.headBranch.matches(Regex("chore/update-dependencies-\\d{4}-\\d{2}-\\d{2}(?:-[1-9]\\d*)?")) &&
                    pr.body?.lineSequence()?.firstOrNull() == DEPENDENCY_PR_MARKER

            githubClient.listOpenPullRequests(owner, repo, base).filter(::eligible).forEach { candidate ->
                val current = githubClient.getPullRequest(owner, repo, candidate.number)
                check(current.number == candidate.number) { "GitHub returned mismatched superseded PR metadata" }
                if (eligible(current)) {
                    beforeWrite()
                    githubClient.commentOnPullRequest(
                        owner,
                        repo,
                        current.number,
                        "Superseded by $replacementUrl. Closing this older automated dependency update.",
                    )
                    beforeWrite()
                    githubClient.closePullRequest(owner, repo, current.number)
                    closed += current.url
                    onSuperseded(current.url)
                    log.info { "Closed superseded dependency PR ${current.url}; replacement: $replacementUrl" }
                }
            }
        } catch (failure: IOException) {
            failed(failure)
        } catch (failure: IllegalStateException) {
            failed(failure)
        } catch (failure: IllegalArgumentException) {
            failed(failure)
        } catch (failure: JsonParseException) {
            failed(failure)
        }
    }
}
