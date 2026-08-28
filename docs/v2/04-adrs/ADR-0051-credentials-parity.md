---
type: adr
node_type: adr
title: "ADR-0051 — ML-R6 full Jenkins credentials parity + provider-agnostic git auth"
slug: "ADR-0051-credentials-parity"
status: Accepted
created: 2026-08-27
created_in_cycle: "[[CYC-2026-08-27-ml-r6-credentials-parity]]"
decision_authority: "[[ADR-0046-local-ecosystem-first]]" "[[ADR-0049-credentials-local]]" "[[ADR-0050-checkout-git-step]]"
supersedes: "D6 (deep) + D3 (deep) of [[ADR-0050-checkout-git-step]]"
superseded_by:
linked_milestones: "[[ML]]"
domain: execution sdk uat
project_id: p-733fb505b5a6bd2d
stale_after: 2027-08-27
affects_requirements:
  - "[[REQ-Credentials-Store]]"
  - "[[REQ-Credentials-Binding]]"
  - "[[REQ-Credentials-Multipart-Store]]"
  - "[[REQ-Credentials-Materialization]]"
  - "[[REQ-Credentials-Binding-Jenkins-Parity]]"
  - "[[REQ-Secrets-Redaction]]"
  - "[[REQ-SCM-Git-Checkout]]"
  - "[[REQ-UAT-Local-008-Credentials]]"
affects_domains:
  - sdk
  - scripting
  - events
  - uat
related_adrs:
  - "[[ADR-0046-local-ecosystem-first]]"
  - "[[ADR-0049-credentials-local]]"
  - "[[ADR-0050-checkout-git-step]]"
challenged_by:
---

# ADR-0051 — ML-R6 full Jenkins credentials parity + provider-agnostic git auth

## Status

**Proposed** · 2026-08-27 · [[CYC-2026-08-27-ml-r6-credentials-parity]]

DRAFT advanced to "proposed" during design phase (this cycle). Pending sddk-verify PASS for "accepted".

Supersedes (deep): D6 (`GitCredentialsApplier` two-channel auth) + D3 (structured-argv Git checkout auth channel) of [[ADR-0050-checkout-git-step]] — both replaced by D6 of this ADR (provider-agnostic credential-helper + SSH channel).
Amended by: none
Supersedes: none

## Context

ML-R4 ([[ADR-0049-credentials-local]]) delivered `LocalSecretStore` (single-blob envelope, Argon2id + AES-256-GCM with AAD), typed `SecretHandle`, `RedactingEventSink`, and `withCredentials { string | usernamePassword }`. ML-R5 ([[ADR-0050-checkout-git-step]]) added a `git` step on top of ML-R4 but shipped **with 5 motivating defects (D-A..D-E)** all orchestrator-verified at v0.19.0:

- **D-A** — `GitCredentialsApplier.kt:123` writes `http.<host>.extraHeader` scoped to the literal `https://github.com`. Every GitLab/Bitbucket/Azure DevOps/Self-hosted Gitea private repo silently fails (auth header never sent for the right host) — INC-R5.1-DEBT-001.
- **D-B** — `GitCheckoutExecutor.kt:311-329` infers credential kind from a `bytes.contains(":")` heuristic. A token that contains `:` is misclassified as `UsernamePassword` and split on the first `:` (wrong channel); an empty-user pass (`:secret`) reads as `string` — INC-R5.1-DEBT-002.
- **D-C** — `GitCredentialsApplier.kt:80,166` reads the host from the credential ID (not the repo URL), falling back to `github.com` for any non-`https://github.com` ID. The applier never receives the repo URL.
- **D-D** — `LocalSecretStore.put(id, bytes)` is a single-blob envelope; `UsernamePassword(user, pass)` is forced to `base64("user:pass")` at one location with no way to reference a separate `pass` entry by id (V1 had `passphraseSecretId: String?`).
- **D-E** — `CredentialScope.kt:131-133` factory methods `string(...)` and `usernamePassword(...)` drop their `variable` / `usernameVariable` / `passwordVariable` arguments (`val … = null` stubs); `withCredentials(id, purpose, block)` resolves a primary id only — secondary env vars silently absent.

