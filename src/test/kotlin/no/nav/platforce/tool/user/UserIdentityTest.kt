package no.nav.platforce.tool.user

import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

class UserIdentityTest {
    @Test
    fun `preferred username identifies the user independently of token renewal`() {
        // Extract preferred_username from two synthetic tokens with different subjects.
        val first = bearer("""{"preferred_username":"alice@example.test","sub":"old-subject"}""")
        val renewed = bearer("""{"preferred_username":"alice@example.test","sub":"new-subject"}""")

        assertEquals("alice@example.test", extractPreferredUsername(first))
        assertEquals(extractPreferredUsername(first), extractPreferredUsername(renewed))
        // Both tokens yield the same user key. This tests extraction, not JWT validation.
    }

    @Test
    fun `different preferred usernames produce different user keys`() {
        // Extract the identities for two users, without creating database-backed contexts.
        assertEquals(
            "alice@example.test",
            extractPreferredUsername(bearer("""{"preferred_username":"alice@example.test"}""")),
        )
        assertEquals(
            "bob@example.test",
            extractPreferredUsername(bearer("""{"preferred_username":"bob@example.test"}""")),
        )
    }

    @Test
    fun `GUI request without authorization cannot obtain a user context`() {
        // Call userContext on a GUI API request with no Authorization header.
        val error =
            assertThrows(RuntimeException::class.java) {
                Request(Method.GET, "/internal/api/target-versions").userContext()
            }

        assertEquals("Missing Authorization", error.message)
        // The request fails before database access instead of selecting a default user.
    }

    @Test
    fun `missing preferred username is rejected instead of using another claim`() {
        // Supply a token containing sub but no preferred_username.
        val error =
            assertThrows(RuntimeException::class.java) {
                extractPreferredUsername(bearer("""{"sub":"alice"}"""))
            }

        assertEquals("preferred_username not found in token", error.message)
    }

    @Test
    fun `missing bearer prefix and invalid token structure are rejected`() {
        // Reject absent authentication, a non-Bearer header and an incomplete JWT.
        listOf(null, "Basic synthetic", "Bearer incomplete").forEach { header ->
            assertThrows(RuntimeException::class.java) {
                extractPreferredUsername(header)
            }
        }
    }

    private fun bearer(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(UTF_8))
        val body = encoder.encodeToString(payload.toByteArray(UTF_8))
        return "Bearer $header.$body.synthetic"
    }
}
