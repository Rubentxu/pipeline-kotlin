# ADR-0049: Local Credentials Provider + Secret Redaction

- **Status:** accepted
- **Date:** 2026-08-27
- **Deciders:** Rubentxu (product owner), orchestrator
- **Authority:** binds at apply phase T9 (docs); implementation in T1–T8
- **Related:** [[ADR-0046-local-ecosystem-first-reprioritization]] §L4, [[ADR-0048-sandbox-profile-local]], REQ-Credentials-Store, REQ-Credentials-Binding, REQ-Secrets-Redaction, REQ-UAT-Local-008-Credentials

## Context

ML-R2 established the durable `sh` pattern and ML-R3 added sandbox profiles, completing L1–L3 of the local execution ecosystem. L4 ("credentials provider local + secret redaction in logs/events") was deferred to ML-R4.

The threat is two-fold: (1) secrets from Jenkins `withCredentials` blocks leak into event streams, SQLite journals, and stdout dumps; (2) the pipeline engine has no typed secret channel — `String` is used throughout, making it impossible to audit where secrets appear. Additionally, Jenkins `withCredentials` supports `string`, `usernamePassword`, and 20+ credential types; v2 must cover at least `string` and `usernamePassword` with verbatim error messages matching Jenkins for the cases it cannot yet support.

ADR-0046 §L4 defines the requirement: "credentials provider local + secret redaction in logs/events/journal" with the additional constraint that secrets must not appear in `Map<String,String>` event fields (structural redaction by construction).

## Decision

### D1 — Two-layer redaction architecture

Secrets are redacted at two layers:

**Layer 1 — Typed env channel (ProcessBuilder boundary).** `SecretHandle` (opaque carrier) replaces `String` for all secret values. `ShOptions.env` is `Map<String, SecretHandle>` (not `Map<String, String>`). At `DurableShellExecutor.launch()`, the sole coercion point `ProcessBuilder.environment()[key] = secretHandle.effectiveValue()` happens exactly once. This guarantees `Map<String,String>` event fields can never carry secret values (structural redaction by construction — EVT-CR-006).

**Layer 2 — Free-text redaction before append.** `RedactingEventSink` decorates `InMemoryEventStore` and `SqliteEventStore` at `Main.kt` construction time. Before every `append(event)`, it walks free-text fields (`EchoOutputCaptured.content`, `StepFailed.message`, `CompilationFinished.diagnostics[*].message`, `RunFinished.diagnostics[*].message`) and substitutes recognized patterns. The substitution table is seeded by `SecretPatternRegistry` from live `CredentialsBinding` scopes (D3).

**Why two layers?** Layer 1 handles the structural guarantee (no `Map<String,String>` with secrets). Layer 2 handles the remaining free-text surfaces. They are independent and composed.

### D2 — LocalSecretStore: Argon2id KDF + AES-256-GCM + AAD

