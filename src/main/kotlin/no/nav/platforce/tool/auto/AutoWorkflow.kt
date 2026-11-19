package no.nav.platforce.tool.auto

import no.nav.platforce.tool.node.compareNodeVersions
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
        kind: String = "dependency",
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

    fun scanNode(
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
        val warnings = mutableListOf<String>()
        progress.step("Draft latest stable non-major versions") {
            original = operations.targets(username).snapshot()
            state = original.snapshot()
            for ((kind, targets) in listOf(
                "dependency" to original.dependencies.filterKeys { it !in original.ignoredDependencies },
                "node" to original.nodeDependencies,
            )) {
                targets.toSortedMap().forEach { (key, current) ->
                    progress.checkActive()
                    val available = operations.available(username, key, current, kind)
                    val next = latestStableNonMajor(available, kind = kind)
                    val withoutDateCheck = latestStableNonMajor(available, requireReleaseDate = false, kind = kind)
                    val label = if (kind == "node") "npm:$key" else key
                    if (withoutDateCheck != null && withoutDateCheck != next) {
                        warnings += "Warning: $label $withoutDateCheck skipped because its release date is unknown."
                    }
                    if (next != null) {
                        (if (kind == "node") state.nodeDependencies else state.dependencies)[key] = next
                        changes += "$label: $current -> $next"
                    }
                }
            }
            val summary =
                if (changes.isEmpty()) {
                    "No mature stable non-major dependency upgrades found"
                } else {
                    "Drafted ${changes.size} dependency upgrades"
                }
            AutoReport(
                summary + if (warnings.isNotEmpty()) "; ${warnings.size} release-date warnings" else "",
                (changes + warnings).takeIf { it.isNotEmpty() }?.joinToString("\n"),
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
        progress.step("Scan Node") { operations.scanNode(username, state, progress::checkActive) }
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

internal fun latestStableNonMajor(
    available: AvailableVersions,
    requireReleaseDate: Boolean = true,
    kind: String = "dependency",
): String? {
    fun major(version: String) =
        Regex("^\\d+")
            .find(version)
            ?.value
            ?.trimStart('0')
            ?.ifEmpty { "0" }
    val major = major(available.currentVersion) ?: return null
    val ordering = if (kind == "node") Comparator(::compareNodeVersions) else compareBy<String> { ComparableVersion(it) }
    return available.versions
        .filter {
            !it.prerelease && !it.majorUpgrade && !it.young && (!requireReleaseDate || it.releaseDate != null) &&
                major(it.version) == major &&
                ordering.compare(it.version, available.currentVersion) > 0
        }.maxWithOrNull { first, second -> ordering.compare(first.version, second.version) }
        ?.version
}
