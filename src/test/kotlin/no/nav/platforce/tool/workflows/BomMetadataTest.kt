package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.GradleResolutionBuildFile
import no.nav.platforce.tool.resolution.GradleTargetResolutionService
import no.nav.platforce.tool.resolution.ResolutionDebugFiles
import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.resolution.TargetResolution
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class BomMetadataTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `production coverage build resolves only relevant targets without catalog or baseline configurations`() {
        val repository = directory.resolve("repository")

        fun pom(
            name: String,
            body: String,
        ) {
            val path = repository.resolve("example/$name/1.0/$name-1.0.pom")
            Files.createDirectories(path.parent)
            path.writeText(
                """<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>1.0</version><packaging>pom</packaging>$body</project>""",
            )
        }

        fun managed(
            name: String,
            version: String,
        ) = "<dependency><groupId>example</groupId><artifactId>$name</artifactId><version>$version</version></dependency>"
        pom(
            "parent",
            "<properties><child.version>2.0</child.version></properties><dependencyManagement><dependencies>${managed(
                "inherited",
                "\${child.version}",
            )}</dependencies></dependencyManagement>",
        )
        pom(
            "imported",
            "<dependencyManagement><dependencies>" +
                managed("unused", "3.0") + managed("own", "2.0") +
                "</dependencies></dependencyManagement>",
        )
        pom(
            "bom",
            "<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>" +
                "<dependencyManagement><dependencies><dependency><groupId>example</groupId><artifactId>imported</artifactId>" +
                "<version>1.0</version><type>pom</type><scope>import</scope></dependency>" +
                managed("own", "4.0") + "</dependencies></dependencyManagement>",
        )
        directory.resolve("settings.gradle").writeText("rootProject.name = 'bom-metadata-fixture'")
        directory.resolve("build.gradle").writeText(
            """
            import groovy.json.JsonOutput
            plugins { id 'java' }
            repositories { maven { url = uri('${repository.toUri()}') } }
            def catalogs = [:]
            dependencies {
                components {
                    all { details ->
                        details.allVariants {
                            withDependencyConstraints { constraints ->
                                def entries = constraints.collect { constraint ->
                                    [key: constraint.group + ':' + constraint.name, version: constraint.versionConstraint.requiredVersion]
                                }
                                catalogs[details.id.toString()] = entries
                            }
                        }
                    }
                }
                implementation platform('example:bom:1.0')
            }
            tasks.register('inspectBom') {
                doLast {
                    configurations.compileClasspath.incoming.resolutionResult.allComponents.each { it.id }
                    file('catalogs.json').text = JsonOutput.toJson(catalogs)
                }
            }
            """.trimIndent(),
        )
        val gradle = Path.of(checkNotNull(System.getProperty("platforce.tests.gradleHome"))).resolve("bin/gradle")
        val process =
            ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--console=plain", "inspectBom")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply { environment()["GRADLE_USER_HOME"] = directory.resolve("gradle-home").toString() }
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        val catalogs = JsonParser.parseString(directory.resolve("catalogs.json").readText()).asJsonObject
        val entries = catalogs.entrySet().flatMap { it.value.asJsonArray.toList() }
        val versions = entries.associate { it.asJsonObject["key"].asString to it.asJsonObject["version"].asString }
        assertEquals("2.0", versions["example:inherited"], catalogs.toString())
        assertEquals("3.0", versions["example:unused"], catalogs.toString())
        assertEquals("4.0", versions["example:own"], catalogs.toString())
        assertTrue(catalogs.has("example:bom:1.0"), catalogs.toString())

        // Run the production generator against the same offline repository, not a replica of its algorithm.
        fun module(
            name: String,
            version: String,
            children: String = "",
        ) {
            val path = repository.resolve("example/$name/$version/$name-$version.pom")
            Files.createDirectories(path.parent)
            path.writeText(
                "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>$name</artifactId><version>$version</version><dependencies>$children</dependencies></project>",
            )
        }
        module("own", "4.0")
        module("own", "1.0")
        module("own", "5.0")
        module("library", "1.0", managed("own", "1.0"))
        module("higher-library", "1.0", managed("own", "5.0"))
        pom("native", "<!-- do_not_remove: published-with-gradle-metadata -->")
        repository.resolve("example/native/1.0/native-1.0.module").writeText(
            """
            {
              "formatVersion": "1.1",
              "component": {"group": "example", "module": "native", "version": "1.0"},
              "variants": [{
                "name": "runtimeElements",
                "attributes": {"org.gradle.category": "platform", "org.gradle.usage": "java-runtime"},
                "dependencyConstraints": [{"group": "example", "module": "own", "version": {"requires": "4.0", "strictly": "4.0"}}]
              }]
            }
            """.trimIndent(),
        )
        val targets =
            TargetVersionsState(
                mutableMapOf(),
                mutableMapOf("example:library" to "1.0", "example:higher-library" to "1.0", "example:never-resolve" to "1.0"),
                "8.14.4",
                platformBoms = mutableMapOf("example:bom" to "1.0", "example:native" to "1.0"),
            )
        directory.resolve("build.gradle").writeText(
            GradleResolutionBuildFile().createCoverage(targets, setOf("example:library", "example:higher-library")) +
                "\nrepositories.clear()\nrepositories { maven { url = uri('${repository.toUri()}') } }\n",
        )
        val generated =
            ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--console=plain", "platforceResolve")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply { environment()["GRADLE_USER_HOME"] = directory.resolve("gradle-home").toString() }
                .start()
        val generatedOutput = generated.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, generated.waitFor(), generatedOutput)
        val result = JsonParser.parseString(directory.resolve("resolution.json").readText()).asJsonObject
        val bom = result["platformBoms"].asJsonObject["example:bom"].asJsonObject
        assertTrue(bom["managed"].asJsonArray.isEmpty)
        assertTrue(result["individual"].asJsonObject.entrySet().isEmpty())
        assertTrue(result["roots"].asJsonArray.isEmpty)
        assertEquals(4, bom["coverage"].asJsonArray.size())
        bom["coverage"].asJsonArray.forEach { coverage ->
            val candidate = coverage.asJsonObject
            val root = candidate["roots"].asJsonArray.single().asJsonObject
            val expected =
                if (candidate["dependency"].asString == "example:higher-library" &&
                    !candidate["enforcedPlatform"].asBoolean
                ) {
                    "5.0"
                } else {
                    "4.0"
                }
            assertEquals(
                expected,
                root["dependencies"]
                    .asJsonArray
                    .single()
                    .asJsonObject["version"]
                    .asString,
            )
        }
        val native = result["platformBoms"].asJsonObject["example:native"].asJsonObject
        assertTrue(native["managed"].asJsonArray.isEmpty)
        val timings = JsonParser.parseString(directory.resolve("gradle-configuration-timings.json").readText()).asJsonObject
        assertEquals(8, timings["configurations"].asJsonArray.size())
        assertTrue(timings["configurations"].asJsonArray.all { it.asJsonObject["kind"].asString == "bom-coverage" })
        assertTrue(timings["taskElapsedMs"].asDouble > 0)

        val initScript = directory.resolve("fixture.init.gradle")
        initScript.writeText(
            "allprojects { afterEvaluate { repositories.clear(); repositories { maven { url = uri('${repository.toUri()}') } } } }",
        )
        val executable = directory.resolve("installation/bin/gradle")
        Files.createDirectories(executable.parent)
        executable.writeText("#!/bin/sh\nexec '$gradle' --offline --init-script '$initScript' \"\$@\"\n")
        executable.toFile().setExecutable(true)
        val debug = ResolutionDebugFiles(directory.resolve("debug"))
        val service = GradleTargetResolutionService(executable.parent.parent, debugFiles = debug)
        val node = ResolvedDependency("example", "library", "1.0", "1.0", emptyList())
        val baseline = TargetResolution("8.14.4", listOf(node), mapOf("example:library" to listOf(node)))
        val coverage = service.resolveCoverage("alice", targets, baseline, setOf("example:library"))
        assertEquals(baseline.roots, coverage.roots)
        assertEquals(baseline.individual, coverage.individual)
        assertEquals(setOf("example:bom", "example:native"), coverage.platformBoms.keys)
        assertTrue(coverage.platformBoms.values.all { it.coverage.size == 2 && it.coverage.all { c -> c.dependency == "example:library" } })
        val inspection = debug.inspectionDirectory("alice")
        val buildFile = inspection.resolve("build-bom.gradle").readText()
        assertTrue("targetResolution" !in buildFile)
        assertTrue("example:never-resolve" !in buildFile)
        assertTrue("example:higher-library" !in buildFile)
        val serviceTimings = JsonParser.parseString(inspection.resolve("gradle-configuration-timings-bom.json").readText()).asJsonObject
        assertEquals(4, serviceTimings["configurations"].asJsonArray.size())
        assertTrue(inspection.resolve("gradle-resolution-report-bom.json").readText().contains("gradleProcessMs"))
    }
}
