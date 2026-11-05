package dev.rubentxu.pipeline.v2.runtime.telemetry

/**
 * One row of the map between the keys this product exports and the upstream
 * conventions it was written against.
 *
 * The row says which upstream attribute carries the SAME FACT as the key. A
 * row with no upstream attribute says that the conventions define none for
 * that fact, which is a different statement from "the product does not know
 * yet": the conventions are a closed vocabulary, and a fact that is not in it
 * is not in it.
 *
 * The two spellings of the same fact are the point of the row. The product
 * spells its keys its own way — `run.id`, and not `cicd.pipeline.run.id` — so
 * a consumer that bridges to the upstream vocabulary reads the row instead of
 * the specification, and a rename upstream does not reach the consumer.
 */
data class TelemetryAttributeBinding(
    /** The key this product exports. */
    val key: String,

    /** The upstream attribute that carries the same fact, or null when the conventions define none. */
    val upstreamAttribute: String?,
)

/**
 * The compatibility contract of the telemetry this product exports.
 *
 * ## The three questions, and where their answers live
 *
 * Somebody outside this repository asks three questions about an export: which
 * version of the OpenTelemetry Java libraries produced it, which version of the
 * semantic conventions its names were taken from, and what an exported key
 * means in the upstream vocabulary. The exported document answers none of the
 * three — it states the schema it speaks and the trace it belongs to, and
 * nothing else — so the answers live here, in the published module, where a
 * consumer that compiles against the product can read them.
 *
 * ## The versions
 *
 * [OTEL_JAVA_BOM_VERSION] is the version of the OpenTelemetry Java BOM this
 * product is compiled against. The BOM is a build fact, and it is declared
 * here as well because the exported document does not say which library wrote
 * it: a consumer comparing two exports needs to know whether the difference
 * they see is the product's or the library's.
 *
 * The conventions are pinned at [OTEL_SEMCONV_CICD_VERSION] and declared a
 * release candidate at [OTEL_SEMCONV_CICD_STATUS]: their names may still move
 * between two versions of the specification, so the pin is part of what the
 * product promises.
 *
 * ## The map
 *
 * The keys the product exports are its own spelling, and [attributeMap] says
 * which upstream attribute carries the same fact for each of them. Four of the
 * five have no upstream counterpart, and the map says so rather than leaving
 * the reader to guess: the CI/CD conventions define no ordinal, no script
 * origin and no failure class. A consumer that bridges to the upstream
 * vocabulary carries those itself, and the map is where it learns that it has
 * to.
 *
 * The map covers the attributes and not the metric dimensions. A dimension is
 * a label of a measurement, and the upstream conventions define attributes
 * rather than labels: there is nothing upstream for a label to correspond to.
 *
 * ## The migration rule
 *
 * A change to the upstream is a change to a ROW, never to a key. The product
 * spells its keys its own way, so a rename upstream does not break a consumer
 * of this product; the row changes, the export does not.
 *
 * A change to a KEY is a change to a published contract: it is versioned, it
 * is recorded in `published-contract-exceptions.json`, and the previous
 * spelling stays in the vocabulary while the producers still set it. The map
 * is where the two spellings meet, so a consumer that has to migrate reads it
 * and not a changelog.
 *
 * ## The OpenTelemetry types of a published ABI
 *
 * [otelTypesInPublishedAbi] is the explicit decision about the OpenTelemetry
 * Java types that may appear in the ABI this repository publishes. It is
 * empty, and the emptiness is the guarantee: a consumer of the published
 * contracts resolves no OpenTelemetry type, and the published contracts carry
 * no OpenTelemetry dependency. Naming a type here is how that changes,
 * deliberately and in one place.
 */
object TelemetryCompatibility {

    /**
     * The version of the OpenTelemetry Java BOM this product is compiled
     * against.
     *
     * It is the version of the libraries, not of the conventions: the two
     * number independently, and a consumer that names one of them is asking
     * about the code that produced the export rather than about the names it
     * carries.
     */
    const val OTEL_JAVA_BOM_VERSION: String = "1.67.0"

    /**
     * The map between the keys this product exports and the upstream
     * conventions they were written against.
     *
     * It covers every attribute the published vocabulary names, and it is
     * refused otherwise: a key the export may carry and a key the map does not
     * name are two vocabularies, and the reader of the map would not know
     * which one they hold.
     */
    val attributeMap: List<TelemetryAttributeBinding> = listOf(
        TelemetryAttributeBinding(
            key = "run.id",
            upstreamAttribute = "cicd.pipeline.run.id",
        ),
        TelemetryAttributeBinding(
            key = "stage.index",
            upstreamAttribute = null,
        ),
        TelemetryAttributeBinding(
            key = "step.index",
            upstreamAttribute = null,
        ),
        TelemetryAttributeBinding(
            key = "script.origin",
            upstreamAttribute = null,
        ),
        TelemetryAttributeBinding(
            key = "failure.class",
            upstreamAttribute = null,
        ),
    )

    /**
     * The OpenTelemetry Java types that may appear in the ABI this repository
     * publishes.
     *
     * It is empty: a published contract resolves no OpenTelemetry type, so a
     * consumer of it resolves none either. A type named here is a type a
     * consumer has to resolve, and naming one is a decision about the
     * consumer's classpath rather than about the telemetry.
     */
    val otelTypesInPublishedAbi: Set<String> = emptySet()

    init {
        validate()
    }

    /**
     * The refusals of a map that does not describe the vocabulary it claims
     * to describe.
     *
     * A malformed map is refused rather than repaired: the reader of a map
     * that silently dropped a row cannot tell which facts the upstream
     * carries, and a repaired map says something the product never decided.
     */
    private fun validate() {
        val mapped = attributeMap.map { it.key }
        require(mapped.size == mapped.toSet().size) {
            "the attribute map names a key twice: a key has ONE row"
        }

        val published = TelemetryIdentityTable.Default.allowedAttributeKeys()
        require(published == mapped.toSet()) {
            val missing = published - mapped.toSet()
            val extra = mapped.toSet() - published
            "the attribute map does not describe the published vocabulary: " +
                "the keys it does not name are ${missing.sorted()}, and the keys it names that the " +
                "vocabulary does not are ${extra.sorted()}"
        }

        val upstream = attributeMap.mapNotNull { it.upstreamAttribute }
        require(upstream.none { it.isBlank() }) {
            "the attribute map names a blank upstream attribute: a blank name is not a name"
        }
        require(upstream.size == upstream.toSet().size) {
            "the attribute map names the same upstream attribute twice: an upstream attribute " +
                "carries ONE fact"
        }
    }
}
