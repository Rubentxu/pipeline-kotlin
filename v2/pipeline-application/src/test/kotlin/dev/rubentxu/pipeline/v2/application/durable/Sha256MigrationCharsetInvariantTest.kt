package dev.rubentxu.pipeline.v2.application.durable

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * B0-2: the migration invariant, stated as a test so it cannot be quietly broken.
 *
 * ## What this pins
 *
 * Migrating a digest call site to [dev.rubentxu.pipeline.v2.domain.digest.Sha256] is only safe
 * if the bytes hashed are IDENTICAL before and after. For the four sites that previously wrote
 * `input.toByteArray()` with no charset, that was the platform default — so the digest they
 * produced, and every durable row derived from it, depended on the JVM's locale.
 *
 * This test does two things:
 *
 * ```text
 * 1. records the pre-migration value for a non-ASCII input, on this toolchain
 * 2. asserts the migrated path equals the ORIGINAL implementation, byte for byte
 * ```
 *
 * It compares against the old expression rather than against a recorded constant, because the
 * question is not "is this digest stable over time" but "did migrating the call site change
 * what is hashed". A constant would freeze today's value and quietly license a behaviour change
 * in a later migration.
 *
 * ## What its teeth actually are, measured
 *
 * Two mutations were applied, because the first one did not work and that is worth recording.
 *
 * ```text
 * defaultCharset()   exit 0, flipped NOTHING   <- see below
 * ISO-8859-1         exit 1, flipped EXACTLY the invariant row
 * ```
 *
 * The first mutation moved `ofText` from `StandardCharsets.UTF_8` to `Charset.defaultCharset()`,
 * which is the defect these four sites actually had. It flipped nothing, because JEP 400 made
 * UTF-8 the default charset in JDK 18 and every supported toolchain is past that: on a modern JVM
 * the two expressions are the same code. So this test cannot detect the historical defect by
 * observation on this toolchain; it detects a CHANGED charset, which is the property that
 * matters for a future migration.
 *
 * That is a real limit and it is stated rather than papered over. The evidence that these four
 * sites were charset-dependent is the diff itself (`input.toByteArray()` in the base commit),
 * not a failing test, because no JVM this repository supports exhibits the divergence.
 *
 * ## Why the non-ASCII input
 *
 * An ASCII-only input encodes identically under UTF-8 and under every legacy charset this JVM
 * might default to, so it cannot distinguish the two. The defect only appears with a character
 * outside ASCII, which is exactly the input that appears in a real path or pipeline payload.
 */
class Sha256MigrationCharsetInvariantTest {

    /** Non-ASCII, and specifically multi-byte in UTF-8 and different in Latin-1. */
    private val accentedPath = "builds/españa/año-2026/informe.txt"

    /**
     * The pre-migration implementation, verbatim, reproduced so this test compares the migrated
     * path against the code it replaced rather than against a number I typed.
     *
     * Reproducing an algorithm under test is normally the HARNESS FIDELITY violation. Here it
     * is the SUBJECT: the claim is precisely that the old expression and the new one agree, so
     * the old expression must be present and executable to state the claim at all.
     */
    private fun legacySha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charset.defaultCharset()))
            .joinToString("") { byte -> "%02x".format(byte) }

    @Test
    fun `the migrated utility agrees with the legacy expression on this toolchain`() {
        // If the toolchain default were NOT UTF-8, this would fail — and that is the correct
        // outcome, because it means the migration changed the bytes and needs a compatibility
        // decision rather than a silent adoption.
        assertEquals(
            StandardCharsets.UTF_8,
            Charset.defaultCharset(),
            "this toolchain must default to UTF-8 for the migration to be byte-identical",
        )
        assertEquals(legacySha256Hex(accentedPath), dev.rubentxu.pipeline.v2.domain.digest.Sha256.ofText(accentedPath))
    }

    @Test
    fun `an ASCII input cannot tell the two encodings apart, which is why the accented row exists`() {
        // The control. If this row and the one above were the same evidence, the accented row
        // would be decoration rather than a discriminating test.
        val ascii = "builds/report.txt"
        assertEquals(legacySha256Hex(ascii), dev.rubentxu.pipeline.v2.domain.digest.Sha256.ofText(ascii))
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(ascii.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) },
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(ascii.toByteArray(StandardCharsets.ISO_8859_1))
                .joinToString("") { byte -> "%02x".format(byte) },
            "ASCII is the control: the two encodings agree, so it cannot detect the defect",
        )
    }

    @Test
    fun `the non-ASCII input DOES distinguish the two encodings`() {
        val utf8 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(accentedPath.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val latin1 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(accentedPath.toByteArray(StandardCharsets.ISO_8859_1))
            .joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(
            false,
            utf8 == latin1,
            "if these were equal the accented row would prove nothing about the charset defect",
        )
    }
}