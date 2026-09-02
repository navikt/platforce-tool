package no.nav.platforce.tool.dependencies

class GradleBuildFileUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): String {
        var updated = content

        findings
            .filter { it.kind != DependencyKind.GRADLE }
            .filter { it.status != DependencyStatus.OK }
            .forEach { f ->

                if (f.status == DependencyStatus.ADD) {
                    val dependencyLine =
                        "    ${f.key}:${f.targetVersion} // Transitive dependency for ${
                            f.overriddenBy.joinToString(", ") {
                                "${it.key}:${it.version}"
                            }
                        }"

                    updated = addDependency(updated, dependencyLine)
                    return@forEach
                }

                val from = f.currentVersion ?: return@forEach
                val to = f.targetVersion

                updated =
                    when (f.kind) {
                        DependencyKind.PLUGIN -> {
                            updated.replace(
                                Regex(
                                    """id\s+['"]${Regex.escape(f.key)}['"]\s+version\s+['"]${Regex.escape(from)}['"]""",
                                ),
                                """id '${f.key}' version '$to'""",
                            )
                        }

                        DependencyKind.DEPENDENCY -> {
                            updated.replace(
                                Regex(
                                    """(${Regex.escape(f.key)}:)${Regex.escape(from)}(['"])""",
                                ),
                                """$1$to$2""",
                            )
                        }

                        DependencyKind.GRADLE -> {
                            updated
                        }
                    }
            }

        return updated
    }

    private fun addDependency(
        content: String,
        dependencyLine: String,
    ): String {
        // Prefer inserting after the last existing dependency declaration.
        val dependencyRegex =
            Regex("""(?m)^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\s+["'][^"']+["']\s*$""")

        val matches = dependencyRegex.findAll(content).toList()

        if (matches.isNotEmpty()) {
            val last = matches.last()
            val insertAt = last.range.last + 1

            return content.substring(0, insertAt) +
                "\n" +
                dependencyLine +
                content.substring(insertAt)
        }

        // No existing dependency declaration found.
        // Fall back to the dependencies block.
        val dependenciesBlock =
            Regex("""(?m)^dependencies\s*\{\s*$""")

        val match = dependenciesBlock.find(content)

        if (match != null) {
            val insertAt = match.range.last + 1

            return content.substring(0, insertAt) +
                "\n" +
                dependencyLine +
                content.substring(insertAt)
        }

        // Nothing sensible to attach to.
        return content
    }
}
