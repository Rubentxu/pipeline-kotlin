# SH-VAR-SCOPE-CONTRACT — Proposal

**Author:** SDDK orchestrator (F1 phase, option B per 2026-09-20 operator decision).
**Base:** `main` @ `1fdd3dce`.
**Upstream WU:** continuation of `SH-VAR-SCOPE.S0..S2` already closed on `main`
(commits `eff39dbe`, `83882467`).

## Intent

Make explicit the runtime contract between Kotlin source-text authoring and
shell-time variable expansion inside `sh(...)` calls, so that future Steps and
plugins can rely on documented behaviour instead of rediscovering the seam.

This is a **documentation + contract-test deliverable** (F1). It does NOT modify
`ScriptTextEscaper`, `EnvVarNameExtractor`, the Kotlin compiler, the `core.sh`
handler, or any public DSL API. Code-touching fall-out, if any, belongs to a
gated F2 change produced from empirical reproduction inside F1.

## Why now

SH-VAR-SCOPE.S0..S2 closed the empirical characterisation
(`v2/docs/f5-2/sh-var-scope/CHARACTERISATION.md`, 620 lines) and the minimal
correction (doc-comment + negative fixture). Six gaps listed in §2.3 of that
document are still open:

| Gap # | Summary | Source ref |
|---|---|---|
| 1 | No test covers a Kotlin local whose name shadows a shell var. | CHARACTERISATION.md §2.3 item 1 |
| 2 | No test covers `$VAR` outside any `withCredentials` block. | §2.3 item 2 |
| 3 | No test covers shell-specific expansions (`${VAR:-default}`, `$(cmd)`, `${VAR##pat}`). | §2.3 item 3 |
| 4 | No test covers multi-line strings, in particular `"""..."""` raw triples with `$VAR`. | §2.3 item 4 |
| 5 | No test verifies the diagnostic line/column when the escaper has shifted positions. | §2.3 item 5 |
| 6 | `$VAR`-prefixed env names in `withEnv` vs Kotlin literal-dollar escapes — separate layers, no test. | §2.3 item 6 |

Without an explicit contract and reproducible tests, the next SH family (`sh`
invoked by future Steps — Gradle/Maven/Node/etc. as per WU-LPR-062/063/064) will
discover these one plugin at a time.

## Scope firewall

**In scope (F1):**

- A single source of truth for the contract. Not "more docs"; one doc with
  evidence per claim, readable by an advanced author.
- Contract tests for each gap 1..6 (where reproduction is possible in-process)
  + the still-missing "Form F" raw triple-quoted case.
- OpenSpec change artefacts: `proposal.md`, `spec.md`, `design.md`, `tasks.md`.
- Capture of any reproduction found by the contract tests into the same
  document with SHA-256 of each captured log.

**Out of scope (gated F2, NOT this change):**

- Modifying `ScriptTextEscaper.escape`.
- Extending `EnvVarNameExtractor` to read `withEnv` blocks. (Operator
  decision 2026-09-20.)
- Adding an offset map for diagnostics (operator decision: only if F1
  reproduces a real deviation).
- Adding a new `shellScript { ... }` API.
- Adopting Jenkins Groovy semantics (option D — excluded by §3 of the
  characterisation).

## What the operator authorised on 2026-09-20

Verbatim from the operator:

> P1 — Cómo representar `$VAR`: no aprobaría sustituir globalmente
> `${'$'}VAR` por `\$VAR`. En un string Kotlin ordinario, `"\$VAR"` expresa un
> dólar literal; en un string multilínea `"""..."""`, la barra invertida no
> escapa `$`, por lo que `${'$'}VAR` sigue siendo una forma válida de expresar
> ese dólar literal. La elección depende del tipo de literal y de los bytes
> que realmente recibe `sh`. Si la caracterización ha identificado una trap
> form, debemos corregir exactamente esa forma, no declarar defectuosa toda
> aparición de `${'$'}`.

> P2 — Contrato: parto de A como base documental, con B restringida a las
> transformaciones justificadas por fallos reproducidos. E —mapa de
> offsets— queda condicionada a que las pruebas demuestren una desviación real
> de los diagnósticos. No aprobaría ampliar automáticamente
> `EnvVarNameExtractor` a `withEnv`: conocer el nombre de una variable de
> entorno no autoriza a reescribir todas las referencias homónimas de Kotlin.
> C queda como alternativa para evaluar únicamente si la sintaxis ordinaria no
> permite ofrecer una experiencia suficientemente clara.

> Así evitamos que un cambio aparentemente pequeño en el escaper determine por
> accidente el lenguaje de nuestra DSL. El siguiente entregable debe decirnos
> qué contrato queremos garantizar y cuáles de los seis huecos requieren
> realmente tocar código.

The four artefacts in `openspec/changes/sh-var-scope-contract/` translate that
decision into:

