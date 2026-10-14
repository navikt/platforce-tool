package no.nav.platforce.tool.github

import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey

fun parsePrivateKey(pem: String): RSAPrivateKey {
    val reader = PEMParser(StringReader(pem))
    val converter = JcaPEMKeyConverter()

    val keyPair =
        converter.getKeyPair(
            reader.readObject() as PEMKeyPair,
        )

    return keyPair.private as RSAPrivateKey
}
