package no.nav.platforce.tool.dependencies

class GradleBuildFileUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): String {
        var updated = content

        findings
            .filter { it.kind != DependencyKind.GRADLE }
            .filter { it.status == DependencyStatus.UPDATE || it.status == DependencyStatus.AHEAD || it.status == DependencyStatus.ADD }
            .forEach { f ->

                if (f.status == DependencyStatus.ADD) {
                    val parent =
                        f.relatedTo.firstOrNull()
                            ?: return@forEach

                    val parentKey = parent.key
                    val parentVersion = parent.version

                    val implementationRegex =
                        Regex(
                            """^(\s*)implementation\s+["']${Regex.escape(parentKey)}:${Regex.escape(parentVersion)}["']\s*$""",
                            RegexOption.MULTILINE,
                        )

                    val match =
                        implementationRegex.find(updated)
                            ?: return@forEach

                    val indentation = match.groupValues[1]

                    val newLine =
                        """${indentation}implementation "${f.key}:${f.targetVersion}" // Transitive dependency for $parentKey:$parentVersion"""

                    updated =
                        updated.replaceRange(
                            match.range.last + 1,
                            match.range.last + 1,
                            "\n$newLine",
                        )

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
}