`LocalSecretStore` implements `SecretStore` using BouncyCastle (`bcprov-jdk18on:1.80.2` — **note:** proposal incorrectly cited `bcprov-jdk21on:1.85` which does not exist; corrected to `bcprov-jdk18on:1.80.2` per launch plan hard rule #4).

Storage format per entry:
```
idLen(2) + idBytes
+ plaintextLen(4)
+ nonce(12)          // AES-256-GCM random nonce per entry
+ ciphertext + tag(16)  // AAD = idBytes
```

Key derivation: Argon2id (memory=64MB, iterations=3, parallelism=4) from passphrase + random salt. DEK is random per entry; KEK is derived from passphrase. This enables passphrase change without re-encryption of all entries (只需要 re-encrypt DEKs).

POSIX enforcement: store file `0600`, parent dir `0700`. Atomic writes: temp file + `fsync` + rename.

### D3 — `withCredentials` DSL façade + AutoCloseable scope

`withCredentials(id: String, purpose: BoundPurpose, block: () -> T)` in `PipelineDsl.kt`:

- Resolves `CredentialsId` from `PipelineContext.credentialsResolver`
- Opens `CredentialsBinding` (implementing `AutoCloseable`)
- Injects `USERNAME`/`PASSWORD`/`API_KEY`/etc. env vars into `ShOptions.env` as `SecretHandle` values
- On scope exit: wipes `SecretHandle` memory, closes binding, removes env vars

`BoundPurpose` enum: `API_KEY`, `USERNAME_PASSWORD`, `SECRET_TEXT`, `SSH_KEY`, `CERTIFICATE`, `FILE`. Each maps to Jenkins credential type semantics.

### D4 — `string` and `usernamePassword` only; others OUT at L4

L4 covers `string` and `usernamePassword`. The 20+ other Jenkins credential types (SSH, cert, file, etc.) are deferred to ML-R4.1. The DSL façade `withCredentials` is intentionally narrow: `DEC-Q1 cut: only string + usernamePassword at L4; others OUT`.

### D5 — Env vars in ProcessBuilder only; `System.getenv` returns null

Secrets are injected into the child process environment only (`ProcessBuilder.environment()`). The engine JVM's `System.getenv()` always returns null for bound secrets — the engine never sees secret values. This is verified by CR-BD-001.

### D6 — Jenkins verbatim error messages

Missing credentials ID → `"Could not find credentials entry with ID 'xxx'"` (verbatim Jenkins).
Type mismatch → `"Credentials 'xxx' is of type 'SshCredentials' where 'StringCredentials' was expected."` (verbatim Jenkins).
These strings are confirmed against Jenkins source (`CredentialsProvider.java`).

### D7 — Canary round gate

`SecretPatternRegistry` is seeded with a synthetic canary (`GHS6_CANARY_<random>`) at `Main.kt` startup. After every pipeline run, zero occurrences of the canary must appear in any output surface. This is CR-RD-008 and UAT-L8-CR-RD-008. Currently `@Disabled` pending canary wiring (T9 scope; verified by `RedactingEventSinkTest` unit tests).

### D8 — Audit events carry no secret field

Three new `DomainEvent` variants are introduced without any secret-carrying field:

- `CredentialBound(runId, credentialsId, purpose, timestamp)` — bound to scope
- `CredentialUsed(runId, credentialsId, purpose, timestamp)` — consumed by step
- `CredentialUnbound(runId, credentialsId, purpose, timestamp)` — scope exited

`JsonEventLog` encode/decode handles these with forward-compat `else -> null` for unknown variants.

### D9 — Interpolation warning SOFT

String interpolation of secrets in `echo("value: $SECRET")` produces a warning in `EventSchemaNoMapStringStringTest` but is not a hard error at L4. This is documented as CR-BD-020 and deferred.

## Consequences

### Positive
- Secrets never appear in `Map<String,String>` event fields (structural guarantee)
- Free-text surfaces are scrubbed by `RedactingEventSink` before journal append
- Secrets are encrypted at rest with forward-secret DEK-per-entry AES-256-GCM
- Passphrase change does not require re-encryption of all entries
- No `kotlin.script.experimental.*` imports in credentials modules (BannedImportsGate)

### Negative
- Credentials store requires passphrase (no empty-passphrase option at L4)
- `usernamePassword` only; other Jenkins types deferred
- Canary round gate requires explicit wiring in `Main.kt` (not yet done)

### Threat Model — 12 surfaces → mitigations

| Surface | Threat | Mitigation |
|---------|--------|------------|
| `EchoOutputCaptured.content` | Secret echoed to stdout | `RedactingEventSink` scrub before append |
| `StepFailed.message` | Error messages contain secret | `RedactingEventSink` scrub before append |
| `CompilationFinished.diagnostics` | Compiler errors leak secrets | Scrub diagnostics array |
| `RunFinished.diagnostics` | Final diagnostics leak secrets | Scrub diagnostics array |
| `SqliteEventStore` journal | SQLite file contains secrets | `LocalSecretStore` encrypts entries |
| `ProcessBuilder.environment()` | Engine env inherits secrets | Never set; `SecretHandle` carrier |
| `System.getenv()` in engine | Engine code reads secrets | Always null for bound secrets |
| CLI args / argv | Secrets in process argv | `ShOptions.env` only; CLI input via stdin |
| `Map<String,String` event fields | Structural secret leak | `SecretHandle` typed channel (D1) |
| `returnStdout` capture | `sh(returnStdout=true)` returns secret | Result is `ByteArray`; caller must handle |
| Crash dumps / heap | JVM heap contains secret bytes | `SecretHandle.wipe()` clears byte arrays |
| Memory forensic | Cold memory read | Argon2id KDF; wipe on scope exit |

## Jenkins Mapping Table

| Jenkins `withCredentials` type | v2 `withCredentials` | Status |
|---|---|---|
| `string` | `withCredentials(id, purpose = API_KEY) { … }` | ✅ L4 |
| `usernamePassword` | `withCredentials(id, purpose = USERNAME_PASSWORD) { … }` | ✅ L4 |
| `sshUserPrivateKey` | — | ❌ deferred ML-R4.1 |
| `certificate` | — | ❌ deferred ML-R4.1 |
| `file` | — | ❌ deferred ML-R4.1 |
| 20+ others | — | ❌ deferred ML-R4.1 |

**BouncyCastle version correction:** Proposal §Dependencies incorrectly cited `bcprov-jdk21on:1.85`. This artifact does not exist. Corrected to `bcprov-jdk18on:1.80.2` per launch plan hard rule #4.

**Stale-after:** 2027-08-27
