# PK-57A — Protocolo verificable, ejecución observable y RC inmutable

**Última actualización:** 2026-10-13T08:00Z
**HEAD al abrir:** `e69c6c8f187fc340a32f1a825954424c4d5a771e` (+1 docs commit: `a904438c`)
**Ciclo:** `pk-release-0-57-consolidation`
**WorkItem SDDK:** `93158a45-2997-49ff-9a85-7906c56344c6`
**Estado de entrada:** `0.57.0 — IMPLEMENTED_UNCERTIFIED / RELEASE_BLOCKED`
(R1-F + R2 + R3-0 cerrados en código y evidencia; R3-1 ejecución controlada
bloqueada por entorno; R4 / R5 autoridad externa)

## Mandato

Cerrar PK-57A sobre la rama `pk-release-0-57-consolidation`, publicando
`v0.57.0-rc1` como `CANDIDATE_PUBLISHED` (nunca `PRODUCT_GATE_PASS` ni
`PAIR_CERTIFIED` — esos quedan para PK-57B con veredicto independiente).

Refs operativas verificadas al abrir:

| Ref | Esperado por mandato | Real |
|---|---|---|
| `origin/main` | `05ce6236...` | `05ce62361b4f75e86fa1cec1b26a0ac821ac0a21` ✓ |
| Branch HEAD | `e69c6c8f...` | `e69c6c8f187fc340a32f1a825954424c4d5a771e` (+1 `a904438c` docs) |
| `v0.56.0` tag | `2dfe7396...` | `2dfe739657f6470d6ed22537da3a4c0e052040ee` ✓ |
| `v0.57.0*` tag | ninguno | ninguno ✓ |
| `gh auth` | Rubentxu activo | ✓ scopes `gist,read:org,repo,workflow` |
| Load 1-min | <5 (umbral `BLOCK_E_RUN_21`) | 5.21-7.21 esta mañana; trending down |

## Defectos a cerrar (nombrados por el operador en el roadmap)

El propio `docs/Roadmap_Agente_PipelineK_2026-10-13.md` lista:

1. **`OutputProtocol.kt` wire ids** — acepta `output/v1` y `output/v2`.
   El ADR exige el patrón `pipelinek.*/N` y declara explícitamente
   `pipelinek.output/2`. Hay que alinearlo, no parchearlo con tests
   sintéticos.
2. **`Main.kt:1281` negociador** — implementa `tokens.last()` en lugar
   de intersección de preferencias. La regla del ADR es "el cliente
   pasa una lista ordenada por prioridad; el servidor falla cerrado
   si la intersección es vacía". Ningún fallback silencioso.
3. **Ausencia de tests del negociador** — la matriz del ADR exige
   casos para `[válido,inválido]`, `[inválido,válido]`, vacío,
   duplicados, ids desconocidos, priorización, y salida tipada con
   exit 2 al fallar cerrado.

## Tareas

- [ ] **T1** `OutputProtocol.kt` — renombrar wire ids a `pipelinek.output/1` y
  `pipelinek.output/2`. Añadir `companion.negotiate(tokens): OutputProtocol?`
  que aplica la regla de intersección (vacío → DEFAULT; primer id
  soportado en orden del cliente; sin intersección → null). Tests directos.
- [ ] **T2** `Main.kt:1281-1295` — reemplazar `negotiateOutputProtocolOrExit`
  por una implementación que llama `OutputProtocol.negotiate(...)` y emite
  el envelope `CliExit.InvalidArgument` con la lista de ids soportados al
  no haber intersección.
- [ ] **T3** `CliParser.kt:915-917` — corregir el comentario que dice
  "the last one wins"; ahora declara que se acumula una lista de
  preferencia (ordenada por prioridad) y la resolución es por intersección.
- [ ] **T4** Test nuevo `OutputProtocolNegotiationTest.kt` con la matriz:
  vacío, `[válido]`, `[inválido]`, `[válido,inválido]`, `[inválido,válido]`,
  duplicados, `[inválido,sin-soporte]`, todos desconocidos.
- [ ] **T5** Build con `releaseGate` verde (`./gradlew --no-daemon
  --max-workers=1 :pipeline-release:releaseGate`). Requiere load 1-min <5.
  Si no es posible, queda `BLOCKED_ENVIRONMENT` honesto y NO se publica.
- [ ] **T6** Suite `:pipeline-application:test` verde, incluyendo
  `ProcessOutputDecoderTest`, `CliContractTestKitTest`,
  `OutputProtocolNegotiationTest`, `DistributionMandatoryPluginsTest`.
- [ ] **T7** Suite release UAT/AAT contra el binario extraído del ZIP
  (`./gradlew --no-daemon :pipeline-release:uatInstalled`).
- [ ] **T8** Regenerar artefactos `evidence/0-57-rc1/` (zip, sbom, poms,
  module) si T5..T7 pasan; nuevo `SHA256SUMS` self-verify 9/9;
  actualizar `STATUS.md` a `CANDIDATE_PUBLISHED` con `R3 PASS` real,
  no por reinterpretación.
- [ ] **T9** Push rama → abrir PR base=main → tras revisión (RDD gate si
  aplica) merge por policy → tag anotado `v0.57.0-rc1` sobre el commit
  exacto construido → `gh release create --prerelease` con zip/sdk/sbom/
  poms/module + SHA256SUMS + RECEIPT-R3 + HANDOFF-TO-CERTIFIER.
- [ ] **T10** `PK_CONTRACT_HANDOFF` a Fabric con `product_version`,
  `published_sdk_coordinates`, `sdk_bom_sha256`, `distribution_zip_sha256`,
  `abi_diff`, `supported_protocol_ids=("pipelinek.output/1","pipelinek.output/2")`,
  capabilities, refusals enumerados, `runId` provenance, SLO medido,
  PASS/FAIL/SKIP exacto de UAT/AAT, autoridad de certifier independiente
  (Firma ausente → estado `CANDIDATE_PUBLISHED`, no `PAIR_CERTIFIED`).

## Criterios de salida

- Protocolo de salida **y bytes reales** coinciden con contrato.
- CLI instalada rechaza un protocolo no soportado con exit 2 tipado.
- Matriz 8 del ADR + adversarial pass.
- Ningún artefacto stale.
- Balance PASS/FAIL/SKIP explícito.
- Gate local `:pipeline-release:releaseGate`, `:pipeline-application:test`,
  suite UAT/AAT sin omitidos obligatorios.
- `v0.57.0-rc1` publicado como pre-release; `0.57.0 — CANDIDATE_PUBLISHED`
  (no `PRODUCT_GATE_PASS`); `PK_CONTRACT_HANDOFF` enviado a Fabric.

## Condiciones explícitas para NO avanzar

- Load 1-min ≥5 → NO correr gradle, NO publicar.
- Cualquier `releaseGate` rojo / omitido → NO publicar release de ese alcance.
- No reinterpretar FAIL como PASS modificando el test.
- No reescribir tag `v0.56.0` previo.
- Certifier independiente ausente → NO afirmar `PAIR_CERTIFIED`.

## Working tree no relacionado

`examples/04-kotlin-control-flow.pipeline.kts` tiene `M countdown 3→13 + sleep 1`
(no commiteado). No es parte del scope PK-57A — queda fuera del commit
del bloque, sin tocarlo.