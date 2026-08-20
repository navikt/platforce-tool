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
    val individual: Map<String, List<ResolvedDependency>>,
)

data class ResolvedDependency(
    val group: String?,
    val name: String,
    val version: String,
    val requestedVersion: String?,
    val dependencies: List<ResolvedDependency>,
) {
    val key: String
        get() = if (group != null) "$group:$name" else name
}

enum class ResolutionStatus {
    IDLE,
    RUNNING,
    READY,
    FAILED,
}

data class TargetResolutionSnapshot(
    val status: ResolutionStatus,
    val targetFingerprint: String?,
    val result: TargetResolution?,
    val error: String?,
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

            log.info {
                "java.io.tmpdir=${System.getProperty("java.io.tmpdir")}"
            }

            log.info {
                "user.home=${System.getProperty("user.home")}"
            }

            log.info {
                "user.dir=${System.getProperty("user.dir")}"
            }

            log.info {
                "TMPDIR=${System.getenv("TMPDIR")}"
            }

            log.info {
                "GRADLE_USER_HOME=${System.getenv("GRADLE_USER_HOME")}"
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
                """.trimIndent(),
            )

            state.dependencies
                .toSortedMap()
                .entries
                .forEachIndexed { index, (key, version) ->
                    requireValidDependencyKey(key)

                    val configurationName =
                        "targetResolution_$index"

                    appendLine(
                        """
                        def $configurationName =
                            configurations.create("$configurationName")

                        $configurationName.ext.targetDependencyKey =
                            "${escape(key)}"

                        dependencies.add(
                            "$configurationName",
                            "${escape(key)}:$version"
                        )
                        """.trimIndent(),
                    )
                }

            appendLine(
                """
                import groovy.json.JsonOutput

                tasks.register("platforceResolve") {
                    doLast {
                        def buildNode

                        buildNode = { component, requested, path ->
                            def moduleVersion = component.moduleVersion

                            def node = [
                                group: moduleVersion?.group,
                                name: moduleVersion?.name ?: component.id.displayName,
                                version: moduleVersion?.version,
                                requestedVersion: requested?.version,
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

                        def resolveConfiguration = { configuration ->
                            def resolutionResult =
                                configuration.incoming.resolutionResult

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

                            return roots
                        }

                        def roots =
                            resolveConfiguration(
                                configurations.targetResolution
                            )

                        def individual = [:]

                        configurations
                            .findAll { configuration ->
                                configuration.name.startsWith("targetResolution_")
                            }
                            .each { configuration ->
                                def key =
                                    configuration.ext.targetDependencyKey

                                if (key != null) {
                                    individual[key] =
                                        resolveConfiguration(configuration)
                                }
                            }

                        def result = [
                            roots: roots,
                            individual: individual
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
        require(version == "8.11.1") {
            "Only Gradle 8.11.1 is currently installed in the container, requested $version"
        }
        val installationDir = gradleBaseDir
        val executable = installationDir.resolve("bin").resolve("gradle")
        log.info {
            "Looking for Gradle $version at $installationDir"
        }
        check(executable.exists()) {
            buildString {
                appendLine("Gradle $version is not installed in the container.")
                appendLine("Expected executable: $executable")
                appendLine("Configured Gradle base directory: $gradleBaseDir")
            }
        }
        log.info {
            "Found Gradle $version at $executable"
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

        val gradleTmpDir =
            projectDir
                .resolve("gradle-tmp")

        gradleTmpDir.createDirectories()

        executable.toFile().setExecutable(true)

        log.info {
            "Running Gradle"
        }

        log.info {
            "Executable: $executable"
        }

        log.info {
            "Project: $projectDir"
        }

        log.info {
            "Gradle temporary directory: $gradleTmpDir"
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
                .apply {
                    environment()["GRADLE_USER_HOME"] =
                        projectDir
                            .resolve("gradle-user-home")
                            .toString()

                    environment()["TMPDIR"] =
                        gradleTmpDir.toString()

                    environment()["JAVA_TOOL_OPTIONS"] =
                        "-Djava.io.tmpdir=$gradleTmpDir"
                }.start()

        val output =
            process.inputStream
                .bufferedReader()
                .use { it.readText() }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                buildString {
                    appendLine("Gradle dependency resolution failed")
                    appendLine("Gradle: $gradleHome")
                    appendLine("Project: $projectDir")
                    appendLine("Exit code: $exitCode")
                    appendLine()
                    appendLine(output)
                },
            )
        }

        log.info {
            "Gradle resolution completed successfully"
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
            individual = file.individual,
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
        val individual: Map<String, List<ResolvedDependency>>,
    )
}
