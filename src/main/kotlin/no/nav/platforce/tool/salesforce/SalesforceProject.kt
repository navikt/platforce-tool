package no.nav.platforce.tool.salesforce

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import no.nav.platforce.tool.node.nodeJson
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException

const val SALESFORCE_MANIFEST = "sfdx-project.json"

data class SalesforceDependencyDeclaration(
    val key: String,
    val version: String,
    val declaration: String,
)

private fun JsonObject.string(field: String): String? {
    val value = get(field) ?: return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
        "$SALESFORCE_MANIFEST $field must be a non-empty string"
    }
    return value.asString
}

fun parseSalesforceProject(source: String): List<SalesforceDependencyDeclaration> {
    val root = JsonParser.parseString(source)
    require(root.isJsonObject) { "$SALESFORCE_MANIFEST must contain an object" }
    val directories = root.asJsonObject["packageDirectories"] ?: return emptyList()
    require(directories.isJsonArray) { "$SALESFORCE_MANIFEST packageDirectories must be an array" }
    val aliases = root.asJsonObject["packageAliases"]
    require(aliases == null || aliases.isJsonObject) { "$SALESFORCE_MANIFEST packageAliases must be an object" }
    val paths = mutableSetOf<String>()
    return directories.asJsonArray.flatMap { entry ->
        require(entry.isJsonObject) { "$SALESFORCE_MANIFEST package directories must be objects" }
        val directory = entry.asJsonObject
        val path = requireNotNull(directory.string("path")) { "$SALESFORCE_MANIFEST package directory path is required" }
        require(paths.add(path)) { "$SALESFORCE_MANIFEST duplicate package directory: $path" }
        val dependencies = directory["dependencies"]
        require(dependencies == null || dependencies.isJsonArray) { "$SALESFORCE_MANIFEST dependencies must be an array" }
        val keys = mutableSetOf<String>()
        dependencies
            ?.asJsonArray
            ?.map { dependency ->
                require(dependency.isJsonObject) { "$SALESFORCE_MANIFEST dependencies must be objects" }
                val item = dependency.asJsonObject
                val key = item.string("package") ?: item.string("subscriberPackageVersionId")
                require(key != null) { "$SALESFORCE_MANIFEST dependency package is required" }
                require(keys.add(key)) { "$SALESFORCE_MANIFEST duplicate dependency $key in $path" }
                val version =
                    item.string("versionNumber") ?: item.string("subscriberPackageVersionId")
                        ?: aliases?.asJsonObject?.string(key) ?: key
                SalesforceDependencyDeclaration(key, version, path)
            }.orEmpty()
    }
}

fun validSalesforceVersion(version: String): Boolean = version.length <= 100 && Regex("""\d+\.\d+\.\d+\.(?:\d+|LATEST)""").matches(version)

fun validateSalesforceTargets(targets: Map<String, String>) {
    targets.forEach { (key, version) ->
        require(key.isNotBlank() && key.length <= 500 && key.none { it.isISOControl() }) { "Invalid Salesforce package name" }
        require(validSalesforceVersion(version)) { "Salesforce target $key must use major.minor.patch.build or major.minor.patch.LATEST" }
    }
}

fun salesforceDependencyStatus(
    current: String,
    target: String,
): DependencyStatus {
    if (!validSalesforceVersion(current) || !validSalesforceVersion(target)) return DependencyStatus.UNRESOLVED
    val from = current.split('.')
    val to = target.split('.')
    val release =
        from.take(3).zip(to.take(3)).firstNotNullOfOrNull { (a, b) ->
            a.toBigInteger().compareTo(b.toBigInteger()).takeIf { it != 0 }
        } ?: 0
    val comparison =
        if (release != 0) {
            release
        } else if (from.last() == to.last()) {
            0
        } else if (from.last() == "LATEST" || to.last() == "LATEST") {
            return DependencyStatus.UNRESOLVED
        } else {
            from.last().toBigInteger().compareTo(to.last().toBigInteger())
        }
    return when {
        comparison < 0 -> DependencyStatus.UPDATE
        comparison > 0 -> DependencyStatus.AHEAD
        else -> DependencyStatus.OK
    }
}

fun updateSalesforceProject(
    source: String,
    findings: List<DependencyFinding>,
): String {
    val declarations = parseSalesforceProject(source)
    val updates =
        findings.filter {
            it.kind == DependencyKind.SALESFORCE &&
                it.status in setOf(DependencyStatus.UPDATE, DependencyStatus.AHEAD)
        }
    if (updates.isEmpty()) return source
    val root = JsonParser.parseString(source).asJsonObject
    updates.forEach { finding ->
        val declaration = declarations.singleOrNull { it.key == finding.key && it.declaration == finding.declaration }
        if (declaration == null || declaration.version != finding.currentVersion || finding.sourceFile != SALESFORCE_MANIFEST) {
            throw PullRequestReadinessException("$SALESFORCE_MANIFEST changed since scanning. Refresh the repository and try again.")
        }
        require(validSalesforceVersion(finding.targetVersion)) { "Invalid Salesforce target for ${finding.key}" }
        val directory =
            root
                .getAsJsonArray(
                    "packageDirectories",
                ).single { it.asJsonObject.string("path") == declaration.declaration }
                .asJsonObject
        val dependency = directory.getAsJsonArray("dependencies").single { it.asJsonObject.string("package") == finding.key }.asJsonObject
        require(dependency.has("versionNumber")) { "Pinned Salesforce package aliases cannot be updated by versionNumber" }
        dependency.addProperty("versionNumber", finding.targetVersion)
    }
    return nodeJson(root, source)
}
