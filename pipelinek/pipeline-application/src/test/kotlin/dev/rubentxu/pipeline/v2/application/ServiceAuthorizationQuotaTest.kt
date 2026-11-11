package dev.rubentxu.pipeline.v2.application

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The quota the authorizations of the service are read under.
 *
 * ## The guarantee under trial
 *
 * That an authorizations file or a standard input that does not fit the quota
 * is REFUSED with a sentence that names the limit, and that nothing of what
 * was read is exposed: the file is measured before it is read, and a stream
 * is read one byte more than the limit and no further.
 *
 * ## The capacity this serves
 *
 * The authorizations are the secret the service hands out, and they arrive
 * from outside the process: a file the operator named, or the standard input
 * of a service that keeps the authorization off the disk. A file that is not
 * there is refused, a file that any local user can read is refused, and a file
 * that is too big to be an authorization list has to be refused too — without
 * being read into the memory of the service on the way.
 *
 * ## Why the quota is INJECTED
 *
 * Because a refusal has to be provable without a large file: the rows that
 * refuse name a quota of a few dozen bytes. The product's own quota is
 * declared in the module that owns the reading of files, and the row that
 * checks it says where it comes from.
 *
 * ## What this does not freeze
 *
 * The words of the refusal beyond the number it carries, and the format of
 * the file: those are other guarantees.
 */
class ServiceAuthorizationQuotaTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a file of authorizations over the quota is refused with the limit`() {
        val named = root.resolve("elsewhere")
        Files.writeString(named, "token operator all\n".repeat(BIG_LINES))

        val reading = ServiceAuthorizationStore.read(named, quotaBytes = QUOTA)

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a file that does not fit the quota is refused, and not read",
        )
        assertTrue(
            refused.detail.contains(QUOTA.toString()),
            "the refusal names the limit that was applied: ${refused.detail}",
        )
        assertFalse(
            refused.detail.contains("token operator all"),
            "the refusal exposes nothing of what was read: ${refused.detail}",
        )
    }

    @Test
    fun `a standard input over the quota is refused with the limit`() {
        val stream = ByteArrayInputStream("token operator all\n".repeat(BIG_LINES).toByteArray(Charsets.UTF_8))

        val reading = ServiceAuthorizationStore.readStandardInput(stream, quotaBytes = QUOTA)

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a stream that holds more than the quota is refused, and not read whole",
        )
        assertTrue(
            refused.detail.contains(QUOTA.toString()),
            "the refusal names the limit that was applied: ${refused.detail}",
        )
        assertFalse(
            refused.detail.contains("token operator all"),
            "the refusal exposes nothing of what was read: ${refused.detail}",
        )
    }

    @Test
    fun `a file of authorizations within the quota keeps the authorizations it holds`() {
        val named = root.resolve("elsewhere")
        Files.writeString(named, "token operator all\n")

        val reading = ServiceAuthorizationStore.read(named, quotaBytes = QUOTA)

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "a file that fits the quota is read, as it was before the quota existed",
        )
        assertEquals(1, read.authorizations.size, "the authorization of the file is the one it holds")
        assertEquals("operator", read.authorizations.single().label, "the label is the one the file carries")
    }

    @Test
    fun `a standard input within the quota keeps the authorizations it holds`() {
        val stream = ByteArrayInputStream("token operator all\n".toByteArray(Charsets.UTF_8))

        val reading = ServiceAuthorizationStore.readStandardInput(stream, quotaBytes = QUOTA)

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "a stream that fits the quota is read, as it was before the quota existed",
        )
        assertEquals(1, read.authorizations.size, "the authorization of the stream is the one it holds")
    }

    private companion object {
        /** The quota every row of this file uses. */
        private const val QUOTA: Long = 64L

        /** How many lines the file that does not fit holds. */
        private const val BIG_LINES: Int = 10
    }
}
