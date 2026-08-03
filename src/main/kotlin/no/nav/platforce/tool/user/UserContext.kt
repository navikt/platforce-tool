package no.nav.platforce.tool.user

import com.google.gson.JsonParser
import no.nav.platforce.tool.dependencies.TargetVersionsStore
import no.nav.platforce.tool.ignore.IgnoredRepositoriesStore
import no.nav.platforce.tool.notes.RepositoryNotesStore
import org.http4k.core.Request
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class UserContext(
    val username: String,
    val targetVersionsStore: TargetVersionsStore =
        TargetVersionsStore(userId = username),
    val repositoryNotesStore: RepositoryNotesStore =
        RepositoryNotesStore(userId = username),
    val ignoredRepositoriesStore: IgnoredRepositoriesStore =
        IgnoredRepositoriesStore(userId = username),
    var selectedTeam: String? = null,
)

object UserContextCache {
    private val userContexts = ConcurrentHashMap<String, UserContext>()

    fun get(username: String): UserContext =
        userContexts.computeIfAbsent(username) {
            UserContext(username)
        }
}

fun Request.userContext(): UserContext {
    val auth =
        header("Authorization")
            ?: throw RuntimeException("Missing Authorization")

    return UserContextCache.get(
        extractPreferredUsername(auth),
    )
}

fun extractPreferredUsername(authHeader: String?): String {
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        throw RuntimeException("Missing Bearer token")
    }

    val token = authHeader.removePrefix("Bearer ").trim()

    val parts = token.split(".")

    if (parts.size < 2) {
        throw RuntimeException("Invalid JWT token")
    }

    val payloadJson =
        String(
            Base64.getUrlDecoder().decode(parts[1]),
        )

    val payload = JsonParser.parseString(payloadJson).asJsonObject

    return payload["preferred_username"]?.asString
        ?: throw RuntimeException("preferred_username not found in token")
}
