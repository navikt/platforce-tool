package no.nav.platforce.tool.pullrequests

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import no.nav.platforce.tool.node.PackageJsonParser
import no.nav.platforce.tool.node.nodeJson
import no.nav.platforce.tool.node.nodeTargetVersion
import no.nav.platforce.tool.node.validNodePackage
import no.nav.platforce.tool.node.validNodeVersion
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException

data class NodePackageJsonUpdate(
    val manifest: String,
    val resolutionManifest: String,
)

class NodePackageJsonUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): NodePackageJsonUpdate {
        val root = JsonParser.parseString(content).asJsonObject
        val declarations = PackageJsonParser().parse(content)
        val changed = mutableMapOf<List<String>, String>()

        fun parent(path: List<String>): JsonObject {
            var current = root
            path.dropLast(1).forEach { key -> current = current.getAsJsonObject(key) }
            return current
        }

        fun stale(): Nothing =
            throw PullRequestReadinessException("package.json changed since scanning. Refresh the repository and try again.")

        findings
            .filter { it.kind == DependencyKind.NODE }
            .filter { it.status in setOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD, DependencyStatus.ADD, DependencyStatus.REMOVE) }
            .sortedBy { if (it.declaration == "dependencies") 0 else 1 }
            .forEach { finding ->
                require(validNodePackage(finding.key)) { "Unsupported npm package: ${finding.key}" }
                val version = nodeTargetVersion(finding.targetVersion)
                require(validNodeVersion(version)) { "Expected an exact npm target for ${finding.key}" }
                val path =
                    if (finding.status == DependencyStatus.ADD) {
                        when (finding.declaration) {
                            "dependencies" -> {
                                if (!root.has("dependencies")) root.add("dependencies", JsonObject())
                                listOf("dependencies", finding.key)
                            }

                            "overrides > ${finding.key}" -> {
                                if (!root.has("overrides")) root.add("overrides", JsonObject())
                                val existing = root.getAsJsonObject("overrides")[finding.key]
                                if (existing?.isJsonObject == true) {
                                    listOf("overrides", finding.key, ".")
                                } else {
                                    listOf("overrides", finding.key)
                                }
                            }

                            else -> {
                                error("Unsupported npm addition declaration: ${finding.declaration}")
                            }
                        }
                    } else {
                        val declaration =
                            declarations.singleOrNull { it.key == finding.key && it.declaration == finding.declaration } ?: stale()
                        if (declaration.version != finding.currentVersion) stale()
                        declaration.path
                    }
                val owner = parent(path)
                val key = path.last()
                if (finding.status == DependencyStatus.REMOVE) {
                    check(path.first() == "overrides") { "Ordinary npm dependencies cannot be removed as redundant covers" }
                    owner.remove(key)
                    path.dropLast(1).indices.reversed().forEach { index ->
                        val ancestor = path.take(index + 1)
                        val container = parent(ancestor)
                        if (container.getAsJsonObject(ancestor.last()).size() == 0) container.remove(ancestor.last())
                    }
                } else {
                    val globalOverride = path == listOf("overrides", finding.key) || path == listOf("overrides", finding.key, ".")
                    val direct = root.getAsJsonObject("dependencies")?.get(finding.key)
                    val specification =
                        if (globalOverride && direct != null) {
                            check(nodeTargetVersion(direct.asString) == version) {
                                "Global override ${finding.key} must match its direct dependency. Refresh and align its target version."
                            }
                            changed[listOf("dependencies", finding.key)] = version
                            "\$${finding.key}"
                        } else {
                            "^$version"
                        }
                    if (finding.status == DependencyStatus.ADD && owner.has(key) && path !in changed) stale()
                    owner.addProperty(key, specification)
                    changed[path] = version
                }
            }
        val resolution = root.deepCopy()
        changed.forEach { (path, version) ->
            var owner = resolution
            path.dropLast(1).forEach { owner = owner.getAsJsonObject(it) }
            if (!owner[path.last()].asString.startsWith('$')) owner.addProperty(path.last(), version)
        }
        return NodePackageJsonUpdate(nodeJson(root, content), nodeJson(resolution, content))
    }
}
