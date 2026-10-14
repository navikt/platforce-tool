package no.nav.platforce.tool.dependencies

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ResolutionDebugFiles(
    private val baseDir: Path = Path.of("/tmp/files"),
) {
    fun start(username: String): Path {
        val userDir = userDirectory(username)
        Files.createDirectories(userDir)
        val id = UUID.randomUUID().toString()
        val directory = Files.createDirectory(userDir.resolve(id))
        val pointer = Files.createTempFile(userDir, "latest-", ".tmp")
        try {
            pointer.writeText(id)
            Files.move(pointer, userDir.resolve("latest"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pointer)
        }
        return directory
    }

    fun latestDirectory(username: String): Path {
        val userDir = userDirectory(username)
        val pointer = userDir.resolve("latest")
        if (!pointer.exists()) return userDir.resolve("no-resolution-yet")
        val id = pointer.readText()
        require(UUID.fromString(id).toString() == id) { "Invalid resolution debug directory" }
        return userDir.resolve(id)
    }

    private fun userDirectory(username: String): Path {
        val key =
            MessageDigest
                .getInstance("SHA-256")
                .digest(username.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return baseDir.resolve(key)
    }
}
