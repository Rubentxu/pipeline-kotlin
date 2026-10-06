# Migration and compatibility strategy

## 1. Principle

Migration is **strangler-style around V2**, not adapter-heavy resurrection of V1.

For every V1 idea, record one disposition:

- `MIGRATED` — capability rebuilt natively on V2;
- `SUPERSEDED` — V2 already has a better implementation;
- `REJECTED` — V1 idea is deliberately not retained;
- `DELETE` — implementation/reference has no remaining value.

## 2. Current provisional disposition

| V1 area | Disposition |
|---|---|
| Shared Libraries | MIGRATED → Shared Libraries 2.0 |
| LSP server/completions | MIGRATED → LSP v2 over introspection |
| error suggestion engine | MIGRATED → typed actionable diagnostics |
| compilation cache | REJECT V1 implementation; MIGRATE identity/phase correctness; artifact/session reuse only after separate measured GO |
| plugin security validator | REJECT implementation; MIGRATE intent via artifact admission/provenance |
| resource limiter | REJECT JVM implementation; retain only honestly enforceable budgets |
| EventBus/event manager | SUPERSEDED by V2 event spine |
| logger/event console model | SUPERSEDED by output/event separation |
| V1 plugin manager/classloader model | SUPERSEDED by V2 plugin SPI + new pre-load admission |
| compiler plugin step system | SUPERSEDED/rejected for runtime semantics; V2 KSP metadata only |
| service locator/context | REJECTED |
| Docker/Kubernetes agents | OUT OF THIS PROGRAMME |
| generic lookup language | REJECTED; typed config/utility/credential models instead |

## 3. Compatibility windows

### `--plugin-jar`

Keep through M9. Internally adapt to resolved artifact identity. Deprecation/removal is not required by this programme unless another accepted ADR replaces it.

### LSP metadata v1

Keep loader compatibility while LSP v2 is introduced. A richer schema is additive/version-negotiated.

### CacheKey v1

Keep decoding/reading only where persisted historical event data requires it. New compilations switch to v2 at an explicit version boundary; never reinterpret v1 values as v2.

### CLI JSON

New Affordance API starts at `formatVersion 1.0`; minor additive fields do not break clients.

## 4. No dual runtime semantics

Compatibility adapters may translate inputs/outputs. They may not select a V1 execution engine.

## 5. Deprecation evidence

A surface may be deleted only when:

- repository search shows no production callers;
- compatibility corpus no longer requires it or has migrated fixture;
- installed distribution passes equivalent user scenario;
- documentation points to the replacement;
- removal is called out in release/migration notes.

## 6. Compiler migration compatibility

Preserve `ScriptingHost.compile` compile-and-evaluate consumers initially; implement canonical phases internally, inventory `ScriptCompilationResult` and `CompiledScriptedEntryPoint` consumers and version any public ABI migration. Test both frontends and validate's real construction contract.

Keep historical v1 events readable, but bypass/recompile an entry unable to prove v2 profile/format identity. Identity key versions, backend artifact formats and replay fingerprints are distinct versioned contracts. A cache hit recreates invocation context, current admission and source mapping; it never reuses secrets/Step results/old failures.

An optional cache/session NO-GO remains explicit in M9 disposition. M10 stays the final legacy gate; optional optimization choices do not manufacture a migration PASS.
