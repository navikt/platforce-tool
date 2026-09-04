package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.TargetSecurityStatus
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.user.UserContext
import java.time.Instant

class DependencyScanner(
    private val githubClient: GithubClient,
    private val targetSecurityScanner: TargetSecurityScanner,
) {
    private val gradleDependencyParser = GradleDependencyParser()
    private val gradleWrapperParser = GradleWrapperParser()

    fun scanAllRepositoriesWithProgress(
        cache: DependencyScanCache,
        userContext: UserContext,
    ): List<RepositoryDependencyScan> {
        cache.setProgress(
            ScanProgress(
                total = 0,
                done = 0,
                running = true,
            ),
        )

        val repos = githubClient.listRepositories()

        cache.setProgress(
            ScanProgress(
                total = repos.size,
                done = 0,
                running = true,
            ),
        )

        val results = mutableListOf<RepositoryDependencyScan>()

        repos.forEachIndexed { index, repo ->
            scanRepository(repo, userContext)?.let {
                results += it
            }

            cache.setProgress(
                cache.getProgress().copy(
                    done = index + 1,
                ),
            )
        }

        cache.setProgress(
            cache.getProgress().copy(
                running = false,
            ),
        )

        return results
    }

    private fun scanRepository(
        repository: String,
        userContext: UserContext,
    ): RepositoryDependencyScan? {
        val owner = repository.substringBefore("/")
        val repo = repository.substringAfter("/")

        val wrapperFile = tryGetWrapperFile(owner, repo)

        val parsedWrapper = gradleWrapperParser.parse(wrapperFile ?: "")

        val buildFile = tryGetBuildFile(owner, repo) ?: return null

        val parsedBuildFile = gradleDependencyParser.parse(buildFile)

        val findings = mutableListOf<DependencyFinding>()

        val store = userContext.targetVersionsStore.get()

        // To apply data from security scan:
        val targetState = userContext.targetVersionsStore.get()
        val securitySnapshot =
            targetSecurityScanner.get(targetState)

        val securityResult =
            targetSecurityScanner
                .get(targetState)
                .takeIf { it.status == SecurityScanStatus.READY }
                ?.result

        store.plugins.forEach { (plugin, target) ->
            val current = parsedBuildFile.plugins[plugin] ?: return@forEach

            findings +=
                DependencyFinding(
                    kind = DependencyKind.PLUGIN,
                    key = plugin,
                    currentVersion = current,
                    targetVersion = target,
                    status = dependencyStatus(current, target),
                )
        }

        store.dependencies.forEach { (dep, target) ->
            val current = parsedBuildFile.dependencies[dep] ?: return@forEach

            findings +=
                DependencyFinding(
                    kind = DependencyKind.DEPENDENCY,
                    key = dep,
                    currentVersion = current,
                    targetVersion = target,
                    status = dependencyStatus(current, target),
                )
        }

        store.gradleVersion.let { target ->
            val current = parsedWrapper ?: ""

            findings +=
                DependencyFinding(
                    kind = DependencyKind.GRADLE,
                    key = "gradle-wrapper",
                    currentVersion = current,
                    targetVersion = target,
                    status = dependencyStatus(current, target),
                )
        }

        val trackedDependencies = store.dependencies.keys
        val trackedPlugins = store.plugins.keys

        val untrackedDependencies =
            parsedBuildFile.dependencies
                .filterKeys { it !in trackedDependencies }
                .map { (key, version) ->
                    UntrackedDependency(
                        key = key,
                        version = version,
                    )
                }

        val untrackedPlugins =
            parsedBuildFile.plugins
                .filterKeys { it !in trackedPlugins }
                .map { (key, version) ->
                    UntrackedPlugin(
                        key = key,
                        version = version,
                    )
                }

        val enrichedFindings =
            if (securityResult != null) {
                enrichSecurityFindings(
                    findings = findings,
                    securityResult = securityResult,
                )
            } else {
                findings
            }

        return RepositoryDependencyScan(
            repository = repository,
            scannedAt = Instant.now().toString(),
            findings = enrichedFindings,
            untrackedDependencies = untrackedDependencies,
            untrackedPlugins = untrackedPlugins,
        )
    }

    fun enrichSecurityScan(
        scan: RepositoryDependencyScan,
        securityResult: TargetSecurityScan,
    ): RepositoryDependencyScan =
        scan.copy(
            findings =
                enrichSecurityFindings(
                    findings = scan.findings,
                    securityResult = securityResult,
                ),
        )

    private fun enrichSecurityFindings(
        findings: List<DependencyFinding>,
        securityResult: TargetSecurityScan,
    ): List<DependencyFinding> {
        val securityByKey =
            securityResult.targets.associateBy { it.key }

        val presentDependencies =
            findings
                .filter { it.kind == DependencyKind.DEPENDENCY }
                .map { it.key }
                .toSet()

        val enriched = mutableListOf<DependencyFinding>()

        findings.forEach { finding ->
            if (finding.kind != DependencyKind.DEPENDENCY) {
                enriched += finding
                return@forEach
            }

            val targetResult = securityByKey[finding.key]

            if (targetResult == null) {
                enriched += finding
                return@forEach
            }

            val current = finding.currentVersion

            if (current == null) {
                enriched += finding
                return@forEach
            }

            val comparison =
                VersionComparator.compare(
                    current,
                    finding.targetVersion,
                )

            // These always win over security status.
            if (comparison < 0) {
                enriched +=
                    finding.copy(
                        status = DependencyStatus.UPDATE,
                    )
                return@forEach
            }

            if (comparison > 0) {
                enriched +=
                    finding.copy(
                        status = DependencyStatus.AHEAD,
                    )
                return@forEach
            }

            // We are exactly on the target version.
            when (targetResult.status) {
                TargetSecurityStatus.OK -> {
                    enriched += finding
                }

                TargetSecurityStatus.OK_TRANSIENT -> {
//                    val relatedTo =
//                        targetResult.overriddenBy
//
//                    val presentOverrides =
//                        relatedTo.filter {
//                            it.dependency in presentDependencies
//                        }

                    enriched +=
                        finding.copy(
                            status = DependencyStatus.OK_TRANSIENT,
//                            relatedTo =
//                                presentOverrides.map {
//                                    DependencyReference(
//                                        kind = DependencyKind.DEPENDENCY,
//                                        key = it.dependency,
//                                        version = it.targetVersion,
//                                    )
//                                },
                        )
                }

                TargetSecurityStatus.TRANSIENT_UNUSED -> {
                    enriched +=
                        finding.copy(
                            status = DependencyStatus.DELETE,
                        )
                }

                TargetSecurityStatus.VULNERABLE -> {
                    enriched +=
                        finding.copy(
                            status = DependencyStatus.VULNERABLE,
                        )
                }

                TargetSecurityStatus.OK_OVERRIDDEN -> {
                    val relatedTo =
                        targetResult.overriddenBy

                    val presentOverrides =
                        relatedTo.filter {
                            it.dependency in presentDependencies
                        }

                    if (presentOverrides.isNotEmpty()) {
                        enriched +=
                            finding.copy(
                                status = DependencyStatus.OK_OVERRIDDEN,
                                relatedTo =
                                    presentOverrides.map {
                                        DependencyReference(
                                            kind = DependencyKind.DEPENDENCY,
                                            key = it.dependency,
                                            version = it.targetVersion,
                                        )
                                    },
                            )
                    } else {
                        // Target is safe in the global target set,
                        // but this repository does not contain the
                        // dependency that makes it safe.
                        val missingOverrides =
                            relatedTo.filter {
                                it.dependency !in presentDependencies
                            }

                        enriched +=
                            finding.copy(
                                status = DependencyStatus.OK_WITH_ADD,
                                relatedTo =
                                    missingOverrides.map {
                                        DependencyReference(
                                            kind = DependencyKind.DEPENDENCY,
                                            key = it.dependency,
                                            version = it.targetVersion,
                                        )
                                    },
                            )

                        missingOverrides.forEach { override ->
                            enriched +=
                                DependencyFinding(
                                    kind = DependencyKind.DEPENDENCY,
                                    key = override.dependency,
                                    currentVersion = null,
                                    targetVersion = override.targetVersion,
                                    status = DependencyStatus.ADD,
                                    relatedTo =
                                        listOf(
                                            DependencyReference(
                                                kind = DependencyKind.DEPENDENCY,
                                                key = finding.key,
                                                version = finding.targetVersion,
                                            ),
                                        ),
                                )
                        }
                    }
                }
            }
        }

        return enriched
    }

    fun dependencyStatus(
        current: String?,
        target: String,
    ): DependencyStatus {
        if (current == null) {
            return DependencyStatus.ADD
        }

        return when {
            VersionComparator.compare(current, target) < 0 ->
                DependencyStatus.UPDATE

            VersionComparator.compare(current, target) > 0 ->
                DependencyStatus.AHEAD

            else ->
                DependencyStatus.OK
        }
    }

    private fun tryGetBuildFile(
        owner: String,
        repo: String,
    ): String? =
        runCatching {
            githubClient.getFile(owner, repo, "build.gradle")
        }.getOrNull()
            ?: runCatching {
                githubClient.getFile(owner, repo, "build.gradle.kts")
            }.getOrNull()

    private fun tryGetWrapperFile(
        owner: String,
        repo: String,
    ): String? =
        runCatching {
            githubClient.getFile(owner, repo, "gradle/wrapper/gradle-wrapper.properties")
        }.getOrNull()
}
