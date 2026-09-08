# Mini-ADR — CDE.3-e: Registry Durable Input Envelope

Status: DECIDED (slice e1, grounding + decision). No production rewiring in this ADR.
Scope: `lfc2-step-constitution-plugin-seam` (CDE.3-e). Amends ADR-0070 (open Step registry)
and the CDE durable-input contract. Companion to `CDE_HANDOFF.md`.

## Context

A registered step (`StepDefinition<I, O>` via a `StepRegistry`) must be able to traverse the SAME
durable spine as a legacy core step: structural prepare -> durable resolution -> Execute ->
`StepRegistry` + `inputCodec.decode(I)` -> `PreparedRegistryExecution` -> `CommonExecutionBoundary`
-> handler -> `StepOutcome`. This requires a canonical, durable, lossless, step-agnostic
representation of the raw registry input as it is carried from the structural invocation to
`StepCodec<I>.decode`, with the durable engine remaining ignorant of `I` and `O`.

The concrete blocker that stopped d5c/d5d (`eed96afe`) is: the durable spine's structural gate
requires `StepNode.payload.encoded` to be a well-formed `dsl-v1` JSON OBJECT, while the registry
seam's own seam-level tests drive an `inputCodec` that encodes raw, non-JSON text (e.g.
`EncodedStepValue("ok:hello")`). This ADR grounds the real types and fixes the durable envelope so
registry steps can flow through the spine honestly.

## Grounding (evidence: real types read, not inferred)

### Fact sheet

| Concepto      | Representación actual | Persistido | Fingerprint | Interpretado antes de Execute |
| ------------- | --------------------- | ---------: | ----------: | ----------------------------: |
| stepKey       | `OperationInput.stepId` / `StepNode.pluginStepId` (String) | journal via `OperationInput.stepId` | sí (campo) | sí (routing por key en metadata resolver, d5b) |
| kind (core vs registry) | NO existe campo durable; se decide por pertenencia de key (catálogo core vs `StepRegistry`) en el resolver composite | no (derivado) | no | sí (routing estructural por key) |
| schemaVersion | `VersionedStepPayload.schemaVersion == "dsl-v1"` | en el payload del nodo compilado | no (no entra a `OperationInput`; el gate lo lee del nodo) | sí (gate exige `dsl-v1`) |
| raw input (encoded) | `StepNode.payload.encoded: String` == `EncodedStepValue.value` (invocación `fromNode` lo proyecta verbatim) | sí: `OperationInput.params["payload"] = JsonPrimitive(payload.encoded)` (string) | sí: el fingerprint SHA-256 canónico se computa sobre `OperationInput` (params con `payload` string) | NO semánticamente (gate solo exige que sea JSON object; ningún campo se lee) |
| typed input | tipo concreto `I` (ej. `EchoInput`) | NO | no | no |

### Tipo real `EncodedStepValue` (`domain/step/StepRegistry.kt`)

Single `@JvmInline value class EncodedStepValue(val value: String)`; `init` require `value` non-empty.
No variants. Doc: "Canonical serialized form of a step input/output payload (**JSON today**)."
=> There is exactly one real variant (a String). Per user rule, do NOT invent a tagged union.

### `StepCodec<I>.decode` expectation (`StepCodec<T>`)

`symmetry` seam: `decode(encode(value)) == value`. `decode` receives exactly an `EncodedStepValue`.
Durable transport must hand `decode` an `EncodedStepValue` whose `value` is exactly the durable
payload string it was authored with (lossless identity), and must NOT re-encode or infer.

### Shape of `StepNode.payload.encoded` + `dsl-v1` scope

- `CanonicalInvocation.fromNode` (application) sets `encodedInput = EncodedStepValue(node.payload.encoded)`:
  the registry strategy feeds this **unchanged** to `inputCodec.decode` (doc, same file).
- `CanonicalStructuralPreparation.prepare` gate (`CanonicalInvocation.kt`): for EVERY spine step,
  `schemaVersion == "dsl-v1"` AND `payload.encoded` must parse as a JSON **object**. No field is read.
- `dsl-v1` is the per-step payload **schema version** carried in `VersionedStepPayload`; structurally it
  additionally asserts the payload is a JSON object. It does NOT version whole pipelines and does NOT
  carry a registry-input sub-version.
- For legacy core steps, `payload.encoded` is the `dsl-v1` canonical object decoded by
  `CanonicalCoreStepDecoder`. That closed decode world is separate from the registry codec world.

### Real mismatch (the d5c/d5d blocker, evidence)

`CoreEchoStep.inputCodec.encode(EchoInput("hi")) = EncodedStepValue("hi")` (raw text, non-JSON).
Legacy/core echo in the compiled IR is a `dsl-v1` JSON object (payload.encoded is an object with the
echo text as a field). So the registry codec's raw-text `EncodedStepValue` would FAIL the spine gate,
which demands a JSON object. The registry seam (`RegistryExecutionPreparation`) currently accepts any
`EncodedStepValue` in isolation, so seam tests pass while the spine path is unreachable.

### Fingerprint / journal authority

Fingerprint = SHA-256 over canonical JSON of `OperationInput(stepId, params{payload:<string>}, runId,
attempt, replayPolicy)` (`Fingerprint.compute`). params value is a JSON **string** containing the whole
encoded payload text. Fingerprint identity is over the exact payload string. Journal persists the
serialized `OperationInput`. => Keeping `payload.encoded` equal to the authored `EncodedStepValue.value`
keeps fingerprint/journal/replay semantics identical to today.

