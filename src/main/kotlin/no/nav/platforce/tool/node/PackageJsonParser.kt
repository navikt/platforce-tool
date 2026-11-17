package no.nav.platforce.tool.node

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import no.nav.platforce.tool.repositories.DependencyStatus

internal val nodeDependencySections = listOf("dependencies", "devDependencies")

data class NodeDependencyDeclaration(
    val key: String,
    val version: String,
    val declaration: String,
    val path: List<String> = emptyList(),
)

class PackageJsonParser {
    fun parse(source: String): List<NodeDependencyDeclaration> {
        val root = JsonParser.parseString(source)
        require(root.isJsonObject) { "package.json must contain an object" }
        val result = mutableListOf<NodeDependencyDeclaration>()

        fun section(name: String): JsonObject? {
            val value = root.asJsonObject.get(name) ?: return null
            require(value.isJsonObject) { "package.json $name must be an object" }
            return value.asJsonObject
        }

        fun version(value: JsonElement): String {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
                "package.json dependency versions must be non-empty strings"
            }
            return value.asString
        }

        val dependencies = nodeDependencySections.associateWith(::section)
        dependencies.forEach { (name, entries) ->
            entries?.entrySet()?.forEach { (key, value) ->
                result += NodeDependencyDeclaration(key, version(value), name, listOf(name, key))
            }
        }

        fun overrides(
            entries: JsonObject,
            path: List<String>,
            parent: String? = null,
        ) {
            entries.entrySet().forEach { (selector, value) ->
                val key =
                    if (selector == ".") {
                        requireNotNull(parent) { "package.json override '.' requires a parent package" }
                    } else {
                        selector.substringBeforeLast('@', selector).takeIf { it.isNotEmpty() } ?: selector
                    }
                val location = path + selector
                if (value.isJsonObject) {
                    require(selector != ".") { "package.json override '.' must contain a version" }
                    overrides(value.asJsonObject, location, key)
                } else {
                    val specification = version(value)
                    val reference =
                        specification.takeIf { it.startsWith('$') }?.drop(1)?.let { key ->
                            dependencies["devDependencies"]?.get(key) ?: dependencies["dependencies"]?.get(key)
                        }
                    result +=
                        NodeDependencyDeclaration(
                            key,
                            reference?.let(::version) ?: specification,
                            location.joinToString(" > "),
                            location,
                        )
                }
            }
        }

        section("overrides")?.let { overrides(it, listOf("overrides")) }
        return result
    }
}

fun nodeDependencyStatus(
    current: String,
    target: String,
): DependencyStatus {
    val simpleVersion = Regex("""^[~^]?(\d+)\.(\d+)\.(\d+)$""")
    val from = simpleVersion.matchEntire(current)?.groupValues?.drop(1) ?: return DependencyStatus.UNRESOLVED
    val to = simpleVersion.matchEntire(target)?.groupValues?.drop(1) ?: return DependencyStatus.UNRESOLVED
    val comparison =
        from.zip(to).firstNotNullOfOrNull { (a, b) ->
            a.toBigInteger().compareTo(b.toBigInteger()).takeIf { it != 0 }
        } ?: 0
    return when {
        comparison < 0 -> DependencyStatus.UPDATE
        comparison > 0 -> DependencyStatus.AHEAD
        else -> DependencyStatus.OK
    }
}
