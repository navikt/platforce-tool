package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.resolution.GradleResolutionBuildFile
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
    fun `public Gradle metadata APIs expose unused inherited and imported BOM constraints`() {
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
        module("inherited", "2.0")
        module("unused", "3.0")
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
                mutableMapOf("example:library" to "1.0", "example:higher-library" to "1.0"),
                "8.14.4",
                platformBoms = mutableMapOf("example:bom" to "1.0", "example:native" to "1.0"),
            )
        directory.resolve("build.gradle").writeText(
            GradleResolutionBuildFile().create(targets) +
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
        val managedVersions =
            bom["managed"].asJsonArray.associate {
                it.asJsonObject["name"].asString to it.asJsonObject["version"].asString
            }
        assertEquals(mapOf("inherited" to "2.0", "unused" to "3.0", "own" to "4.0"), managedVersions)
        val baseline =
            result["individual"]
                .asJsonObject["example:library"]
                .asJsonArray
                .single()
                .asJsonObject
        assertEquals(
            "1.0",
            baseline["dependencies"]
                .asJsonArray
                .single()
                .asJsonObject["version"]
                .asString,
        )
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
        assertEquals(
            "4.0",
            native["managed"]
                .asJsonArray
                .single()
                .asJsonObject["version"]
                .asString,
        )
    }
}
