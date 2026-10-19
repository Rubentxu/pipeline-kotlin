package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * [Zip] carries a `Map<String, ByteArray>`, so its equality has to descend into the
 * arrays by content. The other six credentials in `Credential.kt` do exactly that
 * with `contentEquals`/`contentHashCode`; `Zip` overrides both by hand, and a
 * reference comparison there would make two byte-identical archives unequal — the
 * kind of defect that only shows up as a cache miss or a `distinct()` that keeps
 * duplicates, never as a crash.
 */
class ZipTest {

    private val id = CredentialsId.from("aws-prod")

    @Test
    fun `two archives with identical entries are equal`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "same-bytes".toByteArray()))
        val b = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "same-bytes".toByteArray()))

        assertEquals(a, b, "byte-identical archives are equal")
    }

    @Test
    fun `two archives with identical entries hash equally`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "same-bytes".toByteArray()))
        val b = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "same-bytes".toByteArray()))

        assertEquals(
            a.hashCode(),
            b.hashCode(),
            "the hash follows the content-based equality, so identical archives land in the same bucket",
        )
    }

    @Test
    fun `one differing byte in one entry makes the archives unequal`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "AAAA".toByteArray()))
        val b = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "AAAB".toByteArray()))

        assertNotEquals(a, b, "one byte of one entry is enough")
    }

    @Test
    fun `a differing entry NAME makes the archives unequal`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "x".toByteArray()))
        val b = Zip(id, CredentialScope.GLOBAL, linkedMapOf("key.pem" to "x".toByteArray()))

        assertNotEquals(a, b, "the entry name is part of the identity")
    }

    @Test
    fun `an extra entry makes the archives unequal`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "x".toByteArray()))
        val b = Zip(
            id,
            CredentialScope.GLOBAL,
            linkedMapOf("cert.pem" to "x".toByteArray(), "extra.pem" to ByteArray(0)),
        )

        assertNotEquals(a, b, "a different key set is a different archive")
    }

    @Test
    fun `a different id makes the archives unequal`() {
        val a = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "x".toByteArray()))
        val b = Zip(CredentialsId.from("other"), CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "x".toByteArray()))

        assertNotEquals(a, b, "the credential id is part of the identity")
    }

    @Test
    fun `an empty archive equals another empty archive`() {
        val a = Zip(id, CredentialScope.GLOBAL, emptyMap())
        val b = Zip(id, CredentialScope.GLOBAL, emptyMap())

        assertEquals(a, b, "two empty archives are the same archive")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString never reveals the entry bytes`() {
        val zip = Zip(id, CredentialScope.GLOBAL, linkedMapOf("cert.pem" to "TOP-SECRET-MATERIAL".toByteArray()))

        val rendered = zip.toString()

        assertEquals(
            "[SECRET Zip(id=aws-prod, scope=GLOBAL, entryCount=1)]",
            rendered,
            "the safe representation reports the count, never the bytes",
        )
    }
}