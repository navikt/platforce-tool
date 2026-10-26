package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.GradlePluginResolutionBuildFile
import no.nav.platforce.tool.resolution.GradleTargetResolutionService
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.security.PluginSecurityScanner
import no.nav.platforce.tool.security.PluginSecurityService
import no.nav.platforce.tool.security.PluginSecurityStatus
import no.nav.platforce.tool.security.SecurityScanStatus
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PluginSecurityFlowTest {
    @TempDir
    lateinit var directory: Path

    private val advisory = Vulnerability("PLUGIN-TEST-1", "Synthetic advisory", emptyList(), emptyList(), listOf("2.0"))
    private val queried = mutableSetOf<String>()
    private val vulnerabilityService =
        object : VulnerabilityService {
            override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                dependencies.associate {
                    val coordinate = "${it.key}:${it.version}"
                    queried += coordinate
                    coordinate to if (coordinate == "example:shared:1.0") listOf(advisory) else emptyList()
                }
        }
    private val service = PluginSecurityService(vulnerabilityService)
    private val state =
        TargetVersionsState(
            mutableMapOf("example.trap" to "1.0", "example.safe" to "1.0"),
            mutableMapOf("example:shared" to "99.0"),
            "8.14.4",
            platformBoms = mutableMapOf("example:bom" to "99.0"),
        )

    @Test
    fun `offline published marker resolves implementation and transitive metadata without executing malicious plugin code`() {
        val gradleHome = Path.of(checkNotNull(System.getProperty("platforce.tests.gradleHome")))
        val repository = directory.resolve("maven")
        publish(repository, "example.trap", "example.trap.gradle.plugin", "1.0", "pom", listOf("example:trap-implementation:1.0"))
        publish(repository, "example.safe", "example.safe.gradle.plugin", "1.0", "pom", listOf("example:safe-implementation:1.0"))
        publish(repository, "example", "trap-implementation", "1.0", dependencies = listOf("example:shared:1.0"))
        publish(repository, "example", "safe-implementation", "1.0", dependencies = listOf("example:shared:2.0"))
        publish(repository, "example", "shared", "1.0")
        publish(repository, "example", "shared", "2.0")
        val trap = directory.resolve("plugin-code-executed")
        maliciousPluginJar(
            gradleHome,
            repository.resolve("example/trap-implementation/1.0/trap-implementation-1.0.jar"),
            trap,
        )
        publish(repository, "example.incomplete", "example.incomplete.gradle.plugin", "1.0", "pom", listOf("example:broken:1.0"))
        publish(repository, "example", "broken", "1.0", dependencies = listOf("example:shared:1.0", "example:missing-transitive:1.0"))
        publish(repository, "example.no-implementation", "example.no-implementation.gradle.plugin", "1.0", "pom")
        publish(
            repository,
            "example.missing-implementation",
            "example.missing-implementation.gradle.plugin",
            "1.0",
            "pom",
            listOf("example:missing-implementation:1.0"),
        )
        val targets =
            state.copy(
                plugins =
                    (
                        state.plugins +
                            mapOf(
                                "example.missing-marker" to "1.0",
                                "example.incomplete" to "1.0",
                                "example.no-implementation" to "1.0",
                                "example.missing-implementation" to "1.0",
                                "java" to "",
                                "example.inject'; throw new Exception('trap')" to "1.0",
                                "example.bad-version" to "${'$'}{new File('trap').createNewFile()}",
                            )
                    ).toMutableMap(),
            )
        val initScript = directory.resolve("offline-repository.gradle")
        initScript.writeText(
            """
            allprojects {
                afterEvaluate {
                    repositories.clear()
                    repositories.maven { url = uri('${repository.toUri()}') }
                }
            }
            """.trimIndent(),
        )
        val launcher = directory.resolve("gradle/bin/gradle")
        Files.createDirectories(launcher.parent)
        launcher.writeText("#!/bin/sh\nexec '${gradleHome.resolve("bin/gradle")}' --offline --init-script '$initScript' \"${'$'}@\"\n")
        check(launcher.toFile().setExecutable(true))
        val debug = ResolutionDebugFiles(directory.resolve("files"))
        val inspection = debug.inspectionDirectory("alice")
        Files.createDirectories(inspection)
        inspection.resolve("build.gradle").writeText("normal artifact")
        inspection.resolve("build-bom.gradle").writeText("BOM artifact")

        val resolution = GradleTargetResolutionService(launcher.parent.parent, debugFiles = debug).resolvePlugins("alice", targets)
        val results = service.scan(resolution, targets).plugins.associateBy { it.key }
        assertTrue(resolution.roots.isEmpty(), "No combined plugin classpath or application override graph is built")
        assertEquals(6, resolution.individual.size)
        assertEquals(PluginSecurityStatus.VULNERABLE, results.getValue("example.trap").status)
        assertEquals(
            "example:shared",
            results
                .getValue("example.trap")
                .vulnerableDependencies
                .single()
                .dependency.key,
        )
        assertEquals(listOf(advisory), results.getValue("example.trap").vulnerabilities)
        assertEquals(PluginSecurityStatus.OK, results.getValue("example.safe").status)
        listOf("example.missing-marker", "example.incomplete", "example.no-implementation", "example.missing-implementation").forEach {
            assertEquals(PluginSecurityStatus.UNRESOLVED, results.getValue(it).status, it)
            assertFalse(results.getValue(it).error.isNullOrBlank(), it)
        }
        assertTrue(
            results
                .getValue("example.incomplete")
                .error
                .orEmpty()
                .contains("missing-transitive"),
        )
        assertEquals(listOf(advisory), results.getValue("example.incomplete").vulnerabilities)
        assertEquals(3, results.values.count { it.status == PluginSecurityStatus.UNSUPPORTED })
        assertTrue(results.values.all { it.relatedTo.isEmpty() })
        assertTrue(queried.containsAll(listOf("example:shared:1.0", "example:shared:2.0")))
        assertFalse(queried.contains("example:shared:99.0"), "Application dependency and BOM targets cannot override plugin findings")
        assertFalse(trap.exists(), "Neither plugin static initialization, constructor nor apply may execute")
        assertFalse(Path.of(System.getProperty("user.dir")).resolve("trap").exists())
        assertEquals("normal artifact", inspection.resolve("build.gradle").readText())
        assertEquals("BOM artifact", inspection.resolve("build-bom.gradle").readText())
        val build = inspection.resolve("build-plugins.gradle").readText()
        assertTrue(build.contains("plugins { id 'java' }"))
        assertFalse(build.contains("buildscript"))
        assertFalse(build.contains("apply plugin"))
        assertFalse(build.contains("example.inject"))
        assertFalse(build.contains("example:shared"))
        assertTrue(inspection.resolve("gradle-output-plugins.txt").readText().contains("BUILD SUCCESSFUL"))
        assertTrue(inspection.resolve("gradle-unresolved-dependencies-plugins.txt").readText().contains("missing-transitive"))
        val report = JsonParser.parseString(inspection.resolve("gradle-resolution-report-plugins.json").readText()).asJsonObject
        assertEquals("READY", report["status"].asString)
        assertEquals(6, report["gradleTask"].asJsonObject["configurations"].asJsonArray.size())
        assertFalse(debug.latestDirectory("alice").resolve("plugin-metadata-project").exists())
    }

    @Test
    fun `malicious plugin input never becomes generated executable Groovy`() {
        val invalid =
            listOf(
                "example'; throw new Exception('bad')" to "1.0",
                "example.valid" to "1.0\"; throw new Exception('bad')",
                "example.valid" to "${'$'}{new File('trap').createNewFile()}",
                "example.valid" to "1.+",
                "example.valid" to "latest.release",
                "example.valid" to "",
                "java" to "1.0",
                "org.gradle.java" to "1.0",
            )
        invalid.forEach { (id, version) ->
            assertThrows(IllegalArgumentException::class.java) { GradlePluginResolutionBuildFile().create(mapOf(id to version)) }
            val result =
                service
                    .scan(
                        TargetResolution("8.14.4", emptyList(), emptyMap()),
                        state.copy(plugins = mutableMapOf(id to version)),
                    ).plugins
                    .single()
            assertEquals(PluginSecurityStatus.UNSUPPORTED, result.status)
            assertFalse(result.error.isNullOrBlank())
        }
    }

    @Test
    fun `missing trees incomplete marker and failed vulnerability lookup cannot become green or hide other plugin results`() {
        val marker = marker("example.trap", node("trap", children = listOf(node("shared"))))
        val safe = marker("example.safe", node("safe", version = "2.0"))
        val resolution = TargetResolution("8.14.4", emptyList(), mapOf("example.trap" to listOf(marker), "example.safe" to listOf(safe)))
        val failing =
            PluginSecurityService(
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> {
                        if (dependencies.any { it.key == "example:trap" }) error("synthetic lookup failure")
                        return dependencies.associate { "${it.key}:${it.version}" to emptyList() }
                    }
                },
            )
        val results = failing.scan(resolution, state).plugins.associateBy { it.key }
        assertEquals(PluginSecurityStatus.UNRESOLVED, results.getValue("example.trap").status)
        assertTrue(
            results
                .getValue("example.trap")
                .error
                .orEmpty()
                .contains("synthetic lookup failure"),
        )
        assertEquals(PluginSecurityStatus.OK, results.getValue("example.safe").status)
        assertTrue(
            service.scan(resolution.copy(individual = emptyMap()), state).plugins.all { it.status == PluginSecurityStatus.UNRESOLVED },
        )
        val emptyLookup =
            PluginSecurityService(
                object : VulnerabilityService {
                    override fun find(dependencies: List<ResolvedDependency>) = emptyMap<String, List<Vulnerability>>()
                },
            )
        assertTrue(emptyLookup.scan(resolution, state).plugins.all { it.status == PluginSecurityStatus.UNRESOLVED })
    }

    @Test
    fun `plugin jobs are per user deduplicated and isolated from changed or saved targets`() {
        val executor = QueuedExecutor()
        val requests = mutableListOf<Pair<String, TargetVersionsState>>()
        val scanner =
            PluginSecurityScanner(
                { username, targets ->
                    requests += username to targets
                    TargetResolution("8.14.4", emptyList(), emptyMap())
                },
                service,
                executor,
            )
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", state).status)
        val running = scanner.start("alice", state)
        scanner.start("alice", state)
        assertEquals(1, executor.queuedTasks)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("bob", state).status)
        scanner.start("bob", state)
        executor.runNext()
        assertEquals(SecurityScanStatus.READY, scanner.get("alice", state).status)
        assertEquals(SecurityScanStatus.RUNNING, scanner.get("bob", state).status)
        executor.runNext()
        assertEquals(listOf("alice", "bob"), requests.map { it.first })
        scanner.invalidate("alice")
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", state).status)
        assertEquals(SecurityScanStatus.READY, scanner.get("bob", state).status)
        scanner.start("alice", state)
        scanner.invalidate("alice")
        executor.runNext()
        assertEquals(2, requests.size, "Saved targets discard an already queued plugin job")
        assertNull(scanner.get("alice", state).result)

        val changed = state.copy(dependencies = mutableMapOf("unrelated:dependency" to "1.0"))
        assertNotEquals(running.targetFingerprint, scanner.get("bob", changed).targetFingerprint)
        assertEquals(SecurityScanStatus.IDLE, scanner.get("bob", changed).status)
        scanner.start("alice", state)
        state.plugins["example.trap"] = "2.0"
        executor.runNext()
        assertEquals("1.0", requests.last().second.plugins["example.trap"])
        assertEquals(SecurityScanStatus.IDLE, scanner.get("alice", state).status)
    }

    @Test
    fun `catastrophic Gradle failures are failed jobs while core and unversioned plugins do not run Gradle`() {
        val executor = QueuedExecutor()
        val scanner = PluginSecurityScanner({ _, _ -> error("Synthetic Gradle process failure") }, service, executor)
        scanner.start("alice", state)
        executor.runNext()
        val failed = scanner.get("alice", state)
        assertEquals(SecurityScanStatus.FAILED, failed.status)
        assertEquals("Synthetic Gradle process failure", failed.error)
        assertNull(failed.result)
        val core = state.copy(plugins = mutableMapOf("java" to "", "example.unversioned" to ""))
        scanner.start("alice", core)
        executor.runNext()
        assertEquals(SecurityScanStatus.READY, scanner.get("alice", core).status)
        assertTrue(
            scanner
                .get("alice", core)
                .result!!
                .plugins
                .all { it.status == PluginSecurityStatus.UNSUPPORTED },
        )
    }

    @Test
    fun `failed plugin Gradle execution publishes separate diagnostic artifacts and a failed job`() {
        val launcher = directory.resolve("failed-gradle/bin/gradle")
        Files.createDirectories(launcher.parent)
        launcher.writeText("#!/bin/sh\necho 'Synthetic plugin Gradle execution failure'\nexit 19\n")
        check(launcher.toFile().setExecutable(true))
        val debug = ResolutionDebugFiles(directory.resolve("debug"))
        val executor = QueuedExecutor()
        val resolutionService = GradleTargetResolutionService(launcher.parent.parent, debugFiles = debug)
        val scanner = PluginSecurityScanner(resolutionService::resolvePlugins, service, executor)
        scanner.start("alice", state)
        executor.runNext()
        assertEquals(SecurityScanStatus.FAILED, scanner.get("alice", state).status)
        assertTrue(
            scanner
                .get("alice", state)
                .error
                .orEmpty()
                .contains("Exit code: 19"),
        )
        val inspection = debug.inspectionDirectory("alice")
        assertTrue(inspection.resolve("build-plugins.gradle").readText().contains("configuredPlugins"))
        assertTrue(inspection.resolve("gradle-output-plugins.txt").readText().contains("Synthetic plugin Gradle execution failure"))
        assertTrue(inspection.resolve("gradle-unresolved-dependencies-plugins.txt").readText().contains("has not started"))
        assertEquals(
            "FAILED",
            JsonParser.parseString(inspection.resolve("gradle-resolution-report-plugins.json").readText()).asJsonObject["status"].asString,
        )
        assertFalse(debug.latestDirectory("alice").resolve("plugin-metadata-project").exists())
    }

    private fun node(
        name: String,
        version: String = "1.0",
        children: List<ResolvedDependency> = emptyList(),
    ) = ResolvedDependency("example", name, version, version, children)

    private fun marker(
        id: String,
        implementation: ResolvedDependency,
    ) = ResolvedDependency(id, "$id.gradle.plugin", "1.0", "1.0", listOf(implementation))

    private fun publish(
        repository: Path,
        group: String,
        artifact: String,
        version: String,
        packaging: String = "jar",
        dependencies: List<String> = emptyList(),
    ) {
        val location = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
        Files.createDirectories(location)
        location.resolve("$artifact-$version.pom").writeText(
            """
            <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>
            <version>$version</version><packaging>$packaging</packaging><dependencies>
            ${dependencies.joinToString("") {
                val (g, a, v) = it.split(':')
                "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
            }}
            </dependencies></project>
            """.trimIndent(),
        )
    }

    private fun maliciousPluginJar(
        gradleHome: Path,
        destination: Path,
        trap: Path,
    ) {
        val source = directory.resolve("TrapPlugin.java")
        val path = trap.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        source.writeText(
            """
            public class TrapPlugin implements org.gradle.api.Plugin<org.gradle.api.Project> {
                static { trap(); }
                public TrapPlugin() { trap(); }
                public void apply(org.gradle.api.Project project) { trap(); }
                private static void trap() {
                    try { java.nio.file.Files.writeString(java.nio.file.Path.of("$path"), "EXECUTED"); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                }
            }
            """.trimIndent(),
        )
        val classpath =
            Files.list(gradleHome.resolve("lib")).use { paths ->
                paths
                    .filter { it.toString().endsWith(".jar") }
                    .map { it.toString() }
                    .toList()
                    .joinToString(File.pathSeparator)
            }
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                classpath,
                "-d",
                directory.toString(),
                source.toString(),
            ),
        )
        JarOutputStream(Files.newOutputStream(destination)).use { jar ->
            jar.putNextEntry(JarEntry("TrapPlugin.class"))
            Files.copy(directory.resolve("TrapPlugin.class"), jar)
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/gradle-plugins/example.trap.properties"))
            jar.write("implementation-class=TrapPlugin\n".toByteArray())
            jar.closeEntry()
        }
    }
}
