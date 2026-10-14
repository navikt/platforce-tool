package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
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

        require(state.dependencies.isNotEmpty()) {
            "No target dependencies configured"
        }

        val debugDir = debugFiles.start(username)
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
                    maven {
                        url = uri("https://packages.confluent.io/maven/")
                    }
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
                        def taskStarted = System.nanoTime()
                        def configurationTimings = []
                        def timingReport = file("gradle-configuration-timings.json")
                        def writeTimings = {
                            timingReport.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                                taskElapsedMs: (System.nanoTime() - taskStarted) / 1000000.0,
                                executedGradleVersion: gradle.gradleVersion,
                                configurations: configurationTimings
                            ]))
                        }
                        def unresolvedReport =
                            file("gradle-unresolved-dependencies.txt")
                
                        unresolvedReport.parentFile.mkdirs()
                        unresolvedReport.text = ""
                
                        def reportUnresolved = { dependency, context ->
                            def requested = dependency.requested
                        
                            unresolvedReport << "UNRESOLVED DEPENDENCY\n" +
                                "Context: " + context + "\n" +
                                "Group: " + requested.group + "\n" +
                                "Module: " + requested.module + "\n" +
                                "Version: " + requested.version + "\n" +
                                "Display: " + requested.displayName + "\n" +
                                "Reason: " + (dependency.failure?.message ?: "unknown") + "\n" +
                                "----------------------------------------\n"
                        }
                    
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
                                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    node.dependencies << buildNode(
                                        dependency.selected,
                                        dependency.requested,
                                        newPath
                                    )
                                } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                    reportUnresolved(
                                        dependency,
                                        "dependency of ${'$'}{component.id.displayName}"
                                    )
                                    
                                    def unresolvedRequested = dependency.requested
                            
                                    node.dependencies << [
                                        group: unresolvedRequested.group,
                                        name: unresolvedRequested.module,
                                        version: "<unresolved>",
                                        requestedVersion: unresolvedRequested.version,
                                        requested: unresolvedRequested.displayName,
                                        dependencies: [],
                                        unresolved: true
                                    ]
                                }
                            }

                            return node
                        }

                        def resolveConfiguration = { configuration ->
                            def configurationStarted = System.nanoTime()
                            def configurationStatus = "FAILED"
                            def rootCount = 0
                            try {
                            def resolutionResult =
                                configuration.incoming.resolutionResult
                        
                            def roots = []
                        
                            resolutionResult.root.dependencies.each { dependency ->
                                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    roots << buildNode(
                                        dependency.selected,
                                        dependency.requested,
                                        []
                                    )
                                } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                    reportUnresolved(
                                        dependency,
                                        "root dependency in ${'$'}{configuration.name}"
                                    )
                                    
                                    def unresolvedRequested = dependency.requested
                        
                                    roots << [
                                        group: unresolvedRequested.group,
                                        name: unresolvedRequested.module,
                                        version: "<unresolved>",
                                        requestedVersion: unresolvedRequested.version,
                                        requested: unresolvedRequested.displayName,
                                        dependencies: [],
                                        unresolved: true
                                    ]
                                }
                            }
                        
                            rootCount = roots.size()
                            configurationStatus = "READY"
                            return roots
                            } finally {
                                configurationTimings << [
                                    configuration: configuration.name,
                                    kind: configuration.name == "targetResolution" ? "combined" : "individual",
                                    dependency: configuration.name == "targetResolution" ? null : configuration.ext.targetDependencyKey,
                                    durationMs: (System.nanoTime() - configurationStarted) / 1000000.0,
                                    rootCount: rootCount,
                                    status: configurationStatus
                                ]
                                writeTimings()
                            }
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
                        writeTimings()
                    }
                }
                """.trimIndent(),
            )
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
