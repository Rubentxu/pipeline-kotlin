# ADR-LFC-023 — Step Done means Certified

**Status:** proposed

## Context

El origen de muchas regresiones es llamar "implemented" a una feature cuando sólo existe una de sus capas.

## Decision

Un Step tiene estados formales:

- `DESIGNED`
- `IMPLEMENTED_UNCERTIFIED`
- `CERTIFIED`
- `QUARANTINED`
- `RETIRED`

`DONE/PASS` no se usa para un Step uncertified.

## Certification dimensions

Aplicables según Step:

1. descriptor/contract;
2. input codec;
3. output codec;
4. positive DSL compile;
5. negative DSL compile;
6. canonical IR;
7. registry;
8. capability admission;
9. handler success;
10. typed failure;
11. observability;
12. cancellation;
13. replay;
14. body contract;
15. credentials/security;
16. real distribution;
17. Jenkins compatibility;
18. executable scenario.

Core y plugins externos usan exactamente la misma suite.

## Consequence

El roadmap deja de medir presencia de código y empieza a medir evidencia de comportamiento.
