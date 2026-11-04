package no.nav.platforce.tool.targets

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.resolution.unsupportedPluginReason
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.security.VulnerabilityService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

data class UpgradeSecurityResult(
    val version: String,
    val status: String,
    val vulnerabilityCount: Int,
    val error: String? = null,
)

data class UpgradeSecuritySnapshot(
    val status: String,
    val targetFingerprint: String,
    val result: List<UpgradeSecurityResult>? = null,
    val error: String? = null,
)

class UpgradeSecurityScanner(
    private val versions: AvailableVersionsService,
    private val resolve: (String, TargetVersionsState, Map<String, String>) -> TargetResolution,
    private val osv: VulnerabilityService,
    private val executor: ExecutorService = scanExecutor("upgrade-security"),
) {
    private data class Key(
        val kind: String,
        val name: String,
        val currentVersion: String,
    )

    private val jobs = ConcurrentHashMap<String, ConcurrentHashMap<Key, UserScanJobs<List<UpgradeSecurityResult>>>>()

    fun snapshot(
        username: String,
        state: TargetVersionsState,
        kind: String,
        name: String,
        currentVersion: String,
        start: Boolean,
    ): UpgradeSecuritySnapshot {
        require(kind == "plugin" || kind == "dependency") { "Standalone upgrade scanning supports dependencies and plugins" }
        require(name.length <= 300 && name.matches(Regex("""[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?"""))) { "Invalid target name" }
        require(currentVersion.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,199}"""))) { "Invalid current version" }
        val userJobs = jobs.computeIfAbsent(username) { ConcurrentHashMap() }
        val key = Key(kind, name, currentVersion)
        val job =
            if (start) {
                userJobs.computeIfAbsent(key) {
                    UserScanJobs(executor) { user, savedState -> scan(user, savedState, kind, name, currentVersion) }
                }
            } else {
                userJobs[key]
            }
        if (job == null) return UpgradeSecuritySnapshot("IDLE", fingerprint(state))
        val snapshot = if (start) job.start(username, state) else job.get(username, state)
        return UpgradeSecuritySnapshot(snapshot.status.name, snapshot.targetFingerprint, snapshot.result, snapshot.error)
    }

    fun invalidate(username: String) {
        jobs.remove(username)?.values?.forEach { it.invalidate(username) }
    }

    private fun scan(
        username: String,
        state: TargetVersionsState,
        kind: String,
        name: String,
        current: String,
    ): List<UpgradeSecurityResult> {
        val candidates = versions.available(username, name, current, kind).versions.filterNot { it.prerelease }
        if (candidates.isEmpty()) return emptyList()
        if (kind == "plugin") require(unsupportedPluginReason(name, current) == null) { "Plugin has no supported published marker" }
        val coordinate = if (kind == "plugin") "$name:$name.gradle.plugin" else name
        val modules = candidates.associate { it.version to "$coordinate:${it.version}" }
        val resolution = resolve(username, state, modules)

        fun flatten(nodes: List<ResolvedDependency>): List<ResolvedDependency> = nodes.flatMap { listOf(it) + flatten(it.dependencies) }
        val trees = candidates.associate { it.version to resolution.individual[it.version].orEmpty() }
        val nodes = trees.values.flatMap { flatten(it) }.distinctBy { "${it.key}:${it.version}" }
        val valid = nodes.filter { it.group != null && it.version != "<unresolved>" && it.resolutionError == null }
        val findings = osv.find(valid.map { it.copy(dependencies = emptyList()) })
        return candidates.map { candidate ->
            val roots = trees.getValue(candidate.version)
            val tree = flatten(roots)
            val errors = mutableListOf<String>()
            if (roots.size != 1 || roots.singleOrNull()?.let { it.key == coordinate && it.version == candidate.version } != true) {
                errors += "Candidate root did not resolve completely"
            }
            if (kind == "plugin" && roots.singleOrNull()?.dependencies.isNullOrEmpty()) errors += "Missing plugin implementation"
            tree.forEach {
                if (it.group == null || it.version.isBlank() || it.version == "<unresolved>" || it.resolutionError != null) {
                    errors += it.resolutionError ?: "Incomplete metadata for ${it.key}"
                } else if (findings["${it.key}:${it.version}"] == null) {
                    errors += "Missing OSV result for ${it.key}:${it.version}"
                }
            }
            val count = tree.flatMap { findings["${it.key}:${it.version}"].orEmpty() }.distinctBy { it.id }.size
            UpgradeSecurityResult(
                candidate.version,
                if (errors.isNotEmpty()) {
                    "UNRESOLVED"
                } else if (count > 0) {
                    "VULNERABLE"
                } else {
                    "OK"
                },
                count,
                errors.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n"),
            )
        }
    }
}
