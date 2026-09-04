# SPEC-LFC-007 — Environment and credentials

**Status:** proposed

## Canonical environment precedence

```text
host/base runtime environment
-> pipeline environment
-> stage environment
-> block overlays (`withEnv`)
-> credential projection
-> tool/PATH additions
-> sandbox/security filtering
-> process environment
```

`EnvironmentComposer` is the sole authority for computing effective process environment. `EnvModel` is removed after migration.

## Credentials

`withCredentials` is fail-closed. If the requested provider/binding/secret is unavailable:

```text
CredentialUnavailable -> StepOutcome.Failure
```

The body MUST NOT execute without injection.

## Local providers

Initial providers may include:

- encrypted local credential file;
- explicitly opted-in environment credential mapping;
- later command-based provider adapters (`pass`, `op`, OS keychain) behind the same port.

No Vault/controller dependency is required for local-first 1.0.

## Secret material

- never persist raw secret values in event/output/journal stores;
- temporary files use restrictive permissions and deterministic cleanup;
- redaction is applied before persistence and terminal fan-out;
- borrowed secret material has explicit lifetime.
