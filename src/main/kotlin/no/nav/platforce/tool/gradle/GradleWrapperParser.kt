package no.nav.platforce.tool.gradle

data class ParsedGradleWrapperFile(
    val gradleVersion: String? = null,
)

class GradleWrapperParser {
    private val distributionRegex =
        Regex(
            """distributionUrl=.*gradle-([0-9.]+)-.*\.zip""",
        )

    fun parse(content: String): String? =
        distributionRegex
            .find(content)
            ?.groupValues
            ?.get(1)
}
