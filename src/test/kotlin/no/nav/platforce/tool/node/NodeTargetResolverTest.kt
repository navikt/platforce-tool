package no.nav.platforce.tool.node

import no.nav.platforce.tool.resolution.ResolvedDependency
import no.nav.platforce.tool.security.Vulnerability
import no.nav.platforce.tool.security.VulnerabilityService
import no.nav.platforce.tool.targets.TargetVersionsState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NodeTargetResolverTest {
    @TempDir
    lateinit var directory: Path

    private val lock =
        """
        {"lockfileVersion":3,"packages":{
          "":{"dependencies":{"@scope/root":"1.0.0"}},
          "node_modules/@scope/root":{"version":"1.0.0","resolved":"https://registry.npmjs.org/@scope/root/-/root-1.0.0.tgz"},
          "node_modules/transitive":{"version":"2.0.0"},
          "node_modules/@scope/root/node_modules/transitive":{"version":"1.0.0"},
          "node_modules/alias":{"name":"actual-package","version":"3.0.0"},
          "node_modules/optional":{"version":"4.0.0","optional":true,"os":["linux"]}
        }}
        """.trimIndent()

    @Test
    fun `npm version comparison follows semver ordering and ignores build metadata`() {
        val ordered =
            listOf(
                "1.0.0-alpha",
                "1.0.0-alpha.1",
                "1.0.0-alpha.beta",
                "1.0.0-beta.2",
                "1.0.0-beta.11",
                "1.0.0-rc.1",
                "1.0.0",
                "1.2.0",
                "1.10.0",
                "2.0.0",
            )
        ordered.zipWithNext().forEach { (first, second) ->
            assertTrue(compareNodeVersions(first, second) < 0, "$first < $second")
            assertTrue(compareNodeVersions(second, first) > 0, "$second > $first")
        }
        assertEquals(0, compareNodeVersions("1.0.0+build.1", "1.0.0+build.2"))
    }

    @Test
    fun `lock parser retains scoped names aliases nested versions and optional packages`() {
        val packages = NodeTargetResolver().parseLockfile(lock, "@scope/root", "1.0.0")
        assertEquals(
            listOf(
                "@scope/root" to "1.0.0",
                "transitive" to "2.0.0",
                "transitive" to "1.0.0",
                "actual-package" to "3.0.0",
                "optional" to "4.0.0",
            ),
            packages.map { it.name to it.version },
        )
        assertTrue(packages.all { it.group == "npm" })
    }

    @Test
    fun `combined lockfile traces each root through nested hoisted scoped alias peer and cyclic dependencies`() {
        val source =
            """
            {"lockfileVersion":3,"packages":{
              "":{"dependencies":{"alpha":"1.0.0","@scope/beta":"1.0.0"}},
              "node_modules/alpha":{"version":"1.0.0","dependencies":{"shared":"^1","leaf":"^1"},
                "optionalDependencies":{"absent":"^1"},"peerDependencies":{"peer":"^1","optional-peer":"^1"},
                "peerDependenciesMeta":{"optional-peer":{"optional":true}}},
              "node_modules/alpha/node_modules/shared":{"version":"1.0.0","dependencies":{"alpha":"1.0.0"}},
              "node_modules/@scope/beta":{"version":"1.0.0","dependencies":{"shared":"^2","alias":"npm:actual-source@1.0.0"}},
              "node_modules/shared":{"version":"2.0.0"},
              "node_modules/leaf":{"version":"2.0.0"},
              "node_modules/peer":{"version":"1.0.0"},
              "node_modules/alias":{"name":"actual-source","version":"1.0.0"}
            }}
            """.trimIndent()
        val resolver = NodeTargetResolver()
        val dependencies = mapOf("alpha" to "1.0.0", "@scope/beta" to "1.0.0")
        val overrides = mapOf("leaf" to "2.0.0", "unused" to "3.0.0")
        val result = resolver.parseCombinedLockfile(source, dependencies, overrides)
        assertEquals(
            setOf("alpha" to "1.0.0", "shared" to "1.0.0", "leaf" to "2.0.0", "peer" to "1.0.0"),
            result.targets
                .getValue("alpha")
                .map { it.name to it.version }
                .toSet(),
        )
        assertEquals(
            setOf("@scope/beta" to "1.0.0", "shared" to "2.0.0", "actual-source" to "1.0.0"),
            result.targets
                .getValue("@scope/beta")
                .map { it.name to it.version }
                .toSet(),
        )
        assertEquals(listOf("leaf"), result.targets.getValue("leaf").map { it.name })
        assertTrue(result.targets.getValue("unused").isEmpty())
        assertThrows(IllegalStateException::class.java) {
            resolver.parseCombinedLockfile(source.replace("\"peer\":\"^1\"", "\"missing-peer\":\"^1\""), dependencies, overrides)
        }
        assertThrows(IllegalStateException::class.java) {
            resolver.parseCombinedLockfile(source, dependencies, overrides + ("leaf" to "3.0.0"))
        }
    }

    @Test
    fun `combined manifest keeps ordinary dependencies and overrides separate`() {
        val manifest = directory.resolve("manifest")
        val home = fakeNode("/bin/cp package.json '$manifest'\n/bin/cat > package-lock.json <<'LOCK'\n$lock\nLOCK")
        val result =
            NodeTargetResolver(home).resolveCombined(
                mapOf("@scope/root" to "1.0.0", "optional" to "4.0.0"),
                mapOf("actual-package" to "3.0.0", "unused" to "3.0.0"),
            )
        val json =
            com.google.gson.JsonParser
                .parseString(manifest.readText())
                .asJsonObject
        assertEquals(setOf("@scope/root", "optional"), json["dependencies"].asJsonObject.keySet())
        assertEquals(setOf("actual-package", "unused"), json["overrides"].asJsonObject.keySet())
        assertTrue(result.targets.getValue("unused").isEmpty())
    }

    @Test
    fun `incomplete foreign linked or wrong version lockfiles fail explicitly`() {
        listOf(
            "{}",
            lock.replace("\"lockfileVersion\":3", "\"lockfileVersion\":2"),
            lock.replace("\"version\":\"1.0.0\"", "\"version\":\"9.0.0\""),
            lock.replace("\"version\":\"4.0.0\"", "\"version\":\"*\""),
            lock.replace("\"optional\":true", "\"link\":true"),
            lock.replace("https://registry.npmjs.org", "https://example.test"),
            lock.replace("https://registry.npmjs.org", "http://registry.npmjs.org"),
        ).forEach { source ->
            assertThrows(Exception::class.java) { NodeTargetResolver().parseLockfile(source, "@scope/root", "1.0.0") }
        }
    }

    @Test
    fun `resolver generates an exact manifest disables scripts and isolates npm configuration and cleanup`() {
        val recorded = directory.resolve("recorded")
        val home =
            fakeNode(
                """
                /bin/mkdir -p '$recorded'
                /bin/cp package.json '$recorded/package.json'
                /usr/bin/printf '%s\n' "${'$'}@" > '$recorded/arguments'
                /usr/bin/printf '%s' "${'$'}PWD" > '$recorded/workdir'
                /usr/bin/env > '$recorded/environment'
                """.trimIndent() + "\n/bin/cat > package-lock.json <<'LOCK'\n$lock\nLOCK\n",
            )
        val result = NodeTargetResolver(home).resolveWithOverrides("@scope/root", "1.0.0", mapOf("@scope/transitive" to "2.0.0"))
        assertEquals(5, result.size)
        assertTrue(recorded.resolve("package.json").readText().contains("\"@scope/root\":\"1.0.0\""))
        assertTrue(recorded.resolve("package.json").readText().contains("\"overrides\":{\"@scope/transitive\":\"2.0.0\"}"))
        val arguments = recorded.resolve("arguments").readText()
        assertTrue(arguments.contains("--package-lock-only"))
        assertTrue(arguments.contains("--ignore-scripts"))
        assertTrue(arguments.contains("--registry=https://registry.npmjs.org"))
        assertTrue(arguments.contains("--userconfig="))
        assertTrue(arguments.contains("--globalconfig="))
        assertTrue(arguments.contains("--prefer-offline"))
        assertFalse(recorded.resolve("environment").readText().contains("NODE_OPTIONS="))
        assertFalse(Path.of(recorded.resolve("workdir").readText()).exists())
    }

    @Test
    fun `session reuses a private npm cache but not project directories and cleans up on success or failure`() {
        val caches = directory.resolve("caches")
        val projects = directory.resolve("projects")
        val home =
            fakeNode(
                """
                for argument in "${'$'}@"; do
                  case "${'$'}argument" in
                    --cache=*) /usr/bin/printf '%s\n' "${'$'}{argument#--cache=}" >> '$caches' ;;
                  esac
                done
                /usr/bin/printf '%s\n' "${'$'}PWD" >> '$projects'
                """.trimIndent() + "\n/bin/cat > package-lock.json <<'LOCK'\n$lock\nLOCK\n",
            )
        val resolver = NodeTargetResolver(home)
        resolver.withSession { session ->
            session.resolve("@scope/root", "1.0.0")
            session.resolveWithOverrides("@scope/root", "1.0.0", mapOf("unrelated" to "2.0.0"))
            val paths =
                caches
                    .readText()
                    .trim()
                    .lines()
                    .map(Path::of)
            assertEquals(2, paths.size)
            assertEquals(paths[0], paths[1])
            assertTrue(paths[0].exists())
            val workdirs =
                projects
                    .readText()
                    .trim()
                    .lines()
                    .map(Path::of)
            assertEquals(2, workdirs.distinct().size)
            assertTrue(workdirs.none { it.exists() })
        }
        val firstCache =
            Path.of(
                caches
                    .readText()
                    .trim()
                    .lines()
                    .first(),
            )
        assertFalse(firstCache.exists())
        assertThrows(IllegalStateException::class.java) {
            resolver.withSession { session ->
                session.resolve("@scope/root", "1.0.0")
                error("Synthetic scan failure")
            }
        }
        val secondCache =
            Path.of(
                caches
                    .readText()
                    .trim()
                    .lines()
                    .last(),
            )
        assertTrue(firstCache != secondCache)
        assertFalse(secondCache.exists())
    }

    @Test
    fun `resolver process failures and timeouts are surfaced and temporary directories are cleaned`() {
        val recorded = directory.resolve("workdir")
        val home = fakeNode("/usr/bin/printf '%s' \"${'$'}PWD\" > '$recorded'\necho synthetic-error\nexit 1")
        val failure = assertThrows(IllegalStateException::class.java) { NodeTargetResolver(home).resolve("example", "1.0.0") }
        assertTrue(failure.message!!.contains("synthetic-error"))
        assertFalse(Path.of(recorded.readText()).exists())
        fakeNode("/usr/bin/printf '%s' \"${'$'}PWD\" > '$recorded'\nexec /bin/sleep 10")
        val timeout =
            assertThrows(IllegalStateException::class.java) { NodeTargetResolver(home, timeoutSeconds = 1).resolve("example", "1.0.0") }
        assertTrue(timeout.message!!.contains("timed out"))
        assertFalse(Path.of(recorded.readText()).exists())
        assertThrows(IllegalArgumentException::class.java) { NodeTargetResolver(home).resolve("example", "^1.0.0") }
    }

    @Test
    fun `real npm resolves pinned public targets without installing node modules`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run the public registry integration test")
        val packages = NodeTargetResolver(checkNotNull(home)).resolve("is-odd", "3.0.1")
        assertTrue(packages.any { it.name == "is-odd" && it.version == "3.0.1" })
        assertTrue(packages.any { it.name == "is-number" && it.version == "6.0.0" })
        val overridden = NodeTargetResolver(checkNotNull(home)).resolveWithOverrides("is-odd", "3.0.1", mapOf("is-number" to "7.0.0"))
        assertTrue(overridden.any { it.name == "is-number" && it.version == "7.0.0" })
        assertFalse(overridden.any { it.name == "is-number" && it.version == "6.0.0" })
        val conflict =
            assertThrows(IllegalStateException::class.java) {
                NodeTargetResolver(home).resolveWithOverrides("is-odd", "3.0.1", mapOf("is-odd" to "3.0.0"))
            }
        assertTrue(conflict.message!!.contains("EOVERRIDE"))
    }

    @Test
    fun `real npm security scan completes override coverage with a private session cache`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run the public registry integration test")
        val osv =
            object : VulnerabilityService {
                override fun find(dependencies: List<ResolvedDependency>): Map<String, List<Vulnerability>> =
                    dependencies.associate {
                        "${it.key}:${it.version}" to
                            if (it.name == "is-number" && it.version == "6.0.0") {
                                listOf(Vulnerability("TEST", "Synthetic", emptyList(), emptyList(), emptyList()))
                            } else {
                                emptyList()
                            }
                    }
            }
        val progress = mutableListOf<NodeScanProgress>()
        val result =
            NodeSecurityService(NodeTargetResolver(checkNotNull(home)), osv).scan(
                TargetVersionsState(
                    mutableMapOf(),
                    mutableMapOf(),
                    "9.0",
                    nodeDependencies = mutableMapOf("is-odd" to "3.0.1", "is-number" to "7.0.0"),
                    nodeTransientDependencies = setOf("is-number"),
                ),
                progress::add,
            )
        assertEquals(NodeSecurityStatus.OK_OVERRIDDEN, result.targets.single { it.key == "is-odd" }.status)
        assertEquals(NodeSecurityStatus.OK_TRANSIENT, result.targets.single { it.key == "is-number" }.status)
        assertEquals(NodeScanProgress(NodeScanPhase.COVERAGE, 3, 3), progress.last())
    }

    @Test
    fun `real combined npm resolution does not hide a vulnerable nested version behind a safe direct version`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run the public registry integration test")
        val result =
            NodeTargetResolver(checkNotNull(home)).resolveCombined(
                mapOf("is-odd" to "3.0.1", "is-number" to "7.0.0"),
                emptyMap(),
            )
        assertTrue(result.targets.getValue("is-odd").any { it.name == "is-number" && it.version == "6.0.0" })
        assertEquals(listOf("7.0.0"), result.targets.getValue("is-number").map { it.version })
        assertEquals(
            setOf("6.0.0", "7.0.0"),
            result.packages
                .filter { it.name == "is-number" }
                .map { it.version }
                .toSet(),
        )
    }

    private fun fakeNode(body: String): Path {
        val home = directory.resolve("node")
        home.resolve("bin").createDirectories()
        home.resolve("bin/node").writeText("#!/bin/sh\n$body\n")
        check(home.resolve("bin/node").toFile().setExecutable(true))
        return home
    }
}
