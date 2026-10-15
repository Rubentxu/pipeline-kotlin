# ADR-0072 HF ladder — decision ready, awaiting the owner

- **Date:** 2026-10-15
- **Status of this file: NOT an ADR.** It decides nothing. It packages the evidence so the
  owner can pick between the three options already listed in
  `docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md` §R5 instead of re-reading the tree.
- **Authority:** the reconciliation facts live in PIPELINE_TEST_HARNESS §R5 (B1, 2026-10-15);
  this file adds a recommendation and the exact edits per option. `ADR-0072.status` is still
  `proposed`, so no gate may depend on the HF names and no receipt may claim HF conformance.

---

## 1. The facts, in one block

```text
ADR-0072 Decision says     : "pipeline-testkit defines these levels"
Reality                    : no HF0..HF6 type, annotation, registry or constant exists
                             anywhere in the repo (rg 'HF[0-6]' v2 --glob '*.kt' -> nothing)
What DOES exist            : a review vocabulary in documents + UAT suite names, and a real
                             boundary in practice (python suites = HF0, testkit fixtures =
                             HF1, forked-distribution Kotlin tests = HF2, kill/resume
                             characterisations = HF3, sandbox scripts = HF4/5, the external
                             harness = HF6)
Consequence                : R4 ("a property testable at a lower HF must not be escalated")
                             cannot be enforced mechanically; only by review
```

## 2. Options, with their exact cost

| Option | Edits required | Cost | What it buys |
| --- | --- | --- | --- |
| **A** accept ADR-0072 and implement the ladder | `HarnessFidelity` type in `:pipeline-testkit` (sealed ADT/enum + per-level metadata), a fitness test that every suite declares a level, then tagging every Kotlin suite and every `scripts/test_*.py` suite | Largest: touches hundreds of test files and adds a public SDK surface; the tagging work is mechanical but unreviewable in one pass | R4 becomes mechanical; a new suite inherits a required declaration |
| **B** amend ADR-0072 to a review vocabulary | delete the "testkit defines the levels" claim; add one paragraph: the ladder names the minimum faithful level for a review, it is not a type, and gates must not branch on it | Smallest honest change: one document, no code, no gate change | Removes a normative statement that is false about the code; keeps the vocabulary that reviewers already use in pipeline reviews |
| **C** keep as documentation only | none (status quo) | none | Nothing changes; ADR-0072 keeps saying something untrue about testkit, and R4 stays review-only |

## 3. Recommendation (for the owner to accept or reject)

**Option B, now; A only if a falsifiable need appears.** Reasons, in order:

1. The ladder's only operational use so far is *refusing escalation* in review ("this is an
   HF0 property; it does not need a forked distribution"). That is a judgement call reviewers
   make with the vocabulary in front of them; a type would not adjudicate it, it would only
   record it.
2. Option A adds a public SDK surface to `:pipeline-testkit` (see the external plugin
   constitution: a new public construct needs a contract and a certification), and every
   tagged suite becomes a maintenance obligation.
3. Option C is the one state that is strictly worse than B: a `proposed` ADR that asserts a
   false fact is exactly the "silent drop" the Semantic Constitution forbids, applied to
   documentation.
4. If a real need appears (e.g. a gate that must refuse an over-escalated test
   mechanically), A can be re-opened with an executable justification, and the reconciliation
   in §R5 will still be the evidence.

## 4. What would have to be true for A to be right

- a mechanical rule exists that the ladder would enforce and review cannot (candidate: "a
  `scripts/test_*.py` suite must not fork the installed distribution"; that is testable from
  the suite's own source without the HF type);
- the tagging can be done without a mass, unreviewable diff;
- the SDK surface is accepted as part of `:pipeline-testkit`'s public contract.

Until then, option C is what the repository actually is, and this file exists so that
choosing B is a one-commit decision rather than a re-investigation.
