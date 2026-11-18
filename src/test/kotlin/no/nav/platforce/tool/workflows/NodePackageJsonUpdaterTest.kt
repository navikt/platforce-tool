package no.nav.platforce.tool.workflows

import com.google.gson.JsonParser
import no.nav.platforce.tool.node.PackageJsonParser
import no.nav.platforce.tool.pullrequests.NodePackageJsonUpdater
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyStatus
import no.nav.platforce.tool.repositories.PullRequestReadinessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodePackageJsonUpdaterTest {
    private val updater = NodePackageJsonUpdater()

    private fun finding(
        key: String,
        current: String?,
        target: String,
        declaration: String = "dependencies",
        status: DependencyStatus = DependencyStatus.UPDATE,
    ) = DependencyFinding(DependencyKind.NODE, key, current, target, status, declaration = declaration)

    @Test
    fun `updates production dependencies with carets but resolves exact versions and preserves unrelated fields and formatting`() {
        val source =
            """
            {
                "name": "example",
                "dependencies": {"express": "~4.20.0"},
                "devDependencies": {"test": "^1.0.0"},
                "scripts": {"start": "node app.js"},
                "engines": {"node": ">=18"},
                "custom": null
            }

            """.trimIndent().replace("\n", "\r\n")
        val update = updater.apply(source, listOf(finding("express", "~4.20.0", "4.21.2")))
        val result = JsonParser.parseString(update.manifest).asJsonObject
        val original = JsonParser.parseString(source).asJsonObject
        assertEquals("^4.21.2", result.getAsJsonObject("dependencies")["express"].asString)
        listOf("devDependencies", "scripts", "engines", "custom").forEach { assertEquals(original[it], result[it]) }
        assertTrue(update.manifest.contains("\r\n    \"name\""))
        assertTrue(update.manifest.endsWith("\r\n"))
        assertEquals(
            "4.21.2",
            JsonParser
                .parseString(update.resolutionManifest)
                .asJsonObject
                .getAsJsonObject("dependencies")["express"]
                .asString,
        )
    }

    @Test
    fun `matching targets are pinned for lock refresh without changing their saved manifest ranges`() {
        val source = """{"dependencies":{"app":"^1.0.0","other":"~2.0.0"},"overrides":{"leaf":"^3.0.0"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("app", "^1.0.0", "1.1.0"),
                    finding("other", "~2.0.0", "2.0.0", status = DependencyStatus.OK),
                    finding("leaf", "^3.0.0", "3.0.0", "overrides > leaf", DependencyStatus.OK_TRANSIENT),
                ),
            )
        val final = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("~2.0.0", final.getAsJsonObject("dependencies")["other"].asString)
        assertEquals("^3.0.0", final.getAsJsonObject("overrides")["leaf"].asString)
        val resolution = JsonParser.parseString(update.resolutionManifest).asJsonObject
        assertEquals("1.1.0", resolution.getAsJsonObject("dependencies")["app"].asString)
        assertEquals("2.0.0", resolution.getAsJsonObject("dependencies")["other"].asString)
        assertEquals("3.0.0", resolution.getAsJsonObject("overrides")["leaf"].asString)
    }

    @Test
    fun `unchanged override references pin their direct dependency even when it is excluded from repository findings`() {
        val source = """{"dependencies":{"app":"1.0.0"},"devDependencies":{"uuid":"^11.1.1"},"overrides":{"uuid":"${'$'}uuid"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("app", "1.0.0", "1.1.0"),
                    finding("uuid", "^11.1.1", "11.1.1", "overrides > uuid", DependencyStatus.OK_TRANSIENT),
                ),
            )
        val final = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("^11.1.1", final.getAsJsonObject("devDependencies")["uuid"].asString)
        val resolution = JsonParser.parseString(update.resolutionManifest).asJsonObject
        assertEquals("11.1.1", resolution.getAsJsonObject("devDependencies")["uuid"].asString)
        assertEquals("\$uuid", resolution.getAsJsonObject("overrides")["uuid"].asString)
    }

    @Test
    fun `nested and versioned scoped overrides retain their scopes and sibling entries`() {
        val source = """{"overrides":{"@scope/parent@^1":{".":"1.0.0","leaf":"2.0.0"},"other":"3.0.0"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("@scope/parent", "1.0.0", "1.1.0", "overrides > @scope/parent@^1 > ."),
                    finding("leaf", "2.0.0", "2.1.0", "overrides > @scope/parent@^1 > leaf"),
                ),
            )
        val overrides = JsonParser.parseString(update.manifest).asJsonObject.getAsJsonObject("overrides")
        assertEquals("^1.1.0", overrides.getAsJsonObject("@scope/parent@^1")["."].asString)
        assertEquals("^2.1.0", overrides.getAsJsonObject("@scope/parent@^1")["leaf"].asString)
        assertEquals("3.0.0", overrides["other"].asString)
        assertFalse(update.manifest.endsWith("\n"))
    }

    @Test
    fun `new covers are global overrides and normal additions stay normal`() {
        val update =
            updater.apply(
                """{"dependencies":{"parent":"1.0.0"}}""",
                listOf(
                    finding("cover", null, "2.0.0", "overrides > cover", DependencyStatus.ADD),
                    finding("normal", null, "3.0.0", status = DependencyStatus.ADD),
                ),
            )
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("^2.0.0", result.getAsJsonObject("overrides")["cover"].asString)
        assertEquals("^3.0.0", result.getAsJsonObject("dependencies")["normal"].asString)
        assertEquals("1.0.0", result.getAsJsonObject("dependencies")["parent"].asString)
    }

    @Test
    fun `a direct dependency and its global cover use matching npm references without dropping either role`() {
        val source = """{"dependencies":{"uuid":"^8.3.2"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("uuid", null, "11.1.1", "overrides > uuid", DependencyStatus.ADD),
                    finding("uuid", "^8.3.2", "11.1.1"),
                ),
            )
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("^11.1.1", result.getAsJsonObject("dependencies")["uuid"].asString)
        assertEquals("\$uuid", result.getAsJsonObject("overrides")["uuid"].asString)
        val resolution = JsonParser.parseString(update.resolutionManifest).asJsonObject
        assertEquals("11.1.1", resolution.getAsJsonObject("dependencies")["uuid"].asString)
        assertEquals("\$uuid", resolution.getAsJsonObject("overrides")["uuid"].asString)
        assertEquals(listOf("^11.1.1", "^11.1.1"), PackageJsonParser().parse(update.manifest).map { it.version })
    }

    @Test
    fun `development upgrades and normal additions remain in dev dependencies with exact resolution versions`() {
        val source = """{"dependencies":{"app":"1.0.0"},"devDependencies":{"tool":"^1.0.0"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("tool", "^1.0.0", "1.1.0", "devDependencies"),
                    finding("helper", null, "2.0.0", "devDependencies", DependencyStatus.ADD),
                ),
            )
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals(setOf("app"), result.getAsJsonObject("dependencies").keySet())
        assertEquals("^1.1.0", result.getAsJsonObject("devDependencies")["tool"].asString)
        assertEquals("^2.0.0", result.getAsJsonObject("devDependencies")["helper"].asString)
        val resolution = JsonParser.parseString(update.resolutionManifest).asJsonObject.getAsJsonObject("devDependencies")
        assertEquals("1.1.0", resolution["tool"].asString)
        assertEquals("2.0.0", resolution["helper"].asString)

        val addition = updater.apply("{}", listOf(finding("tool", null, "1.1.0", "devDependencies", DependencyStatus.ADD)))
        assertEquals(setOf("devDependencies"), JsonParser.parseString(addition.manifest).asJsonObject.keySet())
    }

    @Test
    fun `development direct dependencies and global overrides preserve references across repeated upgrades`() {
        val source = """{"devDependencies":{"uuid":"^8.3.2"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("uuid", null, "11.1.1", "overrides > uuid", DependencyStatus.ADD),
                    finding("uuid", "^8.3.2", "11.1.1", "devDependencies"),
                ),
            )
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertFalse(result.has("dependencies"))
        assertEquals("^11.1.1", result.getAsJsonObject("devDependencies")["uuid"].asString)
        assertEquals("\$uuid", result.getAsJsonObject("overrides")["uuid"].asString)
        val resolution = JsonParser.parseString(update.resolutionManifest).asJsonObject
        assertEquals("11.1.1", resolution.getAsJsonObject("devDependencies")["uuid"].asString)
        assertEquals("\$uuid", resolution.getAsJsonObject("overrides")["uuid"].asString)
        val declarations = PackageJsonParser().parse(update.manifest)
        assertEquals(listOf("^11.1.1", "^11.1.1"), declarations.map { it.version })

        val next = updater.apply(update.manifest, declarations.map { finding(it.key, it.version, "12.0.1", it.declaration) })
        assertEquals(listOf("^12.0.1", "^12.0.1"), PackageJsonParser().parse(next.manifest).map { it.version })
        assertEquals(
            listOf("devDependencies", "overrides > uuid"),
            PackageJsonParser().parse(next.manifest).map { it.declaration },
        )
    }

    @Test
    fun `new global self override preserves nested overrides and pins an unchanged direct range for resolution`() {
        val source = """{"dependencies":{"uuid":"^11.1.1"},"overrides":{"uuid":{"leaf":"1.0.0"}}}"""
        val update = updater.apply(source, listOf(finding("uuid", null, "11.1.1", "overrides > uuid", DependencyStatus.ADD)))
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("\$uuid", result.getAsJsonObject("overrides").getAsJsonObject("uuid")["."].asString)
        assertEquals("1.0.0", result.getAsJsonObject("overrides").getAsJsonObject("uuid")["leaf"].asString)
        assertEquals(
            "11.1.1",
            JsonParser
                .parseString(update.resolutionManifest)
                .asJsonObject
                .getAsJsonObject("dependencies")["uuid"]
                .asString,
        )
    }

    @Test
    fun `reference overrides follow direct upgrades and can be scanned again after merge`() {
        val source = """{"dependencies":{"uuid":"^8.3.2"},"overrides":{"uuid":"${'$'}uuid"}}"""
        val declarations = PackageJsonParser().parse(source)
        val update =
            updater.apply(
                source,
                declarations.map { finding(it.key, it.version, "11.1.1", it.declaration) },
            )
        val reparsed = PackageJsonParser().parse(update.manifest)
        assertTrue(reparsed.all { it.version == "^11.1.1" })
        assertEquals(listOf("overrides", "uuid"), reparsed.last().path)
    }

    @Test
    fun `removing covers prunes empty objects but preserves direct dependencies and nested siblings`() {
        val source = """{"dependencies":{"uuid":"11.1.1"},"overrides":{"uuid":{".":"11.1.1","leaf":"2.0.0"},"unused":"3.0.0"}}"""
        val update =
            updater.apply(
                source,
                listOf(
                    finding("uuid", "11.1.1", "11.1.1", "overrides > uuid > .", DependencyStatus.REMOVE),
                    finding("unused", "3.0.0", "3.0.0", "overrides > unused", DependencyStatus.REMOVE),
                ),
            )
        val result = JsonParser.parseString(update.manifest).asJsonObject
        assertEquals("11.1.1", result.getAsJsonObject("dependencies")["uuid"].asString)
        assertEquals(setOf("uuid"), result.getAsJsonObject("overrides").keySet())
        assertEquals(setOf("leaf"), result.getAsJsonObject("overrides").getAsJsonObject("uuid").keySet())
        val empty =
            updater.apply(
                """{"overrides":{"unused":"3.0.0"}}""",
                listOf(finding("unused", "3.0.0", "3.0.0", "overrides > unused", DependencyStatus.REMOVE)),
            )
        assertFalse(JsonParser.parseString(empty.manifest).asJsonObject.has("overrides"))
        assertThrows(IllegalStateException::class.java) {
            updater.apply(
                """{"dependencies":{"uuid":"11.1.1"}}""",
                listOf(finding("uuid", "11.1.1", "11.1.1", status = DependencyStatus.REMOVE)),
            )
        }
    }

    @Test
    fun `stale declarations unsupported versions and mismatched direct overrides fail explicitly`() {
        assertThrows(PullRequestReadinessException::class.java) {
            updater.apply("""{"dependencies":{"uuid":"9.0.0"}}""", listOf(finding("uuid", "8.3.2", "11.1.1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            updater.apply("""{"dependencies":{"uuid":"8.3.2"}}""", listOf(finding("uuid", "8.3.2", "file:/tmp/private")))
        }
        assertThrows(IllegalStateException::class.java) {
            updater.apply(
                """{"dependencies":{"uuid":"8.3.2"}}""",
                listOf(finding("uuid", null, "11.1.1", "overrides > uuid", DependencyStatus.ADD)),
            )
        }
    }
}
