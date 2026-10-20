package example.uppercase

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler

/**
 * S6/I — the Step that CONSUMES the plugin's own capability.
 *
 * ## Why a third Step rather than a capability on one of the two existing ones
 *
 * Because both of those are load-bearing references. `uppercase` is the standing proof that an
 * external Step runs asking the host for nothing at all, and `uppercaseObserved` is the standing
 * proof that one asking for exactly one seam gets only that seam. Adding a dependency to either
 * would not extend the demonstration, it would delete one of the two claims it exists to make —
 * and a reference that has quietly acquired a dependency is no longer a reference.
 *
 * So the capability gets the Step that needs it, and the two references stay untouched.
 *
 * ## The loop this closes
 *
 * ```text
 * this JAR supplies example.uppercase.case-table
 *   -> this JAR's manifest declares it
 *     -> this Step declares it as required
 *       -> admission cross-checks all three against each other
 *         -> the handler receives the value and nothing else
 * ```
 *
 * Every arrow crosses a public seam. No arrow requires the host to learn the name `example.*`,
 * which is what BLOCK 1-J measures separately.
 */
object UppercaseCasedStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {

    val KEY = PluginStepId("example.uppercase.cased")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase-cased",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        // The same two codecs as the other two Steps, for the same reason: "text in, text out"
        // is one wire shape, and a third encoding of it would be a second way to say it.
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        // Fail-closed by construction, not by check. A composition that never resolved this
        // plugin's contributor exposes no such capability, so admission refuses the Step before
        // its handler runs; there is no "declared but silently inert" mode, which is the whole
        // reason for declaring instead of reaching.
        requiredCapabilities = setOf(EXAMPLE_CASE_TABLE_CAPABILITY),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, context ->
        // The handler sees the typed capability and nothing else: no registry, no contributor,
        // no plugin id, no way to reach a second one. `get` is a total lookup that throws rather
        // than returning a null the Step would then have to decide what to do with — a Step that
        // passed admission and found nothing here is a defect in admission, not a case to handle.
        val table: CaseTable = context.capabilities.get(EXAMPLE_CASE_TABLE_CAPABILITY)
        UppercaseOutput(value = table.apply(input.text))
    }
}
