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

class NodeTargetResolver(
    private val nodeHome: Path = Path.of(System.getenv("PLATFORCE_NODE_HOME") ?: "/opt/node"),
    private val timeoutSeconds: Long = 120,
) {
    @OptIn(ExperimentalPathApi::class)
    fun resolve(
        key: String,
        version: String,
    ): List<ResolvedDependency> {
        require(validNodePackage(key)) { "Unsupported npm package name: $key" }
        require(validNodeVersion(version)) { "Expected an exact npm version for $key, got $version" }
        val directory = Files.createTempDirectory("platforce-npm-resolution-")
        try {
            directory.resolve("package.json").writeText(
                Gson().toJson(
                    mapOf(
                        "name" to "platforce-target-resolution",
                        "version" to "1.0.0",
                        "private" to true,
                        "dependencies" to mapOf(key to version),
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
                    "--cache=${directory.resolve("cache")}",
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
                check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "npm resolution timed out for $key after ${timeoutSeconds}s" }
                check(process.exitValue() == 0) {
                    val details =
                        Files.newInputStream(output).use {
                            it.skipNBytes((Files.size(output) - 4000).coerceAtLeast(0))
                            String(it.readNBytes(4000), Charsets.UTF_8)
                        }
                    "npm resolution failed for $key: $details"
                }
            } finally {
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            return parseLockfile(directory.resolve("package-lock.json").readText(), key, version)
        } finally {
            directory.deleteRecursively()
        }
    }

    internal fun parseLockfile(
        source: String,
        key: String,
        version: String,
    ): List<ResolvedDependency> {
        val root = JsonParser.parseString(source).asJsonObject
        check(root["lockfileVersion"]?.asInt == 3) { "Expected npm lockfile version 3" }
        val packages = root["packages"]?.asJsonObject ?: error("npm lockfile has no packages")
        check(packages["node_modules/$key"]?.asJsonObject?.get("version")?.asString == version) {
            "npm did not resolve $key to the exact target $version"
        }
        return packages
            .entrySet()
            .filter { it.key.isNotEmpty() }
            .map { (path, value) ->
                val entry = value.asJsonObject
                check(entry["link"]?.asBoolean != true) { "Linked npm dependencies are not supported: $path" }
                val name = entry["name"]?.asString ?: path.substringAfterLast("node_modules/")
                val resolvedVersion = entry["version"]?.asString ?: error("Missing resolved npm version for $path")
                check(validNodePackage(name) && validNodeVersion(resolvedVersion)) { "Unsupported npm lockfile entry: $path" }
                entry["resolved"]?.asString?.let {
                    val uri = URI(it)
                    check(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.userInfo == null && uri.port in setOf(-1, 443)) {
                        "Only public npm registry packages are supported: $name"
                    }
                }
                ResolvedDependency("npm", name, resolvedVersion, resolvedVersion, emptyList())
            }.distinctBy { it.name to it.version }
    }
}
