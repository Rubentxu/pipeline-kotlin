# Gradle refinement traceability

**Status:** Proposed requirements; implementation acceptance NOT_RUN. GR labels are bundle planning identifiers, not registered SDDK requirements. Existing canonical compiler/SDK/roadmap authorities must adopt them through their normal process.

M = mandatory correctness. MEM = enabled bounded memory reuse. PER = enabled persistent reuse. SES = enabled existing-owner sessions. A mixed row has mandatory boundary laws and conditional reuse cases; the UAT/AAT plan declares each branch. A disabled optimization uses an explicit NO-GO/NOT_APPLICABLE disposition, never PASS. Every enabled mode requires its whole applicable suite and exact-candidate evidence, not only a row below.

| ID | Normative contract | Proposed ADR | Work units | UAT | AAT | Scope |
| --- | --- | --- | --- | --- | --- | --- |
| GR-001 | [Spec 19 §1–2: canonical ports and phases](../specifications/19-script-compilation-and-evaluation.md) | ADR-EVO-011 | WU-M0-03, WU-M7-04 | UAT-001, UAT-054, UAT-055 | AAT-015, AAT-022 | M |
| GR-002 | [Spec 19 §2: compilation does not evaluate body](../specifications/19-script-compilation-and-evaluation.md) | ADR-EVO-011 | WU-M7-04 | UAT-054 | AAT-022 | M |
| GR-003 | [Spec 19 §2/6: fresh evaluation and frontend semantics](../specifications/19-script-compilation-and-evaluation.md) | ADR-EVO-011, ADR-EVO-012 | WU-M7-04, WU-M7-03 | UAT-055, UAT-056, UAT-059 | AAT-015, AAT-021, AAT-025 | M; MEM/PER warm branches |
| GR-004 | [Spec 09 §3–4: complete effective compilation identity](../specifications/09-cachekey-v2-and-compilation-performance.md) | ADR-EVO-008, ADR-EVO-014 | WU-M7-01 | UAT-050, UAT-051, UAT-052, UAT-053, UAT-072 | AAT-014, AAT-023 | M |
| GR-005 | [Spec 09 §5 and spec 06 §11: immutable admitted bytes](../specifications/06-plugin-and-library-artifact-admission.md) | ADR-EVO-002, ADR-EVO-003, ADR-EVO-008 | WU-M4-05, WU-M5-04, WU-M7-01 | UAT-036, UAT-050, UAT-064 | AAT-010, AAT-023 | M; every enabled reuse mode |
| GR-006 | [Spec 19 §4: one actual compiler profile](../specifications/19-script-compilation-and-evaluation.md) | ADR-EVO-014 | WU-M0-03, WU-M7-01 | UAT-053, UAT-072 | AAT-023, AAT-026 | M |
| GR-007 | [Spec 19 §5: standard reader and JVM deny regression](../specifications/19-script-compilation-and-evaluation.md) | ADR-EVO-014 | WU-M0-03, WU-M7-04 | UAT-070, UAT-071, UAT-072 | AAT-026 | M |
| GR-008 | [Spec 19 §6 and spec 08 §8: honest validate/pure typed planning](../specifications/08-plan-why-not-and-inline-invocation.md) | ADR-EVO-006, ADR-EVO-011 | WU-M2-04, WU-M7-04 | UAT-012, UAT-013, UAT-055, UAT-073 | AAT-008, AAT-015, AAT-022 | M |
| GR-009 | [Spec 19 §7 and spec 07 §10: diagnostic fidelity](../specifications/07-introspection-diagnostics-lsp.md) | ADR-EVO-007, ADR-EVO-014 | WU-M6-01, WU-M7-04 | UAT-040, UAT-041, UAT-062, UAT-071 | AAT-016, AAT-026 | M; enabled warm branches |
| GR-010 | [Spec 20 §2: code/metadata, no evaluated invocation state](../specifications/20-compiled-artifact-cache.md) | ADR-EVO-011, ADR-EVO-012 | WU-M7-04, WU-M7-03, WU-M7-05 | UAT-055, UAT-056, UAT-058, UAT-059 | AAT-021, AAT-025 | M boundary; MEM/PER retention |
| GR-011 | [Spec 20 §3/5: safe lookup, complete publication and invalidation](../specifications/20-compiled-artifact-cache.md) | ADR-EVO-012 | WU-M7-03, WU-M7-05 | UAT-056, UAT-057, UAT-058, UAT-059, UAT-065, UAT-066 | AAT-023, AAT-024, AAT-025 | MEM/PER; disk mechanics PER |
| GR-012 | [Spec 20 §3 and spec 06 §11: current admission on hits](../specifications/20-compiled-artifact-cache.md) | ADR-EVO-003, ADR-EVO-012 | WU-M3-02, WU-M7-03, WU-M7-05 | UAT-020, UAT-063, UAT-064 | AAT-005, AAT-028 | M admission law; enabled reuse branches |
| GR-013 | [Spec 21 §1–2: reuse inside existing owner only](../specifications/21-compiler-session-lifecycle.md) | ADR-EVO-013 | WU-M7-06 | UAT-080 | AAT-027 | SES |
| GR-014 | [Spec 21 §3–5: lifecycle, cancellation and enforced budgets](../specifications/21-compiler-session-lifecycle.md) | ADR-EVO-013 | WU-M7-06, WU-M7-03 | UAT-057, UAT-058, UAT-080, UAT-081, UAT-082 | AAT-025, AAT-027 | SES; MEM borrower/retention branches |
| GR-015 | [Spec 04 §13: precompiled authoring and complete graph](../specifications/04-shared-libraries-2.0.md) | ADR-EVO-001, ADR-EVO-002, ADR-EVO-003 | WU-M4-02, WU-M4-05, WU-M5-01, WU-M5-04 | UAT-030, UAT-031, UAT-032, UAT-033, UAT-036 | AAT-004, AAT-009, AAT-010, AAT-023 | M |
| GR-016 | [Spec 05 §13 and spec 07 §10: current facts, shared projections](../specifications/05-affordance-api-and-cli-discovery.md) | ADR-EVO-004, ADR-EVO-005, ADR-EVO-007 | WU-M2-01, WU-M6-01, WU-M8-03 | UAT-010, UAT-014, UAT-041, UAT-061 | AAT-007, AAT-026 | M for advertised facts |

## Adoption receipts

The implementation receipt adds candidate/source/distribution digests, requirement disposition, actual test result and diagnostic/resource evidence. Register accepted requirements/ADRs in the existing authority; do not infer acceptance from this ZIP.

The [benchmark protocol](gradle-benchmark-protocol.md) and WU-M7-02 add separate measured MEM/PER/SES decisions after actual correctness/representation tests. SLO approval belongs to existing WU-RP-022 governance. Optional loaded-class reuse additionally needs static-state isolation evidence; bytecode reuse alone does not certify it.

M0–M10 milestones, original tests, compatibility obligations and final V1 deletion remain. UAT-090/100 and AAT-018/019/020 certify retirement at the terminal M10; compiler optionality cannot excuse retaining V1.
