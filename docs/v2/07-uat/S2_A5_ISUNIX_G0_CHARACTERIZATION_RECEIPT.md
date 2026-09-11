# S2-A5 / G0 — CHARACTERIZATION: `core.isUnix`

**Slice:** S2-A5 (`core.isUnix`)
**Gate:** G0 — characterization only; **production changes = 0**
**Entry state:** legacy residual **8 / 8 / 8**; `core.isUnix`: REGISTERED/REGISTRY_PRIMARY/LEGACY_UNREACHABLE/LEGACY_REMOVED/CONTRACT_SUITE/CERTIFIED = **all false**
**HEAD:** `902f3b21` (base = current HEAD; no base-SHA divergence — no production change in this slice yet)

## 1. The central finding: TWO INDEPENDENT isUnix computations exist

The user's G0 hypothesis ("the comment says the Step returns true, but the dispatcher returns Success — where does the boolean actually live?") is **confirmed, and stronger than suspected**: there are **two disjoint value paths with different whitelists**.

### Path A — script-visible value (DSL-side, synchronous)

`PipelineDsl.isUnix()` (`pipeline-scripting-api/…/PipelineDsl.kt:1590`):

- Reads `runtimeConfig.osName()` through the RuntimeConfig port (Lfc0GlobalStateFitnessTest forbids global JVM coupling on this side).
- **Does NOT touch the dispatched step's result at all.** It computes the boolean synchronously at script-construction/dispatch time in the scripting host.
- Whitelist (exact membership): `{linux, macos, darwin, sunos, aix, hp-ux, freebsd, openbsd, netbsd}`.
- **Empty `osName` → returns `true` as a placeholder** (documented backward-compat with StubRuntimeConfig).

### Path B — durable observable (dispatcher-side)

`CanonicalIsUnixNodeDispatcher.dispatch`:

- Reads `System.getProperty("os.name", "")` — an **implicit runtime dependency** (no capability, no port).
- Whitelist (substring `contains`): `linux | mac | darwin | freebsd`.
- Emits **`UnixDetected(isUnix, osName, sha256(osName))`** to the event sink; returns `StepOutcome.Success`. **The boolean never returns to the script through the durable path** — it is observable ONLY through the event.
- Metadata: `core.isUnix → ({READ_ONLY}, MEMOIZED)` — consistent with `core.sleep`-style reuse.
- Wire payload: `"{}"` (empty object — compiler `encodePayload` else-branch). `OperationInput.params` is empty.

**Answer to "¿dónde vive realmente el valor booleano observable?":** the script consumes Path A (DSL-side, not durable); the durable path (Path B) publishes the value only as a `UnixDetected` event. There is **no OperationOutput carrying the boolean**.

## 2. Behavior matrix — the two whitelists DIVERGE

Simulated from the exact production code (both whitelists applied to the mandated cases; live-verified `"Linux" → true` on the installed binary):

| `os.name` | DSL (script sees) | Dispatcher (UnixDetected) | MATCH |
| --- | --- | --- | --- |
| `Linux` | true | true | OK |
| `linux` | true | true | OK |
| `Mac OS X` | **false** | **true** | **DIVERGE** |
| `Darwin` | true | true | OK |
| `FreeBSD` | true | true | OK |
| `Windows 11` | false | false | OK |
| `""` | **true** (placeholder) | **false** | **DIVERGE** |
| `unknown` | false | false | OK |
| `SunOS` | **true** | **false** | **DIVERGE** |
| `AIX` | **true** | **false** | **DIVERGE** |
| `OpenBSD` | **true** | **false** | **DIVERGE** |

Five divergent inputs. The mandated matrix is hereby extended: **the real G0 output is not the matrix but the divergence itself.** A G1+ candidate Step must decide which semantics are canonical (this is a `UNKNOWN_DIFFERENTIAL`-class decision for G2, not a silent pick). Note also `contains("mac")` would match a hypothetical `"Smacos"`-style name — substring matching is looser than exact membership.

## 3. Durable characterization answers

- **¿El booleano vuelve realmente al script?** No through the durable path. Script value comes from Path A (DSL-side `runtimeConfig.osName()`), computed synchronously, not journaled.
- **¿Se persiste como OperationOutput?** No. The step produces no typed output; `payload = "{}"`.
- **¿Sólo se observa mediante UnixDetected?** Yes — `UnixDetected(isUnix, osName, sha256)` is the only durable observation of Path B.
- **¿Replay reutiliza un valor o simplemente evita recalcular?** Evita recalcular: MEMOIZED + `payload="{}"` means the journal stores no value; replay reuse skips the handler entirely (no `UnixDetected` re-emitted).
- **¿`os.name` forma parte del fingerprint?** **No.** `Fingerprint.compute = f(stepId, params, runId, attempt, replayPolicy)`; `params` is empty and `os.name` is read inside the handler only.
- **¿Cambiar de SO entre fresh y resume produce una observación obsoleta?** **Yes, by construction**: resume of a SUCCEEDED `core.isUnix` reuses the journaled result and replays the persisted `UnixDetected` (verified: same `eventId` `752e5766…` replayed), so the event can report an `osName` that no longer matches the current machine. This is acceptable under MEMOIZED semantics but must be an explicit G2 decision (stale-observation acceptance), not an accident.
- **¿UnixDetected se duplica en rerun/resume?** **No.** Live wire observation on the installed binary (`13-workspace-helpers.pipeline.kts`): fresh run emits 1 event (`eventId 752e5766…`, `isUnix=true, osName=Linux`); `--resume` with the same `--db` keeps exactly 1 occurrence — the same eventId replayed from the journal projection, zero duplication.

## 4. Live evidence (installed binary `3e1cb6fe…` at HEAD)

- Fixture `13-workspace-helpers.pipeline.kts` (real DSL: `val unix = isUnix(); echo("is unix: " + unix)`): exit 0, SUCCESS.
- Script-visible echo: `"is unix: true"` (Path A).
- Wire event: `"kind":"UnixDetected","isUnix":true,"osName":"Linux","sha256":"4828e602…"` (Path B) — both paths live in one run, independently computed.
- Existing unit coverage: `CanonicalIsUnixNodeDispatcherTest` (Linux-assumed; Success + 1 event). No test covers the divergent whitelist cases — the matrix above is code-derived and wire-verified for the local platform only; the divergent rows are characterization-by-code-reading, to be frozen as executable differentials in G2.

## 5. Architectural notes (decisions DEFERRED, not taken)

- `System.getProperty("os.name")` is an implicit runtime dependency inside the legacy handler. **No `PlatformIdentityCapability` is created now.** If G1's candidate needs test isolation, the live counterexample (this divergence + the empty-string placeholder) will justify a small `PlatformIdentity(osName, isUnix)` capability — decided at G1 with the evidence in hand, not before.
- The DSL-side whitelist is broader (POSIX family) than the dispatcher's; Jenkins verbatim semantics (`isUnix()` true on Unix-likes) favors the broader set, but the choice belongs to G2's differential freeze with the user's approval.
- The `""` → `true` DSL placeholder is a divergence class of its own (placeholder vs strict false).

## Status

**G0 COMPLETE — characterization only, production changes = 0.**
Per slice protocol: **STOP.** No G1. Entry state for G1 unchanged: legacy residual 8/8/8; `core.isUnix` all-false.
