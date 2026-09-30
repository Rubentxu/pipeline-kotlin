# ADR-PKAS-009 — Profiles como policy compuesta

**Status:** proposed.

## Decision

Un profile puede agrupar credentials, sandbox, network, timeout y output policy. No contiene secretos.

La composición debe ser determinista y conservadora: deny y minimum-posture se vuelven más restrictivos al combinar.

