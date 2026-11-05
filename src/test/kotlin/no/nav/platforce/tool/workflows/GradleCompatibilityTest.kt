package no.nav.platforce.tool.workflows

import no.nav.platforce.tool.pullrequests.GradleBuildFileUpdater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleCompatibilityTest {
    private val updater = GradleBuildFileUpdater()

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
