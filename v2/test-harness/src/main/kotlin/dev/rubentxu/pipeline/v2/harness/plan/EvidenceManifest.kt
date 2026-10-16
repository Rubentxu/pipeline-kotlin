package dev.rubentxu.pipeline.v2.harness.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The per-suite sidecar evidence the gate requires.
 *
 * H1 TODO 2: the [EvidenceReader] reads the JUnit XML
 * AND a JSON sidecar of the same basename
 * (`<resultsDir>/<class>.json` next to
 * `<resultsDir>/TEST-<class>.xml`). The sidecar is the
 * "self-describing" carrier the H1 mandate names:
 *
 * ```text
 *   FQCN        — the fully qualified class name, must
 *                 match the XML's `<testsuite name="...">`
 *   gradlePath  — the Gradle project that ran the suite
 *                 (e.g. `:pipeline-application`)
 *   invocation  — the exact Gradle command (e.g.
 *                 `gradle :pipeline-application:test
 *                  --tests FooTest`)
 *   binarySha   — the SHA-256 of the binary that
 *                 produced the XML (the binary's own
 *                 provenance, not the source commit)
 *   hf          — the fidelity the suite ran at
 *                 (HF0, HF1, ...)
 *   sourceCommit — the source commit the binary was
 *                 built from (so the gate can detect a
 *                 stale binary)
 * ```
 *
 * The gate refuses when ANY of these fields is missing
 * (Datos incompletos → Refused, no passed). The shape is
 * the JSON file: a missing file is the same refusal as
 * a missing field. The reader does not invent defaults;
 * a `null` is a refusal.
 *
 * Wire format (snake_case, lowercase discriminator,
 * JSON object per suite):
 *
 * ```json
 * {
 *   "class_name": "com.example.FooTest",
 *   "gradle_path": ":pipeline-application",
 *   "invocation": "gradle :pipeline-application:test --tests FooTest",
 *   "binary_sha256": "0123456789abcdef...",
 *   "hf": "HF0",
 *   "source_commit": "0123456789abcdef..."
 * }
 * ```
 *
 * The file is read once per suite and held in memory. The
 * gate's `adjudicate` joins the sidecar with the plan and
 * the binary's reported commit; missing sidecars or
 * missing fields are refused.
 */
@Serializable
data class EvidenceManifest(
    @SerialName("class_name") val className: String,
    @SerialName("gradle_path") val gradlePath: String,
    @SerialName("invocation") val invocation: String,
    @SerialName("binary_sha256") val binarySha256: String,
    @SerialName("hf") val hf: String,
    @SerialName("source_commit") val sourceCommit: String,
) {
    init {
        // The init enforces non-blank on every field. A
        // manifest with a blank field is rejected at
        // construction; the sidecar writer cannot
        // produce a manifest with empty strings. This
        // is the "Datos incompletos → Refused" half of
        // the gate: the manifest's data class init is
        // the first line of defence, and the gate is
        // the second.
        require(className.isNotBlank()) { "EvidenceManifest.className must not be blank" }
        require(gradlePath.isNotBlank()) { "EvidenceManifest.gradlePath must not be blank" }
        require(invocation.isNotBlank()) { "EvidenceManifest.invocation must not be blank" }
        require(binarySha256.length == 64) {
            "EvidenceManifest.binarySha256 must be a 64-hex SHA-256; " +
                "got ${binarySha256.length} chars"
        }
        require(binarySha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "EvidenceManifest.binarySha256 must be lowercase hex"
        }
        require(hf.isNotBlank()) { "EvidenceManifest.hf must not be blank" }
        require(sourceCommit.length == 40) {
            "EvidenceManifest.sourceCommit must be a 40-hex SHA-1; " +
                "got ${sourceCommit.length} chars"
        }
        require(sourceCommit.all { it in '0'..'9' || it in 'a'..'f' }) {
            "EvidenceManifest.sourceCommit must be lowercase hex"
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /**
         * The path of the sidecar for a JUnit XML.
         * `TEST-FooTest.xml` -> `FooTest.json`. The XML
         * and sidecar MUST live in the same directory;
         * the writer must place them together.
         */
        fun sidecarFor(xmlPath: Path): Path {
            val fileName = xmlPath.fileName.toString()
            val className = fileName.removePrefix("TEST-").removeSuffix(".xml")
            return xmlPath.resolveSibling("$className.json")
        }

        /**
         * Read a sidecar from [path]. The file MUST exist
         * and parse as a well-formed [EvidenceManifest].
         * Missing or malformed: throws. The gate catches
         * the throw and turns it into [SelectionRefusal.IncompleteEvidence].
         */
        fun load(path: Path): EvidenceManifest {
            val text = Files.readString(path)
            return json.decodeFromString(serializer(), text)
        }
    }
}
