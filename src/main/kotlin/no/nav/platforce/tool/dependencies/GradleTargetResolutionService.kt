package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
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
    private val gson: Gson = Gson(),
) {
    private val log = KotlinLogging.logger {}

    /**
     * Everything used by this service lives below /tmp.
     *
     * The distribution cache survives individual resolution runs,
     * but is lost when the pod/container is recreated.
     */
    private val distributionCacheDir: Path =
        Path.of("/tmp/platforce-gradle-distributions")

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

        /*
         * One completely isolated project per resolution.
         *
         * Example:
         *
         * /tmp/platforce-gradle-resolution-12345/
         *   build.gradle
         *   settings.gradle
         *   resolution.json
         *   gradle-user-home/
         */
        val projectDir =
            Files.createTempDirectory(
                Path.of("/tmp"),
                "platforce-gradle-resolution-",
            )

        val gradleUserHome =
            projectDir.resolve("gradle-user-home")

        gradleUserHome.createDirectories()

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
                "Starting Gradle ${state.gradleVersion} dependency resolution"
            }

            runGradle(
                gradleHome = gradleHome,
                gradleUserHome = gradleUserHome,
                projectDir = projectDir,
            )

            val resultFile =
                projectDir.resolve("resolution.json")

            check(resultFile.exists()) {
                "Gradle resolution completed without producing resolution.json"
            }

            val json = resultFile.readText()

            log.debug {
                "Gradle resolution result: $json"
            }

            return parseResult(
                json = json,
                gradleVersion = state.gradleVersion,
            )
        } finally {
            /*
             * Remove the project AND its Gradle user home/cache.
             */
            runCatching {
                projectDir.deleteRecursively()
            }.onFailure {
                log.warn(it) {
                    "Failed to clean up temporary Gradle directory: $projectDir"
                }
            }
        }
    }

    private fun writeProject(
        projectDir: Path,
        state: TargetVersionsState,
    ) {
        projectDir
            .resolve("settings.gradle")
            .writeText(
                """
                rootProject.name = "platforce-target-resolution"
                """.trimIndent(),
            )

        projectDir
            .resolve("build.gradle")
            .writeText(
                createBuildFile(state),
            )
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
                                requestedVersion: requested?.version,
                                dependencies: []
                            ]

                            def componentId = component.id.displayName

                            // Prevent cycles in the dependency graph.
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

                            def selected = dependency.selected

                            if (selected != null) {
                                roots << buildNode(
                                    selected,
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

    private fun runGradle(
        gradleHome: Path,
        gradleUserHome: Path,
        projectDir: Path,
    ) {
        val executable =
            gradleHome
                .resolve("bin")
                .resolve("gradle")

        check(executable.exists()) {
            "Gradle executable not found: $executable"
        }

        executable.toFile().setExecutable(true)

        val process =
            ProcessBuilder(
                executable.toAbsolutePath().toString(),
                "--no-daemon",
                "--console=plain",
                "--stacktrace",
                "--gradle-user-home",
                gradleUserHome.toAbsolutePath().toString(),
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
            throw IllegalStateException(
                buildString {
                    appendLine("Gradle dependency resolution failed")
                    appendLine("Exit code: $exitCode")
                    appendLine()
                    appendLine(output)
                },
            )
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun ensureGradleDistribution(version: String): Path {
        distributionCacheDir.createDirectories()

        val installationDir =
            distributionCacheDir.resolve(
                "gradle-$version",
            )

        val executable =
            installationDir
                .resolve("bin")
                .resolve("gradle")

        if (executable.exists()) {
            return installationDir
        }

        synchronized(distributionLock(version)) {
            if (executable.exists()) {
                return installationDir
            }

            log.info {
                "Downloading Gradle $version"
            }

            val zipFile =
                distributionCacheDir.resolve(
                    "gradle-$version-bin.zip",
                )

            val shaFile =
                distributionCacheDir.resolve(
                    "gradle-$version-bin.zip.sha256",
                )

            val distributionUrl =
                "https://services.gradle.org/distributions/" +
                    "gradle-$version-bin.zip"

            val checksumUrl =
                "$distributionUrl.sha256"

            download(
                url = distributionUrl,
                destination = zipFile,
            )

            download(
                url = checksumUrl,
                destination = shaFile,
            )

            verifySha256(
                file = zipFile,
                checksumFile = shaFile,
            )

            val temporaryInstall =
                distributionCacheDir.resolve(
                    "gradle-$version-installing",
                )

            temporaryInstall.deleteRecursively()
            temporaryInstall.createDirectories()

            try {
                unzip(
                    zipFile = zipFile,
                    destination = temporaryInstall,
                )

                val extracted =
                    temporaryInstall.resolve(
                        "gradle-$version",
                    )

                check(extracted.exists()) {
                    "Gradle distribution did not contain expected directory " +
                        "gradle-$version"
                }

                installationDir.deleteRecursively()

                Files.move(
                    extracted,
                    installationDir,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                temporaryInstall.deleteRecursively()
            }

            check(executable.exists()) {
                "Gradle executable missing after installation: $executable"
            }

            log.info {
                "Gradle $version installed at $installationDir"
            }

            return installationDir
        }
    }

    private fun download(
        url: String,
        destination: Path,
    ) {
        val connection =
            URI(url)
                .toURL()
                .openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.requestMethod = "GET"

            connection.connect()

            check(connection.responseCode in 200..299) {
                "Failed to download $url: HTTP ${connection.responseCode}"
            }

            val temporary =
                destination.resolveSibling(
                    "${destination.fileName}.download",
                )

            try {
                connection.inputStream.use { input ->
                    Files.newOutputStream(temporary).use { output ->
                        input.copyTo(output)
                    }
                }

                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                runCatching {
                    Files.deleteIfExists(temporary)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256(
        file: Path,
        checksumFile: Path,
    ) {
        val expected =
            checksumFile
                .readText()
                .trim()
                .split(Regex("\\s+"))
                .first()
                .lowercase()

        val digest =
            MessageDigest.getInstance("SHA-256")

        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)

            while (true) {
                val read = input.read(buffer)

                if (read < 0) {
                    break
                }

                digest.update(buffer, 0, read)
            }
        }

        val actual =
            digest
                .digest()
                .joinToString("") {
                    "%02x".format(it)
                }

        check(actual == expected) {
            """
            Gradle distribution checksum mismatch.

            File: $file
            Expected: $expected
            Actual:   $actual
            """.trimIndent()
        }
    }

    private fun unzip(
        zipFile: Path,
        destination: Path,
    ) {
        ZipInputStream(
            Files.newInputStream(zipFile).buffered(),
        ).use { zip ->

            while (true) {
                val entry = zip.nextEntry ?: break

                val target =
                    destination
                        .resolve(entry.name)
                        .normalize()

                check(
                    target.startsWith(
                        destination.normalize(),
                    ),
                ) {
                    "Unsafe ZIP entry: ${entry.name}"
                }

                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent?.createDirectories()

                    Files.newOutputStream(target).use { output ->
                        zip.copyTo(output)
                    }
                }

                zip.closeEntry()
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

    /**
     * JVM intern gives us a process-local lock per Gradle version.
     *
     * This prevents two concurrent HTTP requests from trying to install
     * the same Gradle distribution simultaneously.
     */
    private fun distributionLock(version: String): Any = "platforce-gradle-distribution-$version".intern()

    private data class ResolutionFile(
        val roots: List<ResolvedDependency>,
    )
}
