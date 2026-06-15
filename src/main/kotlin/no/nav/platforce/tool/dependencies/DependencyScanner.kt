package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.github.GithubClient
import java.time.Instant

class DependencyScanner(
    private val githubClient: GithubClient,
    private val targetVersionStore: TargetVersionsStore,
) {
    private val parser = GradleDependencyParser()

    fun scanAllRepositoriesWithProgress(cache: DependencyScanCache): List<RepositoryDependencyScan> {
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
            scanRepository(repo)?.let {
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

    private fun scanRepository(repository: String): RepositoryDependencyScan? {
        val owner = repository.substringBefore("/")
        val repo = repository.substringAfter("/")

        val buildFile =
            tryGetBuildFile(owner, repo)
                ?: return null

        val parsed = parser.parse(buildFile)

        val findings = mutableListOf<DependencyFinding>()

        val store = targetVersionStore.get()

        store.plugins.forEach { (plugin, target) ->
            val current = parsed.plugins[plugin] ?: return@forEach

            findings +=
                DependencyFinding(
                    kind = DependencyKind.PLUGIN,
                    key = plugin,
                    currentVersion = current,
                    targetVersion = target,
                    status =
                        when {
                            VersionComparator.compare(current, target) < 0 ->
                                DependencyStatus.UPDATE

                            VersionComparator.compare(current, target) > 0 ->
                                DependencyStatus.AHEAD

                            else ->
                                DependencyStatus.OK
                        },
                )
        }

        store.dependencies.forEach { (dep, target) ->
            val current = parsed.dependencies[dep] ?: return@forEach

            findings +=
                DependencyFinding(
                    kind = DependencyKind.DEPENDENCY,
                    key = dep,
                    currentVersion = current,
                    targetVersion = target,
                    status =
                        when {
                            VersionComparator.compare(current, target) < 0 ->
                                DependencyStatus.UPDATE

                            VersionComparator.compare(current, target) > 0 ->
                                DependencyStatus.AHEAD

                            else ->
                                DependencyStatus.OK
                        },
                )
        }

        return RepositoryDependencyScan(
            repository = repository,
            scannedAt = Instant.now().toString(),
            findings = findings,
        )
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
}
