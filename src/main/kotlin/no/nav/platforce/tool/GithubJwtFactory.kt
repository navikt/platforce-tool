package no.nav.platforce.tool

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

class GithubJwtFactory(
    private val appId: String = env(secret_PLATFORCE_TOOLING_APP_ID),
) {
    private val privateKey: RSAPrivateKey = parsePrivateKey(env(secret_PLATFORCE_TOOLING_PRIVATE_KEY))

    fun parsePrivateKey(pem: String): RSAPrivateKey {
        val reader = PEMParser(StringReader(pem))
        val converter = JcaPEMKeyConverter()

        val keyPair =
            converter.getKeyPair(
                reader.readObject() as PEMKeyPair,
            )

        return keyPair.private as RSAPrivateKey
    }

    fun createJwt(): String {
        val now = Instant.now()

        return JWT
            .create()
            .withIssuedAt(Date.from(now.minusSeconds(60)))
            .withExpiresAt(Date.from(now.plusSeconds(600)))
            .withIssuer(appId)
            .sign(Algorithm.RSA256(null, privateKey))
    }
}
