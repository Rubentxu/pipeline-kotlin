package dev.rubentxu.pipeline.v2.credentials.local

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Regression tests for the `pipeline credentials add` CLI defect found by the
 * installed-distribution UAT (WU-RP-042 S1, commit 76e3015d).
 *
 * ## The defect
 *
 * All seven CLI readers in [MainCredentialsCli] constructed the credential
 * with a blank placeholder id, `CredentialsId("")`. Since T1 (9c6181e7)
 * `CredentialsId` enforces a non-blank invariant in its `init` block, so the
 * placeholder construction threw `IllegalArgumentException: CredentialsId
 * value must not be blank` on EVERY `add` invocation, before the store was
 * ever reached. The CLI was never executable end-to-end; no prior UAT
 * exercised a real `add`.
 *
 * The fix uses a typed NON-BLANK placeholder (`<pending-store-id>`), which is
 * legal because `SecretStore.add(id, credential)` keys the entry by the
 * `id` argument, not by `credential.id` — the placeholder is never persisted
 * as an entry key.
 *
 * ## What these tests pin (JUnit level; the CLI needs a real PTY via
 * System.console(), so e2e proof lives in the S1 slice receipt)
 *
 * 1. The blank placeholder MUST be rejected by the CredentialsId invariant
 *    (fail-closed guard that exposed the defect).
 * 2. The non-blank placeholder used by the CLI readers MUST be a legal
 *    CredentialsId.
 * 3. store.add(entryKey, credentialWithPlaceholderId) MUST persist and read
 *    back under the entry key — proving placeholder id ≠ entry key.
 */
@DisplayName("credentials add CLI placeholder-id regression (WU-RP-042 S1)")
class CredentialsCliAddPlaceholderRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    private val placeholder = "<pending-store-id>"

    @Test
    fun `blank placeholder id is rejected by CredentialsId invariant`() {
        val ex = assertThrows<IllegalArgumentException> {
            CredentialsId("")
        }
        assertTrue(
            ex.message!!.contains("must not be blank"),
            "Expected the non-blank invariant message, got: ${ex.message}"
        )
    }

    @Test
    fun `cli non-blank placeholder id is a legal CredentialsId`() {
        // This must NOT throw; it is exactly what the seven CLI readers build.
        val id = CredentialsId(placeholder)
        assertEquals(placeholder, id.value)
    }

    @Test
    fun `cli resolves store file to canonical default when env absent`() {
        // Documented contract: no env -> ~/.pipeline/credentials.bin (same
        // default the runtime resolves in Main.kt composeWithCredentialsExecutor
        // when controlRoot is the default user location).
        val resolved = MainCredentialsCli.resolveStoreFile()
        val expected = Path.of(System.getProperty("user.home"), ".pipeline", "credentials.bin")
        // The env var is not guaranteed absent in CI, so assert the env-honoring
        // shape: resolved == env value if set, else the canonical default.
        val env = System.getenv("PIPELINE_CREDENTIALS_STORE")
        assertEquals(
            env?.let { Path.of(it) } ?: expected,
            resolved,
            "CLI store resolution MUST honor PIPELINE_CREDENTIALS_STORE, else canonical default"
        )
    }

    @Test
    fun `add keys entry by store id not by credential embedded placeholder id`() {
        val storeFile = tempDir.resolve("credentials.bin")
        val store = LocalSecretStore(storeFile, "regression-test".toCharArray())
        try {
            val entryKey = CredentialsId("ci-token")
            // Simulates exactly what `credentials add --kind secret-text ci-token`
            // does after the fix: credential carries the placeholder id, the
            // store entry key is the CLI `id` argument.
            val credential = SecretText(
                id = CredentialsId(placeholder),
                scope = CredentialScope.GLOBAL,
                bytes = "super-secret".toByteArray()
            )
            store.add(entryKey, credential)

            val readBack = store.get(entryKey)
            assertTrue(readBack is SecretText, "Credential must read back as SecretText")
            assertTrue(
                (readBack as SecretText).bytes.contentEquals("super-secret".toByteArray()),
                "Secret content must round-trip through the store"
            )

            val ids = store.list()
            assertTrue(ids.any { it.value == "ci-token" }, "Entry must be listed under the CLI id argument")
            assertTrue(
                ids.none { it.value == placeholder },
                "The placeholder id MUST NEVER appear as a persisted entry key"
            )
        } finally {
            store.close()
        }
    }
}
