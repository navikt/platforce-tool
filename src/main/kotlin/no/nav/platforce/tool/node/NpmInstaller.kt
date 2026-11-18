package no.nav.platforce.tool.node

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class NpmInstaller(
    private val nodeHome: Path,
    private val timeoutSeconds: Long,
) {
    fun install(
        directory: Path,
        cacheDirectory: Path = directory.resolve("cache"),
        lockfileVersion: Int? = null,
        updateLockfile: Boolean = false,
    ) {
        val output = directory.resolve("npm-output.txt")
        val command =
            listOf(
                nodeHome.resolve("bin/node").toString(),
                nodeHome.resolve("lib/node_modules/npm/bin/npm-cli.js").toString(),
                if (updateLockfile) "update" else "install",
                "--package-lock-only",
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                "--registry=https://registry.npmjs.org",
                "--fetch-retries=0",
                "--fetch-timeout=30000",
                "--cache=$cacheDirectory",
                "--prefer-offline",
                "--userconfig=${directory.resolve("user.npmrc")}",
                "--globalconfig=${directory.resolve("global.npmrc")}",
                "--git=${directory.resolve("git-disabled")}",
            ) + listOfNotNull(lockfileVersion?.let { "--lockfile-version=$it" })
        val builder =
            ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
        builder.environment().clear()
        builder.environment().putAll(
            mapOf(
                "PATH" to "${nodeHome.resolve("bin")}:/usr/bin:/bin",
                "HOME" to directory.toString(),
                "TMPDIR" to directory.toString(),
            ),
        )
        val process = builder.start()
        try {
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "npm resolution timed out after ${timeoutSeconds}s" }
            check(process.exitValue() == 0) {
                val details =
                    Files.newInputStream(output).use {
                        it.skipNBytes((Files.size(output) - 4000).coerceAtLeast(0))
                        String(it.readNBytes(4000), Charsets.UTF_8)
                    }
                "npm resolution failed: $details"
            }
        } finally {
            process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