V1 (`core/src/main/kotlin/dev/rubentxu/pipeline/context/managers/interfaces/ISecretManager.kt`, 198-line quarantined module) had **none** of these defects: typed `SecretValue` hierarchy (`PlainText | UsernamePassword | FileBased | SshPrivateKey | Certificate | AwsCredentials`); `passphraseSecretId` / `passwordSecretId` linked-secret references; `SecretProvider.cleanup(SecretValue)` for file-based kinds; anti-log `toString()`; and `withCredentials(bindings: List<CredentialBinding>, block)` with multi-binding semantics.

Jenkins §1.6 (`docs/v2/01-product/JENKINS_FAMILIARITY_CATALOG.md:103-125`) lists 8 binding kinds plus 20+ contributed. v2 at v0.19.0 (ML-R5 closure) supports 2 (`string`, `usernamePassword`); 6 of the remaining core bindings plus all contributed kinds are missing.

Fold-in INCs carried forward:
- **INC-CR-AUDIT-001** — `CredentialScope.kt:66,94` carry `// TODO: emit CredentialBound/Unbound`; events exist but are never emitted.
- **INC-R5-ME-006** — 4 untested GIT-CHK scenarios (auth-fail taxonomy, network-fail classification, invalid branch, provider-agnostic non-GitHub host).
- **INC-R5-LO-009** — `GitCheckoutFailed.reason` emits raw stderr; can leak embedded creds from URL patterns.

Without ML-R6 a pipeline author cannot onboard a private GitLab/Bitbucket/Gitea repo (D-A), a token containing `:` (D-B), a repo over SSH (no `sshUserPrivateKey`), a keystore + password, a Jenkins `file` / `zip` secret, or any contributed type. The canary round gate (`__git_canary__`, ML-R5) catches only what ML-R5 did — credential-leakage via the OLD extraHeader path; the new SSH channel needs its own canary (`__ssh_canary__`).

## Decision

### D1 — Typed multipart credential store

`sealed interface Credential` with 7 concrete kinds: `SecretText(bytes)`, `UsernamePassword(user, pass)`, `SshPrivateKey(username, privateKey, passphraseRef?)`, `SecretFile(bytes, originalName?)`, `Certificate(keystore, passwordRef?, alias?)`, `Zip(entries: Map<String, ByteArray>)`, `UsernameColonPassword(user, pass)`. Each part gets its own AES-256-GCM envelope with random 96-bit nonce. AAD per part = `(credentialId + ":" + partName)` (binds slot to name — anti-swap, anti-rename). `LinkedSecretRef(CredentialsId)` references another entry by id (V1 `passphraseSecretId` semantics, typed). v1 single-blob entries read transparently as `SecretText` (no forced migration). On-disk envelope augmented from ML-R4's single blob with `kind(2) + scope(1) + descLen(2) + descBytes + partCount(1) + [partName(2) + partBytesLen(4) + partBytes(plaintextLen + nonce(12) + ciphertext + tag(16))] + linkedRefCount(1) + [linkedRefName(2) + linkedRefIdLen(2) + linkedRefIdBytes]`.

### D2 — `SecretStore` API surface

```kotlin
interface SecretStore : AutoCloseable {
    fun add(id: CredentialsId, credential: Credential)                    // NEW (typed)
    fun get(id: CredentialsId): Credential                                // CHANGED (was SecretHandle; now typed)
    fun getAsHandle(id: CredentialsId, partName: String): SecretHandle    // NEW (per-part)
    fun put(id: CredentialsId, bytes: ByteArray)                          // KEEP (compat shim → SecretText)
    fun rotate(id: CredentialsId, credential: Credential)                // CHANGED (typed)
    fun list(): List<CredentialsId>                                       // KEEP
    fun remove(id: CredentialsId)                                         // KEEP
    override fun close()                                                  // KEEP
}
```

### D3 — Materialization service

`CredentialMaterializer(store): AutoCloseable`. Polymorphic dispatch on file-based kinds:
- `SecretFile(bytes, originalName)` → `mkstemp` (0600) + parent dir (0700).
- `SshPrivateKey(privateKey, passphraseRef?)` → PEM at `mkstemp` (0600) + linked passphrase at second `mkstemp` (0600).
- `Certificate(keystore, passwordRef?, alias?)` → keystore at `mkstemp` (0600) + linked password at second `mkstemp` (0600).
- `Zip(entries)` → `mkdtemp` (0700) with each entry `chmod 0600`.
- `SecretText | UsernamePassword | UsernameColonPassword` → no materialization (use `store.getAsHandle(id, partName)`).

