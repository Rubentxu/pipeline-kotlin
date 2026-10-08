---
type: adr
id: ADR-0105
title: "Admission check publication belongs to the external harness; without it the PRODUCT-GATE is BLOCKED, never PASS"
status: accepted
date: 2026-10-08
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0099
  - docs/v2/08-production-readiness/PRODUCTION_READY_GATE.md
  - docs/v2/07-uat/CERTIFICATION_PROTOCOL.md
  - docs/pipelinek-release-evolution/shared/01-cross-repo-contract.md
  - AGENTS.md
---

# ADR-0105 — Quién publica el check de admisión (G10)

## Context

`PRODUCTION_READY_GATE.md` G10 exige tres cosas, y las tres se evalúan juntas:

```text
G10 — Admission publication
  - immutable receipt written
  - mandatory GitHub admission check published
  - branch/release protection observes that check
```

En este repositorio la segunda y la tercera no se pueden satisfacer, y no por
una omisión pendiente. Dos hechos medidos, no supuestos:

1. **No hay superficie de CI.** `.github/workflows/` no existe. El commit
   `754ddda0` ("chore(ci): remove dead GitHub Actions workflows", 2026-09-30)
   retiró `lpr0-ci.yml`, `release.yml`, `v2-baseline.yml` y
   `sdkman-publish.yml` tras 60 runs cancelados consecutivos con los runners
   self-hosted offline desde 2026-09-28.
2. **Nadie local puede publicar un check de GitHub.** El check run es un objeto
   de la API de GitHub sobre un commit de este repositorio. Crearlo exige
   credenciales de escritura sobre `Rubentxu/pipeline-kotlin`, y el agente que
   trabaja aquí no las tiene ni debeirlas tener.

La contradicción es real y ya estaba reconocida a medias: `AGENTS.md`
(Política de verificación, 2026-10-03) declara el `PRODUCT-GATE` como
`BLOCKED_EXTERNAL` "mientras no exista superficie de CI". Eso resuelve **el
estado**, pero no resuelve **quién publica G10** cuando esa superficie llegue,
y el contrato cross-repo tampoco lo dice: asigna al harness el *verdict* y la
*elegibilidad estable*, y no menciona el check de admisión en ninguna de sus
catorce secciones.

Sin esta decisión, G10 es un requisito que ningún actor del sistema puede
sostener. Un requisito que nadie puede cumplir no es un requisito: es una
condición que se cumple sola cuando nadie mira, que es exactamente el modo de
fallar que este repositorio ya ha pagado.

## Decision

G10 tiene un dueño externo y un contrato de handoff explícito.

```text
pipeline-kotlin
  produces:  candidata inmutable (bytes + digest + descriptor)
  writes:     recibo inmutable por SHA
  does NOT:   publicar checks, ni fingir que lo hizo

pipelinek-release-harness
  verifies:  la candidata que le llegue
  emits:     verdict (CERTIFIED | CERTIFICATION_FAILED | BLOCKED_ENV |
                        SUPERSEDED | NOT_RUN)
  publishes: el check de admisión vinculante al SHA y al digest
```

Cuatro consecuencias, y las cuatro son obligatorias:

**D1 — La fila de G10 se evalúa contra el harness, no contra este repositorio.**
Mientras no exista un check publicado por el harness para el SHA candidato, G10
es `NOT_RUN` y el veredicto global es `RP-5 PRODUCT_GATE_STOP`. No hay un
tercer valor, y `GREEN_WITH_ASSUMPTIONS` sigue prohibido.

**D2 — Un harness sin veredicto vinculante produce `BLOCKED`, no `PASS`.**
Ausencia de veredicto, harness caído, o veredicto que no enlace al digest exacto,
son la misma cosa para efectos del gate. La asimetría es deliberada: el coste de
un falso verde es una candidata publicada sin certificar; el de un falso rojo es
un ciclo más.

**D3 — Este repositorio no reintroduce GitHub Actions para satisfacer G10.**
La retirada de `754ddda0` fue una decisión registrada, no un descuido. Revertirla
por presión de un gate sería degradar un requisito de seguridad y certificación
para hacer pasar una condición. Si la superficie de CI vuelve, vuelve por
decisión registrada en `AGENTS.md` y `CERTIFICATION_PROTOCOL.md`, no como
efecto lateral de un gate que no se puede cerrar de otra forma.

**D4 — La dependencia es verificable y externa, no una nota.**
Si el harness no puede publicar el check, eso es una **dependencia explícita al
proyecto propietario**, con la capacidad que falta enunciada: publicar un check
run de admisión ligado a (SHA, artifact digest) y observado por la protección de
ramas. No se implementa un segundo harness aquí, ni un emisor local de verdicts.

## Lo que NO decide

- **No reintroduce CI.** `D3` lo excluye explícitamente.
- **No degrada ninguna condición de seguridad o certificación.** `G1`..`G9` se
  siguen evaluando igual.
- **No cambia la autoridad del harness sobre el verdict ni sobre la promoción
  estable.** Eso ya lo fija `01-cross-repo-contract.md` §2 y §8; este ADR
  únicamente añade quién materializa el check.
- **No reabre STEP-CERT.** Un Step-CERT nuevo tampoco vuelve verde el
  PRODUCT-GATE; eso ya lo dice `CERTIFICATION_PROTOCOL.md` §4 y sigue vigente.

## Consecuencias

### Positivas

- G10 deja de ser un requisito sin dueño. Si mañana el harness publica checks,
  el gate se puede evaluar sin cambiar una sola línea de este repositorio.
- La contradicción entre la política local (sin CI) y el gate deja de ser una
  tensión permanente y pasa a ser una dependencia declarada.
- Un candidato no certificado no puede aparecer como `PRODUCT_GATE_GO` por
  omisión: la ausencia de check es `NOT_RUN`, que es un `STOP`.

### Negativas y costes asumidos

- **El gate no puede cerrarse localmente mientras tanto.** Es el coste correcto:
  el gate mide capacidad externa, y fingirlo rebajaría el gate.
- **Existe una dependencia de proyecto que este repositorio no controla.** Se
  acepta y se registra, en vez de construir una capacidad local que duplicaría el
  harness y crearía dos autoridades sobre el mismo veredicto.

### Estado actual verificado

`BLOCKED_EXTERNAL` en G10. No hay check de admisión publicado para ningún SHA de
este árbol, y `.github/workflows/` no existe. El PRODUCT-GATE global es
`RP-5 PRODUCT_GATE_STOP`, y lo seguirá siendo hasta que el harness emita un
veredicto vinculante para una candidata concreta.
