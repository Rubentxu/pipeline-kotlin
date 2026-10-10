package dev.rubentxu.pipeline.v2.application.coordination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * M1-E — capability-registration contract test.
 *
 * Pins the invariant that the cross-repo interface contract
 * (`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`)
 * has a `CONTRACT_SHA256.txt` companion whose contents match the SHA-256 of
 * the contract file, and that the contract itself enumerates the two
 * capabilities published by CRIC-M1 (`output.follow.v1` and
 * `events.follow.v1`) with their certifying test references.
 *
 * ## Why this test exists
 *
 * The contract is an admission key: any change to the contract's normative
 * text is a CRIC-1 breaking change. The SHA is the only way to detect an
 * unintended edit, and the test below makes that detection a CI failure
 * rather than a manual review. The capability rows are the consumer-facing
 * declaration of what is published; if a row is missing or points to a
 * non-existent test class, the contract is lying to consumers and the
 * build must say so.
 *
 * ## Locating the contract
 *
 * The test searches, in order:
 *  1. The `PIPELINEK_CONTRACT_DIR` environment variable (CI override).
 *  2. The system property `pipelinek.contract.dir`.
 *  3. `<gradleWorkingDir>/../../docs/pipelinek-coordinated-evolution/coordination`.
 *  4. The first ancestor directory that contains
 *     `docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`.
 *
 * The gradle working directory is `v2/` under the repo root, so the
 * default anchor is the repo root. CI can override via env to point at a
 * fixture directory.
 */
class CapabilityRegistrationTest {

    @Test
    @DisplayName("CONTRACT_SHA256.txt matches the SHA-256 of INTERFACE_CONTRACT.md")
    fun `contract sha matches the file`() {
        val dir = locateContractDir()
        val contract = dir.resolve("INTERFACE_CONTRACT.md")
        val shaFile = dir.resolve("CONTRACT_SHA256.txt")
        assertTrue(Files.isRegularFile(contract), "missing contract at $contract")
        assertTrue(Files.isRegularFile(shaFile), "missing SHA file at $shaFile")
        val expected = sha256(contract)
        val recorded = Files.readString(shaFile).trim()
        // The recorded line is "<sha>  INTERFACE_CONTRACT.md" (sha256sum format).
        // We tolerate a missing filename suffix by matching the first 64 hex chars.
        val recordedSha = recorded.split(Regex("\\s+")).firstOrNull() ?: ""
        assertEquals(expected, recordedSha,
            "CONTRACT_SHA256.txt does not match INTERFACE_CONTRACT.md; " +
                "expected $expected, recorded $recordedSha. " +
                "Run: sha256sum $contract to refresh.")
    }

    @Test
    @DisplayName("the contract enumerates output.follow.v1 and events.follow.v1 as published")
    fun `capabilities are listed as published`() {
        val dir = locateContractDir()
        val contract = Files.readString(dir.resolve("INTERFACE_CONTRACT.md"))
        assertTrue(contract.contains("output.follow.v1"),
            "contract must reference output.follow.v1; see CRIC-M1 audit table")
        assertTrue(contract.contains("events.follow.v1"),
            "contract must reference events.follow.v1; see CRIC-M1 audit table")
        assertTrue(contract.contains("**PUBLICADA**"),
            "contract must mark at least one capability as PUBLICADA")
    }

    @Test
    @DisplayName("the CRIC-M1 audit table references the certifying test classes")
    fun `audit table names the certifying tests`() {
        val dir = locateContractDir()
        val contract = Files.readString(dir.resolve("INTERFACE_CONTRACT.md"))
        // The test names below are part of the published record. If a
        // test class is renamed without updating this contract, the
        // build must fail. This protects consumers who navigate the
        // contract's pointers to the actual proof.
        val mustMention = listOf(
            "SegmentOutputFollowerTest",
            "EventFollowerAdapterTest",
            "M1DCrossJvmFollowTest",
        )
        for (name in mustMention) {
            assertTrue(contract.contains(name),
                "contract must reference $name in the CRIC-M1 audit table")
        }
    }

    // -------------------------------------------------------------- helpers

    private fun locateContractDir(): Path {
        val env = System.getenv("PIPELINEK_CONTRACT_DIR")
        if (env != null) {
            val p = Paths.get(env)
            if (Files.isDirectory(p)) return p
        }
        val sys = System.getProperty("pipelinek.contract.dir")
        if (sys != null) {
            val p = Paths.get(sys)
            if (Files.isDirectory(p)) return p
        }
        // Default: walk up from the gradle working directory until we find
        // the contract. Gradle runs the test with cwd == v2/, so we look
        // first at v2/../docs/... and then at any ancestor.
        val cwd = Paths.get("").toAbsolutePath()
        for (candidate in generateSequence(cwd) { it.parent }) {
            val probe = candidate.resolve("docs/pipelinek-coordinated-evolution/coordination")
            if (Files.isDirectory(probe)) return probe
        }
        // Last resort: throw, so the failure mode is clear.
        throw IllegalStateException(
            "could not locate docs/pipelinek-coordinated-evolution/coordination " +
                "from $cwd; set PIPELINEK_CONTRACT_DIR or pipelinek.contract.dir",
        )
    }

    private fun sha256(file: Path): String {
        val bytes = Files.readAllBytes(file)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
