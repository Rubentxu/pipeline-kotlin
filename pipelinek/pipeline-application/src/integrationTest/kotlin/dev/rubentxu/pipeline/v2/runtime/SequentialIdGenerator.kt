package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.domain.IdGenerator

/**
 * ## WHY THIS FILE IS A COPY
 *
 * The engine's own unit tests keep the same helper in
 * `pipeline-runtime/src/test`, and this lane cannot reach it: a test source
 * set is not on another module's classpath, and there is no shared test
 * module to hang it on. The helper is copied rather than moved because
 * moving it would take the engine's unit tests' instrument away from them.
 *
 * A deterministic [IdGenerator] for tests.
 *
 * The engine draws its identities from a port, so a test can supply the ones it
 * wants to see. This one hands out `prefix-0`, `prefix-1`, … in order: the
 * sequence is reproducible, and two calls never return the same value, so a test
 * that asserts WHICH event was published can name it.
 *
 * An INSTANCE and not an object: a shared counter would make one test's
 * identifiers depend on how many tests ran before it.
 */
internal class SequentialIdGenerator(private val prefix: String = "test-id") : IdGenerator {

    private var counter = 0

    override fun next(): String = "$prefix-${counter++}"
}
