package no.nav.platforce.tool.resolution

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GradleTargetResolutionService(
    private val gradleBaseDir: Path =
        Path.of(
            System.getenv("PLATFORCE_GRADLE_HOME")
                ?: "/opt/gradle",
        ),
    private val gson: Gson = Gson(),
    private val debugFiles: ResolutionDebugFiles = ResolutionDebugFiles(),
) {
    private val log = KotlinLogging.logger { }

    @OptIn(ExperimentalPathApi::class)
    fun resolve(
        username: String,
        state: TargetVersionsState,
    ): TargetResolution {
        val startedAt = Instant.now()
        val started = System.nanoTime()
        val timings = linkedMapOf<String, Double>()
        require(
            state.gradleVersion.matches(
                Regex("""\d+\.\d+(\.\d+)?([.-].+)?"""),
            ),
        ) {
            "Invalid Gradle version: ${state.gradleVersion}"
        }

        require(state.dependencies.isNotEmpty() || state.platformBoms.isNotEmpty()) {
            "No target dependencies or platform BOMs configured"
        }

        val debugDir = debugFiles.start(username)
        val bom = state.platformBoms.isNotEmpty()
        debugFiles.startInspection(username, bom)
        val projectDir =
            Files.createTempDirectory(
                "platforce-gradle-resolution-",
            )
        var failure: Exception? = null
        var status = "RUNNING"

        fun <T> timed(
            name: String,
            action: () -> T,
        ): T {
            val start = System.nanoTime()
            try {
                return action()
            } finally {
                timings[name] = (System.nanoTime() - start) / 1_000_000.0
            }
        }

        log.info {
            "Created temporary Gradle project at $projectDir"
        }

        try {
            debugDir.resolve("gradle-resolution-report.json").writeText(
                gson.toJson(mapOf("status" to status, "startedAt" to startedAt.toString(), "targetFingerprint" to fingerprint(state))),
            )
            timed("projectGenerationMs") {
                writeProject(
                    projectDir = projectDir,
                    state = state,
                )
            }
            Files.copy(projectDir.resolve("build.gradle"), debugDir.resolve("build.gradle"), StandardCopyOption.REPLACE_EXISTING)
            debugFiles.publishInspection(username, debugDir, bom)
            projectDir.resolve("gradle-unresolved-dependencies.txt").writeText(
                "Gradle resolution task has not started. See gradle-output.txt and gradle-resolution-report.json if execution fails.\n",
            )

            val gradleHome =
                timed("gradleDistributionCheckMs") { ensureGradleDistribution() }

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

            timed("gradleProcessMs") {
                runGradle(
                    gradleHome = gradleHome,
                    projectDir = projectDir,
                )
            }

            val resultFile =
                projectDir.resolve("resolution.json")

            check(resultFile.exists()) {
                "Gradle resolution completed without producing $resultFile"
            }

            val json = resultFile.readText()

            log.info {
                "Gradle resolution produced ${json.length} bytes"
            }

            val result =
                timed("resultParsingMs") {
                    parseResult(
                        json = json,
                        gradleVersion = state.gradleVersion,
                    )
                }
            status = "READY"
            return result
        } catch (e: Exception) {
            failure = e
            status = "FAILED"
            throw e
        } finally {
            try {
                for (name in listOf("gradle-unresolved-dependencies.txt", "gradle-output.txt", "gradle-configuration-timings.json")) {
                    val source = projectDir.resolve(name)
                    if (source.exists()) {
                        Files.copy(source, debugDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                val configurationReport = projectDir.resolve("gradle-configuration-timings.json")
                val report =
                    linkedMapOf<String, Any>(
                        "status" to status,
                        "startedAt" to startedAt.toString(),
                        "finishedAt" to Instant.now().toString(),
                        "targetFingerprint" to fingerprint(state),
                        "configuredGradleVersion" to state.gradleVersion,
                        "gradleExecutable" to gradleBaseDir.resolve("bin/gradle").toString(),
                        "totalMs" to (System.nanoTime() - started) / 1_000_000.0,
                        "phasesMs" to timings,
                        "measurementNotes" to
                            "Wall-clock milliseconds. Total excludes queue wait, OSV and temporary directory cleanup. Configuration times include dependency resolution and graph traversal, in execution order. Each execution uses a fresh GRADLE_USER_HOME.",
                    )
                if (configurationReport.exists()) {
                    val task =
                        com.google.gson.JsonParser
                            .parseString(configurationReport.readText())
                            .asJsonObject
                    report["gradleTask"] = task
                    timings["gradleOutsideTaskMs"] =
                        (timings.getValue("gradleProcessMs") - task["taskElapsedMs"].asDouble).coerceAtLeast(0.0)
                }
                failure?.let { report["error"] = it.message ?: it.javaClass.simpleName }
                debugDir.resolve("gradle-resolution-report.json").writeText(
                    com.google.gson
                        .GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(report),
                )
                debugFiles.publishInspection(username, debugDir, bom)
            } catch (e: Exception) {
                log.error(e) { "Could not publish Gradle debug artifacts" }
                if (failure != null) failure.addSuppressed(e) else throw e
            } finally {
                log.info { "Deleting temporary Gradle project $projectDir" }
                projectDir.deleteRecursively()
            }
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
            GradleResolutionBuildFile().create(state),
        )

        log.info {
            "Created temporary Gradle build with " +
                "${state.dependencies.size} target dependencies"
        }
    }

    private fun ensureGradleDistribution(): Path {
//        require(version == "8.11.1") {
//            "Only Gradle 8.11.1 is currently installed in the container, requested $version"
//        }
        val installationDir = gradleBaseDir
        val executable = installationDir.resolve("bin").resolve("gradle")
//        log.info {
//            "Looking for Gradle $version at $installationDir"
//        }
        check(executable.exists()) {
            buildString {
                // appendLine("Gradle $version is not installed in the container.")
                appendLine("Expected executable: $executable")
                appendLine("Configured Gradle base directory: $gradleBaseDir")
            }
        }
//        log.info {
//            "Found Gradle $version at $executable"
//        }
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
        projectDir.resolve("gradle-output.txt").writeText(output)

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
            platformBoms = file.platformBoms.orEmpty(),
        )
    }

    private data class ResolutionFile(
        val roots: List<ResolvedDependency>,
        val individual: Map<String, List<ResolvedDependency>>,
        val platformBoms: Map<String, PlatformBomResolution>? = null,
    )
}
