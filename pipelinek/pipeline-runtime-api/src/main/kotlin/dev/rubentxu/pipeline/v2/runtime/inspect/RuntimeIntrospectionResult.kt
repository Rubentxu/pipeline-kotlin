package dev.rubentxu.pipeline.v2.runtime.inspect

/**
 * M2 — the result envelope returned by [RuntimeIntrospectionPort.inspect].
 *
 * Closed ADT: one case for an observation, one for a refusal. Mirrors the M1
 * read ports ([dev.rubentxu.pipeline.v2.events.identity.EventRecordReadResult]
 * and [dev.rubentxu.pipeline.v2.output.OutputReadResult]) but with only two
 * cases because the introspection result is pull-by-call (not a follow).
 *
 * The result is a typed ADT, not a nullable. The follow-style ports over the
 * runtime (none exist today) would translate a `Refused` into a terminal event;
 * the introspection port is pull-by-call, so it returns the refusal inline.
 */
sealed interface RuntimeIntrospectionResult {

    /** The inspection succeeded; `observation` is the typed view. */
    data class Observation(val observation: RuntimeObservation) :
        RuntimeIntrospectionResult

    /** The inspection could not be served; `refusal` names the closed reason. */
    data class Refused(val refusal: IntrospectionRefusal) :
        RuntimeIntrospectionResult
}
