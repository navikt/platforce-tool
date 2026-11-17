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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

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
    val completedResolutions: Int = 0,
    val totalResolutions: Int = 0,
)

enum class NodeScanPhase {
    RESOLUTION,
    OSV,
    COVERAGE,
}

data class NodeScanProgress(
    val phase: NodeScanPhase,
    val completedResolutions: Int,
    val totalResolutions: Int,
)

class NodeSecurityService(
    private val resolve: (String, String) -> List<ResolvedDependency>,
    private val vulnerabilities: VulnerabilityService,
    private val resolveCombined: (Map<String, String>, Map<String, String>) -> NodeCombinedResolution,
    private val resolver: NodeTargetResolver? = null,
) {
    constructor(resolver: NodeTargetResolver, vulnerabilities: VulnerabilityService) : this(
        resolver::resolve,
        vulnerabilities,
        resolver::resolveCombined,
        resolver,
    )

    private val log = KotlinLogging.logger { }

    private data class Tree(
        val result: NodeSecurityResult,
        val packages: List<ResolvedDependency> = emptyList(),
    )

    fun scan(
        state: TargetVersionsState,
        onProgress: (NodeScanProgress) -> Unit = {},
    ): NodeSecurityScan = scanWithSession(state, null, onProgress)

    fun scan(
        username: String,
        state: TargetVersionsState,
        onProgress: (NodeScanProgress) -> Unit = {},
    ): NodeSecurityScan = scanWithSession(state, username, onProgress)

    private fun scanWithSession(
        state: TargetVersionsState,
        username: String?,
        onProgress: (NodeScanProgress) -> Unit,
    ): NodeSecurityScan =
        if (resolver != null) {
            resolver.withSession(username) { session ->
                NodeSecurityService(session::resolve, vulnerabilities, session::resolveCombined).scan(state, onProgress)
            }
        } else {
            Executors
                .newFixedThreadPool(4) { task ->
                    Thread(task, "node-npm-resolution").apply { isDaemon = true }
                }.use { executor ->
                    try {
                        scan(state, onProgress, executor)
                    } finally {
                        executor.shutdownNow()
                    }
                }
        }

    private fun scan(
        state: TargetVersionsState,
        onProgress: (NodeScanProgress) -> Unit,
        executor: ExecutorService,
    ): NodeSecurityScan {
        var phase = NodeScanPhase.RESOLUTION
        var completed = 0
        val targets = state.nodeDependencies.toSortedMap().mapValues { nodeTargetVersion(it.value) }
        val supported = targets.filter { (key, version) -> validNodePackage(key) && validNodeVersion(version) }
        val overrides = targets.filterKeys { it in state.nodeTransientDependencies }
        val normal = targets - overrides.keys
        val complete = supported.size == targets.size
        val total = supported.size + if (complete && targets.isNotEmpty()) 1 else 0

        fun report() = onProgress(NodeScanProgress(phase, completed, total))
        report()
        val standalone =
            targets
                .mapValues { (key, version) ->
                    Tree(
                        NodeSecurityResult(
                            key,
                            version,
                            NodeSecurityStatus.UNSUPPORTED,
                            error = "Use a public npm package name and an exact version, optionally prefixed with ^ or ~",
                        ),
                    )
                }.toMutableMap()

        fun resolveTree(key: String): Tree {
            val version = targets.getValue(key)
            val initial = NodeSecurityResult(key, version, NodeSecurityStatus.OK)
            return if (!validNodePackage(key) || !validNodeVersion(version)) {
                Tree(
                    NodeSecurityResult(
                        key,
                        version,
                        NodeSecurityStatus.UNSUPPORTED,
                        error = "Use a public npm package name and an exact version, optionally prefixed with ^ or ~",
                    ),
                )
            } else {
                val started = System.nanoTime()
                log.info { "Resolving npm target $key@$version solo" }
                try {
                    val packages = resolve(key, version)
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
                } finally {
                    log.info { "Finished npm target resolution for $key@$version in ${(System.nanoTime() - started) / 1_000_000}ms" }
                }
            }
        }

        fun <T> await(future: Future<T>): T =
            try {
                future.get()
            } catch (failure: ExecutionException) {
                val cause = failure.cause ?: failure
                if (cause is InterruptedException) Thread.currentThread().interrupt()
                throw cause
            }

        val combinedFuture =
            if (complete && targets.isNotEmpty()) {
                executor.submit<Result<NodeCombinedResolution>> {
                    val started = System.nanoTime()
                    log.info { "Resolving combined npm targets: ${normal.size} dependencies, ${overrides.size} overrides" }
                    try {
                        val combined = resolveCombined(normal, overrides)
                        check(combined.packages.all { it.group == "npm" && validNodePackage(it.name) && validNodeVersion(it.version) }) {
                            "Incomplete combined npm resolution"
                        }
                        targets.forEach { (key, version) ->
                            val packages = checkNotNull(combined.targets[key]) { "Missing combined npm target $key" }
                            check(packages.all { it in combined.packages }) { "Incomplete combined npm tree for $key" }
                            if (key in normal) {
                                check(packages.any { it.name == key && it.version == version }) {
                                    "Combined resolution is missing exact target $key@$version"
                                }
                            }
                        }
                        Result.success(combined)
                    } catch (failure: Exception) {
                        if (failure is InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw failure
                        }
                        log.warn(failure) { "Combined npm resolution failed" }
                        Result.failure(failure)
                    } finally {
                        log.info { "Finished combined npm resolution in ${(System.nanoTime() - started) / 1_000_000}ms" }
                    }
                }
            } else {
                null
            }
        val completion = ExecutorCompletionService<Pair<String, Tree>>(executor)
        supported.keys.forEach { key -> completion.submit { key to resolveTree(key) } }
        repeat(supported.size) {
            val (key, resolved) = await(completion.take())
            standalone[key] = resolved
            completed++
            report()
        }
        val combinedResult =
            combinedFuture?.let {
                await(it).also {
                    completed++
                    report()
                }
            }
        val combined = combinedResult?.getOrNull()
        val combinedError = combinedResult?.exceptionOrNull()?.message ?: "Combined npm resolution is incomplete"
        phase = NodeScanPhase.OSV
        report()
        val findings = mutableMapOf<String, List<Vulnerability>>()

        fun lookup(packages: List<ResolvedDependency>) {
            val missing = packages.distinctBy { "${it.key}:${it.version}" }.filterNot { "${it.key}:${it.version}" in findings }
            if (missing.isNotEmpty()) {
                findings.putAll(vulnerabilities.find(missing))
                check(missing.all { "${it.key}:${it.version}" in findings }) { "Missing npm vulnerability lookup results" }
            }
        }
        val allPackages = (standalone.values.flatMap { it.packages } + combined?.packages.orEmpty()).distinctBy { it.name to it.version }
        lookup(allPackages)
        val candidates =
            allPackages.associateWith { dependency ->
                findings
                    .getValue("${dependency.key}:${dependency.version}")
                    .flatMap { it.fixedVersions }
                    .filter {
                        validNodeVersion(it) && '-' !in it.substringBefore('+') && compareNodeVersions(it, dependency.version) > 0
                    }.distinct()
                    .sortedWith(::compareNodeVersions)
            }
        lookup(
            candidates.flatMap { (dependency, versions) ->
                versions.map { dependency.copy(version = it, requestedVersion = it) }
            },
        )

        fun vulnerable(packages: List<ResolvedDependency>): List<VulnerableDependency> =
            packages.mapNotNull { dependency ->
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
        phase = NodeScanPhase.COVERAGE
        report()
        if (combined == null) {
            return NodeSecurityScan(
                baseline.values.map {
                    if (it.status in setOf(NodeSecurityStatus.UNSUPPORTED, NodeSecurityStatus.UNRESOLVED)) {
                        it
                    } else {
                        it.copy(status = NodeSecurityStatus.UNRESOLVED, error = combinedError)
                    }
                },
            )
        }
        val actual =
            baseline.mapValues { (key, original) ->
                val packages = combined.targets.getValue(key)
                if (original.status in setOf(NodeSecurityStatus.UNSUPPORTED, NodeSecurityStatus.UNRESOLVED) || packages.isEmpty()) {
                    original
                } else {
                    evaluate(
                        Tree(original.copy(status = NodeSecurityStatus.OK, resolvedPackageCount = packages.size, error = null), packages),
                    )
                }
            }

        val safeProviders = actual.filterValues { it.status == NodeSecurityStatus.OK }
        val inferred =
            actual.mapValues { (key, result) ->
                if (result.status in setOf(NodeSecurityStatus.UNSUPPORTED, NodeSecurityStatus.UNRESOLVED)) return@mapValues result
                val packages = combined.targets.getValue(key)
                val reasons =
                    baseline.getValue(key).vulnerableDependencies.mapNotNull { item ->
                        if (packages.isEmpty() ||
                            result.vulnerableDependencies.any { remaining ->
                                remaining.dependency.name == item.dependency.name &&
                                    remaining.vulnerabilities.any { issue -> item.vulnerabilities.any { it.id == issue.id } }
                            }
                        ) {
                            return@mapNotNull null
                        }
                        val replacements = packages.filter { it.name == item.dependency.name }.map { it.version }.toSet()
                        val alternatives =
                            safeProviders.keys
                                .filter { provider ->
                                    provider != key &&
                                        standalone.getValue(provider).packages.any {
                                            it.name == item.dependency.name &&
                                                it.version in replacements
                                        } &&
                                        combined.targets.getValue(provider).any {
                                            it.name == item.dependency.name &&
                                                it.version in replacements
                                        }
                                }.toSet()
                        val ancestors =
                            safeProviders.keys.filter { provider ->
                                provider != key &&
                                    standalone
                                        .getValue(
                                            key,
                                        ).packages
                                        .any { it.name == provider && it.version != targets.getValue(provider) } &&
                                    packages.any { it.name == provider && it.version == targets.getValue(provider) }
                            }
                        val direct =
                            item.dependency.name.takeIf {
                                it in overrides && it in safeProviders && overrides[it] in replacements
                            }
                        val providers =
                            when {
                                direct != null -> setOf(direct)
                                ancestors.isNotEmpty() -> ancestors.toSet()
                                else -> alternatives - overrides.keys
                            }
                        OverrideReason(
                            item.dependency.name,
                            targets[item.dependency.name] ?: item.dependency.version,
                            replacements.sortedWith(::compareNodeVersions).joinToString(", ").ifEmpty { "not present" },
                            item.dependency.version,
                            providers.sorted().map { "$it:${targets.getValue(it)}" },
                        )
                    }
                result.copy(
                    status =
                        if (result.status == NodeSecurityStatus.OK &&
                            reasons.isNotEmpty()
                        ) {
                            NodeSecurityStatus.OK_OVERRIDDEN
                        } else {
                            result.status
                        },
                    relatedTo = reasons,
                )
            }
        val incomplete = baseline.values.any { it.status in setOf(NodeSecurityStatus.UNSUPPORTED, NodeSecurityStatus.UNRESOLVED) }

        fun provides(
            key: String,
            reason: OverrideReason,
        ) = "$key:${targets.getValue(key)}" in reason.causedBy
        val ambiguousCoverage = inferred.values.any { it.relatedTo.any { reason -> reason.causedBy.isEmpty() } }
        return NodeSecurityScan(
            inferred.map { (key, result) ->
                val usage =
                    inferred.values
                        .filter { consumer ->
                            consumer.key != key && consumer.relatedTo.any { provides(key, it) }
                        }.map { TransientDependencyUsage(it.key, it.targetVersion) }
                if (key !in overrides || result.status in
                    setOf(
                        NodeSecurityStatus.VULNERABLE,
                        NodeSecurityStatus.UNRESOLVED,
                        NodeSecurityStatus.UNSUPPORTED,
                    )
                ) {
                    if (key in overrides) result.copy(transientUsage = usage) else result
                } else if (incomplete || (ambiguousCoverage && usage.isEmpty() && combined.targets.getValue(key).isNotEmpty())) {
                    result.copy(
                        status = NodeSecurityStatus.UNRESOLVED,
                        error =
                            if (incomplete) {
                                "Incomplete Node targets; override coverage is unknown"
                            } else {
                                "Override coverage cannot be attributed from the combined and solo npm trees"
                            },
                    )
                } else {
                    result.copy(
                        status = if (usage.isNotEmpty()) NodeSecurityStatus.OK_TRANSIENT else NodeSecurityStatus.TRANSIENT_UNUSED,
                        transientUsage = usage,
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
    private val progress = ConcurrentHashMap<Pair<String, String>, NodeScanProgress>()
    private val jobs =
        UserScanJobs(executor) { username: String, state: TargetVersionsState ->
            val key = username to fingerprint(state)
            try {
                service.scan(username, state) { progress[key] = it }
            } finally {
                progress.remove(key)
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

    fun ready(
        username: String,
        state: TargetVersionsState,
    ): Boolean {
        val snapshot = get(username, state)
        return snapshot.status == SecurityScanStatus.READY &&
            snapshot.result?.targets?.all { it.status !in setOf(NodeSecurityStatus.UNRESOLVED, NodeSecurityStatus.UNSUPPORTED) } == true
    }

    fun invalidate(username: String) = jobs.invalidate(username)

    private fun ScanJobSnapshot<NodeSecurityScan>.toNodeSnapshot(username: String): NodeSecuritySnapshot {
        val current = if (status.name == "RUNNING") progress[username to targetFingerprint] else null
        return NodeSecuritySnapshot(
            SecurityScanStatus.valueOf(status.name),
            targetFingerprint,
            result,
            error,
            if (status.name == "RUNNING") current?.phase ?: NodeScanPhase.RESOLUTION else null,
            current?.completedResolutions ?: 0,
            current?.totalResolutions ?: 0,
        )
    }
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
