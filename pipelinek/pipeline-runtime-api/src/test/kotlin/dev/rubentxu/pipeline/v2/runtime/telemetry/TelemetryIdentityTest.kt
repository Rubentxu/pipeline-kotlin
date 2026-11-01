package dev.rubentxu.pipeline.v2.runtime.telemetry

import dev.rubentxu.pipeline.v2.domain.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The table of identities is the one place that says who owns each identity
 * and what it may be used for.
 *
 * ## La garantía bajo prueba
 *
 * Every identity of the product has exactly ONE authority, and the
 * identities that look alike are not interchangeable: the run id is the
 * engine's, the correlation id is the invoker's, the fencing token is the
 * lease's, and the historical trace id is the projection's — while the live
 * trace id belongs to the propagator. The half that matters is the one that
 * is easy to lose: the historical trace id (a digest of the run id) and the
 * live W3C trace id have the SAME SHAPE — 32 hexadecimal characters — so a
 * consumer holding one of them cannot tell which it holds by looking, and a
 * producer that mixes them publishes a synthetic trace as if it were a real
 * one.
 *
 * The guarantee is that the table REFUSES a statement that breaks those
 * rules, and that the shipped table says the truth about every identity.
 *
 * ## La capacidad que esta garantía sirve
 *
 * The adapter that speaks OpenTelemetry and the exporter that renders the
 * historical export both have to answer the same question before they
 * publish anything: WHICH identity am I holding, and what may I do with
 * it. Without one answer, each of them grows its own.
 *
 * ## Por qué es UNA fila y no dos
 *
 * The refusals all come from the SAME table — the validator — and a mutation
 * of the validator turns one of them off. Three separate rows would say
 * three times how much broke and not once what broke.
 *
 * The row is therefore one, and the reading of it is split into named
 * helpers: they say WHAT of the table is being read, and the row says that
 * all of it has to hold at once.
 */
class TelemetryIdentityTest {

    /**
     * The shipped table says who owns each identity, what it may be used
     * for, and where it may travel; and a table that breaks the rules is
     * refused, naming the identity it is about.
     */
    @Test
    fun `the shipped table names the authority of every identity and refuses a table that breaks the rules`() {
        assertTheDurableIdentitiesHaveTheirAuthority()
        assertTheTwoTraceWorldsDoNotShareASlot()
        assertTheVocabularyIsClosed()
        assertATableThatBreaksTheRulesIsRefused()
    }

    /**
     * The identities that live in the journal: the run id, the correlation
     * id, the operation id, the attempt id and the fencing token.
     */
    private fun assertTheDurableIdentitiesHaveTheirAuthority() {
        val run = rule(TelemetryIdentityKind.RUN_ID)
        assertEquals(
            TelemetryIdentityOwner.ENGINE,
            run.owner,
            "el run id lo acuña el motor: es la identidad durable de la ejecución",
        )
        assertEquals(
            TelemetryTravel.Attribute("run.id"),
            run.travel,
            "el run id viaja como el atributo 'run.id', que es la llave con la que se une una traza " +
                "histórica con su ejecución",
        )
        assertTrue(
            TelemetryIdentityUse.CAUSALITY in run.forbiddenUses,
            "el run id NO es una identidad de causalidad distribuida: no es un traceparent ni un span id",
        )

        assertEquals(
            TelemetryIdentityOwner.INVOKER,
            rule(TelemetryIdentityKind.CORRELATION_ID).owner,
            "el correlation id lo asigna el invocador: es la llave del lado de quien invoca",
        )
        assertEquals(
            TelemetryTravel.Nowhere,
            rule(TelemetryIdentityKind.CORRELATION_ID).travel,
            "el correlation id vive en el journal y en los eventos, no en la exportación de telemetría",
        )
        assertTrue(
            TelemetryIdentityUse.WRITE_AUTHORITY in rule(TelemetryIdentityKind.CORRELATION_ID).forbiddenUses,
            "el correlation id NO autoriza una escritura: es una llave de unión, no un permiso",
        )

        assertEquals(
            TelemetryIdentityKind.RUN_ID,
            rule(TelemetryIdentityKind.OPERATION_ID).derivedFrom,
            "el operation id se acuña a partir del run id: es el efecto en el journal, no un span",
        )
        assertEquals(
            TelemetryIdentityKind.RUN_ID,
            rule(TelemetryIdentityKind.ATTEMPT_ID).derivedFrom,
            "el attempt id se acuña a partir del run id",
        )

        val fencing = rule(TelemetryIdentityKind.FENCING_TOKEN)
        assertEquals(
            TelemetryIdentityOwner.LEASE,
            fencing.owner,
            "el fencing token lo emite la factura: es el permiso de escritura bajo lease",
        )
        assertEquals(
            setOf(TelemetryIdentityUse.WRITE_AUTHORITY),
            fencing.allowedUses,
            "el fencing token autoriza UNA cosa y nada más",
        )
        assertEquals(
            TelemetryTravel.Nowhere,
            fencing.travel,
            "el fencing token nunca viaja: es un permiso, y un permiso que viaja es un permiso robado",
        )
    }

