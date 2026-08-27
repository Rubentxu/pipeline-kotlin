# ADR-0050: checkout-git Step — CLI-git, Structured Argv, Temp-File Credentials, SHA-Equality Idempotency

- **Status:** accepted
- **Date:** 2026-08-27
- **Deciders:** Rubentxu (product owner), orchestrator
- **Authority:** binds at apply phase T-08 (docs + tests); implementation in T-01–T-07
- **Related:** [[ADR-0046-local-ecosystem-first-reprioritization]] §L5, [[ADR-0049-credentials-local]] §L5, REQ-Checkout-Git, REQ-UAT-Local-005
- **Supersedes:** none

## Context

ML-R4 established the local credentials provider and secret redaction infrastructure (L4). ML-R5 adds the `checkout` step for Git, the second most-used Jenkins Pipeline step after `sh`.

The Jenkins `git` and `checkout` steps support: clone/fetch, branch checkout, credential binding (username/password, API token, SSH), changelog generation, and poll-based change detection. The v2 implementation must provide equivalent semantics using the CLI-git approach (not JGit) while maintaining the security properties established in ML-R4.

### Requirements

From the ML-R5 checkout-git specification (11 scenarios):

1. Clone fresh repo (no prior `.git`)
2. Idempotent re-run (SHA-equal → no-op, <2s)
3. Branch checkout (existing repo, remote SHA changed)
4. `credentialsId` resolution — string channel (API token)
5. `credentialsId` resolution — `usernamePassword` channel
6. `changelog.txt` format: 7-char SHA + first line of subject
7. Poll changed (remote SHA differs from `previousRemoteSha`)
8. Poll unchanged (remote SHA same as `previousRemoteSha`)
9. `relativeTargetDir` workspace layout
10. Auth-fail error taxonomy (wrong credentials → `GitCheckoutFailed`)
11. Network fail / invalid branch → `GitCheckoutFailed`

## Decision

### D1 — CLI-git, not JGit (F-ARCH-L5-001)

The `scm-git` module uses the **git CLI** exclusively. All git operations (`git clone`, `git ls-remote`, `git fetch`, `git reset --hard`, `git rev-parse`, `git log`) are spawned as subprocesses via `ProcessBuilder` with structured argv.

**Rationale:** JGit (pure-Java Git) introduces a large dependency with its own security history. The CLI approach provides a smaller attack surface and simpler audit. The CLI is universally available (`git --version >= 2.30` required).

**INV-L5-CR-002:** No `org.eclipse.jgit` imports in any `v2/` source file. Enforced by `UatLocal005BannedImportsTest.IMP-001`.

### D2 — Structured argv; credentials never enter process arguments

All git commands use explicit `List<String>` argv passed to `ProcessBuilder`. No shell interpolation, no `bash -c "git ..."` form.

**INV-L5-CR-004:** Credentials (tokens, passwords) NEVER appear in argv. Credentials flow only through:
- Environment variables (`GIT_CONFIG_GLOBAL`, `HOME`) scoped to the credential helper
- Temp files (`.git-credentials`, `.gitconfig`) written with `0600` permissions

This is the same principle as ML-R4's `ProcessBuilder.environment()` approach — the argv boundary is a security surface.

### D3 — Temp file lifecycle: write → use → wipe in `finally`

`GitCredentialsApplier` implements `AutoCloseable`:

1. **Write phase:** Creates temp dir (chmod `0700`), writes `.git-credentials` and/or `.gitconfig` with `0600`
2. **Use phase:** Sets `GIT_CONFIG_GLOBAL` and optionally `HOME` env vars, launches git commands
3. **Wipe phase (always, in `finally`/`close()`):** Overwrites file content with zeros, then deletes

```kotlin
// Pseudocode for GitCredentialsApplier.close()
private fun wipeFile(path: Path) {
    if (Files.exists(path)) {
        val size = Files.size(path)
        Files.writeString(path, CharArray(size.toInt()) { '\u0000' }.joinToString(""))
        Files.delete(path)
    }
}
```

**INV-L5-CR-003:** Temp files `0600`, parent dir `0700`, wiped in `finally`. SIGKILL residue is documented: the wipe is best-effort; the OS may not sync before kill.

### D4 — Two authentication channels

**String channel (API token):**
```
~/.git-credentials  (0600)
  https://x-access-token:<token>@<host>
.gitgitconfig  (0600)
  [credential]
    helper = store --file=<tmpdir>/.git-credentials
GIT_CONFIG_GLOBAL=<tmpdir>/.gitconfig
HOME=<tmpdir>  (credential helper needs HOME for Unix socket)
```

**usernamePassword channel (HTTP Basic Auth):**
```
<tmpdir>/.gitconfig  (0600)
  [http "https://github.com"]
    extraHeader = Authorization: Basic <base64(user:pass)>
GIT_CONFIG_GLOBAL=<tmpdir>/.gitconfig
HOME unset (no credential helper needed)
```

Base64 encoding of `user:pass` happens **only in file content**, never in argv (INV-L5-CR-004).

### D5 — SHA-equality idempotency

The core idempotency guarantee:

```
1. ls-remote <url> <branch> → remoteSHA
2. if .git exists:
     localSHA = git rev-parse HEAD
     if localSHA == remoteSHA → emit GitCheckoutCompleted (no-op, <2s)
     else → git fetch + git reset --hard <remoteSHA>
3. else → git clone --branch <branch> <url> <rel>
4. if changelog: GitChangelogWriter.append(prevSHA..HEAD)
5. emit GitCheckoutCompleted
```

**INV-L5-CR-001:** SHA-equal no-op completes in <2s.

### D6 — Synchronous ls-remote poll (no daemon)

