package dev.rubentxu.pipeline.v2.harness.plan

import java.nio.file.Path

/**
 * Test support for the H1 TODO 2 `adjudicate` shape change.
 *
 * H1 TODO 2 made `adjudicate` consume a
 * `Map<String, EvidenceReader.SuiteEvidence>` instead of a
 * `Set<String>`. Each suite's evidence must carry an
 * [EvidenceManifest] (the sidecar). The pre-existing
 * adversarial tests pass a `Set<String>`; this helper
 * bridges the two shapes so the existing test surface
 * keeps asserting the same properties.
 *
 * The bridge:
 *   - For each suite in [plan.suites], build a
 *     [EvidenceReader.SuiteEvidence] with a manifest
 *     whose `sourceCommit` is [expectedCommit] and whose
 *     `hf` is the plan's first minimum fidelity (or HF0
 *     when the plan has no fidelities). The fidelity
 *     choice is what makes the "clean" test pass without
 *     an `InsufficientFidelity` refusal.
 *   - Suites in [executed] but NOT in [plan.suites] are
 *     ignored (they were never selected; they cannot
 *     discharge anything).
 *   - Suites in [plan.suites] but NOT in [executed] are
 *     omitted from the map; the gate's
 *     `SelectedButNotRun` refusal fires for them. This
 *     is the same shape the previous `Set<String>`
 *     argument produced.
 *
 * The bridge is NOT a production code path. Tests are
 * the only callers; production code reads
 * [EvidenceReader.suitesWithEvidence] and passes the
 * real map. The bridge's job is to keep the in-process
 * test surface focused on the property under test, not
 * on the manifest's wire format.
 *
 * ## The key this bridge used to get wrong
 *
 * An earlier version of this helper keyed its map with
 * `suite.filter` — the simple class name — while the
 * production producer keys by the FQCN the report
 * declares and [ImpactResolver.adjudicate] looks that key
 * up. Every binding then defaulted its `fqcn` to
 * `filter`, so the bridge agreed with the consumer and
 * the whole adversarial suite stayed green while
 * production refused every selected suite forever.
 *
 * That agreement was manufactured here, which is the
 * point: a helper that builds its own input to match the
 * code under study cannot detect the two disagreeing.
 * The seam now has its own test
 * ([EvidenceKeyAgreementTest]), which calls the real
 * producer and the real consumer in sequence, and the
 * bridge keys by `fqcn` so that it models production
 * rather than flattering it.
 */
internal fun adjudicateEvidence(
    plan: ValidationPlan,
    executed: Set<String>,
    expectedCommit: String,
    sourceCommit: String = if (expectedCommit.length == 40) expectedCommit else "a".repeat(40),
): Map<String, EvidenceReader.SuiteEvidence> {
    val selected = plan as? ValidationPlan.Selected ?: return emptyMap()
    val requiredHf = selected.minimumFidelities.firstOrNull()?.name ?: "HF0"
    val placeholderBinarySha = "0".repeat(64)
    return selected.suites
        .filter { it.filter in executed }
        .associate { suite ->
            suite.fqcn to EvidenceReader.SuiteEvidence.WithManifest(
                xml = Path.of("/nonexistent/${suite.fqcn}.xml"),
                manifest = EvidenceManifest(
                    className = suite.fqcn,
                    gradlePath = ":test-harness",
                    invocation = "gradle :test-harness:test --tests ${suite.filter}",
                    binarySha256 = placeholderBinarySha,
                    hf = requiredHf,
                    sourceCommit = sourceCommit,
                ),
            )
        }
}