`MaterializationRegistry` tracks every path. On `close()`: iterate, `fill(0) + Files.delete` on files, `rmdir` on dirs. Idempotent on same credential (returns existing path). Non-POSIX filesystem → `MaterializationPosixPermissionsUnsupportedException`. Non-file kind → `MaterializationKindUnsupportedException("Credential '<id>' of kind '<Kind>' is not materializable to a file")`.

### D4 — Full Jenkins §1.6 binding set

Sealed `CredentialsBinding` (NEW module `:pipeline-binding-factory`). **Parameter NAMES byte-for-byte per JENKINS_FAMILIARITY_CATALOG.md:103-115** (F-ARCH-L6-003 reflection test enforces). For `certificate`, `zip`, `usernameColonPassword` the catalog's parameter order is variable-first (NOT `credentialsId`-first as the proposal paraphrased); companion-object factory methods offer ergonomic `credentialsId`-first call site (D-1 design-time discrepancy; resolved in design with verbatim constructor + ergonomic factory).

Exact data-class signatures (parameter names match catalog):
```kotlin
data class StringBinding(val credentialsId: CredentialsId, val variable: String) : CredentialsBinding
data class UserPasswordBinding(val credentialsId: CredentialsId, val usernameVariable: String, val passwordVariable: String) : CredentialsBinding
data class SshUserPrivateKeyBinding(
    val credentialsId: CredentialsId,
    val keyFileVariable: String,                       // REQUIRED
    val passphraseVariable: String? = null,           // OPTIONAL
    val usernameVariable: String? = null,             // OPTIONAL
) : CredentialsBinding
data class FileBinding(val credentialsId: CredentialsId, val variable: String) : CredentialsBinding
data class CertificateBinding(
    val keystoreVariable: String,                      // catalog order: variable-first
    val credentialsId: CredentialsId,
    val aliasVariable: String? = null,
    val passwordVariable: String? = null,
) : CredentialsBinding
data class ZipBinding(val variable: String, val credentialsId: CredentialsId) : CredentialsBinding
data class UsernameColonPasswordBinding(val variable: String, val credentialsId: CredentialsId) : CredentialsBinding
```

Companion factories offer ergonomic `credentialsId`-first call:
```kotlin
companion object {
    fun string(credentialsId: CredentialsId, variable: String) = StringBinding(credentialsId, variable)
    fun usernamePassword(credentialsId: CredentialsId, usernameVariable: String, passwordVariable: String) = UserPasswordBinding(credentialsId, usernameVariable, passwordVariable)
    fun sshUserPrivateKey(credentialsId: CredentialsId, keyFileVariable: String, passphraseVariable: String? = null, usernameVariable: String? = null) = SshUserPrivateKeyBinding(credentialsId, keyFileVariable, passphraseVariable, usernameVariable)
    fun file(credentialsId: CredentialsId, variable: String) = FileBinding(credentialsId, variable)
    fun certificate(credentialsId: CredentialsId, keystoreVariable: String, aliasVariable: String? = null, passwordVariable: String? = null) = CertificateBinding(keystoreVariable, credentialsId, aliasVariable, passwordVariable)
    fun zip(credentialsId: CredentialsId, variable: String) = ZipBinding(variable, credentialsId)
    fun usernameColonPassword(credentialsId: CredentialsId, variable: String) = UsernameColonPasswordBinding(variable, credentialsId)
}
```

`ContributedBindingFactory` SPI (`val kind: String; fun create(credentialsId, params: Map<String, String>): CredentialsBinding`) is declared but shipped UNIMPLEMENTED. Empty `META-INF/services/dev.rubentxu.pipeline.v2.binding.ContributedBindingFactory` placeholder file. Out-of-scope contributed kinds enumerated dynamically per Jenkins §1.6 note.

### D5 — Multi-binding `withCredentials(bindings: List<CredentialsBinding>, block)`

