package no.nav.platforce.tool.sbom

import com.google.gson.JsonObject

data class SbomGraph(
    val repo: String,
    val packagesBySpdxId: Map<String, SbomNode>,
    val packageNameToSpdxIds: Map<String, MutableList<String>>,
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
        val packagesArray = sbomJson["packages"].asJsonArray
        val relationshipsArray = sbomJson["relationships"].asJsonArray

        val packagesBySpdxId = mutableMapOf<String, SbomNode>()
        val packageNameToSpdxIds = mutableMapOf<String, MutableList<String>>()

        val parentToChildren = mutableMapOf<String, MutableList<String>>()
        val childToParents = mutableMapOf<String, MutableList<String>>()

        packagesArray.forEach { packageElement ->

            val obj = packageElement.asJsonObject

            val name = obj["name"].asString
            val version =
                obj
                    .get("versionInfo")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString

            val spdxId = obj["SPDXID"].asString

            val node =
                SbomNode(
                    name = name,
                    version = version,
                    spdxId = spdxId,
                )

            packagesBySpdxId[spdxId] = node

            packageNameToSpdxIds
                .getOrPut(name) { mutableListOf() }
                .add(spdxId)
        }

        relationshipsArray.forEach { relationshipElement ->

            val obj = relationshipElement.asJsonObject

            val parent = obj["spdxElementId"].asString
            val child = obj["relatedSpdxElement"].asString

            parentToChildren
                .getOrPut(parent) { mutableListOf() }
                .add(child)

            childToParents
                .getOrPut(child) { mutableListOf() }
                .add(parent)
        }

        return SbomGraph(
            repo = repo,
            packagesBySpdxId = packagesBySpdxId,
            packageNameToSpdxIds = packageNameToSpdxIds,
            parentToChildren = parentToChildren,
            childToParents = childToParents,
        )
    }

    fun findDirectParents(
        graph: SbomGraph,
        packageName: String,
    ): List<String> {
        val spdxIds =
            graph.packageNameToSpdxIds[packageName]
                ?: return emptyList()

        return spdxIds
            .flatMap { spdxId ->
                graph.childToParents[spdxId].orEmpty()
            }.mapNotNull { parentId ->
                graph.packagesBySpdxId[parentId]?.name
            }.distinct()
    }

    fun findRootPaths(
        graph: SbomGraph,
        packageName: String,
    ): List<List<String>> {
        val spdxIds =
            graph.packageNameToSpdxIds[packageName]
                ?: return emptyList()

        return spdxIds.flatMap {
            findRootPathsBySpdx(
                graph,
                it,
                mutableSetOf(),
            )
        }
    }

    private fun findRootPathsBySpdx(
        graph: SbomGraph,
        spdxId: String,
        visited: MutableSet<String>,
    ): List<List<String>> {
        if (!visited.add(spdxId)) {
            return emptyList()
        }

        val node =
            graph.packagesBySpdxId[spdxId]
                ?: return emptyList()

        val parents =
            graph.childToParents[spdxId]
                ?: return listOf(listOf(node.name))

        val results = mutableListOf<List<String>>()

        parents.forEach { parentId ->

            val paths =
                findRootPathsBySpdx(
                    graph,
                    parentId,
                    visited.toMutableSet(),
                )

            paths.forEach {
                results += it + node.name
            }
        }

        return results
    }
}
