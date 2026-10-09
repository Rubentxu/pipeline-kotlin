package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * OBS-A — the observation laws that are NOT yet guarded, written BEFORE the live
 * output ingress that will make them load-bearing.
 *
 * ## Why this exists rather than arriving with OBS-B
 *
 * `M1OutputPlaneIndependenceFitnessTest` already guards two of these: no output
 * plane module depends on the event plane, and no production source in the plane
 * reaches for event or domain vocabulary. Those are not repeated here.
 *
 * The rest were written as prose in `OBSERVATION_STREAM_ARCHITECTURE.md` and in
 * the design documents, which means they were true by nobody's enforcement. This
 * class makes them mechanical while the hot path is still untouched — the cheap
 * moment. Adding them after the ingress exists would mean writing a guard whose
 * failures cannot be distinguished from the regressions the ingress introduced.
 *
 * ## What a fitness test can and cannot say
 *
 * Every row here is a STRUCTURAL law: a module edge, a reachable symbol, or a
 * dependency. None of them claims that live output works, that channels survive,
 * or that a consumer cannot backpressure the execution — those are behavioural and
 * belong to the characterisation and UAT work, not here.
 *
 * Comments are stripped before every scan. Without that, the KDoc that explains
 * *why* a law holds names the thing it forbids, and the scan would read its own
 * documentation as a violation. That trap is why `M1` strips comments too.
 */
class FArchObservationContractLawFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private fun productionSources(module: String): List<Path> {
        val main = v2Root.resolve(module).resolve("src/main/kotlin")
        if (!Files.isDirectory(main)) return emptyList()
        return ScannerSupport.walkKotlinFiles(v2Root).filter { it.startsWith(main) }.toList()
    }

    private fun allProductionSources(): List<Path> =
        ScannerSupport.walkKotlinFiles(v2Root).filter { it.toString().contains("/src/main/") }.toList()

    private fun stripComments(source: String): String =
        source.lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private fun offendersMatching(sources: List<Path>, needle: String): List<Path> =
        sources.filter { stripComments(Files.readString(it)).contains(needle) }

    private fun assertNoOffenders(
        offenders: List<Path>,
        law: String,
        why: String,
    ) {
        if (offenders.isNotEmpty()) {
            throw AssertionError(
                "$law\n\n$why\n\noffending files:\n" +
                    offenders.joinToString("\n") { "  ${v2Root.relativize(it)}" },
            )
        }
    }

    /**
     * The reverse of the M1 direction. M1 says the output plane may not reach the
     * event plane; this says the EVENT plane may not reach the output STORE.
     *
     * They are different claims and only one of them was guarded. An event plane
     * that could read output bytes would let a semantic fact be derived from, or
     * invalidated by, a transcript — which is how "one byte authority" quietly
     * becomes two.
     */
    @Test
    fun `the event plane does not depend on the output store`() {
        val buildFile = v2Root.resolve("pipeline-events").resolve("build.gradle.kts")
        assertTrue(Files.exists(buildFile), "cannot read $buildFile; this guard would be vacuous")

        val declared = stripComments(Files.readString(buildFile))
        listOf(":pipeline-output-store", ":pipeline-output").forEach { forbidden ->
            assertTrue(
                !declared.contains(forbidden),
                ":pipeline-events declares $forbidden. The event plane is semantic facts; " +
                    "output bytes are a separate authority (ADR-M1). Depending on the store " +
                    "lets a fact be derived from a transcript.",
            )
        }
    }

    /**
     * Process output must never be carried inside a DomainEvent payload.
     *
     * The rule exists because the temptation is real and local: `EchoOutputCaptured`
     * already carries script message text, so adding a transcript field next to it
     * would compile, look consistent, and merge two authorities inside one envelope.
     *
     * Only the ingress path is forbidden, not the word itself — `ingestTranscriptIntoOutputPlane`
     * is the CORRECT direction and names the transcript on its way TO the output plane.
     */
    @Test
    fun `the event plane does not ingest process transcripts`() {
        val sources = productionSources("pipeline-events")
        assertTrue(
            sources.isNotEmpty(),
            "found no production sources under pipeline-events/src/main; the scan is looking " +
                "in the wrong place and would report success vacuously",
        )

        assertNoOffenders(
            offendersMatching(sources, "ingestTranscript"),
            "the event plane ingests a process transcript.",
            "A DomainEvent must carry semantic facts, not bytes a process produced. Transcript " +
                "ingestion belongs to the output plane; the function named here is the correct " +
                "direction and is called from the APPLICATION layer, not from :pipeline-events.",
        )
    }

    /**
     * The CLI renderer is not reachable from execution packages.
     *
     * The renderer answers "what does a person see". An execution package that can
     * import it has acquired a presentation responsibility inside the hot path, which
     * is how a terminal's preferences start influencing what a step executes.
     *
     * Scoped to the runtime modules the pipeline actually executes through, because a
     * blanket "nothing anywhere" guard would forbid the legitimate wiring in
     * `:pipeline-application`.
     */
    @Test
    fun `execution packages cannot reach the CLI renderer`() {
        val executionModules = listOf(
            "pipeline-step-sdk/runtime",
            "pipeline-domain",
            "pipeline-runtime",
        )

        val reachable = executionModules.flatMap { module ->
            productionSources(module).filter { source ->
                stripComments(Files.readString(source))
                    .contains("application.observation")
            }
        }

        assertNoOffenders(
            reachable,
            "an execution package imports the CLI observation layer.",
            "`:pipeline-application` may render; the execution packages may not. Presentation " +
                "belongs at the edge, and a renderer reachable from the hot path lets a display " +
                "concern influence execution.",
        )
    }

    /**
     * No remote-transport vocabulary inside this repository, with ONE measured exception.
     *
     * Fabric is a separate repository that adapts these ports to gRPC. If gRPC or Fabric's own
     * classes appeared here, the boundary would already be gone and OBS-F would be a migration
     * rather than an adapter.
     *
     * ## Why protobuf is NOT forbidden outright
     *
     * The obvious law — "no protobuf anywhere in pipeline-kotlin" — is **false about this
     * repository**, and a guard asserting it would have been wrong on its first run.
     * `:pipeline-protocol` is a pre-existing published contract module that applies the
     * `com.google.protobuf` Gradle plugin and runs `protoc`. That predates this work and is
     * legitimate: it is the wire format for a contract this project publishes.
     *
     * So the law is pinned rather than absolute: protobuf belongs to `:pipeline-protocol`, and
     * appearing anywhere else — especially in the observation or execution modules — is the
     * violation. That is stricter than "no protobuf" in the place that matters and truthful in
     * the place that already has it.
     */
    @Test
    fun `transport vocabulary is confined to the module that legitimately owns it`() {
        val neverAllowed = listOf(
            "io.grpc",
            "io.jenkins.pipelinek.fabric",
            // OBS-F: the Fabric FQCN above was the only thing guarded, and "no Jenkins here" was
            // therefore true of the adapter while saying nothing about the JENKINS TYPES. These are
            // the roots a real Jenkins dependency arrives through. Bare `jenkins` is deliberately
            // NOT in this list, and the reason is the law this repository already follows for
            // Jenkins users: `PublishHtml`, `httpRequest` and friends are named after Jenkins on
            // purpose, so a bare-word guard would be false on its first run — the same mistake the
            // protobuf row below documents and avoids.
            "hudson.",
            "jenkins.model.",
            "org.kohsuke.stapler",
        )

        val buildFiles = ScannerSupport.walkBuildFiles(v2Root)
        val sources = allProductionSources()

        val hits = mutableListOf<String>()
        buildFiles.forEach { file ->
            val code = stripComments(Files.readString(file))
            neverAllowed.forEach { needle ->
                if (code.contains(needle)) hits += "${v2Root.relativize(file)} -> $needle"
            }
        }
        sources.forEach { file ->
            val code = stripComments(Files.readString(file))
            neverAllowed.forEach { needle ->
                if (code.contains(needle)) hits += "${v2Root.relativize(file)} -> $needle"
            }
        }

        if (hits.isNotEmpty()) {
            throw AssertionError(
                "remote-transport vocabulary reached pipeline-kotlin:\n" +
                    hits.joinToString("\n") { "  $it" } +
                    "\n\nFabric owns the gRPC adapter. This repository publishes ports; it does " +
                    "not speak a wire protocol.",
            )
        }

        // protobuf is allowed in exactly one module, and this is what makes the exception
        // an exception rather than a loophole.
        //
        // `gradle/libs.versions.toml` is also excluded, and deliberately: a version catalog is a
        // shared coordinate DECLARATION, not a use. Pinning the protoc version there is how
        // :pipeline-protocol gets to use protobuf at all. Reading a declaration as usage would
        // make the catalog the one file a guard could never tolerate.
        val protobufElsewhere = mutableListOf<String>()
        buildFiles.forEach { file ->
            if (file.parent?.fileName?.toString() == "pipeline-protocol") return@forEach
            if (file.fileName?.toString() == "libs.versions.toml") return@forEach
            if (stripComments(Files.readString(file)).contains("com.google.protobuf")) {
                protobufElsewhere += "${v2Root.relativize(file)}"
            }
        }
        sources.forEach { file ->
            if (file.toString().contains("/pipeline-protocol/")) return@forEach
            if (stripComments(Files.readString(file)).contains("com.google.protobuf")) {
                protobufElsewhere += "${v2Root.relativize(file)}"
            }
        }

        if (protobufElsewhere.isNotEmpty()) {
            throw AssertionError(
                "protobuf appears outside :pipeline-protocol:\n" +
                    protobufElsewhere.joinToString("\n") { "  $it" } +
                    "\n\n:pipeline-protocol owns the published wire format. Observation and " +
                    "execution modules must keep publishing PORTS; a module that grows its own " +
                    "protobuf is a module that has started speaking the protocol itself.",
            )
        }

        // ---------------------------------------------------------------- network transport
        //
        // OBS-F named "network transport" as forbidden and nothing guarded it. The obvious law —
        // "no socket anywhere in pipeline-kotlin" — is FALSE about this repository, and a guard
        // asserting it would have been wrong on its first run, exactly as the protobuf row above
        // records for protobuf.
        //
        // `:pipeline-step-sdk/http` is a real, shipped HTTP transport (`JdkHttpTransport`,
        // `BoundedBodySubscriber`) and predates this work. So the law is pinned rather than
        // absolute, and pinned in the one place that matters: the OBSERVATION and EXECUTION path
        // must not open a socket. A socket appearing next to the Output Plane or a pump is how
        // "this repository publishes ports" quietly becomes "this repository dials out", and that
        // is the boundary Fabric exists to hold.
        val networkOutsideHttp = mutableListOf<String>()
        val networkNeedles = listOf("java.net.http", "java.net.Socket", "okhttp3.", "io.ktor.")
        buildFiles.forEach { file ->
            if (file.parent?.fileName?.toString() == "http") return@forEach
            val code = stripComments(Files.readString(file))
            networkNeedles.forEach { needle ->
                if (code.contains(needle)) networkOutsideHttp += "${v2Root.relativize(file)} -> $needle"
            }
        }
        sources.forEach { file ->
            if (file.toString().contains("/pipeline-step-sdk/http/")) return@forEach
            val code = stripComments(Files.readString(file))
            networkNeedles.forEach { needle ->
                if (code.contains(needle)) networkOutsideHttp += "${v2Root.relativize(file)} -> $needle"
            }
        }

        if (networkOutsideHttp.isNotEmpty()) {
            throw AssertionError(
                "network transport outside :pipeline-step-sdk/http:\n" +
                    networkOutsideHttp.joinToString("\n") { "  $it" } +
                    "\n\nThe http Step is a sanctioned client capability and owns the only socket in " +
                    "this repository. The observation and execution paths publish PORTS: a client " +
                    "there means a consumer back on the write side, which is the boundary this " +
                    "whole subsystem exists to keep.",
            )
        }

        // ---------------------------------------------------------------- why "controller" is NOT here
        //
        // OBS-F also named "controller". It cannot be guarded as a word and the reason is worth
        // keeping: `FileLockCoordinator` coordinates local locks and has nothing to do with
        // Jenkins' master/controller, and `Capabilities.kt` names a controller concept of its own.
        // A guard on the bare word would fail on those and would therefore be deleted the first
        // time it fired. The boundary is already held by the FQCN and the Jenkins type roots
        // above; a vocabulary collision is not a boundary crossing.
    }

    /**
     * The two cursors are distinct types, not two names for one offset.
     *
     * `EventCursor` and `OutputCursor` are separate declarations, which is what makes
     * "event #143 is not byte #143" a type error rather than a comment. The rule is
     * asserted on the DECLARATIONS: there must be exactly one of each, and neither
     * may be a typealias of the other.
     *
     * A typealias would satisfy a weaker version of this test — same name present,
     * two underlying types — and would be exactly the collapse this forbids.
     */
    @Test
    fun `event and output cursors are distinct declared types`() {
        val eventCursors = allProductionSources().filter {
            stripComments(Files.readString(it))
                .contains(Regex("""\b(class|interface|object|data class)\s+EventCursor\b"""))
        }
        val outputCursors = allProductionSources().filter {
            stripComments(Files.readString(it))
                .contains(Regex("""\b(class|interface|object|data class)\s+OutputCursor\b"""))
        }

        assertTrue(
            eventCursors.isNotEmpty(),
            "no EventCursor declaration found; this guard would be vacuous",
        )
        assertTrue(
            outputCursors.isNotEmpty(),
            "no OutputCursor declaration found; this guard would be vacuous",
        )

        val aliases = allProductionSources().filter {
            stripComments(Files.readString(it))
                .contains(Regex("""typealias\s+(EventCursor|OutputCursor)\b"""))
        }
        assertNoOffenders(
            aliases,
            "one of the two cursors is a typealias of the other.",
            "ADR-M1 D3 requires output continuation to be an order of its own. A typealias " +
                "would let an event sequence be passed where a byte offset belongs, and the " +
                "type would no longer refuse it.",
        )
    }
}
