package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class TargetResolution(
    val gradleVersion: String,
    val roots: List<ResolvedDependency>,
)

data class ResolvedDependency(
    val group: String?,
    val name: String,
    val version: String,
    val requestedVersion: String?,
    val dependencies: List<ResolvedDependency>,
)

class GradleTargetResolutionService(
    private val gradleBaseDir: Path =
        Path.of(
            System.getenv("PLATFORCE_GRADLE_HOME")
                ?: "/opt/gradle",
        ),
    private val gson: Gson = Gson(),
) {
    private val log = KotlinLogging.logger { }

    @OptIn(ExperimentalPathApi::class)
    fun resolve(state: TargetVersionsState): TargetResolution {
        require(
            state.gradleVersion.matches(
                Regex("""\d+\.\d+(\.\d+)?([.-].+)?"""),
            ),
        ) {
            "Invalid Gradle version: ${state.gradleVersion}"
        }

        require(state.dependencies.isNotEmpty()) {
            "No target dependencies configured"
        }

        val projectDir =
            Files.createTempDirectory(
                "platforce-gradle-resolution-",
            )

        log.info {
            "Created temporary Gradle project at $projectDir"
        }

        try {
            writeProject(
                projectDir = projectDir,
                state = state,
            )

            val gradleHome =
                ensureGradleDistribution(
                    state.gradleVersion,
                )

            log.info {
                "Using Gradle ${state.gradleVersion} from $gradleHome"
            }

            runGradle(
                gradleHome = gradleHome,
                projectDir = projectDir,
            )

            val resultFile =
                projectDir.resolve("resolution.json")

            check(resultFile.exists()) {
                "Gradle resolution completed without producing $resultFile"
            }

            val json = resultFile.readText()

            log.info {
                "Gradle resolution produced ${json.length} bytes"
            }

            return parseResult(
                json = json,
                gradleVersion = state.gradleVersion,
            )
        } finally {
            log.info {
                "Deleting temporary Gradle project $projectDir"
            }

            projectDir.deleteRecursively()
        }
    }

    private fun writeProject(
        projectDir: Path,
        state: TargetVersionsState,
    ) {
        projectDir.resolve("settings.gradle").writeText(
            """
            rootProject.name = "platforce-target-resolution"
            """.trimIndent(),
        )

        projectDir.resolve("build.gradle").writeText(
            createBuildFile(state),
        )

        log.info {
            "Created temporary Gradle build with " +
                "${state.dependencies.size} target dependencies"
        }
    }

    private fun createBuildFile(state: TargetVersionsState): String =
        buildString {
            appendLine(
                """
                plugins {
                    id 'java'
                }

                repositories {
                    mavenCentral()
                }

                configurations {
                    targetResolution
                }

                dependencies {
                """.trimIndent(),
            )

            state.dependencies
                .toSortedMap()
                .forEach { (key, version) ->
                    requireValidDependencyKey(key)

                    appendLine(
                        """    targetResolution "${escape(key)}:$version"""",
                    )
                }

            appendLine(
                """
                }

                import groovy.json.JsonOutput

                tasks.register("platforceResolve") {
                    doLast {
                        def configuration = configurations.targetResolution
                        def resolutionResult =
                            configuration.incoming.resolutionResult

                        def buildNode

                        buildNode = { component, requested, path ->
                            def moduleVersion = component.moduleVersion

                            def node = [
                                group: moduleVersion?.group,
                                name: moduleVersion?.name ?: component.id.displayName,
                                version: moduleVersion?.version,
                                requested: requested?.displayName,
                                dependencies: []
                            ]

                            def componentId = component.id.displayName

                            if (path.contains(componentId)) {
                                return node
                            }

                            def newPath = path + componentId

                            component.dependencies.each { dependency ->
                                def selected = dependency.selected

                                if (selected != null) {
                                    node.dependencies << buildNode(
                                        selected,
                                        dependency.requested,
                                        newPath
                                    )
                                }
                            }

                            return node
                        }

                        def roots = []

                        resolutionResult.root.dependencies.each { dependency ->
                            if (dependency.selected != null) {
                                roots << buildNode(
                                    dependency.selected,
                                    dependency.requested,
                                    []
                                )
                            }
                        }

                        def result = [
                            roots: roots
                        ]

                        file("${'$'}projectDir/resolution.json").text =
                            JsonOutput.prettyPrint(
                                JsonOutput.toJson(result)
                            )
                    }
                }
                """.trimIndent(),
            )
        }

    private fun ensureGradleDistribution(version: String): Path {
        val installationDir =
            gradleBaseDir.resolve(
                "gradle-$version",
            )

        val executable =
            installationDir
                .resolve("bin")
                .resolve("gradle")

        log.info {
            "Looking for Gradle $version at $installationDir"
        }

        check(executable.exists()) {
            buildString {
                appendLine(
                    "Gradle $version is not installed in the container.",
                )
                appendLine(
                    "Expected executable: $executable",
                )
                appendLine(
                    "Configured Gradle base directory: $gradleBaseDir",
                )
            }
        }

        executable.toFile().setExecutable(true)

        return installationDir
    }

    private fun runGradle(
        gradleHome: Path,
        projectDir: Path,
    ) {
        val executable =
            gradleHome
                .resolve("bin")
                .resolve("gradle")

        check(executable.exists()) {
            "Gradle executable not found: $executable"
        }

        log.info {
            "Starting Gradle dependency resolution using $executable"
        }

        val process =
            ProcessBuilder(
                executable.toString(),
                "--no-daemon",
                "--console=plain",
                "--stacktrace",
                "platforceResolve",
            ).directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start()

        val output =
            process.inputStream
                .bufferedReader()
                .use { it.readText() }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            log.error {
                "Gradle dependency resolution failed with exit code $exitCode"
            }

            throw IllegalStateException(
                buildString {
                    appendLine(
                        "Gradle dependency resolution failed",
                    )
                    appendLine(
                        "Gradle: $gradleHome",
                    )
                    appendLine(
                        "Project: $projectDir",
                    )
                    appendLine(
                        "Exit code: $exitCode",
                    )
                    appendLine()
                    appendLine(output)
                },
            )
        }

        log.info {
            "Gradle dependency resolution completed successfully"
        }

        if (output.isNotBlank()) {
            log.info {
                "Gradle output:\n$output"
            }
        }
    }

    private fun parseResult(
        json: String,
        gradleVersion: String,
    ): TargetResolution {
        val type =
            object : TypeToken<ResolutionFile>() {}.type

        val file =
            gson.fromJson<ResolutionFile>(
                json,
                type,
            )

        return TargetResolution(
            gradleVersion = gradleVersion,
            roots = file.roots,
        )
    }

    private fun requireValidDependencyKey(key: String) {
        require(
            key.matches(
                Regex("""[^:\s]+:[^:\s]+"""),
            ),
        ) {
            "Invalid Maven dependency: $key"
        }
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    private data class ResolutionFile(
        val roots: List<ResolvedDependency>,
    )
}