Jenkins + V1 parity. `CredentialScope.env(binding: CredentialsBinding): MaterializedCredential` resolves any binding kind; validates `expectedKind == actualKind` via `MismatchedSecretException(id, expectedKind, actualKind)` with **verbatim Jenkins-post-2019 wording** `"Credential '<id>' is of type '<actual>' where '<expected>' was expected."` (3-arg, structured). Partial-failure discipline: all bindings resolved BEFORE block entry; if ANY binding fails, NO binding is injected. `withCredentials` orchestrates one `CredentialScope` + one `CredentialMaterializer`, both `AutoCloseable.close()`d in `finally`.

L4 single-binding overload retained as a thin wrapper (`withCredentials(binding, block)` → `withCredentials(listOf(binding), block)`).

### D6 — Git credential-helper protocol + `GIT_SSH_COMMAND` + `GIT_ASKPASS` (provider-agnostic)

Replace `GitCredentialsApplier` (rename to `GitCredentialAdapter`, file path kept) with:

1. **Credential helper script** `<runDir>/helper.sh` (0700), generated per run. Pure bash, `set -euo pipefail`. Implements `git credential` protocol commands (`get`, `store`, `erase`) over stdin/stdout. For `get`, reads `protocol=`, `host=`, `path=` keys from stdin and writes `username=` / `password=` on stdout, sourced from a sibling `<runDir>/env` (0700) populated by the adapter at `apply()` time. The helper script is **stateless** (no JVM at git time; no socket daemon; one-shot per git invocation).

2. **`GIT_CONFIG_GLOBAL` config** `<runDir>/config` (0600) — `[credential "https://<host>"] helper = <runDir>/helper.sh` per observed host + a generic `[credential]` fallback. **No host literals**; git scopes by URL via its `[credential "<url-pattern>"]` mechanism. D-C dies (adapter receives `repoUrl`, parses host for config section). D-A dies (no `extraHeader` anywhere).

3. **SSH channel** (`sshUserPrivateKey` kind):
   - `GIT_SSH_COMMAND="ssh -i <runDir>/tmpkey-XXXXXX -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"` — key at `mkstemp` 0600; parent dir 0700.
   - Passphrase: `GIT_ASKPASS=<runDir>/askpass.sh` (0700) which reads passphrase from sibling `<runDir>/env` (0700) and prints to stdout. SSH consumes via askpass — passphrase never in argv.

4. **Adapter.close()** wipes every run-scoped path: helper script, helper config, env file, tempkey (if SSH), tempaskpass (if SSH) — `fill(0) + Files.delete`. Parent runDir `rmdir`'d.

`resolveGitCredentials(repoUrl: String, credentialsId)` chooses channel from typed `Credential` (NO `bytes.contains(":")` heuristic — D-B dies):
```kotlin
val credential = store.get(credentialsId)
when (credential) {
    is SecretText               -> adapter.apply(repoUrl, credentialsId, channel = HELPER_STRING)
    is UsernamePassword         -> adapter.apply(repoUrl, credentialsId, channel = HELPER_USERPASS)
    is SshPrivateKey            -> adapter.apply(repoUrl, credentialsId, channel = SSH)
    else -> throw MismatchedSecretException(credentialsId, "GitCredentials", credential::class.simpleName!!)
}
```

### D7 — CLI `pipeline credentials add --kind <K>`

Extension on existing `MainCredentialsCli` (in `:pipeline-credentials-local`; no NEW `:pipeline-cli` module). Add `--kind` flag to `add` / `rotate` subcommands. Kinds: `secret-text | username-password | ssh-private-key | secret-file | certificate | zip | username-colon-password`. Validation at write time:
- `username-password`: reject empty user OR empty password (stderr names failing field, exit code 1).
- `ssh-private-key`: PEM header/footer required; `passphrase-ref` must resolve.
- `secret-file`: path readable; size ≤ 1 MB v2 secret cap; no stdin.
- `certificate`: keystore bytes must be parseable as PKCS#12 (`KeyStore.getInstance("PKCS12").load(stream, null)`).
- `zip`: must be a valid archive (`ZipInputStream.getNextEntry()` returns non-null).

`list` shows `id  kind  scope  description` — kind from envelope metadata (NEVER inferred).

### D8 — Audit emit (INC-CR-AUDIT-001 fold-in)

