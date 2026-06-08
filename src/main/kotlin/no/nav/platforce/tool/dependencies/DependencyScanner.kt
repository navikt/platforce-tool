package no.nav.platforce.tool.dependencies

import no.nav.platforce.tool.github.GithubClient
import java.time.Instant

class DependencyScanner(
    private val githubClient: GithubClient,
) {
    private val parser = GradleDependencyParser()

    fun scanAllRepositories(): List<RepositoryDependencyScan> =
        githubClient
            .listRepositories()
            .mapNotNull(::scanRepository)

    private fun scanRepository(repository: String): RepositoryDependencyScan? {
        val owner = repository.substringBefore("/")
        val repo = repository.substringAfter("/")

        val buildFile =
            tryGetBuildFile(owner, repo)
                ?: return null

        val parsed = parser.parse(buildFile)

        val findings = mutableListOf<DependencyFinding>()

        TargetVersions.plugins.forEach { (plugin, target) ->
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

        TargetVersions.dependencies.forEach { (dep, target) ->
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
            scannedAt = Instant.now(),
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
