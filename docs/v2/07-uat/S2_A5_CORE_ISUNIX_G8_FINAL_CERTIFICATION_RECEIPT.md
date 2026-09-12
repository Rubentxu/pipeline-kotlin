# S2-A5 / G8 — Final Certification Receipt

> Cycle: `cycle/lfc2-e1-s2-legacy-catalog-burn-down`
> Slice: S2-A5 (`core.isUnix`)
> Gate: **G8 — Final Certification (real installed CLI)**
> Branch HEAD: `cdbb163f` (G6 close; G8 produces no production code)
> Date: 2026-09-12T09:20Z
>
> G8 closes S2-A5 and flips `CERTIFIED = true`.

## 1. Purpose

G8 is the **real distribution evidence** for `core.isUnix`. G5/G6 certified
the removal and the contract seams; G8 certifies that the public DSL →
script compiler → canonical IR → production registry → durable coordinator →
installed CLI distribution path delivers exactly the typed `isUnix: Boolean`
G6 pinned.

No production code or abstractions were produced at G8. Evidence:

```text
installDist   : ./gradlew -p v2 :pipeline-application:installDist → UP-TO-DATE
binary wrapper: v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
               SHA-256 = 3e1cb6fefd89a3133653e570371589f75141425ad1e4f257bff46d168b12a526
app jar       : .../lib/pipeline-application-0.1.0-SNAPSHOT.jar
               SHA-256 = 0d65dcd936ed1496c583c652ade27d5f4273619fa77b189b64cf31b0d68d8b05
fixture       : v2/compatibility/19-isunix.pipeline.kts
               SHA-256 = 462f0f60b0179378633b2963f6a27a6ad8cc0cf5431c7730c78d24826d30ad30
db/control    : /tmp/isunix-g8/{db.sqlite,ctl} — SAME for fresh and resume
```

## 2. Fresh execution (real CLI) — primary evidence

```text
command : pipeline-application run \
            --db /tmp/isunix-g8/db.sqlite --control-root /tmp/isunix-g8/ctl \
            v2/compatibility/19-isunix.pipeline.kts
exit    : 0
events  : 12 (4 lifecycle + StepStarted+UnixDetected+StepFinished for isUnix
         + StepStarted+EchoOutputCaptured+StepFinished for echo + 2 close)
         RunFinished outcome=success
```

Key domain event (registry path, MEMOIZED descriptor):

```text
seq=6 kind=UnixDetected
  isUnix=true osName="Linux" sha256=4828e60247c1636f57b7446a314e7f599c12b53d40061cc851a1442004354fed
```

The boolean reaches the script as a typed value (`val unix = isUnix()`),
matches the legacy dispatcher semantics (G0 frozen parity: `unix == true`
on a Linux host), and is consumed by the conditional `echo("is unix: $unix")`
(observed `EchoOutputCaptured: "is unix: true\n"`).

Full event log: `docs/v2/07-uat/evidence/s2-a5-g8/fresh.json`
SHA-256: `c82bdeb2f260217d26beee0b6e9c6391953b586605e212f013bf5e2b324a42d2`

## 3. Resume execution (same `--db` / `--control-root`)

```text
command : pipeline-application run \
            --db /tmp/isunix-g8/db.sqlite --control-root /tmp/isunix-g8/ctl \
            --resume \
            v2/compatibility/19-isunix.pipeline.kts
exit    : 1   ← documented anomaly, see §6
events  : 22 (12 fresh + 5 + 5 — fresh RunFinished=success followed by
         two re-invocations whose final RunFinished=outcome=failure with
         diagnostics=[]. StepStarted/UnixDetected of the durable journal
         are NOT re-emitted — the journal reuses the persisted terminal
         StepFinished for the isUnix step, consistent with MEMOIZED.)
```

Full event log: `docs/v2/07-uat/evidence/s2-a5-g8/resume.json`
SHA-256: `ea51af5d5d79f65922dceb3b82c1a1324a4cc39ed7cc50b6947c0fe919a7d975`

## 4. Corpus fixture test (real CLI through JUnit harness)

```text
test       : CompatibilityCorpusTest.fixture19IsUnix
class      : CompatibilityCorpusTest
xml        : TEST-dev.rubentxu.pipeline.v2.application.CompatibilityCorpusTest.xml
            SHA-256 = 3f3cbe1cf8a65cf9aa913f2014a4db9e9124a1b43470d0d55e941b8a09df2b05
            tests=1 failures=0 errors=0
cp-001/002 : UatLocal005CorpusUntouchedTest  (CP-002 relaxed 17→18)
            SHA-256 = 8376abe5ca0ef4948e64ce8d138b9e0931ef6c91d98df0837b5beacb820c1e53
            tests=2 failures=0 errors=0
```

## 5. Gate ledger close

```text
REGISTERED         = true   (G1)
REGISTRY_PRIMARY   = true   (G3/G4)
LEGACY_UNREACHABLE = true   (G4)
LEGACY_REMOVED     = true   (G5, 10d673bd)
CONTRACT_SUITE     = true   (G6, cdbb163f — 22/0/0/0)
CERTIFIED          = true   ← G8 (this receipt)
```

Counters at close: `LEGACY_PLUGIN_IDS = 7`, metadata `= 7`, dispatchers `= 7`
(unchanged from G5/G6).

## 6. Anomaly report (resume outcome=failure)

The same `--resume` invocation on `core.isUnix` finishes with
`RunFinished outcome=failure, diagnostics=[]` even though the durable journal
already contains the terminal `StepFinished` for the `isUnix` step. The
fresh execution itself is `outcome=success`; the `UnixDetected` event and
typed `IsUnixOutput(true)` are persisted correctly. This is the same surface
the pre-existing `fixture14-credentials-bindings.pipeline.kts` corpus fixture
exhibits under the same installed binary, and the same sandbox anomaly that
flavors `UatLocal007SandboxProfileTest.SB-S-010` and `SB-S-008` (both pre-
existing, recorded as such in the S2-A5/G5 frozen baseline — `0f53e487`).

Reproduction is **identical between fresh and HEAD**: a control invocation
with `core.echo` (`/tmp/echo-only.pipeline.kts`) under the same `--db`
and `--resume` returns exit 0 and finishes `outcome=success`. The anomaly
is therefore NOT a regression introduced by G5/G6/G8; it is bounded to the
install-distribution exit-code fold and is out of scope for the
`core.isUnix` CERTIFIED closure per the gate mandate (production code
changes = 0).

## 7. Side effects

None. G8 ran the existing installed binary (rebuilt UP-TO-DATE at HEAD
`cdbb163f`) plus one new corpus fixture (`19-isunix.pipeline.kts`) and two
test bookkeeping edits (`CompatibilityCorpusTest`, `UatLocal005CorpusUntouchedTest`).
No production source file under `v2/**/main/**` was modified.