    /**
     * The historical trace and the live trace: two things that look the
     * same and may never be published as if they were one.
     */
    private fun assertTheTwoTraceWorldsDoNotShareASlot() {
        val historical = rule(TelemetryIdentityKind.HISTORICAL_TRACE_ID)
        assertEquals(
            TelemetryIdentityOwner.PROJECTION,
            historical.owner,
            "el trace id histórico lo deriva la proyección histórica",
        )
        assertEquals(
            TelemetryIdentityKind.RUN_ID,
            historical.derivedFrom,
            "el trace id histórico es un digest del run id, y de nada más",
        )
        assertTrue(
            TelemetryIdentityUse.CAUSALITY in historical.forbiddenUses,
            "el trace id histórico NO es causalidad distribuida: no es el trace id de una traza viva",
        )

        assertEquals(
            TelemetryIdentityOwner.PROPAGATOR,
            rule(TelemetryIdentityKind.LIVE_TRACE_ID).owner,
            "el trace id vivo lo trae el propagador en la frontera",
        )
        assertEquals(
            TelemetryTravel.LiveTraceField,
            rule(TelemetryIdentityKind.LIVE_TRACE_ID).travel,
            "el trace id vivo ocupa el hueco W3C del trace id",
        )
        assertEquals(
            TelemetryTravel.LiveSpanField,
            rule(TelemetryIdentityKind.LIVE_SPAN_ID).travel,
            "el span id vivo ocupa el hueco W3C del span id",
        )

        assertTrue(
            rule(TelemetryIdentityKind.HISTORICAL_TRACE_ID).travel != rule(TelemetryIdentityKind.LIVE_TRACE_ID).travel,
            "el trace id histórico y el trace id vivo ocupan huecos DISTINTOS: publicarlos en el " +
                "mismo hueco es confundirlos",
        )
        assertTrue(
            TelemetryIdentityTable.Default.identities.none { it.derivedFrom == TelemetryIdentityKind.LIVE_SPAN_ID },
            "ninguna identidad se acuña a partir de un span id: una identidad durable no deriva de " +
                "un span",
        )
    }

    /**
     * What the signals may carry, and how stable the conventions they
     * follow are.
     */
    private fun assertTheVocabularyIsClosed() {
        assertEquals(
            setOf("run.id", "stage.index", "step.index", "compile.outcome", "failure.class"),
            TelemetryIdentityTable.Default.allowedAttributeKeys(),
            "el vocabulario de atributos es el que se dijo: una identidad, dos índices, un resultado " +
                "y una clase. Los nombres de los pasos, los comandos, los paths y los tokens NO " +
                "están, y no se añaden sin una decisión de producto",
        )
        assertEquals(
            setOf("operation_kind", "status", "failure_class"),
            TelemetryIdentityTable.Default.allowedDimensionKeys(),
            "las dimensiones sólo llevan clases: una etiqueta de métrica nunca es una identidad",
        )
        assertEquals(
            "release-candidate",
            OTEL_SEMCONV_CICD_STATUS,
            "las semconv CI/CD fijadas son una CANDIDATA, no un contrato estable: por eso la " +
                "versión va escrita",
        )
        assertEquals(
            "1.44.0",
            OTEL_SEMCONV_CICD_VERSION,
            "la versión de las semconv está FIJADA: moverla es una decisión de producto y tiene que " +
                "salir en el diff, no seguir a una publicación que nadie pidió",
        )
        assertEquals(
            "pipelinek.",
            PIPELINEK_FIELD_NAMESPACE,
            "los nombres propios del motor viven bajo su namespace: un nombre de 'cicd.' es de la " +
                "especificación y uno de 'pipelinek.' es nuestro y cambia cuando lo digamos",
        )
    }

