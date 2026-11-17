package no.nav.platforce.tool.pullrequests

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import mu.KotlinLogging
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.github.GithubPullRequest
import no.nav.platforce.tool.node.NpmLockfileGenerator
import no.nav.platforce.tool.node.nodeDependencySections
import no.nav.platforce.tool.node.nodeTargetVersion
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException
import no.nav.platforce.tool.repositories.RepositoryProjectType
import no.nav.platforce.tool.targets.TargetVersionsState
import java.io.IOException

private const val DEPENDENCY_PR_MARKER = "Automated dependency update via platforce-tool app"

class DependencyPullRequestService(
    private val githubClient: GithubClient,
    private val dependencyScanViewService: DependencyScanViewService,
    private val regenerateNodeLockfile: (String, String, String, String) -> String = NpmLockfileGenerator()::generate,
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
        val isNode = scan.projectType == RepositoryProjectType.NODE
        val checkBeforeWrite = {
            if (isNode) dependencyScanViewService.requirePullRequestReady(username, targetState, fullRepo)
            beforeWrite()
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

        val nodeFiles = if (isNode) prepareNodeFiles(owner, repo, branchSha, actionable) else emptyList()

        checkBeforeWrite()
        githubClient.createBranch(
            owner,
            repo,
            branchName,
            branchSha,
        )

        if (isNode) {
            nodeFiles.forEach { file ->
                checkBeforeWrite()
                githubClient.updateFile(
                    owner,
                    repo,
                    file.path,
                    file.content,
                    file.sha,
                    branchName,
                    "chore: update Node dependencies",
                )
            }
        } else if (buildGradleFindings.isNotEmpty() || wrapperFindings.isNotEmpty()) {
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

                checkBeforeWrite()
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

            checkBeforeWrite()
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

        checkBeforeWrite()
        val url =
            githubClient.createPullRequest(
                owner,
                repo,
                title = if (isNode) "chore: update Node dependencies" else "chore: update Gradle dependencies",
                body =
                    buildString {
                        appendLine(DEPENDENCY_PR_MARKER)
                        appendLine()

                        actionable.forEach { finding ->
                            val targetVersion = if (isNode) "^${nodeTargetVersion(finding.targetVersion)}" else finding.targetVersion
                            val kind = if (finding.kind == DependencyKind.NODE) DependencyKind.DEPENDENCY else finding.kind
                            when (finding.status) {
                                DependencyStatus.ADD -> {
                                    val related = finding.relatedTo.takeIf { it.isNotEmpty() }
                                    val description =
                                        if (isNode && finding.declaration in nodeDependencySections) {
                                            "required dependency"
                                        } else {
                                            "transitive dependency override"
                                        }

                                    if (related != null) {
                                        appendLine(
                                            "- ADD ${finding.key}:$targetVersion " +
                                                "($description for " +
                                                "${related.joinToString { "${it.key}:${it.version}" }})",
                                        )
                                    } else {
                                        appendLine(
                                            "- ADD ${finding.key}:$targetVersion " +
                                                "($description)",
                                        )
                                    }
                                }

                                DependencyStatus.REMOVE -> {
                                    appendLine(
                                        "- REMOVE ${finding.key}:$targetVersion (${if (isNode) "redundant" else "deprecated"} transitive override)",
                                    )
                                }

                                else -> {
                                    appendLine(
                                        "- $kind ${finding.key}: " +
                                            "${finding.currentVersion} → $targetVersion",
                                    )
                                }
                            }
                        }
                    },
                head = branchName,
                base = baseBranch,
            )
        closeSuperseded(owner, repo, url, branchName, baseBranch, checkBeforeWrite, onSuperseded)
        return url
    }

    private data class FileUpdate(
        val path: String,
        val content: String,
        val sha: String,
    )

    private fun prepareNodeFiles(
        owner: String,
        repo: String,
        ref: String,
        findings: List<DependencyFinding>,
    ): List<FileUpdate> {
        try {
            val source =
                githubClient.getFileAtRef(owner, repo, "package.json", ref)
                    ?: throw PullRequestReadinessException("package.json is missing. Refresh the repository and try again.")
            val manifest = JsonParser.parseString(source.content).asJsonObject
            check(!manifest.has("workspaces")) { "Node workspace pull requests are not supported" }
            val manager = manifest["packageManager"]?.asString?.substringBefore('@')
            check(manager == null || manager == "npm") { "Node pull requests currently support npm repositories only" }
            listOf("yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb").forEach { path ->
                check(githubClient.getFileAtRef(owner, repo, path, ref) == null) {
                    "Cannot update $path with npm. Node pull requests currently support npm lockfiles only."
                }
            }
            val locks =
                listOf("package-lock.json", "npm-shrinkwrap.json").mapNotNull { path ->
                    githubClient.getFileAtRef(owner, repo, path, ref)?.let { path to it }
                }
            check(
                locks.size <= 1,
            ) { "Both npm lockfiles are present. Keep package-lock.json or npm-shrinkwrap.json before creating a pull request." }
            val update = NodePackageJsonUpdater().apply(source.content, findings)
            check(update.manifest != source.content) { "No package.json changes remain. Refresh the repository and try again." }
            return listOf(FileUpdate("package.json", update.manifest, source.sha)) +
                locks.map { (path, file) ->
                    FileUpdate(
                        path,
                        regenerateNodeLockfile(update.manifest, update.resolutionManifest, file.content, path),
                        file.sha,
                    )
                }
        } catch (failure: IllegalArgumentException) {
            throw PullRequestReadinessException("Cannot prepare Node pull request: ${failure.message}", failure)
        } catch (failure: IllegalStateException) {
            throw PullRequestReadinessException("Cannot prepare Node pull request: ${failure.message}", failure)
        } catch (failure: IOException) {
            throw PullRequestReadinessException("Cannot prepare Node pull request: ${failure.message}", failure)
        } catch (failure: JsonParseException) {
            throw PullRequestReadinessException("Cannot prepare Node pull request: ${failure.message}", failure)
        }
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
