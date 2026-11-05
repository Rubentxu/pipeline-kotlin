package dev.rubentxu.pipeline.v2.runtime.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The compatibility contract this product publishes has to say the truth about
 * the versions it names and about the map it offers.
 *
 * ## The guarantee
 *
 * A consumer of the published contracts reads three things from
 * [TelemetryCompatibility]: the version of the OpenTelemetry Java libraries
 * the export was produced with, the version of the semantic conventions the
 * exported names were taken from, and the upstream attribute behind each
 * exported key. Every one of the three is a claim about the world, and a
 * claim that is not checked is a claim that rots: the version catalog moves,
 * the conventions are a release candidate, and a key that nobody maps is a key
 * a consumer bridges wrongly.
 *
 * The map is therefore frozen, the versions are compared against the sources
 * that define them, and the upstream names the map uses are checked against
 * the vocabulary of the pinned version.
 *
 * ## Why the upstream vocabulary is a snapshot and not a lookup
 *
 * The upstream conventions live in another repository, and a test that read
 * them over the network would fail for reasons that have nothing to do with
 * this product: a network that is down, a mirror that is stale, a rate limit.
 * The snapshot here is the vocabulary of the pinned version, taken from the
 * upstream registry, and it carries the version it belongs to — so a product
 * that moves the pin without refreshing the snapshot is caught by the
 * comparison, and a snapshot that outlives its version is caught too.
 */
class TelemetryCompatibilityTest {

    /**
     * The map the product publishes, frozen.
     *
     * The map is the contract a consumer bridges with, so its content is not
     * a detail of the implementation: a change to it is a change to what a
     * consumer can rely on, and it has to be a deliberate one. The comparison
     * is over the KEYS and their upstream attributes, and not over the order
     * of the rows: the order of a map is not part of what a reader may act
     * on.
     */
    @Test
    fun `the map names every published key and the upstream attribute behind it`() {
        assertEquals(
            mapOf(
                "run.id" to "cicd.pipeline.run.id",
                "stage.index" to null,
                "step.index" to null,
                "script.origin" to null,
                "failure.class" to null,
            ),
            TelemetryCompatibility.attributeMap.associate { it.key to it.upstreamAttribute },
            "the published map changed: a key the product exports moved to another upstream " +
                "attribute, or gained one. The map is what a consumer bridges with, so its " +
                "content is a decision and not a detail",
        )
    }

    /**
     * Every upstream attribute the map names is an attribute the pinned
     * conventions define.
     *
     * The map names attributes of another specification, and a name that does
     * not exist there is a name a consumer will not find: the failure is
     * silent, because a bridge that maps to an attribute nobody defines
     * produces a query that returns nothing.
     */
    @Test
    fun `the upstream attribute the map names is one the pinned conventions define`() {
        assertEquals(
            OTEL_SEMCONV_CICD_VERSION,
            PINNED_CICD_ATTRIBUTES.version,
            "the snapshot of the upstream vocabulary belongs to another version than the one the " +
                "product pins: moving the pin moves the vocabulary, and the snapshot that " +
                "certifies the map has to move with it",
        )
        assertTrue(
            PINNED_CICD_ATTRIBUTES.attributes.isNotEmpty(),
            "the snapshot of the upstream vocabulary is empty, so this rule proves nothing",
        )

        val named = TelemetryCompatibility.attributeMap.mapNotNull { it.upstreamAttribute }
        assertTrue(
            named.isNotEmpty(),
            "the map names no upstream attribute, so this rule proves nothing",
        )

        named.forEach { attribute ->
            assertTrue(
                attribute in PINNED_CICD_ATTRIBUTES.attributes,
                "the map names '$attribute', which the pinned conventions do not define: a " +
                    "consumer that bridges to it finds nothing",
            )
        }
    }

    /**
     * The declared BOM version is the one the build compiles against.
     *
     * The declaration travels with the published artifact and the version
     * lives in the build's catalog: a consumer reading the declaration and a
     * builder reading the catalog must be talking about the same library, and
     * nothing else keeps them talking about the same library.
     */
    @Test
    fun `the declared BOM version is the one the build compiles against`() {
        val catalog = Files.readString(versionCatalog())

        val alias = requireNotNull(BOM_ALIAS.find(catalog)?.let { it.groupValues[1] }) {
            "the version catalog does not declare the OpenTelemetry BOM: the declared version " +
                "cannot be compared with a build that does not resolve one"
        }

        val version = requireNotNull(
            Regex("""(?m)^\s*${Regex.escape(alias)}\s*=\s*"([^"]+)""")
                .find(catalog)
                ?.let { it.groupValues[1] },
        ) {
            "the version catalog does not declare a version for the '$alias' entry the BOM points at"
        }

        assertEquals(
            version,
            TelemetryCompatibility.OTEL_JAVA_BOM_VERSION,
            "the declared BOM version is not the one the build compiles against: the consumer " +
                "that reads the declaration and the builder that reads the catalog would be " +
                "talking about two different libraries",
        )
    }

    /**
     * The version catalog of the build, found by walking up to the Gradle root.
     */
    private fun versionCatalog(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val catalog = candidate.resolve("gradle/libs.versions.toml")
            if (Files.isRegularFile(catalog)) {
                return catalog
            }
            candidate = candidate.parent
        }
        error("the version catalog was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    private companion object {

        /**
         * The CI/CD attributes the pinned conventions define, and the version
         * they belong to.
         *
         * The list is the upstream registry of the CI/CD conventions at the
         * pinned version: the attributes the specification defines, and
         * nothing else. The version is part of the snapshot on purpose — a
         * vocabulary is the vocabulary of a version, and a snapshot without
         * its version cannot say whether it still certifies anything.
         */
        val PINNED_CICD_ATTRIBUTES = PinnedVocabulary(
            version = "1.44.0",
            attributes = setOf(
                "cicd.pipeline.action.name",
                "cicd.pipeline.name",
                "cicd.pipeline.result",
                "cicd.pipeline.run.id",
                "cicd.pipeline.run.state",
                "cicd.pipeline.run.url.full",
                "cicd.pipeline.task.name",
                "cicd.pipeline.task.run.id",
                "cicd.pipeline.task.run.result",
                "cicd.pipeline.task.run.url.full",
                "cicd.pipeline.task.type",
                "cicd.system.component",
                "cicd.worker.id",
                "cicd.worker.name",
                "cicd.worker.state",
                "cicd.worker.url.full",
            ),
        )

        /** The BOM entry of the version catalog, and the version entry it points at. */
        val BOM_ALIAS = Regex("""opentelemetry-bom\s*=\s*\{[^}]*version\.ref\s*=\s*"([^"]+)"""")
    }

    /**
     * A vocabulary of the upstream conventions, and the version it belongs to.
     */
    private data class PinnedVocabulary(
        val version: String,
        val attributes: Set<String>,
    )
}
