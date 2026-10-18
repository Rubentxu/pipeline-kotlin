package dev.rubentxu.pipeline.v2.binding

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `DefaultBindingFactory` dispatches a sealed [CredentialsBinding] to one of seven
 * resolvers. Six of the seven differ only in WHICH FIELD of the binding names the
 * environment variable that receives the handle, so the interesting contract is the
 * field selection, not the count of entries.
 *
 * The `kind` strings are declared twice — once as `override val kind` on each data
 * class in [CredentialsBinding], and once as the literals in the factory's
 * `SUPPORTED_KINDS`. Nothing links the two sets. A kind renamed on one side would
 * silently stop matching on the other, and a binding whose kind is not advertised
 * can never be routed. That correspondence is what this class freezes.
 */
class DefaultBindingFactoryTest {

    private val factory = DefaultBindingFactory()
    private val id = CredentialsId("cred-1")

    /** A resolver handing out a fresh handle each call: the username/password path
     *  consumes the handle it is given, so a shared one would arrive zeroed. */
    private fun resolving(value: ByteArray): (CredentialsId) -> SecretHandle =
        { SecretHandle.secret(value.copyOf()) }

    private fun resolve(
        binding: CredentialsBinding,
        bytes: ByteArray = "payload".toByteArray(),
    ) = factory.resolve(binding, resolving(bytes))

    @Test
    fun `every binding kind is advertised by the factory`() {
        val advertised = factory.supportedKinds()

        val bindings = listOf(
            StringBinding(id, "V"),
            UsernamePasswordBinding(id, "U", "P"),
            SshUserPrivateKeyBinding(id, "KEY"),
            FileBinding(id, "V"),
            CertificateBinding("KS", id),
            ZipBinding("V", id),
            UsernameColonPasswordBinding("V", id),
        )

        bindings.forEach { binding ->
            assertTrue(
                advertised.contains(binding.kind),
                "binding ${binding::class.simpleName} declares kind '${binding.kind}', " +
                    "which the factory does not advertise; it could never be routed",
            )
        }
        assertEquals(
            bindings.size,
            advertised.size,
            "the factory advertises ${advertised.size} kinds for ${bindings.size} binding types",
        )
    }

    @Test
    fun `each kind selects its own variable field`() {
        // The whole risk of a sealed dispatch whose arms are structurally identical is
        // that one arm starts reading a different field. These are the six names.
        assertEquals(
            listOf("STRING_VAR"),
            resolve(StringBinding(id, "STRING_VAR")).map { it.name },
        )
        assertEquals(
            listOf("FILE_VAR"),
            resolve(FileBinding(id, "FILE_VAR")).map { it.name },
        )
        assertEquals(
            listOf("ZIP_VAR"),
            resolve(ZipBinding("ZIP_VAR", id)).map { it.name },
        )
        assertEquals(
            listOf("COLON_VAR"),
            resolve(UsernameColonPasswordBinding("COLON_VAR", id)).map { it.name },
        )
        assertEquals(
            listOf("KEY_FILE_VAR"),
            resolve(SshUserPrivateKeyBinding(id, "KEY_FILE_VAR")).map { it.name },
            "sshUserPrivateKey names the key through keyFileVariable, not variable",
        )
        assertEquals(
            listOf("KEYSTORE_VAR"),
            resolve(CertificateBinding("KEYSTORE_VAR", id)).map { it.name },
            "certificate names the keystore through keystoreVariable",
        )
    }

    @Test
    fun `username password splits the null separated secret into two variables`() {
        val entries = resolve(
            UsernamePasswordBinding(id, "USER_VAR", "PASS_VAR"),
            "admin".toByteArray() + byteArrayOf(0) + "s3cr3t".toByteArray(),
        )

        assertEquals(listOf("USER_VAR", "PASS_VAR"), entries.map { it.name })
        assertEquals("admin", entries[0].handle.materialize())
        assertEquals("s3cr3t", entries[1].handle.materialize())
    }

    @Test
    fun `an empty password after the separator is accepted, not refused`() {
        val entries = resolve(
            UsernamePasswordBinding(id, "USER_VAR", "PASS_VAR"),
            "admin".toByteArray() + byteArrayOf(0),
        )

        assertEquals(2, entries.size)
        assertEquals("", entries[1].handle.materialize())
    }

    @Test
    fun `a credential with no separator at all is refused with its reason`() {
        val failure = assertThrows(BindingResolutionException::class.java) {
            resolve(
                UsernamePasswordBinding(id, "USER_VAR", "PASS_VAR"),
                "adminonly".toByteArray(),
            )
        }

        // The refusal names the credential it could not resolve, so a pipeline with
        // several bindings reports WHICH one is malformed rather than only why.
        assertEquals(
            "Failed to resolve binding for 'cred-1': UsernamePassword credential format " +
                "invalid: expected null-separated username and password",
            failure.message,
        )
    }

    @Test
    fun `a leading separator is refused rather than yielding an empty username`() {
        // The guard is `nullIndex > 0`, not `>= 0`: a credential whose first byte is the
        // separator has no username to bind, so it must not resolve to one empty value and
        // one populated value. It takes the same refusal as a credential with no separator.
        assertThrows(BindingResolutionException::class.java) {
            resolve(
                UsernamePasswordBinding(id, "USER_VAR", "PASS_VAR"),
                byteArrayOf(0) + "s3cr3t".toByteArray(),
            )
        }
    }

    @Test
    fun `kind matching is exact, not case insensitive`() {
        val advertised = factory.supportedKinds()

        assertTrue(advertised.contains("usernamePassword"))
        assertTrue(
            !advertised.contains("usernamepassword") && !advertised.contains("UsernamePassword"),
            "the advertised kinds are the literals other components match on; a second " +
                "spelling would create a kind nothing dispatches",
        )
    }
}