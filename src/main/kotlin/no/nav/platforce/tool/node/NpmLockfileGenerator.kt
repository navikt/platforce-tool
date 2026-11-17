package no.nav.platforce.tool.node

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NpmLockfileGenerator(
    private val nodeHome: Path = Path.of(System.getenv("PLATFORCE_NODE_HOME") ?: "/opt/node"),
    private val timeoutSeconds: Long = 120,
) {
    @OptIn(ExperimentalPathApi::class)
    fun generate(
        manifest: String,
        resolutionManifest: String,
        lockfile: String,
        filename: String,
    ): String {
        require(filename in setOf("package-lock.json", "npm-shrinkwrap.json")) { "Unsupported npm lockfile: $filename" }
        val finalManifest = JsonParser.parseString(manifest).asJsonObject
        val resolution = JsonParser.parseString(resolutionManifest).asJsonObject
        validateManifest(finalManifest)
        validateManifest(resolution)
        val original = JsonParser.parseString(lockfile).asJsonObject
        val version = original["lockfileVersion"]?.asInt
        require(version in 1..3) { "Unsupported npm lockfile version: $version" }
        validateLockfile(original)
        val directory = Files.createTempDirectory("platforce-npm-pr-")
        try {
            directory.resolve("package.json").writeText(resolutionManifest)
            directory.resolve(filename).writeText(lockfile)
            NpmInstaller(nodeHome, timeoutSeconds).install(directory, lockfileVersion = version)
            val generated = JsonParser.parseString(directory.resolve(filename).readText()).asJsonObject
            check(generated["lockfileVersion"].asInt == version) { "npm changed the lockfile format unexpectedly" }
            validateLockfile(generated)
            verifyResolution(resolutionManifest, generated)
            generated.getAsJsonObject("packages")?.getAsJsonObject("")?.let { root ->
                listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { section ->
                    finalManifest[section]?.let { root.add(section, it.deepCopy()) }
                }
            }
            return nodeJson(generated, lockfile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun verifyResolution(
        manifest: String,
        lockfile: JsonObject,
    ) {
        val packages = mutableMapOf<String, Pair<String, String>>()
        val modern = lockfile.getAsJsonObject("packages")
        if (modern != null) {
            modern.entrySet().filter { it.key.isNotEmpty() }.forEach { (path, value) ->
                val entry = value.asJsonObject
                packages[path] = (entry["name"]?.asString ?: path.substringAfterLast("node_modules/")) to entry["version"].asString
            }
        } else {
            fun dependencies(
                entries: JsonObject,
                parent: String,
            ) {
                entries.entrySet().forEach { (name, value) ->
                    val entry = value.asJsonObject
                    val path = "${parent}node_modules/$name"
                    packages[path] = name to entry["version"].asString
                    entry.getAsJsonObject("dependencies")?.let { dependencies(it, "$path/") }
                }
            }
            lockfile.getAsJsonObject("dependencies")?.let { dependencies(it, "") }
        }
        PackageJsonParser().parse(manifest).filter { validNodeVersion(it.version) }.forEach { declaration ->
            if (declaration.declaration == "dependencies") {
                check(packages["node_modules/${declaration.key}"]?.second == declaration.version) {
                    "npm did not lock ${declaration.key} at the scanned target ${declaration.version}"
                }
            } else if (declaration.declaration.removeSuffix(" > .") == "overrides > ${declaration.key}") {
                check(packages.values.filter { it.first == declaration.key }.all { it.second == declaration.version }) {
                    "npm did not apply override ${declaration.key} at the scanned target ${declaration.version}"
                }
            }
        }
    }

    private fun registrySpecification(specification: String) {
        val trimmed = specification.trim()
        if (trimmed.startsWith("npm:")) {
            val alias = trimmed.removePrefix("npm:")
            val separator = alias.lastIndexOf('@')
            val name = if (separator > 0) alias.substring(0, separator) else alias
            require(validNodePackage(name)) { "Unsupported npm alias: $specification" }
            if (separator > 0) registrySpecification(alias.substring(separator + 1))
        } else {
            require(
                trimmed !in setOf(".", "..") && (trimmed.isEmpty() || Regex("""[a-zA-Z0-9*^~<>=|.+ _-]+""").matches(trimmed)),
            ) { "Only public registry dependency specifications are supported: $specification" }
        }
    }

    private fun validateManifest(root: JsonObject) {
        require(!root.has("workspaces")) { "npm workspace lockfile updates are not supported" }
        listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { section ->
            val entries = root[section] ?: return@forEach
            require(entries.isJsonObject) { "package.json $section must contain an object" }
            entries.asJsonObject.entrySet().forEach { (key, value) ->
                require(validNodePackage(key)) { "Unsupported npm package: $key" }
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Expected a dependency specification for $key" }
                registrySpecification(value.asString)
            }
        }

        fun overrides(entries: JsonObject) {
            entries.entrySet().forEach { (selector, value) ->
                if (selector != ".") {
                    val separator = selector.lastIndexOf('@')
                    val key = if (separator > 0) selector.substring(0, separator) else selector
                    require(validNodePackage(key)) { "Unsupported npm override selector: $selector" }
                    if (separator > 0) registrySpecification(selector.substring(separator + 1))
                }
                if (value.isJsonObject) {
                    overrides(value.asJsonObject)
                } else {
                    val specification = value.asString
                    if (specification.startsWith('$')) {
                        val key = specification.drop(1)
                        require(validNodePackage(key) && root.getAsJsonObject("dependencies")?.has(key) == true) {
                            "Unsupported npm override reference: $specification"
                        }
                    } else {
                        registrySpecification(specification)
                    }
                }
            }
        }
        root.getAsJsonObject("overrides")?.let(::overrides)
    }

    private fun validateLockfile(root: JsonObject) {
        fun entry(value: JsonObject) {
            require(value["link"]?.asBoolean != true) { "Linked npm dependencies are not supported" }
            value["resolved"]?.asString?.let {
                val uri = URI.create(it)
                require(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.userInfo == null && uri.port in setOf(-1, 443)) {
                    "Only public npm registry lockfiles are supported"
                }
            }
            value["version"]?.asString?.let(::registrySpecification)
        }

        fun dependencies(entries: JsonObject) {
            entries.entrySet().forEach { (key, value) ->
                require(validNodePackage(key)) { "Unsupported npm lockfile dependency: $key" }
                entry(value.asJsonObject)
                value.asJsonObject.getAsJsonObject("requires")?.entrySet()?.forEach { (_, specification) ->
                    registrySpecification(specification.asString)
                }
                value.asJsonObject.getAsJsonObject("dependencies")?.let(::dependencies)
            }
        }
        root.getAsJsonObject("packages")?.entrySet()?.forEach { (path, value) ->
            val names = path.removePrefix("node_modules/").split("/node_modules/")
            val validPath = path.isEmpty() || (path.startsWith("node_modules/") && names.all(::validNodePackage))
            require(validPath) { "Unsupported npm lockfile package path: $path" }
            entry(value.asJsonObject)
            listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies").forEach { section ->
                value.asJsonObject.getAsJsonObject(section)?.entrySet()?.forEach { (_, specification) ->
                    registrySpecification(specification.asString)
                }
            }
        }
        root.getAsJsonObject("dependencies")?.let(::dependencies)
    }
}
