package no.nav.platforce.tool.sbom

import com.google.gson.JsonObject

data class SbomGraph(
    val repo: String,
    val packages: Map<String, SbomNode>,
    val parentToChildren: Map<String, MutableList<String>>,
    val childToParents: Map<String, MutableList<String>>,
)

data class SbomNode(
    val name: String,
    val version: String?,
    val spdxId: String,
)

object SbomCache {
    private val cache = mutableMapOf<String, SbomGraph>()

    fun get(repo: String): SbomGraph? = cache[repo]

    fun put(
        repo: String,
        graph: SbomGraph,
    ) {
        cache[repo] = graph
    }

    fun buildSbomGraph(
        repo: String,
        sbomJson: JsonObject,
    ): SbomGraph {
        val packagesObj = sbomJson["packages"].asJsonArray
        val relationships = sbomJson["relationships"].asJsonArray

        val packages = mutableMapOf<String, SbomNode>()
        val parentToChildren = mutableMapOf<String, MutableList<String>>()
        val childToParents = mutableMapOf<String, MutableList<String>>()

        // 1. Nodes
        for (p in packagesObj) {
            val obj = p.asJsonObject
            val name = obj["name"].asString
            val version = obj["versionInfo"]?.asString
            val spdxId = obj["SPDXID"].asString

            packages[name] = SbomNode(name, version, spdxId)
        }

        // 2. Edges
        for (r in relationships) {
            val obj = r.asJsonObject

            val from = obj["spdxElementId"].asString
            val to = obj["relatedSpdxElement"].asString

            parentToChildren.getOrPut(from) { mutableListOf() }.add(to)
            childToParents.getOrPut(to) { mutableListOf() }.add(from)
        }

        return SbomGraph(
            repo = repo,
            packages = packages,
            parentToChildren = parentToChildren,
            childToParents = childToParents,
        )
    }

    fun findParents(
        graph: SbomGraph,
        packageName: String,
    ): List<String> = graph.childToParents[packageName] ?: emptyList()

    fun findRootPaths(
        graph: SbomGraph,
        packageName: String,
        visited: MutableSet<String> = mutableSetOf(),
    ): List<List<String>> {
        if (!visited.add(packageName)) return emptyList()

        val parents = graph.childToParents[packageName] ?: return listOf(listOf(packageName))

        val results = mutableListOf<List<String>>()

        for (parent in parents) {
            val subPaths = findRootPaths(graph, parent, visited.toMutableSet())
            for (path in subPaths) {
                results += path + packageName
            }
        }

        return results
    }
}
