package no.nav.platforce.tool.auto

import no.nav.platforce.tool.targets.AvailableVersions
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.snapshot
import org.apache.maven.artifact.versioning.ComparableVersion

interface AutoOperations {
    fun targets(username: String): TargetVersionsState

    fun available(
        username: String,
        key: String,
        current: String,
    ): AvailableVersions

    fun saveTargets(
        username: String,
        expected: TargetVersionsState,
        updated: TargetVersionsState,
    ): List<String>

    fun scanDependencies(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport

    fun scanBoms(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport

    fun scanPlugins(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport

    fun refreshRepositories(
        username: String,
        state: TargetVersionsState,
        checkActive: () -> Unit,
    ): AutoReport

    fun repositories(): List<String>

    fun createPullRequest(
        username: String,
        state: TargetVersionsState,
        repository: String,
        checkActive: () -> Unit,
    ): AutoReport
}

interface AutoProgress {
    fun checkActive()

    fun step(
        name: String,
        repository: String? = null,
        action: () -> AutoReport,
    )
}

class AutoWorkflow(
    private val operations: AutoOperations,
) {
    fun run(
        username: String,
        progress: AutoProgress,
    ): AutoReport {
        lateinit var original: TargetVersionsState
        lateinit var state: TargetVersionsState
        val changes = mutableListOf<String>()
        progress.step("Draft latest stable non-major versions") {
            original = operations.targets(username).snapshot()
            state = original.snapshot()
            original.dependencies.toSortedMap().filterKeys { it !in original.ignoredDependencies }.forEach { (key, current) ->
                progress.checkActive()
                val next = latestStableNonMajor(operations.available(username, key, current))
                if (next != null) {
                    state.dependencies[key] = next
                    changes += "$key: $current -> $next"
                }
            }
            AutoReport(
                if (changes.isEmpty()) "No stable non-major dependency upgrades found" else "Drafted ${changes.size} dependency upgrades",
                changes.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            )
        }
        if (changes.isNotEmpty()) {
            progress.step("Save Target Versions") {
                progress.checkActive()
                val users = operations.saveTargets(username, original, state)
                AutoReport("Saved Target Versions as $username", "Users in database:\n${users.joinToString("\n")}")
            }
        }
        progress.step("Scan Dependencies") { operations.scanDependencies(username, state, progress::checkActive) }
        progress.step("Scan BOMs") { operations.scanBoms(username, state, progress::checkActive) }
        progress.step("Scan Plugins") { operations.scanPlugins(username, state, progress::checkActive) }
        progress.step("Refresh Dependency Scanner") { operations.refreshRepositories(username, state, progress::checkActive) }
        var created = 0
        val repositories = operations.repositories().distinct().sorted()
        repositories.forEach { repository ->
            progress.step("Check repository and create PR", repository) {
                operations.createPullRequest(username, state, repository, progress::checkActive).also {
                    if (it.url != null) created++
                }
            }
        }
        return AutoReport(
            "Auto process finished normally",
            "${repositories.size} opted-in repositories checked; $created pull requests created.",
        )
    }
}

internal fun latestStableNonMajor(available: AvailableVersions): String? {
    fun major(version: String) =
        Regex("^\\d+")
            .find(version)
            ?.value
            ?.trimStart('0')
            ?.ifEmpty { "0" }
    val major = major(available.currentVersion) ?: return null
    return available.versions
        .filter {
            !it.prerelease && !it.majorUpgrade && major(it.version) == major &&
                ComparableVersion(it.version) > ComparableVersion(available.currentVersion)
        }.maxWithOrNull(compareBy { ComparableVersion(it.version) })
        ?.version
}
