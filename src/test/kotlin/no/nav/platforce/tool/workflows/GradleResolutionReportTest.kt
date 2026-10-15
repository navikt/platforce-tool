package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.GradleTargetResolutionService
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import no.nav.platforce.tool.targets.TargetVersionsState
import no.nav.platforce.tool.targets.fingerprint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GradleResolutionReportTest {
    @TempDir
    lateinit var directory: Path

    private val targets
        get() =
            TargetVersionsState(
                mutableMapOf(),
                mutableMapOf("example.nonexistent:one" to "1.0.0", "example.nonexistent:two" to "2.0.0"),
                "8.14.4",
            )

    @Test
    fun `real offline Gradle run publishes generated build unresolved findings and measured configuration durations`() {
        val gradleHome = Path.of(checkNotNull(System.getProperty("platforce.tests.gradleHome")))
        // Use the project's existing Gradle distribution offline: no downloads or external services.
        val launcher = launcher("exec '${gradleHome.resolve("bin/gradle")}' --offline \"${'$'}@\"")
        val store = ResolutionDebugFiles(directory.resolve("files"))
        val service = GradleTargetResolutionService(launcher, debugFiles = store)
        val result = service.resolve("alice", targets)
        val artifacts = store.latestDirectory("alice")
        val build = artifacts.resolve("build.gradle").readText()
        assertEquals(build, store.inspectionDirectory("alice").resolve("build.gradle").readText())
        assertTrue(build.contains("targetResolution \"example.nonexistent:one:1.0.0\""))
        assertTrue(build.contains("System.nanoTime()"))
        assertEquals(2, result.individual.size)

        val unresolved = artifacts.resolve("gradle-unresolved-dependencies.txt").readText()
        assertTrue(unresolved.contains("UNRESOLVED DEPENDENCY"))
        assertTrue(unresolved.contains("example.nonexistent"))
        assertTrue(unresolved.contains("Reason:"))
        assertTrue(artifacts.resolve("gradle-output.txt").readText().contains("BUILD SUCCESSFUL"))

        val report = JsonParser.parseString(artifacts.resolve("gradle-resolution-report.json").readText()).asJsonObject
        assertEquals("READY", report["status"].asString)
        assertEquals(fingerprint(targets), report["targetFingerprint"].asString)
        val totalMs = report["totalMs"].asDouble
        val processMs = report["phasesMs"].asJsonObject["gradleProcessMs"].asDouble
        val task = report["gradleTask"].asJsonObject
        val configurations = task["configurations"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("combined", "individual", "individual"), configurations.map { it["kind"].asString })
        assertEquals(
            listOf("example.nonexistent:one", "example.nonexistent:two"),
            configurations.drop(1).map { it["dependency"].asString },
        )
        assertTrue(configurations.all { it["durationMs"].asDouble > 0.0 })
        assertTrue(configurations.all { it["status"].asString == "READY" })
        assertTrue(totalMs >= processMs)
        assertTrue(processMs >= task["taskElapsedMs"].asDouble)
        assertEquals(processMs - task["taskElapsedMs"].asDouble, report["phasesMs"].asJsonObject["gradleOutsideTaskMs"].asDouble)
        assertTrue(task["executedGradleVersion"].asString.isNotBlank())
        assertTrue(task["taskElapsedMs"].asDouble >= configurations.sumOf { it["durationMs"].asDouble })
        assertTrue(report["measurementNotes"].asString.contains("OSV"))
    }

    @Test
    fun `failed Gradle process retains current build output and failure timings instead of old artifacts`() {
        val launcher = launcher("echo 'Synthetic Gradle startup failure'\nexit 19")
        val store = ResolutionDebugFiles(directory.resolve("files"))
        val service = GradleTargetResolutionService(launcher, debugFiles = store)
        val error = assertThrows(IllegalStateException::class.java) { service.resolve("alice", targets) }
        assertTrue(error.message.orEmpty().contains("Exit code: 19"))

        val artifacts = store.latestDirectory("alice")
        assertTrue(artifacts.resolve("build.gradle").readText().contains("example.nonexistent:one"))
        assertTrue(artifacts.resolve("gradle-output.txt").readText().contains("Synthetic Gradle startup failure"))
        assertTrue(artifacts.resolve("gradle-unresolved-dependencies.txt").readText().contains("has not started"))
        val report = JsonParser.parseString(artifacts.resolve("gradle-resolution-report.json").readText()).asJsonObject
        assertEquals("FAILED", report["status"].asString)
        assertTrue(report["error"].asString.contains("Exit code: 19"))
        assertTrue(report["phasesMs"].asJsonObject["gradleProcessMs"].asDouble > 0.0)
        assertTrue(report["totalMs"].asDouble > 0.0)
    }

    @Test
    fun `missing Gradle installation retains generated build and records failure before process startup`() {
        val store = ResolutionDebugFiles(directory.resolve("files"))
        val service = GradleTargetResolutionService(directory.resolve("not-installed"), debugFiles = store)
        assertThrows(IllegalStateException::class.java) { service.resolve("alice", targets) }
        val artifacts = store.latestDirectory("alice")
        assertTrue(artifacts.resolve("build.gradle").readText().contains("example.nonexistent:one"))
        assertTrue(artifacts.resolve("gradle-unresolved-dependencies.txt").readText().contains("has not started"))
        val report = JsonParser.parseString(artifacts.resolve("gradle-resolution-report.json").readText()).asJsonObject
        assertEquals("FAILED", report["status"].asString)
        assertTrue(report["error"].asString.contains("Expected executable:"))
        assertTrue(report["phasesMs"].asJsonObject.has("gradleDistributionCheckMs"))
    }

    @Test
    fun `unresolved BOM only scan fails explicitly and retains its diagnostic artifacts`() {
        val gradleHome = Path.of(checkNotNull(System.getProperty("platforce.tests.gradleHome")))
        val launcher = launcher("exec '${gradleHome.resolve("bin/gradle")}' --offline \"${'$'}@\"")
        val store = ResolutionDebugFiles(directory.resolve("files"))
        val service = GradleTargetResolutionService(launcher, debugFiles = store)
        val bomOnly = targets.copy(dependencies = mutableMapOf(), platformBoms = mutableMapOf("example.nonexistent:bom" to "1.0"))
        val error = assertThrows(IllegalStateException::class.java) { service.resolve("alice", bomOnly) }
        assertTrue(error.message.orEmpty().contains("Incomplete BOM resolution"), error.message)
        val artifacts = store.latestDirectory("alice")
        val report = JsonParser.parseString(artifacts.resolve("gradle-resolution-report.json").readText()).asJsonObject
        assertEquals("FAILED", report["status"].asString)
        assertTrue(artifacts.resolve("build.gradle").readText().contains("configuredPlatformBoms"))
        val inspection = store.inspectionDirectory("alice")
        assertEquals(artifacts.resolve("build.gradle").readText(), inspection.resolve("build-bom.gradle").readText())
        assertEquals(
            "FAILED",
            JsonParser.parseString(inspection.resolve("gradle-resolution-report-bom.json").readText()).asJsonObject["status"].asString,
        )
        assertTrue(artifacts.resolve("gradle-output.txt").readText().contains("example.nonexistent:bom:1.0"))
    }

    private fun launcher(script: String): Path {
        val home = directory.resolve("gradle")
        Files.createDirectories(home.resolve("bin"))
        val executable = home.resolve("bin/gradle")
        executable.writeText("#!/bin/sh\n$script\n")
        check(executable.toFile().setExecutable(true))
        return home
    }
}
