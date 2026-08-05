package no.nav.platforce.tool.dependencies

class GradleWrapperUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): String {
        var updated = content

        findings
            .filter { it.kind == DependencyKind.GRADLE }
            .filter { it.status != DependencyStatus.OK }
            .forEach { f ->

                val target = f.targetVersion ?: return@forEach

                updated =
                    updated.replace(
                        Regex(
                            """distributionUrl=.*gradle-([0-9.]+)-bin\.zip""",
                        ),
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-$target-bin.zip",
                    )
            }

        return updated
    }
}
