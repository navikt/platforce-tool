package no.nav.platforce.tool.pullrequests

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import no.nav.platforce.tool.node.PackageJsonParser
import no.nav.platforce.tool.node.nodeDependencySections
import no.nav.platforce.tool.node.nodeDependencyStatus
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
            .sortedBy { if (it.declaration in nodeDependencySections) 0 else 1 }
            .forEach { finding ->
                require(validNodePackage(finding.key)) { "Unsupported npm package: ${finding.key}" }
                val version = nodeTargetVersion(finding.targetVersion)
                require(validNodeVersion(version)) { "Expected an exact npm target for ${finding.key}" }
                val path =
                    if (finding.status == DependencyStatus.ADD) {
                        when (finding.declaration) {
                            in nodeDependencySections -> {
                                val section = checkNotNull(finding.declaration)
                                if (!root.has(section)) root.add(section, JsonObject())
                                listOf(section, finding.key)
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
                    val direct =
                        nodeDependencySections.mapNotNull { section ->
                            root.getAsJsonObject(section)?.get(finding.key)?.let { section to it.asString }
                        }
                    val specification =
                        if (globalOverride && direct.isNotEmpty()) {
                            check(direct.all { nodeTargetVersion(it.second) == version }) {
                                "Global override ${finding.key} must match its direct dependency. Refresh and align its target version."
                            }
                            direct.forEach { (section, _) -> changed[listOf(section, finding.key)] = version }
                            "\$${finding.key}"
                        } else {
                            "^$version"
                        }
                    if (finding.status == DependencyStatus.ADD && owner.has(key) && path !in changed) stale()
                    owner.addProperty(key, specification)
                    changed[path] = version
                }
            }
        findings
            .filter { it.kind == DependencyKind.NODE && it.status != DependencyStatus.REMOVE && it.currentVersion != null }
            .filter { nodeDependencyStatus(checkNotNull(it.currentVersion), it.targetVersion) != DependencyStatus.UNRESOLVED }
            .forEach { finding ->
                val declaration = declarations.singleOrNull { it.key == finding.key && it.declaration == finding.declaration } ?: stale()
                if (declaration.version != finding.currentVersion) stale()
                val version = nodeTargetVersion(finding.targetVersion)
                changed.putIfAbsent(declaration.path, version)
                val specification = parent(declaration.path)[declaration.path.last()].asString
                if (specification.startsWith('$')) {
                    val reference = specification.drop(1)
                    val section =
                        nodeDependencySections.asReversed().firstOrNull { root.getAsJsonObject(it)?.has(reference) == true }
                            ?: stale()
                    val path = listOf(section, reference)
                    check(changed.putIfAbsent(path, version) in setOf(null, version)) {
                        "Override reference $reference must match its scanned target version. Refresh and align its targets."
                    }
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
