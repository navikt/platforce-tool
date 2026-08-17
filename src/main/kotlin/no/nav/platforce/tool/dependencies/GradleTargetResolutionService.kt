package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class TargetResolution(
    val gradleVersion: String,
    val roots: List<ResolvedDependency>,
)

data class ResolvedDependency(
    val group: String?,
    val name: String,
    val version: String?,
    val requested: String?,
    val children: List<ResolvedDependency>,
)

class GradleTargetResolutionService(
    private val distributionCacheDir: Path =
        Path.of(
            System.getenv("PLATFORCE_GRADLE_CACHE")
                ?: "${System.getProperty("user.home")}/.platforce-gradle",
        ),
    private val gson: Gson = Gson(),
) {
    private val log = KotlinLogging.logger { }

    @OptIn(ExperimentalPathApi::class)
    fun resolve(state: TargetVersionsState): TargetResolution {
        require(state.gradleVersion.matches(Regex("""\d+\.\d+(\.\d+)?([.-].+)?"""))) {
            "Invalid Gradle version: ${state.gradleVersion}"
        }

        require(state.dependencies.isNotEmpty()) {
            "No target dependencies configured"
        }

        val projectDir =
            Files.createTempDirectory(
                "platforce-gradle-resolution-",
            )

        try {
            writeProject(projectDir, state)

            val gradleHome =
                ensureGradleDistribution(
                    state.gradleVersion,
                )

            log.info("Starting gradle run ...")

            runGradle(
                gradleHome = gradleHome,
                projectDir = projectDir,
            )

            val resultFile =
                projectDir.resolve("resolution.json")

            check(Files.exists(resultFile)) {
                "Gradle resolution completed without producing resolution.json"
            }

            log.info("Resolition : " + resultFile.readText())

            return parseResult(
                resultFile.readText(),
                state.gradleVersion,
            )
        } finally {
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
                                children: []
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
                                    node.children << buildNode(
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
                            roots << buildNode(
                                dependency.selected,
                                dependency.requested,
                                []
                            )
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
        projectDir: Path,
    ) {
        val executable =
            gradleHome
                .resolve("bin")
                .resolve("gradle")

        check(Files.exists(executable)) {
            "Gradle executable not found: $executable"
        }

        executable.toFile().setExecutable(true)

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
            distributionCacheDir.resolve("gradle-$version")

        val executable =
            installationDir
                .resolve("bin")
                .resolve("gradle")

        if (Files.exists(executable)) {
            return installationDir
        }

        synchronized(distributionLock(version)) {
            if (Files.exists(executable)) {
                return installationDir
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
                distributionUrl,
                zipFile,
            )

            download(
                checksumUrl,
                shaFile,
            )

            verifySha256(
                zipFile,
                shaFile,
            )

            val temporaryInstall =
                distributionCacheDir.resolve(
                    "gradle-$version-installing",
                )

            temporaryInstall.deleteRecursively()
            temporaryInstall.createDirectories()

            unzip(
                zipFile,
                temporaryInstall,
            )

            val extracted =
                temporaryInstall
                    .resolve("gradle-$version")

            check(Files.exists(extracted)) {
                "Gradle distribution did not contain expected directory " +
                    "gradle-$version"
            }

            installationDir.deleteRecursively()

            Files.move(
                extracted,
                installationDir,
                StandardCopyOption.ATOMIC_MOVE,
            )

            temporaryInstall.deleteRecursively()

            check(Files.exists(executable)) {
                "Gradle executable missing after installation: $executable"
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
                .joinToString("") { "%02x".format(it) }

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
            BufferedInputStream(
                Files.newInputStream(zipFile),
            ),
        ).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                val target =
                    destination
                        .resolve(entry.name)
                        .normalize()

                check(target.startsWith(destination.normalize())) {
                    "Unsafe ZIP entry: ${entry.name}"
                }

                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent?.createDirectories()

                    BufferedOutputStream(
                        Files.newOutputStream(target),
                    ).use { output ->
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

    private fun distributionLock(version: String): Any = version.intern()

    private data class ResolutionFile(
        val roots: List<ResolvedDependency>,
    )
}
