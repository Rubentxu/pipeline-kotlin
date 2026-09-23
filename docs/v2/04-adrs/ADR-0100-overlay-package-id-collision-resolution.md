# ADR-0100 — Resolution of ADR-ID collision for the local-first configuration overlay package

**Estado:** proposed (2026-09-23).
**Autor:** pipeline-kotlin (Rubentxu) under auto-run mode, INITIATIVE_LPR_001.
**SHA base:** 74b40a65 (post WU-RP-040 R5..R8 + LPR-0 hardening).
**Aplica a:** V2 desde este SHA, una vez firmada. NO modifica ADRs vigentes.

## Contexto

El paquete **`docs/pipeline-kotlin-config-overlay-package/`** (untracked,
preservado en el árbol de trabajo desde antes del ciclo actual) propone 4
ADRs en estado `proposed`:

| ADR propuesto por el paquete                   | Tema                                    |
| ---------------------------------------------- | --------------------------------------- |
| `ADR-0096-local-first-configuration-behavior-boundary`     | boundary entre config y comportamiento |
| `ADR-0097-legacy-dsl-as-compatibility-overlay`             | DSL legacy = overlay de compatibilidad |
| `ADR-0098-single-resolved-plugin-set`                      | un único conjunto de plugins resuelto |
| `ADR-0099-effective-run-plan-functional-core`              | `EffectiveRunPlan` autoridad de admisión |

Esos IDs **ya están firmados en el repositorio** con semánticas
completamente distintas:

| ID        | ya firmado como                                                                  | estado     |
| --------- | -------------------------------------------------------------------------------- | ---------- |
| `ADR-0096` | `rp042-manifest-limitation-reevaluation` (WU-RP-042 release)                     | accepted   |
| `ADR-0097` | `credential-linked-secret-resolver-port` (WU-RP-049 R1)                         | accepted   |
| `ADR-0098` | `secret-store-linked-secret-resolver-adapter` (WU-RP-050)                        | accepted   |
| `ADR-0099` | (libre en `docs/v2/04-adrs/` — pero reclamado por el paquete como `proposed`)     | proposed   |

Esto produce un **conflicto permanente de IDs**, no resoluble
automáticamente. Si el paquete se incorporase tal cual, sus ADRs llegarían
como `proposed` y NO podrían firmarse sin reasignación. Mientras
`status: proposed` se preserve, el conflicto es nominal (no toca ADRs
firmados). Pero la primera WU que avance propuesta del paquete a
`status: accepted` chocaría contra el árbol real.

## Decisión (propuesta)

Reasignar IDs del paquete a un **rango libre** sin colisión, evitando el
hueco `0094` (cubierto por la propuesta paralela
`ADR-0094-common-impact-selection-policy.md`) y el rango bajo `0100+` que
esta misma ADR ocupa. Propuesta concreta:

| ID vigente en el paquete      | reasignación propuesta |
| ----------------------------- | ---------------------- |
| `ADR-0096-local-first-…`      | `ADR-0201-…`           |
| `ADR-0097-legacy-dsl-…`       | `ADR-0202-…`           |
| `ADR-0098-single-resolved-…`  | `ADR-0203-…`           |
| `ADR-0099-effective-run-plan` | `ADR-0204-…`           |

`0201..0204` no se usan en el repositorio (`git ls-tree HEAD docs/v2/04-adrs/`
lo confirma) y abren un rango `\>= 0200` dedicado a integraciones futuras
sin colisión con la serie 00xx.

Operativamente:

1. El paquete (en su forma actual untracked) NO toca el repo. Permanece
   en `docs/pipeline-kotlin-config-overlay-package/` preservado para
   evidencia.
2. Si el operador decide incorporar el paquete, las 4 ADRs propuestas se
   mueven a sus nuevos IDs y se commitean como `proposed`. El SESSION_POINTER
   registra la reasignación.
3. `status: accepted` requiere explícitamente la firma humana (no se
   asciende en auto-run).

## Consecuencias (positivas)

* El rango `0201..0204` queda nominalmente reservado para integraciones,
  futuros plugins o specs que necesiten rangos superiores.
* La próxima persona que lea el repositorio no verá el conflicto "0096
  colisiona" en cualquier grep.
* El ADR-0094 propuesto en paralelo (impact-policy) puede firmar sin
  chocarse con el paquete.

## Consecuencias (negativas / trade-offs)

* Cambiar el ID de un ADR requiere renombrar el archivo y actualizar
  referencias en otros docs. Si se hace automáticamente hay que mantener
  un mapping explícito para no perder referencias.
* Si se decide nunca incorporar el paquete, el rango `0201..0204` queda
  simplemente sin usar (sin coste, pero con ruido nominal).

## Alternativas consideradas

1. **Renombrar los ADRs firmados con esos IDs a otro número.** Rechazada:
   rompe enlaces históricos, recibos y la regla "alter historical
   receipts → STOP".
2. **Dejar el conflicto permanente, marcar los ADRs del paquete como
   `withdrawn`** hasta que se les reasigne ID. Aceptable como paso
   intermedio, pero no resuelve el rango de IDs `0201..0204` ni la
   trazabilidad de la decisión.
3. **Crear la presente ADR-0100 + reasignar a `0201..0204`** (esta
   opción). Trazabilidad explícita, sin tocar firmados.

## Pendiente (requiere decisión humana para firmar)

Firmar esta ADR **NO** modifica ADRs vigentes. La reasignación posterior
de los ADRs del paquete **sí** es una operación material (renombrar
archivos, actualizar refs) y queda bajo INITIATIVE_LPR_001 §2.4 hasta que
se ejecute.

## Estado del gap

* El paquete está preservado como untracked.
* El conflicto de IDs está documentado en el SESSION_POINTER y en la rama
  actual `adr/0094-impact-policy-and-overlay-id-gap.md`.
* Esta ADR se publica como **propuesta**; el operador puede firmarla o
  refinarla.
