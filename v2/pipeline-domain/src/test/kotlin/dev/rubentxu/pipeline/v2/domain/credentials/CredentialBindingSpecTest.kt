package dev.rubentxu.pipeline.v2.domain.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * LF-0401 unit tests for the [CredentialBindingSpec] sealed hierarchy.
 *
 * These are domain-only tests; no DSL imports, no executor wiring.
 *
 * Covers:
 *  - Each subtype instantiates with the correct `kind` constant.
 *  - Factory methods in the companion object produce the same instance as the
 *    primary constructor.
 *  - The sealed `when` is exhaustively covered (compile-time guarantee plus a
 *    runtime exhaustiveness witness — counting `sealedSubclasses`).
 */
@DisplayName("CredentialBindingSpec sealed hierarchy")
class CredentialBindingSpecTest {

    @Test
    fun `StringBindingSpec exposes kind string and carries variable`() {
        val spec = StringBindingSpec(CredentialsId("k"), "VAR")
        assertEquals("string", spec.kind)
        assertEquals(CredentialsId("k"), spec.credentialsId)
        assertEquals("VAR", spec.variable)
    }

    @Test
    fun `UsernamePasswordBindingSpec carries usernameVariable and passwordVariable`() {
        val spec = UsernamePasswordBindingSpec(CredentialsId("k"), "U", "P")
        assertEquals("usernamePassword", spec.kind)
        assertEquals("U", spec.usernameVariable)
        assertEquals("P", spec.passwordVariable)
    }

    @Test
    fun `SshUserPrivateKeyBindingSpec defaults passphraseVariable and usernameVariable to null`() {
        val spec = SshUserPrivateKeyBindingSpec(CredentialsId("k"), "KEY_FILE")
        assertEquals("sshUserPrivateKey", spec.kind)
        assertEquals("KEY_FILE", spec.keyFileVariable)
        assertEquals(null, spec.passphraseVariable)
        assertEquals(null, spec.usernameVariable)
    }

    @Test
    fun `FileBindingSpec exposes file kind and variable`() {
        val spec = FileBindingSpec(CredentialsId("k"), "FILE_VAR")
        assertEquals("file", spec.kind)
        assertEquals("FILE_VAR", spec.variable)
    }

    @Test
    fun `CertificateBindingSpec carries keystoreVariable first and optional alias password`() {
        val minimal = CertificateBindingSpec("KEYSTORE", CredentialsId("k"))
        assertEquals("certificate", minimal.kind)
        assertEquals("KEYSTORE", minimal.keystoreVariable)
        assertEquals(null, minimal.aliasVariable)
        assertEquals(null, minimal.passwordVariable)

        val full = CertificateBindingSpec("KEYSTORE", CredentialsId("k"), "alias-1", "pass-var")
        assertEquals("alias-1", full.aliasVariable)
        assertEquals("pass-var", full.passwordVariable)
    }

    @Test
    fun `ZipBindingSpec carries variable first then credentialsId`() {
        val spec = ZipBindingSpec("ZIP_VAR", CredentialsId("k"))
        assertEquals("zip", spec.kind)
        assertEquals("ZIP_VAR", spec.variable)
        assertEquals(CredentialsId("k"), spec.credentialsId)
    }

    @Test
    fun `UsernameColonPasswordBindingSpec carries variable first then credentialsId`() {
        val spec = UsernameColonPasswordBindingSpec("UP_VAR", CredentialsId("k"))
        assertEquals("usernameColonPassword", spec.kind)
        assertEquals("UP_VAR", spec.variable)
    }

    // ───── Factory parity ───────────────────────────────────────────────────

    @Test
    fun `factory string produces same instance as primary constructor`() {
        val fromFactory = CredentialBindingSpecFactory.string(CredentialsId("k"), "VAR")
        val fromCtor = StringBindingSpec(CredentialsId("k"), "VAR")
        assertEquals(fromCtor, fromFactory)
        assertEquals(fromCtor.kind, fromFactory.kind)
    }

