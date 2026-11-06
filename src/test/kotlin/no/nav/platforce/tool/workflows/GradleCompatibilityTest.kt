package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.pullrequests.GradleBuildFileUpdater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class GradleCompatibilityTest {
    private val updater = GradleBuildFileUpdater()

    @Test
    fun `generated rules allow duplicate module metadata in actual Gradle Jar tasks`(
        @TempDir directory: Path,
    ) {
        listOf(false, true).forEach { kotlinDsl ->
            val project = Files.createDirectory(directory.resolve(if (kotlinDsl) "kotlin" else "groovy"))
            listOf("first", "second").forEach { name ->
                val metadata = project.resolve("$name/META-INF/example.kotlin_module")
                Files.createDirectories(metadata.parent)
                Files.writeString(metadata, name)
            }
            val plugin =
                if (kotlinDsl) """id("com.gradleup.shadow") version "9.6.1"""" else "id 'com.gradleup.shadow' version '9.6.1'"
            val task =
                if (kotlinDsl) {
                    """
                    tasks.register<org.gradle.jvm.tasks.Jar>("shadowJar") {
                        archiveFileName.set("app.jar")
                        destinationDirectory.set(layout.buildDirectory.dir("libs"))
                        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.FAIL
                        from("first")
                        from("second")
                    }
                    """.trimIndent()
                } else {
                    """
                    tasks.register('shadowJar', Jar) {
                        archiveFileName = 'app.jar'
                        destinationDirectory = layout.buildDirectory.dir('libs')
                        duplicatesStrategy = DuplicatesStrategy.FAIL
                        from('first')
                        from('second')
                    }
                    """.trimIndent()
                }
            val updated = updater.apply("plugins {\n    $plugin\n}\n", emptyList())
            Files.writeString(
                project.resolve(if (kotlinDsl) "build.gradle.kts" else "build.gradle"),
                updated.replace("plugins {\n    $plugin\n}", task),
            )
            Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'shadow-compatibility'")
            val output = project.resolve("gradle-output.txt")
            val gradle = Path.of(checkNotNull(System.getProperty("platforce.tests.gradleHome"))).resolve("bin/gradle")
            val process =
                ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--console=plain", "shadowJar")
                    .directory(project.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            val completed = process.waitFor(90, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue(completed, "Gradle did not finish")
            assertEquals(0, process.exitValue(), Files.readString(output))
            ZipFile(project.resolve("build/libs/app.jar").toFile()).use { jar ->
                assertEquals(2, jar.entries().asSequence().count { it.name == "META-INF/example.kotlin_module" })
            }
        }
    }

    @Test
    fun `Shadow PR preparation includes duplicate Kotlin metadata without relaxing other duplicate checks`() {
        listOf("com.gradleup.shadow", "com.github.johnrengelman.shadow").forEach { plugin ->
            val source =
                """
                plugins {
                    id '$plugin' version '9.6.1'
                }
                shadowJar {
                    archiveBaseName = 'app'
                    duplicatesStrategy = DuplicatesStrategy.FAIL
                }
                """.trimIndent()
            val updated = updater.apply(source, emptyList())
            assertTrue(updated.startsWith(source))
            assertTrue(updated.contains("tasks.named('shadowJar') {"))
            assertTrue(updated.contains("filesMatching('META-INF/*.kotlin_module') {"))
            assertTrue(updated.contains("duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE"))
            assertEquals(updated, updater.apply(updated, emptyList()))
        }
    }

    @Test
    fun `Shadow Kotlin DSL configuration is typed and preserves CRLF line endings`() {
        val source = "plugins {\r\n    id(\"com.gradleup.shadow\") version \"9.6.1\"\r\n}\r\n"
        val updated = updater.apply(source, emptyList())
        assertTrue(updated.contains("""tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar") {"""))
        assertTrue(updated.contains("""filesMatching("META-INF/*.kotlin_module") {"""))
        assertTrue(updated.replace("\r\n", "").contains('\n').not())
        assertEquals(updated, updater.apply(updated, emptyList()))
    }

    @Test
    fun `explicit Kotlin metadata rules are preserved and non Shadow builds stay unchanged`() {
        val existing =
            """
            plugins { id 'com.gradleup.shadow' version '9.6.1' }
            shadowJar {
                filesMatching('META-INF/*.kotlin_module') {
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }
            }
            """.trimIndent()
        assertEquals(existing, updater.apply(existing, emptyList()))
        listOf(
            "plugins {\n    id 'application'\n}",
            "// id 'com.gradleup.shadow' version '9.6.1'\n",
            "plugins {\n    id 'com.gradleup.shadow' version '9.6.1' apply false\n}",
        ).forEach { assertEquals(it, updater.apply(it, emptyList())) }
        val commented =
            """
            plugins {
                id 'com.gradleup.shadow' version '9.6.1'
            }
            // filesMatching('META-INF/*.kotlin_module') { }
            """.trimIndent()
        assertTrue(updater.apply(commented, emptyList()).contains("tasks.named('shadowJar')"))
    }

    @Test
    fun `compatibility updates application property without changing comments strings or similarly named properties`() {
        val source =
            """
            application {
                mainClassName = 'example.Main'
            }
            application.mainClassName = "example.Other"
            // mainClassName = 'leave alone'
            /* mainClassName = 'also leave alone' */
            def label = "mainClassName"
            def mainClassNameSuffix = 'unchanged'
            """.trimIndent()
        val expected =
            source
                .replace("mainClassName =", "mainClass =")
                .replace("// mainClass =", "// mainClassName =")
                .replace("/* mainClass =", "/* mainClassName =")
        assertEquals(expected, updater.apply(source, emptyList()))
    }

    @Test
    fun `Jupiter implementation declarations get a launcher using the matching DSL notation`() {
        listOf(
            "implementation 'org.junit.jupiter:junit-jupiter-api:5.11.4'" to
                "testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'",
            """testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")""" to
                """testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")""",
            "testImplementation(\n        \"org.junit.jupiter:junit-jupiter-api:5.11.4\"\n    )" to
                """testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")""",
        ).forEach { (declaration, launcher) ->
            val source = "dependencies {\n    $declaration\n}"
            val updated = updater.apply(source, emptyList())
            assertEquals("dependencies {\n    $launcher\n    $declaration\n}", updated)
            assertEquals(updated, updater.apply(updated, emptyList()), "Repeated PR preparation must not duplicate the launcher")
        }
    }

    @Test
    fun `existing versioned and versionless launchers are preserved and commented Jupiter declarations do not trigger additions`() {
        listOf(
            "testRuntimeOnly 'org.junit.platform:junit-platform-launcher'",
            """testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.0")""",
            """testRuntimeOnly(group: 'org.junit.platform', name: 'junit-platform-launcher', version: '1.12.0')""",
        ).forEach { launcher ->
            val source = "dependencies {\n    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.4'\n    $launcher\n}"
            assertEquals(source, updater.apply(source, emptyList()))
        }
        val source =
            """
            dependencies {
                // testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.4'
                /* implementation 'org.junit.jupiter:junit-jupiter-params:5.11.4' */
                implementation 'example:library:1.0'
            }
            """.trimIndent()
        assertEquals(source, updater.apply(source, emptyList()))
        val crlf = "dependencies {\r\n    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.11.4'\r\n}"
        assertTrue(updater.apply(crlf, emptyList()).contains("launcher:1.11.4'\r\n"))
    }
}
