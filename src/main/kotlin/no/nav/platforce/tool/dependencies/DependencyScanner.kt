package no.nav.platforce.tool.dependencies

import mu.KotlinLogging
import no.nav.platforce.tool.TargetSecurityStatus
import no.nav.platforce.tool.github.GithubClient
import java.time.Instant

class DependencyScanner(
    private val githubClient: GithubClient,
) {
    private val gradleDependencyParser = GradleDependencyParser()
    private val gradleWrapperParser = GradleWrapperParser()

    val log = KotlinLogging.logger { }

    fun scanAllRepositoriesWithProgress(
        cache: DependencyScanCache,
        username: String,
    ): List<RepositoryDependencyInventory> {
        val scanStart = System.nanoTime()

        cache.setProgress(
            username,
            ScanProgress(
                total = 0,
                done = 0,
                running = true,
            ),
        )

        try {
            val start = System.nanoTime()

            val repos = githubClient.listRepositories()

            val durationMsListing =
                (System.nanoTime() - start) / 1_000_000

            log.info {
                "Repository listing complete: " +
                    "duration=${durationMsListing}ms"
            }

            cache.setProgress(
                username,
                ScanProgress(
                    total = repos.size,
                    done = 0,
                    running = true,
                ),
            )

            val results = mutableListOf<RepositoryDependencyInventory>()

            repos.forEachIndexed { index, repo ->
                val start = System.nanoTime()

                scanRepository(repo)?.let {
                    results += it
                }

                val durationMs =
                    (System.nanoTime() - start) / 1_000_000

                log.info {
                    "Repository scan complete: " +
                        "repo=$repo, " +
                        "duration=${durationMs}ms"
                }

                cache.setProgress(
                    username,
                    cache.getProgress(username).copy(
                        done = index + 1,
                    ),
                )
            }

            val durationMs =
                (System.nanoTime() - scanStart) / 1_000_000

            log.info {
                "Repository dependency scan complete (FULL): " +
                    "repos=${repos.size}, " +
                    "results=${results.size}, " +
                    "duration=${durationMs}ms"
            }

            return results
        } catch (e: Exception) {
            log.error(e) { "Repository refresh failed" }
            cache.setProgress(username, cache.getProgress(username).copy(error = e.message ?: e.javaClass.simpleName))
            throw e
        } finally {
            cache.setProgress(username, cache.getProgress(username).copy(running = false))
        }
    }

    fun scanRepository(repository: String): RepositoryDependencyInventory? {
        val owner = repository.substringBefore("/")
        val repo = repository.substringAfter("/")

        val wrapperFile = tryGetWrapperFile(owner, repo)

        val parsedWrapper = gradleWrapperParser.parse(wrapperFile ?: "")

        val buildFile = tryGetBuildFile(owner, repo) ?: return null

        val parsedBuildFile = gradleDependencyParser.parse(buildFile)

        return RepositoryDependencyInventory(
            repository = repository,
            scannedAt = Instant.now().toString(),
            plugins = parsedBuildFile.plugins,
            dependencies = parsedBuildFile.dependencies,
            gradleVersion = parsedWrapper,
        )
    }

    fun compare(
        inventory: RepositoryDependencyInventory,
        targetState: TargetVersionsState,
    ): RepositoryDependencyScan {
        val findings = mutableListOf<DependencyFinding>()
        val store = targetState

        store.plugins.forEach { (plugin, target) ->
            val current = inventory.plugins[plugin] ?: return@forEach

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
            val current = inventory.dependencies[dep] ?: return@forEach

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
            val current = inventory.gradleVersion ?: ""

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
            inventory.dependencies
                .filterKeys { it !in trackedDependencies }
                .map { (key, version) ->
                    UntrackedDependency(
                        key = key,
                        version = version,
                    )
                }

        val untrackedPlugins =
            inventory.plugins
                .filterKeys { it !in trackedPlugins }
                .map { (key, version) ->
                    UntrackedPlugin(
                        key = key,
                        version = version,
                    )
                }

        return RepositoryDependencyScan(
            repository = inventory.repository,
            scannedAt = inventory.scannedAt,
            findings = findings,
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
                .filter {
                    it.kind == DependencyKind.DEPENDENCY
                }.map { it.key }
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

            if (targetResult.status == TargetSecurityStatus.OK_OVERRIDDEN) {
                val relatedTo = targetResult.relatedTo

                val missingOverrides =
                    relatedTo.filter {
                        it.dependency !in presentDependencies
                    }

                enriched +=
                    finding.copy(
                        status =
                            if (comparison < 0) {
                                DependencyStatus.UPDATE
                            } else if (missingOverrides.isNotEmpty()) {
                                DependencyStatus.OK_WITH_ADD
                            } else {
                                DependencyStatus.OK_OVERRIDDEN
                            },
                        relatedTo =
                            relatedTo.map {
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

                return@forEach
            }

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
            when (targetResult.status) {
                TargetSecurityStatus.OK -> {
                    enriched += finding
                }

                TargetSecurityStatus.OK_TRANSIENT -> {
                    enriched +=
                        finding.copy(
                            status = DependencyStatus.OK_TRANSIENT,
                            relatedTo =
                                targetResult.transientUsage.map {
                                    DependencyReference(
                                        kind = DependencyKind.DEPENDENCY,
                                        key = it.dependency,
                                        version = it.targetVersion,
                                    )
                                },
                        )
                }

                TargetSecurityStatus.TRANSIENT_UNUSED -> {
                    enriched +=
                        finding.copy(
                            status = DependencyStatus.REMOVE,
                        )
                }

                TargetSecurityStatus.VULNERABLE -> {
                    enriched +=
                        finding.copy(
                            status = DependencyStatus.VULNERABLE,
                        )
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
