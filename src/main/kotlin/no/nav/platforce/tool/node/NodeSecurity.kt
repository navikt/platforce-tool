package no.nav.platforce.tool.node

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.VersionSuggestion
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.security.VulnerableDependency
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import no.nav.platforce.tool.user.userContext
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

enum class NodeSecurityStatus {
    OK,
    VULNERABLE,
    UNRESOLVED,
    UNSUPPORTED,
}

data class NodeSecurityResult(
    val key: String,
    val targetVersion: String,
    val status: NodeSecurityStatus,
    val resolvedPackageCount: Int = 0,
    val vulnerableDependencies: List<VulnerableDependency> = emptyList(),
    val error: String? = null,
)

data class NodeSecurityScan(
    val targets: List<NodeSecurityResult>,
)

data class NodeSecuritySnapshot(
    val status: SecurityScanStatus,
    val targetFingerprint: String,
    val result: NodeSecurityScan? = null,
    val error: String? = null,
    val phase: NodeScanPhase? = null,
)

enum class NodeScanPhase {
    RESOLUTION,
    OSV,
}

class NodeSecurityService(
    private val resolve: (String, String) -> List<ResolvedDependency>,
    private val vulnerabilities: VulnerabilityService,
) {
    private val log = KotlinLogging.logger { }

    fun scan(
        state: TargetVersionsState,
        onPhase: (NodeScanPhase) -> Unit = {},
    ): NodeSecurityScan {
        onPhase(NodeScanPhase.RESOLUTION)
        val resolved = mutableMapOf<String, List<ResolvedDependency>>()
        val preliminary =
            state.nodeDependencies.toSortedMap().map { (key, declaration) ->
                val version = nodeTargetVersion(declaration)
                if (!validNodePackage(key) || !validNodeVersion(version)) {
                    NodeSecurityResult(
                        key,
                        version,
                        NodeSecurityStatus.UNSUPPORTED,
                        error = "Use a public npm package name and an exact version, optionally prefixed with ^ or ~",
                    )
                } else {
                    try {
                        val packages = resolve(key, version)
                        check(
                            packages.any { it.name == key && it.version == version },
                        ) { "Resolution is missing exact target $key@$version" }
                        check(packages.all { it.group == "npm" && validNodePackage(it.name) && validNodeVersion(it.version) }) {
                            "Incomplete npm resolution for $key@$version"
                        }
                        resolved[key] = packages
                        NodeSecurityResult(key, version, NodeSecurityStatus.OK, packages.size)
                    } catch (failure: Exception) {
                        if (failure is InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw failure
                        }
                        log.warn(failure) { "npm target resolution failed for $key@$version" }
                        NodeSecurityResult(
                            key,
                            version,
                            NodeSecurityStatus.UNRESOLVED,
                            error =
                                failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
            }
        val packages = resolved.values.flatten().distinctBy { it.name to it.version }
        onPhase(NodeScanPhase.OSV)
        val findings = if (packages.isEmpty()) emptyMap() else vulnerabilities.find(packages)
        val candidates =
            packages.associateWith { dependency ->
                findings["${dependency.key}:${dependency.version}"]
                    .orEmpty()
                    .flatMap { it.fixedVersions }
                    .filter { validNodeVersion(it) && '-' !in it.substringBefore('+') && compareNodeVersions(it, dependency.version) > 0 }
                    .distinct()
                    .sortedWith(::compareNodeVersions)
            }
        val candidatePackages =
            candidates
                .flatMap { (dependency, versions) ->
                    versions.map { dependency.copy(version = it, requestedVersion = it) }
                }.distinctBy { it.name to it.version }
        val unqueried = candidatePackages.filterNot { "${it.key}:${it.version}" in findings }
        val candidateFindings = findings + if (unqueried.isEmpty()) emptyMap() else vulnerabilities.find(unqueried)
        check(candidatePackages.all { "${it.key}:${it.version}" in candidateFindings }) { "Missing npm fix vulnerability lookup results" }
        return NodeSecurityScan(
            preliminary.map { result ->
                val packages = resolved[result.key] ?: return@map result
                val vulnerable =
                    packages.mapNotNull { dependency ->
                        val issues =
                            checkNotNull(findings["${dependency.key}:${dependency.version}"]) {
                                "Missing npm vulnerability lookup for ${dependency.name}@${dependency.version}"
                            }
                        if (issues.isEmpty()) {
                            null
                        } else {
                            val fix =
                                candidates.getValue(dependency).firstOrNull {
                                    candidateFindings["${dependency.key}:$it"]?.isEmpty() == true
                                }
                            VulnerableDependency(dependency, issues, fix?.let { VersionSuggestion(dependency.name, it) })
                        }
                    }
                result.copy(
                    status = if (vulnerable.isEmpty()) NodeSecurityStatus.OK else NodeSecurityStatus.VULNERABLE,
                    vulnerableDependencies = vulnerable,
                )
            },
        )
    }
}

class NodeSecurityScanner(
    service: NodeSecurityService,
    executor: ExecutorService = scanExecutor("node-security"),
) {
    private val phases = ConcurrentHashMap<Pair<String, String>, NodeScanPhase>()
    private val jobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val key = username to fingerprint(state)
            try {
                service.scan(state) { phases[key] = it }
            } finally {
                phases.remove(key)
            }
        }

    fun start(
        username: String,
        state: TargetVersionsState,
    ): NodeSecuritySnapshot = jobs.start(username, state, refreshReady = true).toNodeSnapshot(username)

    fun get(
        username: String,
        state: TargetVersionsState,
    ): NodeSecuritySnapshot = jobs.get(username, state).toNodeSnapshot(username)

    fun invalidate(username: String) = jobs.invalidate(username)

    private fun ScanJobSnapshot<NodeSecurityScan>.toNodeSnapshot(username: String) =
        NodeSecuritySnapshot(
            SecurityScanStatus.valueOf(status.name),
            targetFingerprint,
            result,
            error,
            if (status.name == "RUNNING") phases[username to targetFingerprint] ?: NodeScanPhase.RESOLUTION else null,
        )
}

fun nodeSecurityRoutes(scanner: NodeSecurityScanner) =
    listOf(
        "/internal/api/node-security/start" bind Method.GET to { request ->
            val context = request.userContext()
            nodeSnapshotResponse(scanner.start(context.username, context.targetVersionsStore.get()))
        },
        "/internal/api/node-security" bind Method.GET to { request ->
            val context = request.userContext()
            nodeSnapshotResponse(scanner.get(context.username, context.targetVersionsStore.get()))
        },
    )

private fun nodeSnapshotResponse(snapshot: NodeSecuritySnapshot): Response =
    Response(
        when (snapshot.status) {
            SecurityScanStatus.RUNNING -> Status.ACCEPTED
            SecurityScanStatus.FAILED -> Status.INTERNAL_SERVER_ERROR
            SecurityScanStatus.IDLE, SecurityScanStatus.READY -> Status.OK
        },
    ).header("Content-Type", "application/json").body(Gson().toJson(snapshot))
