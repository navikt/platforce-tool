package no.nav.platforce.tool.auto

import no.nav.platforce.tool.db.PostgresDatabase
import no.nav.platforce.tool.github.GithubClient
import no.nav.platforce.tool.pullrequests.DependencyPullRequestService
import no.nav.platforce.tool.repositories.DependencyScanCache
import no.nav.platforce.tool.repositories.DependencyScanViewService
import no.nav.platforce.tool.repositories.DependencyScanner
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.resolution.GradleTargetResolutionScanner
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.PluginSecurityScanner
import no.nav.platforce.tool.security.PluginSecurityStatus
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TargetSecurityScanner
import no.nav.platforce.tool.security.TargetSecurityStatus
import no.nav.platforce.tool.targets.AvailableVersionsService
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.TargetVersionsUpdater
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.targets.withoutIgnoredDependencies
import no.nav.platforce.tool.user.UserContextCache
import java.time.Duration
import java.util.Locale

class DefaultAutoOperations(
    private val versions: AvailableVersionsService,
    private val updater: TargetVersionsUpdater,
    private val security: TargetSecurityScanner,
    private val resolution: GradleTargetResolutionScanner,
    private val plugins: PluginSecurityScanner,
    private val github: GithubClient,
    private val scanner: DependencyScanner,
    private val cache: DependencyScanCache,
    private val view: DependencyScanViewService,
    private val pullRequests: DependencyPullRequestService,
    private val scanTimeout: Duration = Duration.ofMinutes(30),
    private val readTargets: (String) -> TargetVersionsState = { UserContextCache.get(it).targetVersionsStore.get(includeIgnored = true) },
    private val writeTargets: (String, TargetVersionsState, TargetVersionsState) -> Unit = { username, expected, updated ->
        updater.save(UserContextCache.get(username), updated, expected)
    },
    private val users: () -> List<String> = PostgresDatabase::getUniqueUsers,
    private val optedIn: () -> Set<String> = PostgresDatabase::getAutoRepositories,
    private val ignoredRepositories: (String) -> List<String> = {
        UserContextCache
            .get(it)
            .ignoredRepositoriesStore
            .get()
            .repositories
    },
) : AutoOperations {
    override fun targets(username: String): TargetVersionsState = readTargets(username)

    override fun available(
        username: String,
        key: String,
        current: String,
    ) = versions.available(username, key, current)

    override fun saveTargets(
        username: String,
        expected: TargetVersionsState,
        updated: TargetVersionsState,
    ): List<String> {
        writeTargets(username, expected, updated)
        return users()
    }

    private fun guard(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ) {
        checkActive()
        if (fingerprint(targets(username)) !=
            fingerprint(state)
        ) {
            throw AutoInterrupted("Saved Target Versions changed during Auto processing")
        }
    }

    private fun waitForScan(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
        status: () -> Pair<SecurityScanStatus, String?>,
    ) {
        val deadline = System.nanoTime() + scanTimeout.toNanos()
        while (true) {
            guard(username, state, checkActive)
            val (current, error) = status()
            when (current) {
                SecurityScanStatus.READY -> {
                    return
                }

                SecurityScanStatus.FAILED -> {
                    throw AutoStopped(AutoReport("Auto stopped: security scan failed; work is unfinished", error))
                }

                SecurityScanStatus.IDLE -> {
                    throw AutoInterrupted("Security scan was invalidated or replaced")
                }

                SecurityScanStatus.RUNNING -> {
                    if (System.nanoTime() >=
                        deadline
                    ) {
                        throw AutoStopped(AutoReport("Auto stopped: security scan timed out; work is unfinished"))
                    }
                    Thread.sleep(1000)
                }
            }
        }
    }

    override fun scanDependencies(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport {
        guard(username, state, checkActive)
        val effective = state.withoutIgnoredDependencies()
        resolution.invalidate(username)
        security.invalidate(username)
        security.start(username, effective)
        waitForScan(username, state, checkActive) { security.get(username, effective).let { it.status to it.error } }
        val result = checkNotNull(security.get(username, effective).result)
        val resolved = checkNotNull(resolution.get(username, effective).result)
        val incomplete =
            effective.dependencies
                .filter { (key, version) ->
                    resolved.individual[key]?.singleOrNull()?.let { it.key == key && it.version == version } != true
                }.keys
                .map { "Incomplete resolution for $it" } +
                flatten(resolved.roots + resolved.individual.values.flatten())
                    .filter {
                        it.group.isNullOrBlank() || it.version.isBlank() || it.version == "<unresolved>" || it.resolutionError != null
                    }.map { "${it.key}:${it.version}: ${it.resolutionError ?: "Incomplete dependency metadata"}" }
        val vulnerable = result.targets.filter { it.status == TargetSecurityStatus.VULNERABLE }
        val removals =
            result.targets.filter { it.status == TargetSecurityStatus.TRANSIENT_UNUSED }.map { target ->
                val replacements = target.replacementDependencies
                "REMOVE ${target.key}:${target.targetVersion}: redundant transient override. " +
                    if (replacements.isEmpty()) {
                        "No active overrides require it; review repository declarations before removing the saved target."
                    } else {
                        "Removable in repositories containing ${replacements.joinToString { "${it.dependency}:${it.targetVersion}" }}."
                    }
            }
        if (incomplete.isNotEmpty() || vulnerable.isNotEmpty() ||
            result.targets.associate { it.key to it.targetVersion } != effective.dependencies
        ) {
            throw AutoStopped(
                AutoReport(
                    "Auto stopped: dependency vulnerabilities or incomplete checks; work is unfinished",
                    (
                        incomplete +
                            vulnerable.map { target ->
                                "${target.key}:${target.targetVersion}: ${target.vulnerabilities.joinToString { it.id }}"
                            } + removals
                    ).joinToString("\n").ifBlank { "Security results do not cover every saved dependency target." },
                ),
            )
        }
        return AutoReport(
            "Dependency scan finished: ${result.targets.size} targets checked; no unresolved target vulnerabilities",
            removals.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    override fun scanBoms(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport {
        guard(username, state, checkActive)
        val effective = state.withoutIgnoredDependencies()
        security.start(username, effective, includeBoms = true)
        waitForScan(username, state, checkActive) { security.get(username, effective, includeBoms = true).let { it.status to it.error } }
        val result = checkNotNull(security.get(username, effective, includeBoms = true).result)
        if (result.platformBoms.associate { it.key to it.targetVersion } != effective.platformBoms ||
            result.platformBoms.any { it.status == TargetSecurityStatus.VULNERABLE || it.vulnerableDependencies.isNotEmpty() }
        ) {
            throw AutoStopped(
                AutoReport(
                    "Auto stopped: BOM vulnerabilities or incomplete checks; work is unfinished",
                    result.platformBoms
                        .filter { it.status == TargetSecurityStatus.VULNERABLE || it.vulnerableDependencies.isNotEmpty() }
                        .joinToString("\n") { bom ->
                            "${bom.key}:${bom.targetVersion}: VULNERABLE " +
                                bom.vulnerableDependencies.flatMap { it.vulnerabilities }.joinToString { it.id }
                        }.ifBlank { "BOM scan results do not cover every saved BOM target." },
                ),
            )
        }
        return AutoReport(
            "BOM scan finished: ${result.platformBoms.size} saved BOMs checked for coverage",
        )
    }

    override fun scanPlugins(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport {
        guard(username, state, checkActive)
        val effective = state.withoutIgnoredDependencies()
        plugins.start(username, effective)
        waitForScan(username, state, checkActive) { plugins.get(username, effective).let { it.status to it.error } }
        val result = checkNotNull(plugins.get(username, effective).result)
        val issues = result.plugins.filter { it.status != PluginSecurityStatus.OK }
        if (issues.isNotEmpty() || result.plugins.associate { it.key to it.targetVersion } != effective.plugins) {
            throw AutoStopped(
                AutoReport(
                    "Auto stopped: plugin vulnerabilities or incomplete checks; work is unfinished",
                    issues
                        .joinToString("\n") {
                            "${it.key}:${it.targetVersion}: ${it.status} ${it.error ?: it.vulnerabilities.joinToString { vulnerability ->
                                vulnerability.id
                            }}"
                        }.ifBlank { "Security results do not cover every saved plugin target." },
                ),
            )
        }
        return AutoReport(
            "Plugin scan finished: ${result.plugins.size} plugins checked",
        )
    }

    override fun repositories(): List<String> {
        val installed = github.listRepositories().map { it.lowercase(Locale.ROOT) }.toSet()
        return optedIn().filter { it in installed }
    }

    override fun createPullRequest(
        username: String,
        state: TargetVersionsState,
        repository: String,
        checkActive: () -> Unit,
    ): AutoReport {
        guard(username, state, checkActive)
        val ignored = ignoredRepositories(username)
        if (ignored.any { it.equals(repository, ignoreCase = true) }) return AutoReport("Skipped: repository is ignored by $username")
        if (repository !in repositories()) return AutoReport("Skipped: Auto opt-in or GitHub App access was removed")
        val effective = state.withoutIgnoredDependencies()
        return cache.withRefreshLock(username) {
            val inventory =
                scanner.scanRepository(repository)
                    ?: throw AutoStopped(AutoReport("Auto stopped: repository could not be scanned; work is unfinished", repository))
            cache.updateRepository(username, inventory)
            guard(username, state, checkActive)
            view.requirePullRequestReady(username, effective, repository)
            val scan = view.get(username, effective).single { it.repository == repository }
            val actionable =
                scan.findings.filter {
                    it.status in
                        setOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD, DependencyStatus.ADD, DependencyStatus.REMOVE)
                }
            if (actionable.isEmpty()) {
                AutoReport("No changes required; Create PR is disabled")
            } else {
                guard(username, state, checkActive)
                if (repository !in repositories()) {
                    AutoReport("Skipped: Auto opt-in or GitHub App access was removed")
                } else {
                    val url =
                        pullRequests.createPullRequest(
                            repository.substringBefore("/"),
                            repository.substringAfter("/"),
                            username,
                            effective,
                            beforeWrite = {
                                guard(username, state, checkActive)
                                if (repository !in optedIn()) throw AutoInterrupted("Repository opted out while creating its PR")
                            },
                        )
                    check(
                        url.startsWith("https://github.com/") && "/pull/" in url,
                    ) { "Pull request creation did not return a GitHub PR link: $url" }
                    AutoReport(
                        "Pull request created",
                        actionable.joinToString("\n") {
                            "${it.status} ${it.key}: ${it.currentVersion} -> ${it.targetVersion}"
                        },
                        url,
                    )
                }
            }
        }
    }

    private fun flatten(nodes: List<ResolvedDependency>): List<ResolvedDependency> = nodes.flatMap { listOf(it) + flatten(it.dependencies) }
}
