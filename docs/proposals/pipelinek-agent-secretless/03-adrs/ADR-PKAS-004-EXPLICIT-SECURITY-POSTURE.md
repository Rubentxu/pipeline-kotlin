# ADR-PKAS-004 — Security posture explícita

**Status:** proposed.

## Decision

Toda integración declara una posture:

```text
STRONG_SECRETLESS
SHORT_LIVED_EXPOSURE
ISOLATED_PROCESS_EXPOSURE
RAW_PROCESS_EXPOSURE
UNSUPPORTED
```

El planner no puede promocionar una posture débil a fuerte por usar redaction o un fichero temporal.

## Admission

`minimumPosture` es parte del request/profile y se aplica fail-closed.