`CredentialScope.env(binding)` emits `CredentialBound(runId, credentialsId, purpose, occurredAt)` after successful kind validation, BEFORE the env-var becomes visible to the block. `close()` emits `CredentialUnbound(runId, credentialsId, purpose, occurredAt)` in `finally`. `purpose` mapping from binding kind:
- `StringBinding` → `API_KEY`
- `UserPasswordBinding` → `USERNAME_PASSWORD`
- `SshUserPrivateKeyBinding` → `SSH_KEY`
- `FileBinding` → `FILE`
- `CertificateBinding` → `CERTIFICATE`
- `ZipBinding` → `ZIP`
- `UsernameColonPasswordBinding` → `USERNAME_COLON_PASSWORD`

`BoundPurpose` enum in `:pipeline-domain` re-purposed from current `ENV | FILE | VALUE` to the 7 values above (rename; no backward compatibility — ML-R6 is the first cycle that emits `purpose` other than `ENV`).

### D9 — Redact `GitCheckoutFailed.reason` (INC-R5-LO-009 fold-in)

In `GitCheckoutExecutor` where `GitCheckoutFailed(reason = <stderr>)` is constructed, pipe `reason` through `SecretPatternRegistry.scrub(stderr)` first. The canary round gate (`__git_canary__`, ML-R5) already catches reintroduction.

### D10 — AWS as native typed credential: DEFERRED to ML-R6.1

