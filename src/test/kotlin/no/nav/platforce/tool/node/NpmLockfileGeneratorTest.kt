package no.nav.platforce.tool.node

import com.google.gson.JsonParser
import no.nav.platforce.tool.pullrequests.NodePackageJsonUpdater
import no.nav.platforce.tool.repositories.DependencyFinding
import no.nav.platforce.tool.repositories.DependencyKind
import no.nav.platforce.tool.repositories.DependencyStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NpmLockfileGeneratorTest {
    @TempDir
    lateinit var directory: Path

    private val manifest = """{"name":"example","version":"1.0.0","dependencies":{"is-number":"^7.0.0"}}"""
    private val exact = manifest.replace("^7.0.0", "7.0.0")

    private fun lock(
        version: Int,
        dependencyVersion: String,
        section: String = "dependencies",
    ): String =
        if (version == 1) {
            """{"lockfileVersion":1,"dependencies":{"is-number":{"version":"$dependencyVersion"}}}"""
        } else {
            """{"lockfileVersion":$version,"packages":{"":{"$section":{"is-number":"$dependencyVersion"}},"node_modules/is-number":{"version":"$dependencyVersion"}}}"""
        }

    private fun fakeNode(body: String): Path {
        val home = directory.resolve("node")
        home.resolve("bin").createDirectories()
        home.resolve("bin/node").writeText("#!/bin/sh\n$body\n")
        check(home.resolve("bin/node").toFile().setExecutable(true))
        return home
    }

    @Test
    fun `existing npm lock formats and filenames are preserved while root ranges are restored`() {
        for (version in 1..3) {
            for (filename in listOf("package-lock.json", "npm-shrinkwrap.json")) {
                for (section in listOf("dependencies", "devDependencies")) {
                    val home = fakeNode("/bin/cat > $filename <<'LOCK'\n${lock(version, "7.0.0", section)}\nLOCK")
                    val result =
                        NpmLockfileGenerator(home).generate(
                            manifest.replace("\"dependencies\"", "\"$section\""),
                            exact.replace("\"dependencies\"", "\"$section\""),
                            lock(version, "6.0.0", section),
                            filename,
                        )
                    val json = JsonParser.parseString(result).asJsonObject
                    assertEquals(version, json["lockfileVersion"].asInt)
                    if (version > 1) {
                        val packages = json.getAsJsonObject("packages")
                        assertEquals("^7.0.0", packages.getAsJsonObject("").getAsJsonObject(section)["is-number"].asString)
                        assertEquals("7.0.0", packages.getAsJsonObject("node_modules/is-number")["version"].asString)
                    }
                }
            }
        }
    }

    @Test
    fun `lock generation isolates npm configuration disables scripts and cleans its working directory`() {
        val args = directory.resolve("arguments.txt")
        val project = directory.resolve("project.txt")
        val home =
            fakeNode(
                """
                printf '%s\n' "${'$'}@" > '$args'
                /bin/pwd > '$project'
                test "${'$'}HOME" = "${'$'}TMPDIR" || exit 1
                test -z "${'$'}NODE_OPTIONS" || exit 1
                /bin/cat > package-lock.json <<'LOCK'
                ${lock(3, "7.0.0")}
                LOCK
                """.trimIndent(),
            )
        NpmLockfileGenerator(home).generate(manifest, exact, lock(3, "6.0.0"), "package-lock.json")
        val arguments = args.readText().lines()
        assertTrue(
            arguments.containsAll(listOf("--ignore-scripts", "--package-lock-only", "--no-audit", "--no-fund", "--lockfile-version=3")),
        )
        assertTrue(arguments.contains("--registry=https://registry.npmjs.org"))
        assertTrue(arguments.any { it.startsWith("--userconfig=") })
        assertTrue(arguments.any { it.startsWith("--globalconfig=") })
        assertFalse(Path.of(project.readText().trim()).exists())
    }

    @Test
    fun `unsafe sources linked packages workspaces and foreign locks fail before launching npm`() {
        val generator = NpmLockfileGenerator(directory.resolve("missing-node"))
        val unsafeSpecifications =
            listOf("file:/tmp/private", "../private", " . ", "https://example.test/package.tgz", "git+ssh://example.test/repo")
        unsafeSpecifications.forEach { specification ->
            val unsafe = manifest.replace("^7.0.0", specification)
            assertThrows(IllegalArgumentException::class.java) {
                generator.generate(unsafe, unsafe, lock(3, "6.0.0"), "package-lock.json")
            }
        }
        val workspace = manifest.dropLast(1) + ""","workspaces":["packages/*"]}"""
        assertThrows(
            IllegalArgumentException::class.java,
        ) { generator.generate(workspace, workspace, lock(3, "6.0.0"), "package-lock.json") }
        val foreign =
            """{"lockfileVersion":3,"packages":{"node_modules/is-number":{"version":"6.0.0","resolved":"https://example.test/package.tgz"}}}"""
        assertThrows(IllegalArgumentException::class.java) { generator.generate(manifest, exact, foreign, "package-lock.json") }
        val linked = """{"lockfileVersion":3,"packages":{"node_modules/is-number":{"link":true,"resolved":"../private"}}}"""
        assertThrows(IllegalArgumentException::class.java) { generator.generate(manifest, exact, linked, "package-lock.json") }
        val traversal = """{"lockfileVersion":3,"packages":{"../../private":{"version":"1.0.0"}}}"""
        assertThrows(IllegalArgumentException::class.java) { generator.generate(manifest, exact, traversal, "package-lock.json") }
    }

    @Test
    fun `npm failure and stale resolved versions fail explicitly and clean up`() {
        val project = directory.resolve("failed-project.txt")
        val home = fakeNode("/bin/pwd > '$project'\necho synthetic-EOVERRIDE\nexit 1")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                NpmLockfileGenerator(home).generate(manifest, exact, lock(3, "6.0.0"), "package-lock.json")
            }
        assertTrue(failure.message!!.contains("synthetic-EOVERRIDE"))
        assertFalse(Path.of(project.readText().trim()).exists())
        fakeNode("/bin/cat > package-lock.json <<'LOCK'\n${lock(3, "6.0.0")}\nLOCK")
        assertThrows(IllegalStateException::class.java) {
            NpmLockfileGenerator(home).generate(manifest, exact, lock(3, "6.0.0"), "package-lock.json")
        }
    }

    @Test
    fun `stale development resolutions fail for every supported npm lockfile version`() {
        for (version in 1..3) {
            val home = fakeNode("/bin/cat > package-lock.json <<'LOCK'\n${lock(version, "6.0.0", "devDependencies")}\nLOCK")
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    NpmLockfileGenerator(home).generate(
                        manifest.replace("\"dependencies\"", "\"devDependencies\""),
                        exact.replace("\"dependencies\"", "\"devDependencies\""),
                        lock(version, "6.0.0", "devDependencies"),
                        "package-lock.json",
                    )
                }
            assertTrue(failure.message!!.contains("npm did not lock is-number at the scanned target 7.0.0"))
        }
    }

    @Test
    fun `real npm retains scanned production and dev versions with caret manifests and an npm ci lock without running scripts`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run npm integration tests")
        val node = checkNotNull(home)
        val project = directory.resolve("project").createDirectories()
        val sentinel = directory.resolve("scripts-ran")
        val source =
            """
            {"name":"example","version":"1.0.0",
             "dependencies":{"is-odd":"3.0.0"},
             "devDependencies":{"is-number":"6.0.0","semver":"7.3.8"},
             "overrides":{"is-number":"${'$'}is-number","ansi-regex":"5.0.1"},
             "scripts":{"install":"echo unsafe > $sentinel","prepare":"echo unsafe > $sentinel"}}
            """.trimIndent()
        project.resolve("package.json").writeText(source)
        NpmInstaller(node, 120).install(project, lockfileVersion = 3)
        val oldLock = project.resolve("package-lock.json").readText()
        val findings =
            PackageJsonParser().parse(source).map {
                val target =
                    when (it.key) {
                        "is-odd" -> "3.0.1"
                        "is-number" -> "7.0.0"
                        "semver" -> "7.5.0"
                        else -> "5.0.1"
                    }
                DependencyFinding(
                    DependencyKind.NODE,
                    it.key,
                    it.version,
                    target,
                    if (it.key == "ansi-regex") DependencyStatus.REMOVE else DependencyStatus.UPDATE,
                    declaration = it.declaration,
                )
            }
        val update = NodePackageJsonUpdater().apply(source, findings)
        val generated = NpmLockfileGenerator(node).generate(update.manifest, update.resolutionManifest, oldLock, "package-lock.json")
        val packages = JsonParser.parseString(generated).asJsonObject.getAsJsonObject("packages")
        assertEquals("7.5.0", packages.getAsJsonObject("node_modules/semver")["version"].asString)
        assertTrue(packages.getAsJsonObject("node_modules/semver")["dev"].asBoolean)
        assertEquals("7.0.0", packages.getAsJsonObject("node_modules/is-number")["version"].asString)
        val root = packages.getAsJsonObject("")
        assertEquals(setOf("is-odd"), root.getAsJsonObject("dependencies").keySet())
        assertEquals(setOf("is-number", "semver"), root.getAsJsonObject("devDependencies").keySet())
        assertEquals("^7.5.0", root.getAsJsonObject("devDependencies")["semver"].asString)
        assertEquals("^7.0.0", root.getAsJsonObject("devDependencies")["is-number"].asString)
        assertFalse(
            packages.entrySet().any {
                it.key.endsWith("node_modules/is-number") &&
                    it.value.asJsonObject["version"].asString == "6.0.0"
            },
        )
        project.resolve("package.json").writeText(update.manifest)
        project.resolve("package-lock.json").writeText(generated)
        val output = directory.resolve("ci-output.txt")
        val builder =
            ProcessBuilder(
                node.resolve("bin/node").toString(),
                node.resolve("lib/node_modules/npm/bin/npm-cli.js").toString(),
                "ci",
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                "--registry=https://registry.npmjs.org",
                "--cache=${project.resolve("cache")}",
                "--userconfig=${project.resolve("user.npmrc")}",
                "--globalconfig=${project.resolve("global.npmrc")}",
            ).directory(project.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().clear()
        builder.environment().putAll(mapOf("PATH" to "${node.resolve("bin")}:/usr/bin:/bin", "HOME" to project.toString()))
        val process = builder.start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "npm ci timed out")
            assertEquals(0, process.exitValue(), output.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        assertEquals(
            "7.5.0",
            JsonParser.parseString(project.resolve("node_modules/semver/package.json").readText()).asJsonObject["version"].asString,
        )
        assertFalse(sentinel.exists())
    }

    @Test
    fun `real npm refreshes an existing transitive lock when a new global override is added`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run npm integration tests")
        val node = checkNotNull(home)
        val project = directory.resolve("override-project").createDirectories()
        val source = """{"name":"example","version":"1.0.0","dependencies":{"is-odd":"3.0.1"}}"""
        project.resolve("package.json").writeText(source)
        NpmInstaller(node, 120).install(project, lockfileVersion = 3)
        val update =
            NodePackageJsonUpdater().apply(
                source,
                listOf(
                    DependencyFinding(
                        DependencyKind.NODE,
                        "is-number",
                        null,
                        "7.0.0",
                        DependencyStatus.ADD,
                        declaration = "overrides > is-number",
                    ),
                ),
            )
        val generated =
            NpmLockfileGenerator(
                node,
            ).generate(update.manifest, update.resolutionManifest, project.resolve("package-lock.json").readText(), "package-lock.json")
        val packages = JsonParser.parseString(generated).asJsonObject.getAsJsonObject("packages")
        assertTrue(
            packages.entrySet().any {
                it.key.endsWith("node_modules/is-number") &&
                    it.value.asJsonObject["version"].asString == "7.0.0"
            },
        )
        assertFalse(Files.exists(project.resolve("node_modules")))
    }

    @Test
    fun `real npm refreshes existing scoped overrides without flattening their declaration`() {
        val home = System.getenv("PLATFORCE_TEST_NODE_HOME")?.let(Path::of)
        assumeTrue(home != null, "Set PLATFORCE_TEST_NODE_HOME to run npm integration tests")
        val node = checkNotNull(home)
        val project = directory.resolve("scoped-project").createDirectories()
        val source =
            """{"name":"example","version":"1.0.0","dependencies":{"is-odd":"3.0.1"},"overrides":{"is-odd":{"is-number":"6.0.0"}}}"""
        project.resolve("package.json").writeText(source)
        NpmInstaller(node, 120).install(project, lockfileVersion = 3)
        val update =
            NodePackageJsonUpdater().apply(
                source,
                listOf(
                    DependencyFinding(
                        DependencyKind.NODE,
                        "is-number",
                        "6.0.0",
                        "7.0.0",
                        DependencyStatus.UPDATE,
                        declaration = "overrides > is-odd > is-number",
                    ),
                ),
            )
        val generated =
            NpmLockfileGenerator(node).generate(
                update.manifest,
                update.resolutionManifest,
                project.resolve("package-lock.json").readText(),
                "package-lock.json",
            )
        val packages = JsonParser.parseString(generated).asJsonObject.getAsJsonObject("packages")
        assertTrue(
            packages.entrySet().any {
                it.key.endsWith("node_modules/is-number") &&
                    it.value.asJsonObject["version"].asString == "7.0.0"
            },
        )
        assertEquals(
            "^7.0.0",
            JsonParser
                .parseString(
                    update.manifest,
                ).asJsonObject
                .getAsJsonObject("overrides")
                .getAsJsonObject("is-odd")["is-number"]
                .asString,
        )
    }
}
