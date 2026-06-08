package no.nav.platforce.tool.dependencies

class GradleDependencyParser {
    private val pluginRegex =
        Regex(
            """id\s+["']([^"']+)["']\s+version\s+["']([^"']+)["']""",
        )

    private val dependencyRegex =
        Regex(
            """^\s*[A-Za-z_][A-Za-z0-9_]*\s+["']([^:'"]+):([^:'"]+):([^"']+)["']""",
            RegexOption.MULTILINE,
        )

    fun parse(content: String): ParsedBuildFile {
        val plugins = mutableMapOf<String, String>()
        val dependencies = mutableMapOf<String, String>()

        pluginRegex.findAll(content).forEach {
            plugins[it.groupValues[1]] = it.groupValues[2]
        }

        dependencyRegex.findAll(content).forEach {
            val key =
                "${it.groupValues[1]}:${it.groupValues[2]}"

            dependencies[key] = it.groupValues[3]
        }

        return ParsedBuildFile(
            plugins = plugins,
            dependencies = dependencies,
        )
    }
}

data class ParsedBuildFile(
    val plugins: Map<String, String>,
    val dependencies: Map<String, String>,
)
