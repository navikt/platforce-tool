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

        DependencyInventoryCache.put(
            RepositoryDependencyInventory(
                repository = repository,
                scannedAt = Instant.now().toString(),
                plugins =
                    parsed.plugins.map {
                        ConfiguredDependency(
                            key = it.key,
                            version = it.value,
                        )
                    },
                dependencies =
                    parsed.dependencies.map {
                        ConfiguredDependency(
                            key = it.key,
                            version = it.value,
                        )
                    },
            ),
        )

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

        val trackedDependencies = store.dependencies.keys
        val trackedPlugins = store.plugins.keys

        val untrackedDependencies =
            parsed.dependencies
                .filterKeys { it !in trackedDependencies }
                .map { (key, version) ->
                    UntrackedDependency(
                        key = key,
                        version = version,
                    )
                }

        val untrackedPlugins =
            parsed.plugins
                .filterKeys { it !in trackedPlugins }
                .map { (key, version) ->
                    UntrackedPlugin(
                        key = key,
                        version = version,
                    )
                }

        return RepositoryDependencyScan(
            repository = repository,
            scannedAt = Instant.now().toString(),
            findings = findings,
            untrackedDependencies = untrackedDependencies,
            untrackedPlugins = untrackedPlugins,
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
