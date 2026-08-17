package no.nav.platforce.tool.dependencies

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
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

private const val BUNDLED_GRADLE_VERSION = "8.11.1"
private const val BUNDLED_GRADLE_RESOURCE =
    "/gradle/gradle-8.11.1-bin.zip"

class GradleTargetResolutionService(
    private val httpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(2))
            .writeTimeout(Duration.ofMinutes(2))
            .build(),
    private val gson: Gson = Gson(),
) {
    private val log = KotlinLogging.logger { }
    private val distributionCacheDir: Path =
        Path.of("/tmp/platforce-gradle")

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
        log.info {
            "Starting Gradle target resolution for Gradle ${state.gradleVersion}"
        }
        log.info {
            "Target dependencies: ${state.dependencies.size}"
        }
        state.dependencies
            .toSortedMap()
            .forEach { (key, version) ->
                log.info {
                    "Target dependency: $key:$version"
                }
            }
        distributionCacheDir.createDirectories()
        val projectDir =
            Files.createTempDirectory(
                Path.of("/tmp"),
                "platforce-gradle-resolution-",
            )
        log.info {
            "Created temporary Gradle project at $projectDir"
        }
        try {
            writeProject(projectDir, state)
            log.info {
                "Temporary Gradle project written to $projectDir"
            }
            val gradleHome =
                ensureGradleDistribution(
                    state.gradleVersion,
                )
            log.info {
                "Using Gradle installation at $gradleHome"
            }
            runGradle(
                gradleHome = gradleHome,
                projectDir = projectDir,
            )
            val resultFile =
                projectDir.resolve("resolution.json")
            check(resultFile.exists()) {
                "Gradle resolution completed without producing resolution.json"
            }
            log.info {
                "Reading resolution result from $resultFile"
            }
            val json = resultFile.readText()
            log.info {
                "Resolution result size: ${json.length} characters"
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
                                requestedVersion: requested?.displayName,
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
        check(executable.exists()) {
            "Gradle executable not found: $executable"
        }
        executable.toFile().setExecutable(true)
        log.info {
            "Starting Gradle process: $executable"
        }
        log.info {
            "Gradle working directory: $projectDir"
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
                .use { reader ->
                    reader.readText()
                }
        val exitCode = process.waitFor()
        log.info {
            "Gradle process exited with code $exitCode"
        }
        log.info {
            "Gradle output:\n$output"
        }
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
        require(version == BUNDLED_GRADLE_VERSION) {
            "Only bundled Gradle $BUNDLED_GRADLE_VERSION is currently supported"
        }

        distributionCacheDir.createDirectories()

        val installationDir =
            distributionCacheDir.resolve("gradle-$version")

        val executable =
            installationDir
                .resolve("bin")
                .resolve("gradle")

        if (executable.exists()) {
            log.info {
                "Using cached bundled Gradle $version at $installationDir"
            }
            return installationDir
        }

        synchronized(distributionLock(version)) {
            if (executable.exists()) {
                return installationDir
            }

            log.info {
                "Installing bundled Gradle $version"
            }

            val temporaryInstall =
                distributionCacheDir.resolve(
                    "gradle-$version-installing",
                )

            temporaryInstall.deleteRecursively()
            temporaryInstall.createDirectories()

            try {
                val resource =
                    javaClass.getResourceAsStream(
                        BUNDLED_GRADLE_RESOURCE,
                    )
                        ?: error(
                            "Bundled Gradle distribution not found: " +
                                BUNDLED_GRADLE_RESOURCE,
                        )

                val zipFile =
                    distributionCacheDir.resolve(
                        "gradle-$version-bin.zip",
                    )

                resource.use { input ->
                    Files.newOutputStream(zipFile).use { output ->
                        input.copyTo(output)
                    }
                }

                log.info {
                    "Extracting bundled Gradle $version"
                }

                unzip(
                    zipFile = zipFile,
                    destination = temporaryInstall,
                )

                val extracted =
                    temporaryInstall.resolve(
                        "gradle-$version",
                    )

                check(extracted.exists()) {
                    "Bundled Gradle distribution did not contain " +
                        "gradle-$version"
                }

                installationDir.deleteRecursively()

                Files.move(
                    extracted,
                    installationDir,
                    StandardCopyOption.ATOMIC_MOVE,
                )

                check(executable.exists()) {
                    "Gradle executable missing after installation: $executable"
                }

                log.info {
                    "Bundled Gradle $version installed at $installationDir"
                }

                return installationDir
            } finally {
                temporaryInstall.deleteRecursively()
            }
        }
    }

    private fun download(
        url: String,
        destination: Path,
    ) {
        val host = URI(url).host
        log.info {
            "Resolving DNS for $host"
        }
        val addresses =
            InetAddress.getAllByName(host)
        log.info {
            "DNS resolved $host to " +
                addresses.joinToString { it.hostAddress }
        }
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        log.info {
            "Opening HTTP connection to $url"
        }
        try {
            log.info {
                "Connecting to $url " +
                    "(connectTimeout=30s, readTimeout=120s)"
            }
            httpClient
                .newCall(request)
                .execute()
                .use { response ->
                    log.info {
                        "Received HTTP ${response.code} from $url"
                    }
                    check(response.isSuccessful) {
                        "Failed to download $url: HTTP ${response.code}"
                    }
                    val body =
                        response.body
                            ?: error(
                                "Empty response body from $url",
                            )
                    val temporary =
                        destination.resolveSibling(
                            "${destination.fileName}.download",
                        )
                    log.info {
                        "Writing download to $temporary"
                    }
                    body.byteStream().use { input ->
                        Files
                            .newOutputStream(
                                temporary,
                            ).use { output ->
                                input.copyTo(output)
                            }
                    }
                    log.info {
                        "Download completed: $temporary " +
                            "(${Files.size(temporary)} bytes)"
                    }
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    log.info {
                        "Moved download to $destination"
                    }
                }
        } catch (e: Exception) {
            log.error(e) {
                "Failed downloading $url: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            }
            throw e
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

    private fun distributionLock(version: String): Any = version.intern()

    private data class ResolutionFile(
        val roots: List<ResolvedDependency>,
    )
}
