# S2-A2 / G4 — `core.sleep` REGISTRY_PRIMARY

`core.sleep` was removed from `LEGACY_PLUGIN_IDS` only. Legacy decoder, command,
metadata and dispatcher remain physically present for the removal gate.

```text
BEFORE: IDs=11, metadata=11, dispatchers=11, family=LegacyCore
AFTER:  IDs=10, metadata=11, dispatchers=11, family=Registry
```

`CoreSleepRegistryPrimaryFitnessTest` 4/4 PASS proves registry presence, exact residual
legacy set, descriptor metadata authority, real preparation/boundary zero-success, and
structured cancellation. Historical G3 pre-flip readiness is disabled with an explicit
supersession note. `core.sleep` remains IMPLEMENTED_UNCERTIFIED.
