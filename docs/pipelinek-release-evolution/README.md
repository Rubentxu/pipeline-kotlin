# PipelineK — Evolutivo de Release Fast-Lane / Certification Hard-Lane

## Objetivo

Preservar el motivo original de separar `Rubentxu/pipeline-kotlin` y `Rubentxu/pipelinek-release-harness`:

- `pipeline-kotlin` maximiza **entrega de valor**, feedback rápido y calidad local suficiente para producir candidatas frecuentes.
- `pipelinek-release-harness` concentra las validaciones **caras, lentas, adversariales y multi-entorno**, coalesce candidatas y certifica únicamente la candidata relevante de cada release train.

El incidente `v0.43.0` demostró que la separación era correcta, pero la frontera de responsabilidades no estaba suficientemente cerrada: el harness no debe transformar semánticamente los bytes del producto al promocionar una RC a GA.

## Principios vinculantes

1. **Fast lane / hard lane**: el upstream nunca espera al harness para continuar main.
2. **Candidate immutability**: una candidata es una identidad material inmutable por SHA-256.
3. **Latest wins por release train**: el harness no certifica FIFO todas las candidatas si una posterior las supersede.
4. **No semantic transformation on promotion**: promoción significa publicar los bytes certificados, no renombrar/rewritear una identidad binaria diferente.
5. **Product identity is upstream-owned**: versión, ZIP, layout, JAR manifest y `pipelinek version` los produce `pipeline-kotlin`.
6. **Independent verification is harness-owned**: el harness no confía en claims upstream; los verifica desde bytes instalados.
7. **Expensive assurance lives in the harness**: mise/asdf, upgrade/rollback, kill/restart, multi-project, seguridad, performance, concurrency y real toolchains no vuelven al fast lane.
8. **No ambiguous binary resolution in certification**: el harness nunca ejecuta `pipelinek` desnudo sin saber exactamente qué instalación está usando.

## Documentos

### Compartidos

- [`shared/01-cross-repo-contract.md`](shared/01-cross-repo-contract.md) — contrato entre repositorios.
- [`shared/02-release-model-v2.md`](shared/02-release-model-v2.md) — nuevo modelo Candidate/TargetVersion/GA.
- [`shared/03-handoff-schemas.md`](shared/03-handoff-schemas.md) — esquemas de handoff y verdict.
- [`shared/04-migration-plan.md`](shared/04-migration-plan.md) — migración desde RC SemVer transformada a candidata material.

### `pipeline-kotlin`

- [`pipeline-kotlin/01-responsibilities-and-spec.md`](pipeline-kotlin/01-responsibilities-and-spec.md)
- [`pipeline-kotlin/02-agents-patch.md`](pipeline-kotlin/02-agents-patch.md)
- [`pipeline-kotlin/03-roadmap.md`](pipeline-kotlin/03-roadmap.md)
- [`pipeline-kotlin/04-uat.md`](pipeline-kotlin/04-uat.md)

### `pipelinek-release-harness`

- [`pipelinek-release-harness/01-responsibilities-and-spec.md`](pipelinek-release-harness/01-responsibilities-and-spec.md)
- [`pipelinek-release-harness/02-agents-patch.md`](pipelinek-release-harness/02-agents-patch.md)
- [`pipelinek-release-harness/03-roadmap.md`](pipelinek-release-harness/03-roadmap.md)
- [`pipelinek-release-harness/04-uat.md`](pipelinek-release-harness/04-uat.md)

### Apéndice

- [`appendix/asdf-pipelinek-followup.md`](appendix/asdf-pipelinek-followup.md) — cambios necesarios en el tercer repositorio de distribución `asdf-pipelinek`.

## Orden recomendado

```text
R0  Congelar publicaciones 0.44 mientras cambia el protocolo
    |
    +--> pipeline-kotlin P0: TargetVersion + candidate manifest + cheap identity gate
    |
    +--> harness H0: identity verification + candidate scheduler latest-wins
    |
    +--> contrato cross-repo v2
    |
    +--> candidate de reparación 0.43.1
    |
    +--> harness certifica 0.43.1
    |
    +--> promoción exacta, sin transformación
    |
    +--> reanudar candidate train 0.44
```

El desarrollo normal de features en `pipeline-kotlin` puede continuar durante el trabajo del harness; sólo se congela la **publicación bajo el protocolo roto**, no `main`.
