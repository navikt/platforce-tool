package no.nav.platforce.tool.node

import com.google.gson.Gson
import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.ResolvedDependency
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun nodeTargetVersion(version: String): String = version.trim().replace(Regex("^[~^](?=\\d)"), "")

internal fun validNodeVersion(version: String): Boolean =
    Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""").matches(version)

internal fun validNodePackage(key: String): Boolean =
    key.length <= 214 && Regex("""(?:@[a-z0-9_.-]+/)?[a-z0-9_][a-z0-9_.-]*""").matches(key)

internal fun compareNodeVersions(
    first: String,
    second: String,
): Int {
    fun parts(version: String) = version.substringBefore('+').split('-', limit = 2)
    val a = parts(first)
    val b = parts(second)
    a[0].split('.').zip(b[0].split('.')).forEach { (left, right) ->
        val comparison = left.toBigInteger().compareTo(right.toBigInteger())
        if (comparison != 0) return comparison
    }
    if (a.size != b.size) return if (a.size == 1) 1 else -1
    if (a.size == 1) return 0
    val left = a[1].split('.')
    val right = b[1].split('.')
    left.zip(right).forEach { (x, y) ->
        val nx = x.toBigIntegerOrNull()
        val ny = y.toBigIntegerOrNull()
        val comparison =
            when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

data class NodeCombinedResolution(
    val packages: List<ResolvedDependency>,
    val targets: Map<String, List<ResolvedDependency>>,
)

class NodeTargetResolver private constructor(
    private val nodeHome: Path,
    private val timeoutSeconds: Long,
    private val cacheDirectory: Path?,
) {
    constructor(
        nodeHome: Path = Path.of(System.getenv("PLATFORCE_NODE_HOME") ?: "/opt/node"),
        timeoutSeconds: Long = 120,
    ) : this(nodeHome, timeoutSeconds, null)

    @OptIn(ExperimentalPathApi::class)
    fun <T> withSession(action: (NodeTargetResolver) -> T): T {
        val cache = Files.createTempDirectory("platforce-npm-cache-")
        try {
            return action(NodeTargetResolver(nodeHome, timeoutSeconds, cache))
        } finally {
            cache.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun resolve(
        key: String,
        version: String,
    ): List<ResolvedDependency> = resolveWithOverrides(key, version, emptyMap())

    @OptIn(ExperimentalPathApi::class)
    fun resolveWithOverrides(
        key: String,
        version: String,
        overrides: Map<String, String>,
    ): List<ResolvedDependency> = resolveProject(mapOf(key to version), overrides).packages

    fun resolveCombined(
        dependencies: Map<String, String>,
        overrides: Map<String, String>,
    ): NodeCombinedResolution = resolveProject(dependencies, overrides)

    @OptIn(ExperimentalPathApi::class)
    private fun resolveProject(
        dependencies: Map<String, String>,
        overrides: Map<String, String>,
    ): NodeCombinedResolution {
        require(dependencies.all { (key, version) -> validNodePackage(key) && validNodeVersion(version) }) {
            "Expected exact public npm dependencies"
        }
        require(
            overrides.all { (name, value) ->
                validNodePackage(name) && validNodeVersion(value)
            },
        ) { "Expected exact public npm overrides" }
        val directory = Files.createTempDirectory("platforce-npm-resolution-")
        try {
            directory.resolve("package.json").writeText(
                Gson().toJson(
                    mapOf(
                        "name" to "platforce-target-resolution",
                        "version" to "1.0.0",
                        "private" to true,
                        "dependencies" to dependencies.toSortedMap(),
                        "overrides" to overrides.toSortedMap(),
                    ),
                ),
            )
            val output = directory.resolve("npm-output.txt")
            val processBuilder =
                ProcessBuilder(
                    nodeHome.resolve("bin/node").toString(),
                    nodeHome.resolve("lib/node_modules/npm/bin/npm-cli.js").toString(),
                    "install",
                    "--package-lock-only",
                    "--lockfile-version=3",
                    "--ignore-scripts",
                    "--no-audit",
                    "--no-fund",
                    "--registry=https://registry.npmjs.org",
                    "--fetch-retries=0",
                    "--fetch-timeout=30000",
                    "--cache=${cacheDirectory ?: directory.resolve("cache")}",
                    "--prefer-offline",
                    "--userconfig=${directory.resolve("user.npmrc")}",
                    "--globalconfig=${directory.resolve("global.npmrc")}",
                ).directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
            processBuilder.environment().clear()
            processBuilder.environment().putAll(
                mapOf(
                    "PATH" to "${nodeHome.resolve("bin")}:/usr/bin:/bin",
                    "HOME" to directory.toString(),
                    "TMPDIR" to directory.toString(),
                ),
            )
            val process = processBuilder.start()
            try {
                check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "npm resolution timed out after ${timeoutSeconds}s" }
                check(process.exitValue() == 0) {
                    val details =
                        Files.newInputStream(output).use {
                            it.skipNBytes((Files.size(output) - 4000).coerceAtLeast(0))
                            String(it.readNBytes(4000), Charsets.UTF_8)
                        }
                    "npm resolution failed: $details"
                }
            } finally {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            return parseCombinedLockfile(directory.resolve("package-lock.json").readText(), dependencies, overrides)
        } finally {
            directory.deleteRecursively()
        }
    }

    internal fun parseLockfile(
        source: String,
        key: String,
        version: String,
    ): List<ResolvedDependency> = parseCombinedLockfile(source, mapOf(key to version), emptyMap()).packages

    internal fun parseCombinedLockfile(
        source: String,
        dependencies: Map<String, String>,
        overrides: Map<String, String>,
    ): NodeCombinedResolution {
        val root = JsonParser.parseString(source).asJsonObject
        check(root["lockfileVersion"]?.asInt == 3) { "Expected npm lockfile version 3" }
        val packages = root["packages"]?.asJsonObject ?: error("npm lockfile has no packages")
        dependencies.forEach { (key, version) ->
            check(packages["node_modules/$key"]?.asJsonObject?.get("version")?.asString == version) {
                "npm did not resolve $key to the exact target $version"
            }
        }
        val entries = packages.entrySet().filter { it.key.isNotEmpty() }.associate { it.key to it.value.asJsonObject }
        val resolved =
            entries
                .entries
                .map { (path, value) ->
                    val entry = value.asJsonObject
                    check(entry["link"]?.asBoolean != true) { "Linked npm dependencies are not supported: $path" }
                    val name = entry["name"]?.asString ?: path.substringAfterLast("node_modules/")
                    val resolvedVersion = entry["version"]?.asString ?: error("Missing resolved npm version for $path")
                    check(validNodePackage(name) && validNodeVersion(resolvedVersion)) { "Unsupported npm lockfile entry: $path" }
                    entry["resolved"]?.asString?.let {
                        val uri = URI(it)
                        check(
                            uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.userInfo == null && uri.port in setOf(-1, 443),
                        ) {
                            "Only public npm registry packages are supported: $name"
                        }
                    }
                    path to ResolvedDependency("npm", name, resolvedVersion, resolvedVersion, emptyList())
                }.toMap()
        overrides.forEach { (key, version) ->
            check(resolved.values.filter { it.name == key }.all { it.version == version }) {
                "npm did not apply exact override $key@$version"
            }
        }

        fun locate(
            path: String,
            name: String,
        ): String? {
            var parent = path
            while (true) {
                val candidate = if (parent.isEmpty()) "node_modules/$name" else "$parent/node_modules/$name"
                if (candidate in resolved) return candidate
                if (parent.isEmpty()) return null
                parent = parent.substringBeforeLast("/node_modules/", "")
            }
        }

        val edges =
            entries.mapValues { (path, entry) ->
                val optional = entry["optionalDependencies"]?.asJsonObject?.keySet().orEmpty()
                val peers = entry["peerDependencies"]?.asJsonObject?.keySet().orEmpty()
                val required = entry["dependencies"]?.asJsonObject?.keySet().orEmpty() - optional
                (required + optional + peers).mapNotNull { name ->
                    val dependency = locate(path, name)
                    val optionalPeer =
                        entry["peerDependenciesMeta"]
                            ?.asJsonObject
                            ?.get(name)
                            ?.asJsonObject
                            ?.get("optional")
                            ?.asBoolean == true
                    check(dependency != null || name in optional || (name in peers && name !in required && optionalPeer)) {
                        "Missing npm dependency $name for $path"
                    }
                    dependency
                }
            }
        val targets =
            (dependencies + overrides).mapValues { (key, version) ->
                val pending =
                    ArrayDeque(
                        if (key in dependencies) {
                            listOf("node_modules/$key")
                        } else {
                            resolved.filterValues { it.name == key && it.version == version }.keys.toList()
                        },
                    )
                val visited = mutableSetOf<String>()
                while (pending.isNotEmpty()) {
                    val path = pending.removeFirst()
                    if (visited.add(path)) pending.addAll(edges.getValue(path))
                }
                visited.map { resolved.getValue(it) }.distinctBy { it.name to it.version }
            }
        return NodeCombinedResolution(resolved.values.distinctBy { it.name to it.version }, targets)
    }
}
