# SPEC-PK-QV-04 — Ciclo de pruebas efímeras y retirada segura

**Estado:** PROPOSED. Integra `EXECUTABLE_SCENARIO_CORPUS.md` + `CERTIFICATION_PROTOCOL.md`; respeta `RESPONSIBILITY_MIGRATION_ROADMAP.md`.

## Propiedad y almacenamiento

- `CycleManifest` contiene `cycleId`, `sourceSHA`, `tasks`, `probes`, `contracts`, `expectedHF`, `replacement` y política de aislamiento.
- Directorio de pruebas temporales fuera del Git productor, bajo `workspace/.pipelinek-cycles/<cycle-id>/` (ignorado y nunca añadido al repo); al cerrar se borra este directorio o las rutas exactas registradas.
- Los `.pipeline.kts` canónicos del corpus **no son temporales** y se conservan según ADR-0071.
- Evidencia raw en storage externo del harness / artefactos CI; repo guarda ruta/URI/digest, jamás secretos.

## Estados

```text
Draft -> Running -> Observed -> Promoted -> Closed
               \-> Refused
               \-> BlockedExternal
```

`Promoted` significa: toda garantía necesaria tiene contrato permanente (test unit/fitness/property) o recibo equivalente del harness sobre el `CandidateId` **exacto**. No es una afirmación genérica de `PASS`.

## Acciones de lifecycle

`prepare`: valida contrato de entrada y ubicación de directorios.
`run`: ejecuta probes con timeout, isolation, no shared state, productor/candidato correcto; recoge exit code y resultados discriminantes.
`adjudicate`: combina plan, XML/probes, mutations, sustitutos y fidelidad con una función pura total.
`close`: si verdict `Verified`, borra exclusivamente paths del manifiesto; no borrado global `*Uat*`/`*E2E*`. Si `Blocked`, conserva referencia y retorna código distinto de cero.

## Invariantes de cierre

- Fichero de prueba temporal inexistente al terminar el bloque, y tampoco en `git ls-files` del SHA de release.
- `sourceSHA` y artifact sha256 de evidencia coinciden con el que se afirma proteger (si hay binario real).
- Toda observación `Skipped`, `NotRun`, `NoEvidence` o `OtherCommit` niega `Verified`.
- Un `CycleProbe` de HF3+ que usa doble `run` sin kill real es inválido para esa frontera.
- Prohibido promover por eliminar un test; primero guardar pruebas sustitutas y ejecutar mutantes relevantes.
- Si falta veredicto del harness, el estado es `BlockedExternal`; no intentar fabricar `CERTIFIED` local.

## Matriz mínima de sesión

| Probe | HF | Experimento | Resultado observado | Futura garantía |
|---|---|---|---|---|
| AAT (negativa) | HF0 | mutar condición de decisión | falla la propiedad concreta | contrato de ADT |
| UAT (usuario) | HF1..HF2 | DSL `.pipeline.kts` ejecuta funcionalidad | outcome + efecto + diagnóstico | escenario/contract y/o certifier |
| E2E (frontera) | HF2..HF6 | build ZIP, instalar, matar/reintentar si aplica | bytes y estado correctos | certifier externo |

## Eliminar al cerrar bloque

1. Identificar archivos creados por `cycleId`.
2. Ejecutar y verificar mutaciones/pares negativo-positivo.
3. Registrar sustitutos y hashes de evidencia; `retirement_guard` valida cada fila.
4. Borrar SOLO los archivos temporales listados (no tests heredados), comprobar ausencia en tree y registrar conteo real de eliminaciones.
5. Release gate contra código FINAL sin probes temporales: las leyes permanentes siguen protegiéndolo.