    @Test
    fun `factory usernamePassword produces same instance as primary constructor`() {
        val fromFactory = CredentialBindingSpecFactory.usernamePassword(CredentialsId("k"), "U", "P")
        val fromCtor = UsernamePasswordBindingSpec(CredentialsId("k"), "U", "P")
        assertEquals(fromCtor, fromFactory)
    }

    @Test
    fun `factory sshUserPrivateKey preserves Jenkins verbatim field order`() {
        val fromFactory = CredentialBindingSpecFactory.sshUserPrivateKey(
            CredentialsId("k"),
            "KEY_FILE",
            "PASSPHRASE",
            "USER",
        )
        val fromCtor = SshUserPrivateKeyBindingSpec(CredentialsId("k"), "KEY_FILE", "PASSPHRASE", "USER")
        assertEquals(fromCtor, fromFactory)
    }

    @Test
    fun `factory file and factory zip round-trip through primary constructor`() {
        assertEquals(
            FileBindingSpec(CredentialsId("k"), "F"),
            CredentialBindingSpecFactory.file(CredentialsId("k"), "F"),
        )
        assertEquals(
            ZipBindingSpec("Z", CredentialsId("k")),
            CredentialBindingSpecFactory.zip("Z", CredentialsId("k")),
        )
    }

    @Test
    fun `factory certificate preserves Jenkins verbatim keystoreVariable first ordering`() {
        val fromFactory = CredentialBindingSpecFactory.certificate(
            keystoreVariable = "KS",
            credentialsId = CredentialsId("k"),
            aliasVariable = "alias",
            passwordVariable = "pw",
        )
        val fromCtor = CertificateBindingSpec("KS", CredentialsId("k"), "alias", "pw")
        assertEquals(fromCtor, fromFactory)
    }

    @Test
    fun `factory usernameColonPassword round-trips through primary constructor`() {
        assertEquals(
            UsernameColonPasswordBindingSpec("UP", CredentialsId("k")),
            CredentialBindingSpecFactory.usernameColonPassword("UP", CredentialsId("k")),
        )
    }

    // ───── Exhaustiveness ───────────────────────────────────────────────────

    @Test
    fun `sealed sub-types are exactly the seven binding kinds`() {
        val subclasses = CredentialBindingSpec::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "StringBindingSpec",
                "UsernamePasswordBindingSpec",
                "SshUserPrivateKeyBindingSpec",
                "FileBindingSpec",
                "CertificateBindingSpec",
                "ZipBindingSpec",
                "UsernameColonPasswordBindingSpec",
            ),
            subclasses,
            "CredentialBindingSpec must have exactly the 7 LF-0401 sealed subtypes",
        )
    }

    @Test
    fun `exhaustive when over CredentialBindingSpec returns every kind label`() {
        val id = CredentialsId("k")
        val samples: List<CredentialBindingSpec> = listOf(
            StringBindingSpec(id, "v"),
            UsernamePasswordBindingSpec(id, "u", "p"),
            SshUserPrivateKeyBindingSpec(id, "k"),
            FileBindingSpec(id, "v"),
            CertificateBindingSpec("k", id),
            ZipBindingSpec("v", id),
            UsernameColonPasswordBindingSpec("v", id),
        )

        val kinds = samples.map { spec ->
            when (spec) {
                is StringBindingSpec -> spec.kind
                is UsernamePasswordBindingSpec -> spec.kind
                is SshUserPrivateKeyBindingSpec -> spec.kind
                is FileBindingSpec -> spec.kind
                is CertificateBindingSpec -> spec.kind
                is ZipBindingSpec -> spec.kind
                is UsernameColonPasswordBindingSpec -> spec.kind
            }
        }

        assertEquals(
            setOf(
                "string",
                "usernamePassword",
                "sshUserPrivateKey",
                "file",
                "certificate",
                "zip",
                "usernameColonPassword",
            ),
            kinds.toSet(),
        )
    }
}