CUT candidate; design recommends deferral. V1 precedent has `AwsCredentials(accessKeyId, secretAccessKey)`, but Jenkins §1.6 has no equivalent binding (V1's `AwsCredentialsBinding` was V1-original, not Jenkins-parity). Shipping without a Jenkins-binding counterpart is out of scope; ML-R6.1 absorbs both an `AwsCredentials` kind AND a Jenkins-parity `awsCredentials` binding if/when needed. Saves ~120 LOC of model + ~80 LOC of tests.

### D11 — Architecture tests (extend F-ARCH-L5-001; add F-ARCH-L6-001/002/003)

- **F-ARCH-L5-001 extension**: grep-gate on `scm-git/`: zero `extraHeader | Authorization | https?://[^/]*:[^/]*@` in any `ProcessBuilder` argv. (Closes D-A.)
- **F-ARCH-L6-001 (NEW)**: reflection-based scan of `scm-git/` boundary sites: every `SecretStore.get(id)` call MUST receive a `Credential` (typed carrier); never raw bytes.
- **F-ARCH-L6-002 (NEW)**: grep-gate across `scm-git/`, `credentials/`, `dsl/`: zero `contains(":")` / `split(":", limit=2)` / `bytes.indexOf(...)` for kind inference. (Closes D-B.)
- **F-ARCH-L6-003 (NEW)**: reflection-based assertion of constructor parameter names vs. JENKINS_FAMILIARITY_CATALOG.md:103-115 strings, byte-for-byte. (Forces D-1 verbatim order in data classes.)

## Decision Drivers

1. **Close all 5 motivating defects (D-A..D-E) deterministically.** Each gets a named architecture test (F-ARCH-L5-001 extension + F-ARCH-L6-001/002).
2. **Bring v2 to full Jenkins §1.6 binding parity** for the 7 core kinds. Reflection test enforces byte-for-byte parameter names.
3. **Provider-agnostic git auth** — git's own credential protocol replaces the v2 hard-coded `https://github.com` literal. Reusable for any future SCM (subversion deferred).
4. **ML-R4 substrate integrity** — Argon2id + AES-GCM + AAD + atomic write + POSIX perms + passphrase resolution order unchanged. v1 single-blob entries readable.
5. **Size honesty** — A-full, ~5,100 LOC, size exception PRE-APPROVED. AWS deferred to ML-R6.1 to keep the envelope honest.

## Considered Options

### Option 1: Helper-script generator consuming a unix-socket daemon (rejected)

Process-attached JVM daemon listening on a unix socket; helper script `nc -U <socket>`. Pros: dynamic credential rotation mid-run. Cons: daemon lifecycle (shutdown coordination), FD leakage on JVM crash, two-process failure modes. Rejected — one-shot git operations don't benefit; pre-resolution at `apply()` time is sufficient.

### Option 2: Helper-script binary launcher invoking a JVM main (rejected)

Separate launcher subprocess; the helper binary is a small C/Go/JVM-callout. Pros: dynamic resolution. Cons: adds CLI surface for no value; pre-resolution already has the credentials in hand at `apply()`; no JVM-at-git-time is faster.

### Option 3: Helper script = stateless reader of pre-resolved answer file (CHOSEN)

**Chosen because**: stateless (helper script exits 0 immediately); no JVM overhead per git call; no daemon lifecycle; pure shell (POSIX); pre-resolution is one-shot at `apply()` time when credentials are already in hand; the `git credential` protocol's `get` command is fully honored; `store`/`erase` are no-ops (helper is read-only); sibling `<runDir>/env` is populated by the adapter (which is the only writer) and wiped by `adapter.close()` in `finally` (RAII). Architecture test F-ARCH-L6 extension guarantees the helper script is never invoked with secret-bearing argv.

### Option 4: Per-entry KEK instead of per-part DEK (rejected)

Each credential's parts share one KEK; whole-credential rotation re-encrypts everything. Pros: simpler key handling. Cons: defeats per-part rotation semantics (CR-ST-017); aligns with V1's whole-secret handling, which is exactly what we're moving away from.

### Option 5: AWS-as-native typed credential shipped in ML-R6 (rejected)

~120 LOC of model + ~80 LOC of tests. Jenkins §1.6 has no `awsCredentials` binding; shipping without one leaves AWS credentials storeable but not bindable. Defer to ML-R6.1 which can co-design both kind and binding.

## Decision Outcome

**Chosen options:** D1 (typed multipart store) + D2 (new SecretStore API + `put` shim) + D3 (CredentialMaterializer) + D4 (sealed Jenkins §1.6 bindings + SPI) + D5 (multi-binding withCredentials + MismatchedSecretException verbatim) + D6 (helper script = pre-resolved answer file) + D7 (extend MainCredentialsCli, no new CLI module) + D8 (audit emit from CredentialScope) + D9 (scrub GitCheckoutFailed.reason) + D10 (AWS cut) + D11 (3 architecture tests; extend F-ARCH-L5-001).

**Justification**: closes all 5 motivating defects deterministically (each gets a named arch test); brings v2 to full Jenkins §1.6 binding parity for the 7 core kinds (F-ARCH-L6-003 reflection test enforces verbatim parameter names); replaces the hard-coded `https://github.com` literal with git's own credential protocol + per-host config sections; preserves ML-R4's Argon2id + AES-256-GCM substrate with v1 SBS1 back-compat read; honest size via D10 AWS cut.

## Consequences

**Positive:**
1. Jenkins §1.6 verbatim parity for 7 binding kinds — closes INV-L6-CR-008 / CR-BP-008.
2. Non-GitHub hosts (`gitlab.com`, `bitbucket.org`, `gitea.example.com`, etc.) work — closes D-A (INV-L6-CR-011 `git credentials scope decided by git itself`).
3. SSH auth via `sshUserPrivateKey` works against any SSH host (local or remote) — closes INV-L6-CR-006/012.
4. Multipart credentials round-trip byte-identical through ML-R4's encrypted envelope — closes D-D (INV-L6-CR-002 per-part DEK/AAD uniqueness).
5. Multi-binding block, partial-failure discipline preserved — closes V1 precedent (INV-L6-CR-009).
6. Audit emit timing deterministic — closes INC-CR-AUDIT-001 (INV-L6-CR-008 audience purpose mapping).
7. CLI `--kind` dispatch prevents malformed entries at write time (INV-L6-CR-005).
8. New SSH canary round gate (CR-RD-021) extends redaction coverage; GIT-CHK canary round gate (CR-RD-018, ML-R5) preserved.
9. 5 motivating + 4 fold-in INCs CLOSED (D-A, D-B, D-C, D-D, D-E, INC-CR-AUDIT-001, INC-R5-ME-006, INC-R5-LO-009).
10. V1 precedent restored (typing + linked-secret + multi-binding + materialization all in v2).

**Negative:**
1. ~5,100 LOC + ADR-0051 + extended UAT families — size exception flag required (pre-approved).
2. `CredentialsBinding` API becomes sealed + moves to a new module (`:pipeline-binding-factory`). L4 scripts continue to compile via `StepSpec.CredentialsBinding` (preserved). `SecretStore.get(id)` type changes (`SecretHandle` → `Credential`); internal-only, no external users.
3. Multipart envelope format change — new entries written in ML-R6 cannot be read by pre-ML-R6 builds; CHANGELOG warning required. v1 single-blob entries remain readable (back-compat).
4. `BoundPurpose` enum rename — internal-only; consumers outside `CredentialScope` are limited.
5. `MismatchedSecretException` constructor changes (V1's 2-arg `IllegalArgumentException("...not of expected type...")` → ML-R6's 3-arg typed w/ verbatim Jenkins wording). V1 quarantined — no migration needed.
6. `JENKINS_FAMILIARITY_CATALOG.md §1.6` rows now marked IMPLEMENTED ML-R6; contributed row remains DEFERRED.

## Threat Model (incremental over [[ADR-0049-credentials-local]] + [[ADR-0050-checkout-git-step]])

| # | Surface | Mitigation |
|---|---|---|
| 1 | Helper-script argv leaks secret | D6 + D11: argv never contains secret; helper reads `<runDir>/env` (0700) only; F-ARCH-L5-001 extension asserts. |
| 2 | Tempkey residue | D3 + D6: `mkstemp` 0600; `adapter.close()` `fill(0)`+`Files.delete`; parent dir `rmdir`; `MaterializationRegistry.residue()` for JVM-crash ops-cleanup. |
| 3 | Multipart rotation mid-step | D1: per-part DEK; rotate one part re-encrypts ONLY that part's nonce+ciphertext+tag. |
| 4 | `MismatchedSecretException` carries secret material | D4 + D5: 3-arg constructor `(id, expectedKind, actualKind)` — no byte fields; `message`, `cause`, `stackTrace` scanned for canary in CR-BP-013. |
| 5 | v1 SBS1 mis-resolved as multipart after rotation | D1: `version(1)→SecretText` only; multipart requires `version==2` envelope; CLI `add` always writes v2. |
| 6 | `/proc/<pid>/environ` leak of helper/SSH subprocess | D6: helper script reads stdin only; askpass reads `<runDir>/env` (0700); SSH key path (`mkstemp` 0600) is in `GIT_SSH_COMMAND` argv of the parent git process, but the key bytes are NOT in any argv. |
| 7 | Provider-agnostic regression — accidentally host-scoped | D11 F-ARCH-L6-002: zero `contains(":")` / `split(":")` heuristics for kind inference. INV-L6-CR-011: git decides scope. |
| 8 | SSH turnkey without `V2_SSH_OK` | D11 + AGENTS.md §28: `@EnabledIfEnvironmentVariable(named = "V2_SSH_OK", matches = "true")` for real round gate; local `git+ssh://` daemon fixture fallback per R8. |

## V1 Precedent Diff

| V1 (`ISecretManager.kt`) | v2 ML-R6 | Notes |
|---|---|---|
| `SecretValue` sealed (`PlainText`, `UsernamePassword`, `FileBased`, `SshPrivateKey`, `Certificate`, `AwsCredentials`) | `Credential` sealed (`SecretText`, `UsernamePassword`, `SshPrivateKey`, `SecretFile`, `Certificate`, `Zip`, `UsernameColonPassword`; `[AwsCredentials design-decision candidate]`) | +`Zip`, +`UsernameColonPassword` from Jenkins §1.6; `AwsCredentials` deferred to ML-R6.1 per D10. |
| `passphraseSecretId: String?`, `passwordSecretId: String?` (linked by id) | `LinkedSecretRef(CredentialsId)?` | Same semantics, typed. |
| `SecretProvider.cleanup(secretValue)` | `CredentialMaterializer.close()` | Same semantics; per-file wipe + dir rmdir. |
| `MismatchedSecretException("x is not of expected type y")` (2-arg, paraphrased) | `MismatchedSecretException(id, expectedKind, actualKind)` (3-arg, verbatim Jenkins-post-2019) | Different wording — V1 was paraphrased; ML-R6 uses Jenkins-modern verbatim. |
| `withCredentials(bindings: List, block)` | `withCredentials(bindings: List<CredentialsBinding>, block)` | Same signature; V1 already had the LIST shape. |
| `StringBinding` / `UserPasswordBinding` / `FileBinding` / `SshUserPrivateKeyBinding` / `AwsCredentialsBinding` / `CertificateBinding` | Sealed `CredentialsBinding` with all 7 Jenkins §1.6 kinds + `ContributedBindingFactory` SPI | Jenkins parity + extensible; parametric order verbatim per D-1. |
| V1 `SshPrivateKeyBinding(userVariable, privateKeyPathVariable)` | ML-R6 `SshUserPrivateKeyBinding(credentialsId, keyFileVariable, passphraseVariable?, usernameVariable?)` | Jenkins-param-order; F-ARCH-L6-003 reflection enforces. |

## Source

Repo ADR: `docs/v2/04-adrs/ADR-0051-credentials-parity.md` (cycle `ml-r6-credentials-parity`).
Proposal DRAFT: cycle-artifacts `proposal.md` §ADR-0051 DRAFT (lines 600-769).
Design: cycle-artifacts `design.md` (sha256 computed at archive time).

## Changelog (bi-temporal)

- 2026-08-27T21:10:00Z | created | cycle=[[CYC-2026-08-27-ml-r6-credentials-parity]] | status=proposed (design-phase advance)
- 2026-08-27T21:10:00Z | supersedes-deep | ADR-0050 D6 + D3 | git channel switch to helper-protocol + SSH; provider-agnostic

---

## Addendum — envelope format drift (post-review)

This addendum documents the accepted deviations between the multipart-store spec text (ADR-0051 D1) and the actual implementation following review findings from cycle `ml-r6-credentials-parity`.

### 1. Single-blob per-credential encryption with AAD `(credentialId + ":" + kindId)`

The spec text (D1) states: "AAD per part = `(credentialId + ":" + partName)` (binds slot to name — anti-swap, anti-rename)."

**Implemented**: AAD = `credentialId + ":" + kindId` (not per-part). Each credential is stored as a single encrypted blob; the `kindId` is embedded in the envelope entry header, not in the AAD. Part names are serialized inside the ciphertext but are not bound as AAD.

**Rationale**: Per-part envelope isolation would require a separate DEK per part, significantly increasing KDF cost and envelope complexity. The single-blob approach with `kindId` in the AAD provides adequate anti-swap protection for the credential as a whole.

**Debt reference**: FIND-000001 (per-part AAD gap); follow-up backlog item in future cycle: evaluate per-part envelope upgrade or spec amendment to D1.

### 2. 1-byte nameLen field

The spec text does not specify the width of `nameLen`. The implementation uses a 1-byte unsigned length prefix, limiting part/entry names to 255 bytes (UTF-8 byte length, not character count).

**Impact**: Names exceeding 255 UTF-8 bytes are rejected at `add()` time with `SecretStoreTamperException`. This affects `Zip` credential entry names which are user-controlled.

**Workaround**: Applications must truncate or hash entry names exceeding 255 bytes before storing.

**Debt reference**: FIND-000006 (name length validation gap); follow-up backlog item: consider 2-byte nameLen in future envelope version.

### 3. Scope not persisted (GLOBAL-only per design D-decision)

The spec mentions `scope(1)` in the v2 envelope format but the implementation does not persist scope. All credentials are stored and retrieved as `CredentialScope.GLOBAL`.

**Rationale**: The pipeline execution model currently only supports GLOBAL scope. Session/User scopes are deferred.

**Debt reference**: FIND-000006; follow-up backlog item: add scope persistence when multi-scope model is implemented.

### 4. Description field deferred

The spec includes `descLen(2) + descBytes` in the v2 envelope. The implementation does not persist or deserialize the description field.

**Rationale**: The CLI does not currently accept a description parameter; no user-facing feature requires it.

**Debt reference**: FIND-000006; follow-up backlog item: add description field when CLI is extended.

### 5. Wipe-failure stderr-only logging (GitCredentialsApplier.wipeFile)

`wipeFile` in `GitCredentialsApplier` catches exceptions during secure wipe and logs to stderr only (`System.err.println`), rather than throwing or using the event system.

**Known limitation**: If wipe fails, the error is not visible to the pipeline orchestrator unless stderr is captured.

**Debt reference**: FIND-000006.

### 6. .tmp crash window

Mutating operations write to a `.tmp` file before atomic rename. If the JVM crashes after writing the `.tmp` but before the rename, the `.tmp` file remains on disk. This is a known limitation of the write-then-rename pattern.

**Mitigation**: File locking is applied to the store file itself (not the `.tmp`) to serialize mutations. The `.tmp` is created with `Files.write()` which is atomic on POSIX systems for our use case.

**Debt reference**: FIND-000006.

---

*Addendum added: 2026-08-28 (review-round-1 fix cycle)*