    /**
     * Every table that breaks a rule is refused, and the refusal says WHICH
     * identity broke and WHICH rule it broke.
     */
    private fun assertATableThatBreaksTheRulesIsRefused() {
        val tampered = listOf(
            // Una identidad durable acuñada a partir de un span id.
            Tampering(
                what = "the operation id minted from a span id",
                build = {
                    tableOf(
                        withRule(
                            rule(TelemetryIdentityKind.OPERATION_ID).copy(
                                derivedFrom = TelemetryIdentityKind.LIVE_SPAN_ID,
                            ),
                        ),
                    )
                },
                names = "OPERATION_ID",
                why = "a durable identity is never derived from a span",
            ),
            // El trace id histórico ocupando el hueco del trace id vivo.
            Tampering(
                what = "the historical trace id in the live trace slot",
                build = {
                    tableOf(
                        withRule(
                            rule(TelemetryIdentityKind.HISTORICAL_TRACE_ID).copy(
                                travel = TelemetryTravel.LiveTraceField,
                            ),
                        ),
                    )
                },
                names = "HISTORICAL_TRACE_ID",
                why = "the same travel slot",
            ),
            // Un uso permitido y prohibido a la vez.
            Tampering(
                what = "the run id allowed and forbidden for causality",
                build = {
                    tableOf(
                        withRule(
                            rule(TelemetryIdentityKind.RUN_ID).copy(
                                allowedUses = rule(TelemetryIdentityKind.RUN_ID).allowedUses +
                                    TelemetryIdentityUse.CAUSALITY,
                            ),
                        ),
                    )
                },
                names = "RUN_ID",
                why = "allowed and forbidden",
            ),
            // Una identidad convertida en etiqueta de métrica.
            Tampering(
                what = "the run id as a metric label",
                build = {
                    tableOf(
                        identities = TelemetryIdentityTable.Default.identities,
                        dimensions = TelemetryIdentityTable.Default.dimensionVocabulary +
                            TelemetryVocabularyEntry("run.id", TelemetryVocabularyRole.CLASS),
                    )
                },
                names = "run.id",
                why = "a metric label is a class, never an identity",
            ),
        )

        for (case in tampered) {
            val refusal = assertThrows(IllegalArgumentException::class.java) {
                case.build()
            }
            val message = refusal.message.orEmpty()
            assertTrue(
                message.contains(case.names),
                "el rechazo nombra la identidad que se rompió (${case.names}) — ${case.what}: el " +
                    "mensaje dice '$message'",
            )
            assertTrue(
                message.contains(case.why),
                "el rechazo dice QUÉ regla se rompió (${case.why}) — ${case.what}: el mensaje dice " +
                    "'$message'",
            )
        }
    }

    /**
     * Un contexto vivo que lleva el trace id histórico de su propia ejecución
     * se RECHAZA: los dos tienen la misma forma y un recolector no puede
     * distinguirlos mirando.
     */
    @Test
    fun `the live context refuses the historical trace id of its own run`() {
        val runId = RunId("run-1")
        val historical = TelemetryTrace.traceIdOf(runId.value)
        val live = "0123456789abcdef0123456789abcdef"
        val span = "0123456789abcdef"

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            LiveTraceContext.of(runId, historical, span)
        }
        assertTrue(
            refusal.message.orEmpty().contains("HISTORICAL"),
            "el rechazo dice que el trace id es el HISTÓRICO derivado del run id y no uno vivo: el " +
                "mensaje dice '${refusal.message}'",
        )

        // Un trace id vivo que NO es el derivado se acepta: un guard que
        // rechaza todo no protege nada.
        val context = LiveTraceContext.of(runId, live, span)
        assertEquals(live, context.traceId)
        assertEquals(span, context.spanId)

        // Y uno mal formado se rechaza también, con el motivo del formato.
        assertThrows(IllegalArgumentException::class.java) {
            LiveTraceContext.of(runId, "not-a-trace-id", span)
        }
    }

    /**
     * One way of breaking the table, and what the refusal has to say.
     *
     * The table is BUILT inside the refusal, not before it: a table that is
     * malformed is malformed the moment it is constructed, so building it
     * outside would throw before the refusal could be observed.
     */
    private data class Tampering(
        val what: String,
        val build: () -> TelemetryIdentityTable,
        val names: String,
        val why: String,
    )

    private fun rule(kind: TelemetryIdentityKind): TelemetryIdentityRule =
        requireNotNull(TelemetryIdentityTable.Default.ruleOf(kind)) { "the shipped table has no rule for $kind" }

    private fun withRule(replacement: TelemetryIdentityRule): List<TelemetryIdentityRule> =
        TelemetryIdentityTable.Default.identities.map { if (it.kind == replacement.kind) replacement else it }

    private fun tableOf(
        identities: List<TelemetryIdentityRule>,
        dimensions: List<TelemetryVocabularyEntry> = TelemetryIdentityTable.Default.dimensionVocabulary,
    ): TelemetryIdentityTable = TelemetryIdentityTable(
        identities = identities,
        attributeVocabulary = TelemetryIdentityTable.Default.attributeVocabulary,
        dimensionVocabulary = dimensions,
    )
}
