package no.nav.platforce.tool.node

import com.google.gson.Gson
import mu.KotlinLogging
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.scanning.ScanJobSnapshot
import no.nav.platforce.tool.scanning.UserScanJobs
import no.nav.platforce.tool.scanning.scanExecutor
import no.nav.platforce.tool.security.OverrideReason
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.TransientDependencyUsage
import no.nav.platforce.tool.security.VersionSuggestion
import no.nav.platforce.tool.security.Vulnerability
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
    OK_OVERRIDDEN,
    OK_TRANSIENT,
    TRANSIENT_UNUSED,
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
    val relatedTo: List<OverrideReason> = emptyList(),
    val transientUsage: List<TransientDependencyUsage> = emptyList(),
    val replacementDependencies: List<TransientDependencyUsage> = emptyList(),
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
    private val resolveWithOverrides: ((String, String, Map<String, String>) -> List<ResolvedDependency>)? = null,
) {
    private val log = KotlinLogging.logger { }

    private data class Tree(
        val result: NodeSecurityResult,
        val packages: List<ResolvedDependency> = emptyList(),
    )

    fun scan(
        state: TargetVersionsState,
        onPhase: (NodeScanPhase) -> Unit = {},
    ): NodeSecurityScan {
        onPhase(NodeScanPhase.RESOLUTION)
        val trees = mutableMapOf<Pair<String, Map<String, String>>, Tree>()

        fun tree(
            key: String,
            overrides: Map<String, String> = emptyMap(),
        ): Tree =
            trees.getOrPut(key to overrides.toMap()) {
                val version = nodeTargetVersion(state.nodeDependencies.getValue(key))
                val initial = NodeSecurityResult(key, version, NodeSecurityStatus.OK)
                if (!validNodePackage(key) || !validNodeVersion(version)) {
                    Tree(
                        NodeSecurityResult(
                            key,
                            version,
                            NodeSecurityStatus.UNSUPPORTED,
                            error = "Use a public npm package name and an exact version, optionally prefixed with ^ or ~",
                        ),
                    )
                } else {
                    try {
                        onPhase(NodeScanPhase.RESOLUTION)
                        val packages =
                            if (overrides.isEmpty()) {
                                resolve(key, version)
                            } else {
                                checkNotNull(resolveWithOverrides) {
                                    "npm override resolution is not configured"
                                }(key, version, overrides)
                            }
                        check(
                            packages.any { it.name == key && it.version == version },
                        ) { "Resolution is missing exact target $key@$version" }
                        check(packages.all { it.group == "npm" && validNodePackage(it.name) && validNodeVersion(it.version) }) {
                            "Incomplete npm resolution for $key@$version"
                        }
                        Tree(initial.copy(resolvedPackageCount = packages.size), packages)
                    } catch (failure: Exception) {
                        if (failure is InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw failure
                        }
                        log.warn(failure) { "npm target resolution failed for $key@$version" }
                        Tree(initial.copy(status = NodeSecurityStatus.UNRESOLVED, error = failure.message ?: failure.javaClass.simpleName))
                    }
                }
            }
        val standalone = state.nodeDependencies.toSortedMap().mapValues { (key, _) -> tree(key) }
        onPhase(NodeScanPhase.OSV)
        val findings = mutableMapOf<String, List<Vulnerability>>()

        fun lookup(packages: List<ResolvedDependency>) {
            val missing = packages.distinctBy { "${it.key}:${it.version}" }.filterNot { "${it.key}:${it.version}" in findings }
            if (missing.isNotEmpty()) {
                onPhase(NodeScanPhase.OSV)
                findings.putAll(vulnerabilities.find(missing))
                check(missing.all { "${it.key}:${it.version}" in findings }) { "Missing npm vulnerability lookup results" }
            }
        }
        lookup(standalone.values.flatMap { it.packages })

        fun vulnerable(packages: List<ResolvedDependency>): List<VulnerableDependency> {
            lookup(packages)
            val candidates =
                packages.associateWith { dependency ->
                    findings
                        .getValue("${dependency.key}:${dependency.version}")
                        .flatMap { it.fixedVersions }
                        .filter {
                            validNodeVersion(
                                it,
                            ) && '-' !in it.substringBefore('+') && compareNodeVersions(it, dependency.version) > 0
                        }.distinct()
                        .sortedWith(::compareNodeVersions)
                }
            val candidatePackages =
                candidates
                    .flatMap { (dependency, versions) ->
                        versions.map { dependency.copy(version = it, requestedVersion = it) }
                    }.distinctBy { it.name to it.version }
            lookup(candidatePackages)
            return packages.mapNotNull { dependency ->
                val issues = findings.getValue("${dependency.key}:${dependency.version}")
                if (issues.isEmpty()) {
                    null
                } else {
                    val fix =
                        candidates.getValue(dependency).firstOrNull {
                            findings.getValue("${dependency.key}:$it").isEmpty()
                        }
                    VulnerableDependency(dependency, issues, fix?.let { VersionSuggestion(dependency.name, it) })
                }
            }
        }
        val evaluated = mutableMapOf<Tree, NodeSecurityResult>()

        fun evaluate(tree: Tree): NodeSecurityResult =
            evaluated.getOrPut(tree) {
                if (tree.result.status != NodeSecurityStatus.OK) {
                    tree.result
                } else {
                    val issues = vulnerable(tree.packages)
                    tree.result.copy(
                        status = if (issues.isEmpty()) NodeSecurityStatus.OK else NodeSecurityStatus.VULNERABLE,
                        vulnerableDependencies = issues,
                    )
                }
            }
        val baseline = standalone.mapValues { evaluate(it.value) }
        val overrides =
            state.nodeDependencies.filterKeys { it in state.nodeTransientDependencies }.mapValues {
                nodeTargetVersion(
                    it.value,
                )
            }
        if (overrides.isEmpty()) return NodeSecurityScan(baseline.values.toList())
        val normal = (state.nodeDependencies.keys - overrides.keys).sorted()
        val effective =
            normal.associateWith { key ->
                if (baseline.getValue(key).status in setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED)) {
                    baseline.getValue(key)
                } else {
                    evaluate(tree(key, overrides))
                }
            }
        val coverResults =
            overrides.keys.associateWith { key ->
                if (baseline.getValue(key).status in setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED)) {
                    baseline.getValue(key)
                } else {
                    evaluate(tree(key, overrides))
                }
            }

        fun risks(result: NodeSecurityResult) =
            result.vulnerableDependencies.flatMap { item -> item.vulnerabilities.map { item.dependency.name to it.id } }.toSet()
        val retained = overrides.toMutableMap()
        val uncertain = mutableMapOf<String, String>()
        val usage = mutableMapOf<String, List<TransientDependencyUsage>>()
        val coveredRisks = mutableMapOf<String, Map<String, Set<Pair<String, String>>>>()
        val introduced = mutableMapOf<String, List<VulnerableDependency>>()
        val incomplete = effective.values.any { it.status in setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED) }
        if (!incomplete) {
            var removed: Boolean
            do {
                removed = false
                for (key in retained.keys.sorted()) {
                    if (coverResults.getValue(key).status != NodeSecurityStatus.OK) continue
                    val without = normal.associateWith { evaluate(tree(it, retained - key)) }
                    val failed =
                        without.values.firstOrNull {
                            it.status in
                                setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED)
                        }
                    if (failed != null) {
                        uncertain[key] = failed.error ?: "Incomplete npm override comparison"
                        continue
                    }
                    uncertain.remove(key)
                    val added =
                        normal.flatMap { parent ->
                            val remaining = risks(without.getValue(parent))
                            effective.getValue(parent).vulnerableDependencies.mapNotNull { item ->
                                val issues = item.vulnerabilities.filter { (item.dependency.name to it.id) !in remaining }
                                item.copy(vulnerabilities = issues).takeIf { issues.isNotEmpty() }
                            }
                        }
                    introduced[key] = added
                    val protected =
                        normal
                            .associateWith {
                                risks(
                                    without.getValue(it),
                                ) - risks(effective.getValue(it))
                            }.filterValues { it.isNotEmpty() }
                    coveredRisks[key] = protected
                    usage[key] = protected.keys.map { TransientDependencyUsage(it, baseline.getValue(it).targetVersion) }
                    if (protected.isEmpty() && added.isEmpty()) {
                        retained.remove(key)
                        removed = true
                    }
                }
            } while (removed)
        }
        return NodeSecurityScan(
            baseline.map { (key, original) ->
                if (key in overrides) {
                    val actual = coverResults.getValue(key)
                    when {
                        actual.status != NodeSecurityStatus.OK -> {
                            actual
                        }

                        incomplete -> {
                            actual.copy(
                                status = NodeSecurityStatus.UNRESOLVED,
                                error = "Incomplete Node targets; override coverage is unknown",
                            )
                        }

                        key in uncertain -> {
                            actual.copy(status = NodeSecurityStatus.UNRESOLVED, error = uncertain.getValue(key))
                        }

                        introduced[key].orEmpty().isNotEmpty() -> {
                            actual.copy(status = NodeSecurityStatus.VULNERABLE, vulnerableDependencies = introduced.getValue(key))
                        }

                        key !in retained -> {
                            actual.copy(
                                status = NodeSecurityStatus.TRANSIENT_UNUSED,
                                replacementDependencies = retained.map { TransientDependencyUsage(it.key, it.value) },
                            )
                        }

                        else -> {
                            actual.copy(status = NodeSecurityStatus.OK_TRANSIENT, transientUsage = usage[key].orEmpty())
                        }
                    }
                } else {
                    val actual = effective.getValue(key)
                    val remaining = risks(actual)
                    val covered =
                        original.vulnerableDependencies.filter { item ->
                            item.vulnerabilities.all {
                                (item.dependency.name to it.id) !in
                                    remaining
                            }
                        }
                    val reasons =
                        if (actual.status in
                            setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED)
                        ) {
                            emptyList()
                        } else {
                            covered.map { item ->
                                OverrideReason(
                                    item.dependency.name,
                                    item.dependency.version,
                                    tree(key, overrides)
                                        .packages
                                        .filter {
                                            it.name == item.dependency.name
                                        }.map { it.version }
                                        .distinct()
                                        .joinToString(", ")
                                        .ifEmpty { "not present" },
                                    item.dependency.version,
                                    retained
                                        .filterKeys { cover ->
                                            item.vulnerabilities.any { issue ->
                                                (item.dependency.name to issue.id) in coveredRisks[cover]?.get(key).orEmpty()
                                            }
                                        }.map { "${it.key}:${it.value}" },
                                )
                            }
                        }
                    actual.copy(
                        status =
                            if (actual.status == NodeSecurityStatus.OK &&
                                covered.isNotEmpty()
                            ) {
                                NodeSecurityStatus.OK_OVERRIDDEN
                            } else {
                                actual.status
                            },
                        relatedTo = reasons,
                    )
                }
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
