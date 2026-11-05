package no.nav.platforce.tool.pullrequests

import no.nav.platforce.tool.gradle.GradleDependencyParser
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyStatus

class GradleBuildFileUpdater {
    fun apply(
        content: String,
        findings: List<DependencyFinding>,
    ): String {
        var updated = content

        findings
            .filter { it.kind != DependencyKind.GRADLE }
            .filter {
                it.status == DependencyStatus.UPDATE || it.status == DependencyStatus.AHEAD || it.status == DependencyStatus.ADD ||
                    it.status == DependencyStatus.REMOVE
            }.forEach { f ->
                if (f.status == DependencyStatus.REMOVE) {
                    val version = f.currentVersion ?: return@forEach

                    updated =
                        updated.replace(
                            Regex(
                                """(?m)^[ \t]*implementation\s+["']${Regex.escape(f.key)}:${Regex.escape(version)}["'].*\r?\n?""",
                            ),
                            "",
                        )

                    return@forEach
                }

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

                        DependencyKind.DEPENDENCY, DependencyKind.PLATFORM_BOM -> {
                            var result = updated
                            GradleDependencyParser()
                                .declarations(updated)
                                .filter {
                                    it.key == f.key && it.version == from &&
                                        it.platformBom == (f.kind == DependencyKind.PLATFORM_BOM)
                                }.sortedByDescending { it.versionRange.first }
                                .forEach { result = result.replaceRange(it.versionRange, to) }
                            result
                        }

                        DependencyKind.GRADLE -> {
                            updated
                        }
                    }
            }

        return applyGradleCompatibility(updated)
    }

    // Keep generated PRs compatible with newer Gradle application and JUnit test execution APIs.
    private fun applyGradleCompatibility(content: String): String {
        val source = GradleDependencyParser().withoutComments(content)
        val tokens = Regex("""(["'])(?:\\.|(?!\1)[^\\])*\1|\bmainClassName\b""")
        var updated = content
        tokens.findAll(source).filter { it.value == "mainClassName" }.toList().asReversed().forEach {
            updated = updated.replaceRange(it.range, "mainClass")
        }

        val uncommented = GradleDependencyParser().withoutComments(updated)
        val launcher =
            Regex(
                """(?m)^[ \t]*testRuntimeOnly\s*(?:\(\s*)?["']org\.junit\.platform:junit-platform-launcher(?::[^"']*)?["']""",
            )
        val declarations = GradleDependencyParser().declarations(updated)
        if (launcher.containsMatchIn(uncommented) ||
            declarations.any {
                it.configuration == "testRuntimeOnly" && it.key == "org.junit.platform:junit-platform-launcher"
            }
        ) {
            return updated
        }
        val junit =
            Regex(
                """(?m)^([ \t]*)(?:implementation|testImplementation)\s*(?:\(\s*)?["']org\.junit\.jupiter:junit-jupiter-[^"']+["']""",
            ).find(uncommented) ?: return updated
        val indentation = junit.groupValues[1]
        val newline = if ("\r\n" in updated) "\r\n" else "\n"
        val notation =
            if (Regex("""(?:implementation|testImplementation)\s*\(""").containsMatchIn(junit.value)) {
                """testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")"""
            } else {
                "testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'"
            }
        return updated.replaceRange(junit.range.first, junit.range.first, "$indentation$notation$newline")
    }
}
