package dev.rubentxu.pipeline.v2.harness.cycle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The production-style [OracleLedger] that reads a JSON file
 * from disk. The file's wire format is a list of oracle
 * entries, each identifying a test by its `(module, name)`
 * (fitness) or `(module, testClass, testMethod)` (JUnit) and
 * the set of commits at which the test last PASSed.
 *
 * BQ4 audit (2026-10-16, P0-D): the [CycleCli] needs a
 * way to load a real ledger so the cycle can close
 * end-to-end outside the test JVM. A test that uses
 * [MapOracleLedger] proves the runner; a CLI run that uses
 * [JsonOracleLedger] proves the CLI.
 *
 * Wire format (snake_case keys, lowercase discriminator
 * strings, JSON object per oracle):
 *
 * ```json
 * {
 *   "oracles": [
 *     {
 *       "type": "fitness",
 *       "module": ":test-harness",
 *       "name": "PromotionEvidenceVerifierTest.permanent_with_matching_digest",
 *       "pass_commits": ["0123456789abcdef0123456789abcdef01234567"]
 *     },
 *     {
 *       "type": "junit",
 *       "module": ":pipeline-application",
 *       "test_class": "dev.rubentxu.pipeline.v2.application.Foo",
 *       "test_method": "bar",
 *       "pass_commits": ["0123456789abcdef0123456789abcdef01234567"]
 *     }
 *   ]
 * }
 * ```
 *
 * The ledger is parsed once at load time and held in memory.
 * The verifier's `hasPassed` lookup is a constant-time set
 * membership check. A reference to an oracle NOT in the
 * file is refused by the verifier on the "oracle not PASS"
 * branch — there is no implicit "the file has no record" =
 * "PASS" interpretation; an empty file is equivalent to
 * [EmptyOracleLedger] but with a typed load failure if the
 * JSON is malformed.
 */
class JsonOracleLedger(
    private val passes: Map<OracleRef, Set<String>>,
) : OracleLedger {
    override fun hasPassed(oracle: OracleRef, boundCommit: String): Boolean =
        passes[oracle]?.contains(boundCommit) == true

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /**
         * Load the ledger from [path]. The file MUST exist
         * and parse as a [LedgerFile]. A missing or
         * malformed file is a programmer error, not a
         * soft failure: the runner has no business starting
         * a cycle without a real ledger when one was
         * requested. The throw is intentional.
         */
        fun load(path: Path): JsonOracleLedger {
            val text = Files.readString(path)
            val parsed = json.decodeFromString(LedgerFile.serializer(), text)
            val map = mutableMapOf<OracleRef, Set<String>>()
            for (entry in parsed.oracles) {
                // BQ4 P0-D follow-up (2026-10-17): the previous
                // shape accepted passCommits verbatim and
                // matched by Set.contains at hasPassed time.
                // A non-SHA-1 string ("abc", "v1.0", "main")
                // would silently fail every query and the
                // verifier would record a refused cycle with
                // a "no oracle PASSed" reason — the dual
                // shape that hid the malformed data. The
                // init is now strict: each passCommits entry
                // MUST be a 40-char lowercase hex SHA-1, and
                // the ledger's `module` field MUST be a
                // non-blank Gradle project path. A malformed
                // entry is a programmer error, not a soft
                // failure: the throw is intentional, the
                // same as the malformed-JSON throw above.
                for (commit in entry.passCommits) {
                    require(commit.length == 40) {
                        "ledger entry '${entry.module}' has passCommits entry of length " +
                            "${commit.length}; must be a 40-hex SHA-1; got '$commit'"
                    }
                    require(commit.all { it in '0'..'9' || it in 'a'..'f' }) {
                        "ledger entry '${entry.module}' has non-lowercase-hex passCommits " +
                            "entry '$commit'; the wire format is a 40-char SHA-1 in lowercase hex"
                    }
                }
                require(entry.module.isNotBlank()) {
                    "ledger entry has blank module; every oracle needs a Gradle project"
                }
                val oracle: OracleRef = when (entry) {
                    is LedgerEntry.Fitness -> OracleRef.Fitness(
                        name = entry.name,
                        module = entry.module,
                    )
                    is LedgerEntry.Junit -> OracleRef.Junit(
                        testClass = entry.testClass,
                        testMethod = entry.testMethod,
                        module = entry.module,
                    )
                }
                map[oracle] = entry.passCommits.toSet()
            }
            return JsonOracleLedger(map)
        }
    }

    @Serializable
    private data class LedgerFile(
        @SerialName("oracles") val oracles: List<LedgerEntry> = emptyList(),
    )

    @Serializable
    private sealed interface LedgerEntry {
        val module: String
        val passCommits: List<String>

        @Serializable
        @SerialName("fitness")
        data class Fitness(
            @SerialName("module") override val module: String,
            @SerialName("name") val name: String,
            @SerialName("pass_commits") override val passCommits: List<String>,
        ) : LedgerEntry

        @Serializable
        @SerialName("junit")
        data class Junit(
            @SerialName("module") override val module: String,
            @SerialName("test_class") val testClass: String,
            @SerialName("test_method") val testMethod: String,
            @SerialName("pass_commits") override val passCommits: List<String>,
        ) : LedgerEntry
    }
}