- `spec.md` — the matrix of decisions, one per gap.
- `design.md` — how the gated decisions are triggered and by what evidence.
- `tasks.md` — F1 done now, F2 only triggered.

## Approach (high-level)

1. Read the four artefacts that already exist (this change + S2 receipt +
   characterisation + LB-02 A4_2 capability receipt).
2. For each gap, decide on the spot:
   - **DOC only** (the contract says "user responsibility", with no test).
   - **CONTRACT TEST** (the contract says "guaranteed this exact behaviour",
     with an in-process reproducing test).
3. Single source of truth: `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md`
   referencing each gap's evidence by SHA-256 hash of the captured log.
4. No production code change.

## Evidence reuse (do NOT re-run)

Already fresh on `main`:

- `eff39dbe` SH-VAR-SCOPE.characterisation (14 scenarios, raw terminal output
  in `CHARACTERISATION.md §5`).
- `83882467` SH-VAR-SCOPE.S2 (Forms A..E three-phase probe, byte-level).
- `ScriptTextEscaperTest` (in `v2/pipeline-scripting-kotlin24/src/test/...`).
- `EnvVarNameExtractorTest` (same).
- `WithCredentialsCompileIntegrationTest` (in `pipeline-application`).
- `LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY.md` (the capability surface
  already covers SHELL_OPERATIONS env injection path).

To be produced fresh in F1 (small, targeted):

- New Form F probe case (raw triple-quoted `$VAR`).
- One in-process contract test per gap 1..6 where reproduction is in-process.
- One installed binary run demonstrating the Form F path end-to-end.

## Next step

`spec.md` — gap-by-gap decision matrix.

---

## Operator guard addendum (2026-09-20T08:16Z, with cycle GO)

The operator approved F1 in block with three precise guard rails that
the implementation MUST honour. They are recorded here so that any
later cycle reopening this change cannot accidentally widen scope.

### Guard 1 — `DOC only` is not abandonment

For Gap #1 (Kotlin local shadowing a shell var) the contract links to
the byte-level S1 evidence already present in
`CHARACTERISATION.md §5.2` (s1-05a / 05b / 05c), and treats that
characterisation as the single reference. **No new test** equivalent to
those scenarios is authored.

For Gap #6 (`withEnv` runtime overrides), the contract documents
explicitly: "`withEnv` does not activate any automatic protection or
transformation of `$VAR` references inside `sh("...")`". This is the
**contract**, not a gap. **`EnvVarNameExtractor` MUST NOT be extended**
in F1, F2, or any later slice of this change.

### Guard 2 — bytes, not aspect

The Form F (raw triple-quoted) probes F1, F2 and F3 MUST verify the
**actual byte sequence** at each of four measurable layers, not rely on
how a literal looks in source:

1. **Kotlin source literal** — ordinary `"..."` vs raw `"""..."""`.
2. **Post-Kotlin-compile bytes** — the string value Kotlin produces.
3. **Bytes the shell receives** — captured from `capturedStdout` of an
   installed-binary run of `pipelinek run` against a fixture.
4. **Shell expansion semantics** — what bash does with the bytes
   (expands, ignores, errors).

If a probe finds the bytes diverge from expectation, the probe records
the deviation; it does NOT modify `ScriptTextEscaper`. **`ScriptTextEscaper`
MUST remain untouched during F1.** Discovering a gap is enough; closing
it belongs to a separately numbered change under its own GO.

### Guard 3 — F2 is conditional, not automatic

F2 (the offset-map work) is opened **only** when the Gap #5 reproduction
demonstrates a concrete diagnostic deviation. The predicate is exactly:

```text
F2_TRIGGER = (Gap05Test reproduces a deviation where
              editor_position != mapDiagnostic_position
              AND that deviation causes user-perceivable friction)
```

If `F2_TRIGGER` is NO, the contract remains as documented and the gap
is recorded as a known, measured limitation. F2's body remains a
single sentence until the predicate is satisfied.

**Pre-authorised in F1, F2 or this cycle's later slices:** nothing.

**NOT pre-authorised (require a new proposal under their own GO):**
- any new DSL API (e.g. `shellScript { ... }`);
- any change to the meaning of existing scripts (e.g. global rewrite of
  `${'$'}VAR` to `\$VAR`);
- any change to `ScriptTextEscaper`;
- any change to `EnvVarNameExtractor`;
- any change to `Kotlin24ScriptingHost.mapDiagnostic`;
- any architectural fitness change;
- any production code change in `pipeline-application`,
  `pipeline-domain`, or `pipeline-step-sdk`.

### Closing principle (operator verbatim)

> Con esto avanzamos sobre el trabajo existente y obtenemos primero un
> contrato verificable de `sh`, sin arriesgar el motor por una
> corrección prematura del escaper.

This is the invariant: `sh` first gets a verifiable contract; the
motor is not touched to chase a premature escaper rewrite.

