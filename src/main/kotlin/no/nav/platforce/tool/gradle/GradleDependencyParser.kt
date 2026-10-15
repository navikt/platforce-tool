package no.nav.platforce.tool.gradle

class GradleDependencyParser {
    private val pluginRegex =
        Regex(
            """id\s+["']([^"']+)["']\s+version\s+["']([^"']+)["']""",
        )

    fun parse(content: String): ParsedBuildFile {
        val plugins = mutableMapOf<String, String>()
        val dependencies = mutableMapOf<String, String>()
        val platformBoms = mutableMapOf<String, String>()

        pluginRegex.findAll(withoutComments(content)).forEach {
            plugins[it.groupValues[1]] = it.groupValues[2]
        }

        declarations(content).forEach {
            (if (it.platformBom) platformBoms else dependencies)[it.key] = it.version
        }

        return ParsedBuildFile(
            plugins = plugins,
            dependencies = dependencies,
            platformBoms = platformBoms,
        )
    }

    fun declarations(content: String): List<GradleDependencyDeclaration> {
        val source = withoutComments(content)
        val declaration =
            Regex(
                """(?m)^[ \t]*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(\s*)?(?:(platform|enforcedPlatform)\s*\(\s*)?((["'])([^:'"\r\n]+):([^:'"\r\n]+):([^"'\r\n]+)\4|(?:\w+\s*[:=]\s*["'][^"']*["']\s*,?\s*){3,})""",
            )
        val namedArgument = Regex("""(\w+)\s*[:=]\s*(["'])([^"']*)\2""")
        return declaration
            .findAll(source)
            .mapNotNull { match ->
                if (match.groupValues[1] in setOf("platform", "enforcedPlatform")) return@mapNotNull null
                val platformBom = match.groupValues[2].isNotEmpty()
                if (match.groupValues[4].isNotEmpty()) {
                    GradleDependencyDeclaration(
                        "${match.groupValues[5]}:${match.groupValues[6]}",
                        match.groupValues[7],
                        platformBom,
                        match.groups[7]!!.range,
                    )
                } else {
                    val arguments = namedArgument.findAll(match.groupValues[3]).associateBy { it.groupValues[1] }
                    val group = arguments["group"]?.groupValues?.get(3) ?: return@mapNotNull null
                    val name = arguments["name"]?.groupValues?.get(3) ?: return@mapNotNull null
                    val version = arguments["version"] ?: return@mapNotNull null
                    val offset = match.groups[3]!!.range.first
                    val range = version.groups[3]!!.range
                    GradleDependencyDeclaration(
                        "$group:$name",
                        version.groupValues[3],
                        platformBom,
                        (offset + range.first)..(offset + range.last),
                    )
                }
            }.toList()
    }

    private fun withoutComments(content: String): String {
        val result = content.toCharArray()
        var index = 0
        var quote: Char? = null
        while (index < content.length) {
            val character = content[index]
            if (quote != null) {
                if (character == '\\') {
                    index += 2
                    continue
                }
                if (character == quote) quote = null
            } else if (character == '\'' || character == '"') {
                quote = character
            } else if (content.startsWith("//", index) || content.startsWith("/*", index)) {
                val block = content.startsWith("/*", index)
                val end =
                    if (block) {
                        content.indexOf("*/", index + 2).let { if (it < 0) content.length else it + 2 }
                    } else {
                        content.indexOf('\n', index).let { if (it < 0) content.length else it }
                    }
                while (index < end) {
                    if (result[index] != '\n' && result[index] != '\r') result[index] = ' '
                    index++
                }
                continue
            }
            index++
        }
        return String(result)
    }
}

data class GradleDependencyDeclaration(
    val key: String,
    val version: String,
    val platformBom: Boolean,
    val versionRange: IntRange,
)

data class ParsedBuildFile(
    val plugins: Map<String, String>,
    val dependencies: Map<String, String>,
    val platformBoms: Map<String, String> = emptyMap(),
)
