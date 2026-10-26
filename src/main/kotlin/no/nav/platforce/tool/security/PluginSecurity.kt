package no.nav.platforce.tool.security

import com.google.gson.Gson
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.resolution.unsupportedPluginReason
import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.util.concurrent.ExecutorService

enum class PluginSecurityStatus {
    OK,
    VULNERABLE,
    UNRESOLVED,
    UNSUPPORTED,
}

data class PluginSecurityResult(
    val key: String,
    val targetVersion: String,
    val status: PluginSecurityStatus,
    val vulnerabilities: List<Vulnerability> = emptyList(),
    val vulnerableDependencies: List<VulnerableDependency> = emptyList(),
    val relatedTo: List<OverrideReason> = emptyList(),
    val error: String? = null,
)

data class PluginSecurityScan(
    val plugins: List<PluginSecurityResult>,
)

data class PluginSecuritySnapshot(
    val status: SecurityScanStatus,
    val targetFingerprint: String,
    val result: PluginSecurityScan? = null,
    val error: String? = null,
)

class PluginSecurityService(
    private val vulnerabilityService: VulnerabilityService,
) {
    fun scan(
        resolution: TargetResolution,
        state: TargetVersionsState,
    ): PluginSecurityScan =
        PluginSecurityScan(
            state.plugins.toSortedMap().map { (id, version) ->
                val unsupported = unsupportedPluginReason(id, version)
                if (unsupported != null) {
                    PluginSecurityResult(id, version, PluginSecurityStatus.UNSUPPORTED, error = unsupported)
                } else {
                    scanPlugin(id, version, resolution.individual[id].orEmpty())
                }
            },
        )

    private fun scanPlugin(
        id: String,
        version: String,
        roots: List<ResolvedDependency>,
    ): PluginSecurityResult {
        val nodes = flatten(roots).distinctBy { "${it.key}:${it.version}" }
        val resolved =
            nodes.filter {
                !it.group.isNullOrBlank() && it.version.isNotBlank() && it.version != "<unresolved>" && it.resolutionError == null
            }
        val errors = mutableListOf<String>()
        if (roots.size != 1 || roots.singleOrNull()?.let { it.key == "$id:$id.gradle.plugin" && it.version == version } != true) {
            errors += "Published plugin marker $id:$id.gradle.plugin:$version could not be resolved completely"
        } else if (roots.single().dependencies.isEmpty()) {
            errors += "Published plugin marker has no implementation dependency"
        }
        nodes.filter { it !in resolved }.forEach {
            errors += "${it.key}:${it.requestedVersion ?: it.version}: ${it.resolutionError ?: "Incomplete dependency metadata"}"
        }
        val vulnerable = mutableListOf<VulnerableDependency>()
        try {
            val findings = vulnerabilityService.find(resolved.map { it.copy(dependencies = emptyList()) })
            resolved.forEach { dependency ->
                val coordinate = "${dependency.key}:${dependency.version}"
                val vulnerabilities = findings[coordinate]
                if (vulnerabilities == null) {
                    errors += "No vulnerability lookup result for $coordinate"
                } else if (vulnerabilities.isNotEmpty()) {
                    vulnerable += VulnerableDependency(dependency, vulnerabilities)
                }
            }
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            errors += "Plugin vulnerability lookup failed: ${failure.message ?: failure.javaClass.simpleName}"
        }
        return PluginSecurityResult(
            key = id,
            targetVersion = version,
            status =
                when {
                    errors.isNotEmpty() -> PluginSecurityStatus.UNRESOLVED
                    vulnerable.isNotEmpty() -> PluginSecurityStatus.VULNERABLE
                    else -> PluginSecurityStatus.OK
                },
            vulnerabilities = vulnerable.flatMap { it.vulnerabilities }.distinctBy { it.id },
            vulnerableDependencies = vulnerable,
            error = errors.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n"),
        )
    }

    private fun flatten(roots: List<ResolvedDependency>): List<ResolvedDependency> = roots.flatMap { listOf(it) + flatten(it.dependencies) }
}

class PluginSecurityScanner(
    resolve: (String, TargetVersionsState) -> TargetResolution,
    service: PluginSecurityService,
    executor: ExecutorService = scanExecutor("plugin-security"),
) {
    private val jobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val supported = state.plugins.any { (id, version) -> unsupportedPluginReason(id, version) == null }
            val resolution =
                if (supported) resolve(username, state) else TargetResolution(state.gradleVersion, emptyList(), emptyMap())
            service.scan(resolution, state)
        }

    fun start(
        username: String,
        targetState: TargetVersionsState,
    ): PluginSecuritySnapshot = jobs.start(username, targetState, refreshReady = true).toPluginSnapshot()

    fun get(
        username: String,
        targetState: TargetVersionsState,
    ): PluginSecuritySnapshot = jobs.get(username, targetState).toPluginSnapshot()

    fun invalidate(username: String) = jobs.invalidate(username)

    private fun ScanJobSnapshot<PluginSecurityScan>.toPluginSnapshot() =
        PluginSecuritySnapshot(SecurityScanStatus.valueOf(status.name), targetFingerprint, result, error)
}

fun pluginSecurityRoutes(scanner: PluginSecurityScanner) =
    listOf(
        "/internal/api/plugin-security/start" bind Method.GET to { request ->
            val context = request.userContext()
            pluginSnapshotResponse(scanner.start(context.username, context.targetVersionsStore.get()))
        },
        "/internal/api/plugin-security" bind Method.GET to { request ->
            val context = request.userContext()
            pluginSnapshotResponse(scanner.get(context.username, context.targetVersionsStore.get()))
        },
    )

private fun pluginSnapshotResponse(snapshot: PluginSecuritySnapshot): Response =
    Response(
        when (snapshot.status) {
            SecurityScanStatus.RUNNING -> Status.ACCEPTED
            SecurityScanStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
            SecurityScanStatus.IDLE, SecurityScanStatus.READY -> Status.OK
        },
    ).header("Content-Type", "application/json").body(Gson().toJson(snapshot))
