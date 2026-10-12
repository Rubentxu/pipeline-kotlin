# Protocolo de integración de DOS repositorios — PRF-1

## Responsables

- **PK Producer Agent** — propietario de `Rubentxu/pipeline-kotlin`, ABI, lecturas seguras, ejecución y recibo PK.
- **Fabric Consumer Agent** — propietario de `Rubentxu/pipelinek-fabric`, adapter real, tests cruzados, scheduler y recibo Fabric.
- **Pair Integrator** — árbitro del contrato CRIC-1, matriz de versiones, gate y promoción del par. Puede ser un tercer agente o uno de los dos en una fase independiente, pero no acepta auto-aseveraciones sin evidencias de ambos repos.

**Autoridad única de la pareja:** `PAIR_RECEIPT.json`, conservado como artifact de release/admisión en el workspace de integración, e indexado en el roadmap de la iniciativa de Fabric. No almacenar `STATE/CURRENT/JOURNAL` en los repos. Una copia del recibo en ambos sitios es solo espejo verificado por SHA, jamás una nueva autoridad.

## Flujo de dos fases más publicación secuencial

```
CONTRACT_PROPOSED --(revisión CRIC + fixtures en ambos)--> CONTRACT_LOCKED
         |                                                       |
         |                      IMPLEMENTACIÓN PARALELA          |
         | PK: puertos/ABI candidatos   FAB: adapter/consumer     |
         v                                                       v
PK_PREPARED <------ interop sobre artifacts candidatos ------> FAB_PREPARED
         |                                                       |
         +-------- pruebas cruzadas sobre SHAs exactos ----------+
                                 |
                          PAIR_CANDIDATE_GREEN
                                 |
                 PK publica primero SI cambió su ABI
                                 |
            Fabric resuelve artifact PK PUBLICADO y vuelve a probar
                                 |
                   Fabric publica release remoto
                                 |
        Resolver AMBOS artifacts publicados + replay E2E + matriz
                                 |
                          PAIR_CERTIFIED
                                 |
                     habilita inicio de M(n+1)
```

`CONTRACT_LOCKED` fija el contenido SHA-256 de `INTERFACE_CONTRACT.md`; cualquier cambio requiere actualizar el mismo documento en ambas ramas y repetir contratos, pruebas y aprobación. No modificar un solo lado bajo el pretexto de «añadir un campo opcional».

## Seguridad del orden (evitar interbloqueo)

**Desarrollo paralelo permitido** bajo dos worktrees y artifacts `candidate` inmutables, con contrato congelado. **Promoción bloqueada** hasta aprobación cruzada. No exigir que ambas publicaciones sucedan de forma atómica en Git (no existe transacción entre repos Git). Cuando cambia PK, su release compatible hacia atrás sale primero; Fabric consume la distribución publicada, certifica, integra y publica después. El hito solo se declara íntegramente completo al final. Si Fabric falla, PK puede permanecer publicado como proveedor backward-compatible, pero el par sigue `BLOCKED` y M(n+1) no empieza. No borrar/reversionar tags para fingir atomicidad.

## Estados válidos del gate

`CONTRACT_PROPOSED`, `CONTRACT_LOCKED`, `PK_PREPARED`, `FAB_PREPARED`, `PAIR_CANDIDATE_GREEN`, `PK_RELEASED`, `FAB_RELEASED`, `PAIR_CERTIFIED`, `BLOCKED`.

`BLOCKED` exige motivo, owner, test rojo o recibo de falta de medición, acción correctiva y punto de reentrada. No crear una etiqueta `DONE` por haber abierto dos PR. Un cambio en cualquier SHA de release invalida el recibo; se regenera y se repite suite aplicable.

## Reglas de PR, Git y Release

1. PRs cruzados con enlaces explícitos a `Rubentxu/pipeline-kotlin` y `Rubentxu/pipelinek-fabric`, con mismo ID de hito y mismo fingerprint de contrato. Ambos PRs inicialmente draft.
2. En cada PR incluir: consumer/proveedor, superficies ABI afectadas, capacidades, oráculos UAT/AAT, artifact de la otra parte, evidencias SHA, compat N/N-1, rollback y enlaces recíprocos.
3. Ejecutar **CI local obligatorio** en proyectos que lo requieran; Jenkins/runner independiente puede publicar un required commit status, sin introducir GitHub Actions cuando está descartado por la política del repo.
4. La subida remota se verifica por `git ls-remote`: no aceptar SHA de `git tag -l` local. Artefactos SDK reales se resuelven del repositorio de distribución y se comprueban por digest y test de consumer, nunca como `mavenLocal` en certificación.
5. `PAIR_CERTIFIED` exige `--strict-remote` y receipt con SHA exacto, runbook de release, todos los pruebas obligatorias y `PAIR_RECEIPT.json` de dos releases (un release puede ser reutilizado si no hay cambios).
6. Antes de mover M(n+1), el Pair Integrator comprueba que M(n) está certificado y que las ramas remotas no se han sustituido por un SHA no probado. Si hay drift, BLOCKED hasta recalcular el par.
7. Si existe un tercero que puede empujar a `main`/tags sin gate, **no hay bloqueo técnico real**. Se recomiendan reglas GitHub para branches/tags con required status `cross-repo/paired-release` desde CI local/Jenkins externo + revisiones; si la cuenta carece de permisos para configurar rulesets, declararlo como limitación, NO afirmar enforcement remoto.

## Rollback

- Data formats dual-read; nunca borrar stream/pins. El registro de release pareja mantiene un último par certificado estable.
- Si PK vN nuevo está publicado pero Fabric vN falla: mantener Fabric vN-1 compatible, corregir Fabric en otra candidata; no reescribir vN de PK.
- Si Fabric vN fue publicado y falla antes de `PAIR_CERTIFIED`: deshabilitar feature por negociación si puede hacerse sin pérdida; revertir rollout operativo a último par certificado, no sobreescribir tags.
- Si llega un mensaje irreversible y no existe reader compatible, bloqueo de rollback explícito y plan de migración; no prometer rollback seguro.

## Cadencia de agentes (handoff obligatorio)

- PK → Fabric: `PK_CONTRACT_HANDOFF.md` + SDK artifact candidato/publicado, hashes, idempotencia/errores, ABI y pruebas.
- Fabric → PK: `FAB_CONSUMER_VERDICT.md` con rangos exactos de compat, tests, divergencias y siguientes capacidades.
- Integrator → ambos: `PAIR_RECEIPT.json` o `BLOCKED_RECEIPT.md`. Ningún agente usa estado verbal del otro para promover releases.

## Integridad temporal de la secuencia M0 → M7

El recibo de M0 no necesita predecesor. **Para M1..M7 el verificador requiere además `--previous-receipt`**, cuyo `milestone`, `pair_id` y SHA256 deben coincidir exactamente con `previous_pair` del recibo actual. Un M3 no puede desbloquearse por un M1 aunque todo lo demás esté verde. Si se modifica el contrato compartido, abrir revisión y registrar `contract_migration` con SHA anterior/nuevo, aprobación de ambas partes y prueba `GATE-CONTRACT-MIGRATION`; ambos paquetes deben tener los mismos bytes. Un cambio breaking exige CRIC-2 y adaptación del verificador, no cambiar silenciosamente la identidad de CRIC-1. Los recibos se conservan inmutables fuera de los repos y se enlazan desde ambos roadmaps; no se sobrescriben.
