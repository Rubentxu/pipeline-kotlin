# SPEC-LFC-014 — Local execution and sandbox profiles

**Status:** proposed

## Naming

Do not call normal cwd/env filtering a strong sandbox.

```kotlin
enum class ExecutionProfile {
    STANDARD_LOCAL,
    HARDENED_LOCAL,
}

enum class IsolationBackend {
    NONE,
    BUBBLEWRAP,
    CONTAINER,
}
```

## 1.0 scope

`STANDARD_LOCAL` is mandatory and portable. It provides explicit workspace, controlled environment composition, secret scoping, process-tree lifecycle and output controls.

A Linux `HARDENED_LOCAL` backend (candidate: bubblewrap) is a spike/milestone option after the core is stable. Container isolation is an adapter, not the universal execution model.

## Permissions

Plugin manifests declare required high-level permissions/capabilities. These are admission/documentation inputs and may later map to sandbox policy.
