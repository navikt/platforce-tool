package no.nav.platforce.tool.dependencies

class GradleBuildFileUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): String {
        var updated = content

        findings
            .filter { it.status != DependencyStatus.OK }
            .forEach { f ->

                val from = f.currentVersion
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
                    }
            }

        return updated
    }
}