## Decision

**Durable registry input IS the registry `StepCodec<I>.encode(I).value`, stored verbatim as
`StepNode.payload.encoded` under `schemaVersion == "dsl-v1"`, and the durable-spine contract REQUIRES
that `EncodedStepValue` to be a well-formed JSON object.**

Concretely, for a registered step durably executed on the spine:

```text
StructuralInvocation
   payload.encoded == EncodedStepValue( json-object-string )     # authored by the plugin's inputCodec.encode(I)
        |
        v
CanonicalStructuralPreparation   # gate: schemaVersion==dsl-v1 && payload parses as JSON object (passes)
        |
        v
Durable Resolution               # fingerprint/journal over OperationInput.params["payload"] = that exact string
        |
        v Execute
RegistryExecutionPreparation / composite metadata
        |
        v
inputCodec.decode(EncodedStepValue(payload.encoded))            # typed decode ONLY here
        |
        v
I  ->  PreparedRegistryExecution -> CommonExecutionBoundary -> handler -> StepOutcome
```

No new tagged envelope. No wrapper. No `JSON -> String -> JSON`. `EncodedStepValue` is single-variant,
already authoritative-as-JSON, so `payload.encoded` is both the structural JSON object the gate demands
AND the exact `EncodedStepValue` handed to `decode`. Round-trip `encode(I) -> payload.encoded ->
decode` is lossless and step-agnostic (core never reads registry payload fields).

Routing core vs registry remains purely structural **by stepKey membership** via the composite metadata
resolver (d5b): `key ∈ legacy core catalog -> legacy path`; `key ∈ StepRegistry (non-core) -> registry
path`; unknown -> invariant violation. No heuristics infer registry from payload shape.

### Enforced consequence (documented, not silent)

The registry seam's isolated tests may use any `EncodedStepValue` (raw text), because the seam is not
the spine. But **durable-spine eligibility** for a registered step requires an `inputCodec` that emits a
JSON object (what kotlinx-serialization of a payload data class naturally produces). A raw-text codec
(such as the echo fixture) is NOT durable-spine-eligible; it remains valid at the non-durable registry
seam. This constraint is explicit and belongs to the plugin author's durable step contract, not hidden
behind the same schema semantics.

## Invariants / laws preserved (unchanged from today)

- Typed decode ONLY on Execute: replay reuse `codec.decode=0`; divergence `codec.decode=0`;
  fresh typed-invalid `codec.decode=1, commonExecution=0, handler=0` (SCHEMA-like rejection at prepare);
  fresh valid `codec.decode=1, commonExecution=1, handler=1`. Missing capability `handler=0` (fail-closed).
- Structural gate validates ONLY envelope shape / schema / key / JSON-object-ness; NEVER semantic fields.
- `I` and `PreparedRegistryExecution` are ephemeral; the durable authority is the encoded payload string.
- fingerprint / journal / cursor / replay semantics unchanged; no journal-schema migration.

## Alternatives rejected

1. **Tagged union of `EncodedStepValue` variants (Text|Json|Bytes)**. Rejected: the real type has a
   single String variant; per user rule do not invent a union that does not exist.
2. **Wrapper envelope `{encoding, value}` around raw text.** Rejected: introduces
   `JSON -> String -> JSON`, requires prepare to unwrap before `decode` (breaking the
   "payload.encoded == codec payload fed unchanged to decode" invariant), and double-encodes when the
   codec is already JSON. Not needed because the gate only demands JSON-object payloads.
3. **Make the structural gate registry-aware to accept non-JSON registry payloads.** Rejected: would
   make the step-agnostic envelope gate consult the registry, mixing routing into structural
   validation and weakening the pre-decode gate.
4. **Route `core.echo`'s registry codec as its durable form.** Rejected: changes the legacy core
   durable representation; core.echo stays on the closed legacy `dsl-v1` decode world.

## Versioning

No `dsl-v2`. `dsl-v1` keeps meaning "per-step canonical payload schema == JSON object." Registry input
payloads reuse `dsl-v1`; their semantic field schema is plugin-owned (the codec). A plugin changing its
input shape incompatibly is a plugin-release concern (memoized fingerprint replays only on identical
payload string; new payload shape = new operation), orthogonal to the core durable schema.

## Gate for e2..e5 (enabled by this decision)

- **e2**: prove generic round-trip `EncodedStepValue(json-object) -> payload.encoded ->
  EncodedStepValue` lossless + deterministic fingerprint representation.
- **e3**: `StructuralPreparation` yields a registry structural invocation (stepKey + raw encoded input +
  metadata) with `codec calls = 0` and structural-invalid -> SCHEMA.
- **e4**: `StructuralRegistryInvocation -> composite metadata -> registry prepare selector ->
  StepRegistry -> inputCodec.decode -> Rejected | Ready` inside Execute; typed-invalid passes.
- **e5 / d5c+d5d**: full durable laws fresh/replay/divergence/typed-invalid/missing-capability on the
  real spine + L4 + fresh XML.

## Stop condition (unchanged, restated)

Stop before rewiring if this decision proves incompatible (journal schema change, existing fingerprint
semantics break, replay of persisted executions breaks, or canonical identity change). It does not:
registry durable payloads reuse the existing `payload.encoded` + `OperationInput` + `Fingerprint`
contract additively; legacy core payloads untouched.
