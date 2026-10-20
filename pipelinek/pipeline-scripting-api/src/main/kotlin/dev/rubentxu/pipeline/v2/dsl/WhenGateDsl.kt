package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.StageDirective
import dev.rubentxu.pipeline.v2.domain.directivekey.WHEN_DIRECTIVE_KEY
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicate
import dev.rubentxu.pipeline.v2.domain.directive.WhenPredicateEncoder

/**
 * The encoded `core.when` stage directive for [predicate].
 *
 * A total, pure construction step: it encodes a DECLARED predicate and nothing
 * else. It performs no I/O, reads no clock, and never manufactures a runtime
 * value, so the DSL describes rather than interprets.
 */
internal fun appendWhenGate(predicate: WhenPredicate): StageDirective = StageDirective(
    key = WHEN_DIRECTIVE_KEY.value,
    encodedArguments = WhenPredicateEncoder.encode(predicate),
)

/**
 * Gate on every child predicate being satisfied.
 *
 * Lives here rather than on [StageScope] so the scope's surface stays focused on
 * pipeline construction; the DSL call site is identical.
 */
fun StageScope.whenAll(vararg children: WhenPredicate) {
    whenGate(WhenPredicate.AllOf(children.toList()))
}

/** Gate on at least one child predicate being satisfied. */
fun StageScope.whenAny(vararg children: WhenPredicate) {
    whenGate(WhenPredicate.AnyOf(children.toList()))
}

/** Gate on the negation of a child predicate. */
fun StageScope.whenNot(child: WhenPredicate) {
    whenGate(WhenPredicate.Not(child))
}
