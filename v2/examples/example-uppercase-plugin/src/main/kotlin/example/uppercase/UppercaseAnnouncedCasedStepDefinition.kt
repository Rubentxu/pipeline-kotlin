package example.uppercase

import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission

/**
 * BLOCK 1-J — the artefacto del no-core-change proof.
 *
 * This Step exists to be ADDED, and the interesting thing about it is not what it does. It was
 * added to an external plugin with **zero changes to core production code**, and the diff over
 * `v2/<module>/src/main` was empty. That is the whole claim of the External Library Constitution,
 * until now nothing in the repository had demonstrated it as an event rather than as a sentence.
 *
 * ## Why THIS construct and not a simpler one
 *
 * Because "a plugin can add a Step" was already covered three times over by `uppercase`,
 * `uppercaseObserved` and `uppercase.cased`. A fourth simple Step would prove nothing new.
 *
 * This one declares **two** capabilities at once, and they are of different kinds:
 *
 * ```text
 * example.uppercase.case-table   SUPPLIED BY THIS PLUGIN   (a value it hands the host)
 * plugin.event-emission          SUPPLIED BY THE HOST     (a seam only the host has)
 * ```
 *
 * That combination is what makes it a real test of the seam. A Step may depend on a capability it
 * supplies itself AND on one it does not, and the manifest has to say so correctly for both, and
 * the registry has to admit it without knowing either name. If the composition path were secretly
 * single-owner, this Step is the one that fails.
 *
 * ## What it must NOT need
 *
 * No new `StepKey` case in a dispatcher. No branch anywhere in core. No registry edit. If adding
 * this Step had required any of those, the law would be a sentence and this file would be a
 * demonstration that the sentence was false.
 */
object UppercaseAnnouncedCasedStepDefinition : StepDefinition<UppercaseInput, UppercaseOutput> {

    val KEY = PluginStepId("example.uppercase.announcedCased")

    override val contract = StepContract(
        key = KEY,
        descriptor = StepDescriptor(
            stepId = KEY.value,
            name = "uppercase-announced-cased",
            configRef = "",
            pluginId = "example.uppercase",
            pluginVersion = "0.1.0",
            executionLocation = ExecutionLocation.CONTROLLER,
            effects = listOf(Effect.READ_ONLY),
            replayPolicy = ReplayPolicy.MEMOIZED,
        ),
        inputCodec = UppercaseCodec,
        outputCodec = UppercaseOutputCodec,
        // Two seams, two owners, both declared. The order is not significant and the set is not a
        // bag: each element is a distinct capability with a distinct provider, and admission checks
        // this set against the manifest's per-Step declaration in BOTH directions.
        requiredCapabilities = setOf(EXAMPLE_CASE_TABLE_CAPABILITY, PLUGIN_EVENT_EMISSION_CAPABILITY),
    )

    override val handler = StepHandler<UppercaseInput, UppercaseOutput> { input, context ->
        // Two lookups through the same narrow typed port. The handler is handed values, never the
        // thing that produced them, which is why it cannot tell — and must not care — that one of
        // them came from this very JAR.
        val table: CaseTable = context.capabilities.get(EXAMPLE_CASE_TABLE_CAPABILITY)
        val emission = context.capabilities.get<PluginEventEmission>(PLUGIN_EVENT_EMISSION_CAPABILITY)

        val cased = table.apply(input.text)
        // The outcome is not inspected, for the same reason the observed Step does not inspect it:
        // the uppercase work already succeeded and an observation is not the work. Turning either
        // case into a Step failure would be wrong.
        emission.emit(
            "example.uppercase.applied",
            UppercaseApplied(inputLength = input.text.length, outputLength = cased.length),
        )

        UppercaseOutput(value = cased)
    }
}
