# Specification — Config sources and honest resource budgets

## 1. V1 lookup decision

Do not restore the V1 generic `${...}` lookup mini-language. It conflates configuration, files, encoding, structured data and secrets.

## 2. Typed config sources

If configuration indirection is required, model non-secret sources explicitly:

```kotlin
sealed interface ConfigValueSource {
    data class Literal(...)
    data class Environment(...)
    data class SystemProperty(...)
    data class File(...)
}
```

Secret material belongs to the credentials/profile programme, not generic lookup.

JSON/YAML/TOML parsing, base64 and hashing are utility operations/plugins rather than interpolation magic.

## 3. Resource budgets

Do not port V1 JVM `ResourceLimitEnforcer` as CPU/memory enforcement.

Without an OS/container substrate PipelineK may honestly enforce only budgets it actually controls, for example:

- wall-clock deadlines;
- output byte retention limits;
- retry/attempt limits;
- wait budgets;
- HTTP body limits;
- artifact size/count limits where the owning component can enforce them.

CPU/memory quotas remain out of scope for this programme.

## 4. Exit criteria

- no new generic secret-capable interpolation language;
- every advertised budget has a real enforcement owner;
- documentation distinguishes observation from enforcement.

## 5. Compiler/cache/session budgets

Specs 20/21 may enforce retained artifact counts/bytes, pending request count and owned deadlines. Loaded classes/native compiler memory, RSS and open handles are separate observations; heap flags do not become per-pipeline quotas. Cancellation is certified only where the pinned backend or owned isolated process actually stops work.

Default resolver/compiled-artifact caches and dependency snapshots remain XDG user-owned. Compiler-profile/cache policy comes from the current typed configuration owner; no script interpolation or duplicate CLI configuration authority.