`GitPollExecutor.execute()` runs `git ls-remote <url> <branch>` synchronously. It does **not** spawn a background daemon. The poll result is used inline to decide clone vs. fetch+reset.

**INV-L5-CR-006:** Synchronous poll, no daemon.

### D7 — Plain-text changelog format

`<workspace>/changelog.txt` format (one entry per line):
```
<7-char-sha> <first-line-of-subject>  (max 256 chars, non-ASCII stripped)
```

Example:
```
a1b2c3d Initial commit
e4f5g6h Add feature X
```

Idempotent: `GitChangelogWriter.append()` parses existing SHAs and skips duplicates.

**INV-L5-CR-005:** Plain-text changelog, no JDOM XML.

### D8 — Four GitCheckout event variants

Four `DomainEvent` variants are emitted (D7 from requirements):

| Event | When |
|-------|------|
| `GitCheckoutStarted` | Before any git operation |
| `GitPollChanged` | `ls-remote` returns different SHA than `previousRemoteSha` |
| `GitCheckoutCompleted` | After successful clone, fetch+reset, or SHA-equal no-op |
| `GitCheckoutFailed` | After non-zero exit from clone/fetch/reset/ls-remote |

`GitCheckoutFailed.reason` is the first 256 chars of stderr, non-ASCII stripped — no secret material.

### D9 — Threat Model (R1–R8 with mitigations)

| ID | Threat | Mitigation |
|----|--------|------------|
| R1 | Credentials leaked in process argv | D2: credentials only in env vars + temp files |
| R2 | Temp credential files readable by other users | D3: `0600` on files, `0700` on parent dir |
| R3 | Temp credential files left on disk after kill | D3: `finally` wipe; SIGKILL residue documented |
| R4 | Authorization header in argv (git config injection) | D2: argv guard throws `IllegalArgumentException` if `extraHeader` or `Authorization` found |
| R5 | Shell injection via branch name metacharacters | D1: branch passed as separate argv element, not shell-interpolated |
| R6 | Malformed URL crashes executor | D8: `GitCheckoutFailed` emitted, no crash |
| R7 | Large changelog causes OOM | INV-L5-CR-005: streaming `git log` output, line-by-line processing |
| R8 | Network credential leak on auth fail | D4: credentials applied only after ls-remote succeeds; fail path has no credentials |

## Consequences

### Positive

- Git operations use the same CLI that developers use locally — predictable and auditable
- SHA-equality idempotency avoids unnecessary network traffic on re-runs
- Temp file wipe prevents credential residue on disk
- Argv guard fail-closed on forbidden substrings catches entire class of injection
- Structured events provide observable trace without secret material
- `file://` fixture repos enable fully offline testing

### Negative

- git CLI must be on PATH (enforced by `UatLocal005RequiresGitOnPathTest`)
- No support for SSH credentials at L5 (deferred to ML-R5.1)
- No shallow clone / partial clone (deferred to ML-R5.1)
- Changelog is plain text, not XML/Jenkins format (intentional — INV-L5-CR-005)

### ML-R5.1 Deferrals

| Feature | Reason |
|---------|--------|
| `sshUserPrivateKey` credentials | Requires different temp file strategy (private key file) |
| Shallow clone (`--depth`) | Requires `GitScm.depth` field + test infrastructure |
| `changelog` format options | Plain-text is L5 default; Jenkins XML format deferred |
| `poll()` interval configuration | L5 synchronous poll; interval/deferred poll deferred |
| Subversion/Mercurial SCM | Only `GitScm` at L5 per ADR-0046 §L5 |

## Architecture Constraints

| ID | Constraint | Reference |
|----|-----------|-----------|
| F-ARCH-L5-001 | CLI-git, not JGit | D1 |
| INV-L5-CR-001 | SHA-equal no-op <2s | D5 |
| INV-L5-CR-002 | No `org.eclipse.jgit` imports | D1 |
| INV-L5-CR-003 | Temp files `0600`/`0700`, wiped in `finally` | D3 |
| INV-L5-CR-004 | Credentials never in argv | D2 |
| INV-L5-CR-005 | Plain-text changelog | D7 |
| INV-L5-CR-006 | Synchronous ls-remote, no daemon | D6 |

## Jenkins Mapping Table

| Jenkins `git`/`checkout` param | v2 `GitScm` field | Status |
|-------------------------------|-------------------|--------|
| `url` | `url: String` | ✅ L5 |
| `branch` | `branch: String = "master"` | ✅ L5 |
| `credentialsId` | `credentialsId: CredentialsId?` | ✅ L5 (string + usernamePassword) |
| `changelog` | `changelog: Boolean = true` | ✅ L5 |
| `poll` | `poll: Boolean = true` (sync ls-remote) | ✅ L5 (sync only) |
| `relativeTargetDir` | `relativeTargetDir: String = "."` | ✅ L5 |
| `depth` | — | ❌ deferred ML-R5.1 |
| `shallowClone` | — | ❌ deferred ML-R5.1 |
| `sshCredentialsId` | — | ❌ deferred ML-R5.1 |
| `submodule` options | — | ❌ deferred ML-R5.1 |

## Canary Round Gate

ML-R5 uses a 32-byte random canary encoded in 5 forms (hex-upper, hex-lower, base64-std, base64-url, percent-encoded). The canary flows through the git credential path and must show **zero occurrences** in:

- `events.payload` (JSON event stream via `JsonEventLog.encode`)
- `operation_journal.input` (journal params — not yet implemented for checkout)
- Any temp file surfaces

This mirrors the ML-R4 `UatLocal008CredentialsTest` CR-RD-008 pattern. The canary is not a real credential — it exercises the entire credential path to verify that no surface leaks it.

**Stale-after:** 2027-08-27
