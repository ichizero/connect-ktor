package io.github.ichizero.connect.ktor.conformance

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Builds a [KeyStore] suitable for `sslConnector` from the PEM-encoded
 * certificate chain + PKCS#8 private key supplied by the conformance
 * runner via `ServerCompatRequest.server_creds`.
 */
internal fun keyStoreFromPem(certPem: ByteArray, keyPem: ByteArray): KeyStore {
    val chain = parseCertificateChain(certPem)
    require(chain.isNotEmpty()) { "no certificates found in PEM" }
    val privateKey = parsePkcs8PrivateKey(keyPem)

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    keyStore.setKeyEntry(SERVER_KEY_ALIAS, privateKey, EMPTY_PASSWORD, chain.toTypedArray())
    return keyStore
}

/**
 * Builds a trust store containing the supplied PEM-encoded client
 * certificate. Used when the conformance runner sets
 * `ServerCompatRequest.client_tls_cert` to exercise mTLS.
 */
internal fun trustStoreFromPem(certPem: ByteArray): KeyStore {
    val chain = parseCertificateChain(certPem)
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    chain.forEachIndexed { idx, cert ->
        trustStore.setCertificateEntry("client-$idx", cert)
    }
    return trustStore
}

internal const val SERVER_KEY_ALIAS: String = "conformance"
internal val EMPTY_PASSWORD: CharArray = CharArray(0)

private val PEM_BLOCK = Regex(
    "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)-----END \\1-----",
    RegexOption.DOT_MATCHES_ALL,
)

private fun parseCertificateChain(pem: ByteArray): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return ByteArrayInputStream(pem).use { stream ->
        @Suppress("UNCHECKED_CAST")
        factory.generateCertificates(stream).toList() as List<X509Certificate>
    }
}

private fun parsePkcs8PrivateKey(pem: ByteArray): PrivateKey {
    val text = pem.toString(Charsets.US_ASCII)
    val match = PEM_BLOCK.find(text)
        ?: error("private key PEM has no recognisable BEGIN/END markers")
    val type = match.groupValues[1].trim()
    val body = match.groupValues[2].replace(Regex("\\s"), "")
    val rawDer = Base64.getDecoder().decode(body)

    // Conformance runner emits PKCS#1 ("BEGIN RSA PRIVATE KEY"); newer
    // tooling may also emit SEC1 ("BEGIN EC PRIVATE KEY") or unencrypted
    // PKCS#8 ("BEGIN PRIVATE KEY"). Promote PKCS#1 / SEC1 to PKCS#8 so the
    // standard KeyFactory can consume it.
    val (der, algoCandidates) = when {
        type == "RSA PRIVATE KEY" -> wrapPkcs1RsaIntoPkcs8(rawDer) to listOf("RSA")

        type == "EC PRIVATE KEY" -> rawDer to listOf("EC")

        // SEC1; JDK accepts via PKCS8
        type.contains("RSA") -> rawDer to listOf("RSA")

        type.contains("EC") -> rawDer to listOf("EC")

        type.contains("DSA") -> rawDer to listOf("DSA")

        else -> rawDer to listOf("EC", "RSA", "DSA")
    }

    val keySpec = PKCS8EncodedKeySpec(der)
    var lastError: Throwable? = null
    for (algo in algoCandidates) {
        try {
            return KeyFactory.getInstance(algo).generatePrivate(keySpec)
        } catch (ex: Throwable) {
            lastError = ex
        }
    }
    throw IllegalArgumentException(
        "unable to parse private key (type=$type, tried ${algoCandidates.joinToString()})",
        lastError,
    )
}

/**
 * Wraps a PKCS#1 `RSAPrivateKey` DER blob in a PKCS#8 `PrivateKeyInfo`
 * structure so that [PKCS8EncodedKeySpec] can consume it. The outer ASN.1
 * is built by hand to avoid pulling in Bouncy Castle just for this.
 *
 * Layout (RFC 5208):
 *   SEQUENCE {
 *     INTEGER 0,
 *     SEQUENCE { OID 1.2.840.113549.1.1.1, NULL },
 *     OCTET STRING { <PKCS#1 bytes> }
 *   }
 */
private fun wrapPkcs1RsaIntoPkcs8(pkcs1: ByteArray): ByteArray {
    val algorithmId = byteArrayOf(
        0x30, 0x0D, // SEQUENCE (13 bytes)
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), // OID 1.2.840.113549.1.1.1
        0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
        0x05, 0x00, // NULL
    )
    val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
    val octetString = derTagLength(0x04, pkcs1.size) + pkcs1
    val inner = version + algorithmId + octetString
    return derTagLength(0x30, inner.size) + inner
}

private fun derTagLength(tag: Int, length: Int): ByteArray {
    val tagByte = tag.toByte()
    return when {
        length < 0x80 -> byteArrayOf(tagByte, length.toByte())

        length < 0x100 -> byteArrayOf(tagByte, 0x81.toByte(), length.toByte())

        length < 0x10000 -> byteArrayOf(
            tagByte,
            0x82.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )

        length < 0x1000000 -> byteArrayOf(
            tagByte,
            0x83.toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )

        else -> error("DER length too large: $length")
    }
}
