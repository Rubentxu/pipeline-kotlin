# SPEC-PK-DF-MIN — Validación mínima con calidad de producto y poda radical

**Estado:** PROPUESTA complementaria para el caso de aceptación de ADR-PK-DF-002; **no desplaza** los contratos vigentes antes de esa aceptación. Autoridades existentes: `TEST_EXECUTION_LANES_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, ADR-0072, ADR-PK-QV-003, ADR-0105.

## 1. Objetivo único

Maximizar entrega de capacidades a usuarios con pruebas **mínimas y discriminantes**. No hay que demostrar 7 capas de QA en cada iteración, ni un catálogo por fichero, ni generar tests de tests, ni guardar recibos extensos en Git. Las pruebas dan evidencia, no constituyen una segunda aplicación.

## 2. Niveles de obligación: riesgo antes que inventario

| Riesgo/cambio | Desarrollo/merge | Integración o RC | Obligación duradera |
|---|---|---|---|
| Refactor interno puro sin cambio público | 1–N tests afectados, compile | suite centralizada en RC | tests semánticos existentes, cero E2E nuevas |
| Step/DSL/API pública | tests del contrato público, 1 negativa si aplica | 1 consumidor externo si es nuevo contrato publicado | 1 contrato estable por capacidad |
| Salida, cursor, secretos | unit/contract del redactor + cursor | 1 caso real instalado de flujo/secretos | ley de no fuga y bytes/canales |
| Persistencia/replay/kill | decider + store affected | 1 HF3 kill/restart real (o evidencia equivalente) | no duplicación ni pérdida; fallo claro |
| Plugin/SDK/ABI | API/codecs/ServiceLoader | 1 consumidor Gradle/Maven externo real y rechazo incompatible si se cambia admisión | compatibilidad sin test de cada fixture |
| Sandbox/seguridad | contrato de aislamiento y negativa | HF4/5 según cambio real, certifier externo | no violación de permisos/escape |
| Build/release/artefactos | comprobación de metadatos/identidad | ZIP instalado + 5 assets exactos/checksums/SBOM y certifier | productor y release-harness con responsabilidades separadas |

ADR-0072 mantiene **HF0..HF6** como taxonomía de fidelidad; ninguna fase ejecuta automáticamente todos los niveles. Se exige el nivel necesario **por garantía** y un test que realmente la observe. HF1 no sustituye HF3.

## 3. Tres gates, no tres motores

1. **DEV/MERGE:** `cd v2 && ./gradlew :modulo:test --tests <ClaseReal>` cuando aplica + tareas afectadas de lint/compilación. Las tareas Gradle con etiqueta `installed-contract` solo corren en `installedContractTest`; los plugins externos en `pluginContractTest`. Gradle no usa `--rerun-tasks` por rutina y la caché solo cuenta si su procedencia y selección son correctas. `0 tests` y `all skipped` no descargan una obligación.
2. **INTEGRACIÓN/RC:** gate centralizado vigente de `check` y `releaseGate` **hasta** la enmienda aceptada. Tras ella, la suite amplia corre en la frontera de integración del bloque/RC; los merges solo se amplían por riesgo, sin reejecutar todos los tests. La suite de integración no debe convertirse en la suma de todos los antiguos E2E migrados.
3. **CERTIFICACIÓN externa:** `pipelinek-release-harness` instala **los mismos bytes** candidatos, observa comportamiento real y publica check/estado G10 según ADR-0105. No volver a implementar una FSM de certificados dentro de `pipeline-kotlin`.

**Chequeo operativo negativo mínimo:** cuando se reforma un gate, demostrar UNA vez que un fallo propio del producto lo pone rojo. No añadir 10 pruebas de la negativa del gate ni chain de asserts de `isNotBlank`.

## 4. Inventario y condición de borrado

Clasificar cada test no por prefijo de nombre, sino por: `CONTRACT_KEEP` (public/critical, discriminante), `REPLACE` (consolidar), `RETIRE` (código muerto o cobertura duplicada), `EXTERNAL_RC` (evidencia desde ZIP), `BLOCKED_POLICY` (aún protegido normativamente). Un fichero puede mezclar tests con varias categorías. `migrated-pending` no significa que la migración haya ocurrido. Eliminar después de demostrar la garantía en sustituto o ratificar la enmienda correspondiente; tests `@Disabled` sin ejecución ni consumidores conocidos son candidatos prioritarios, no pruebas de seguridad.

### Cadena de decisión mínima

```text
capacidad del consumidor → riesgo real → 1 test económico de seam →
¿necesita proceso/ZIP/reinicio? → 1 comprobación externa en etapa apropiada →
PASS observado / FAILED / NOT_RUN / BLOCKED →
PROMOTE pequeña | REPLACE | RETIRE temporal
```

Nunca inventar un resultado `PASS`. Si una fuente Kotlin de QA ya no tiene consumidor, borrarla junto a sus tests, no reemplazar con otro montón de componentes Kotlin.

## 5. Ciclo UAT/E2E/AAT temporal

`mktemp -d` fuera del Git → ejecutar escenario real con timeout, workspace aislado y aserciones públicas → guardar 6 campos: capacidad, SHA/CandidateId, comando, ejecutados/fallos/skips, resultado, regresión sustituta si se necesita → borrar SOLO ficheros propios → validar sobre código final. No snapshots de cientos de líneas, no test `TestTest`, no código de sondeo en src/main.

Valores orientativos: **0 E2E por defecto**, **1 UAT CLI** para feature observable sin test previo, **1 E2E HF3** si se tocó recuperación, **1 E2E consumidor** para nueva ABI, **1 smoke instalado por candidata** reutilizable; **AAT solo ante riesgo concreto**, integrada con el escenario anterior si basta.

## 6. Invariante de economía y alcance

- **Fuente del producto no depende de código de QA.** El evento-verifier público de `pipeline-event-harness` no se retira por el nombre.
- **No mantener pruebas deshabilitadas solo por historia.** Git es la historia, previa aprobación de retirada de legacy normativas.
- **No mantener modelos de evidencia/catálogo/plan/runner si su única función es producir más pruebas.** Retirar y delegar en Gradle/skill/certifier.
- **No añadir un test por cada carrier de QA.** Kotlin + tests de comportamiento garantizan la semántica.
- **Para reglas críticas de seguridad, replay, pérdida, compatibilidad y distribución** sí mantener test discriminante, preferentemente uno estable en lugar de varios frágiles.
- **No usar cobertura porcentual para forzar tests** sin nuevo defecto/riesgo. Las cifras de duración y coste son informativas para decidir qué eliminar, no otra puerta P0 basada en una meta arbitraria.

## 7. Criterios de salida por bloque

1. El consumidor usa una feature real o ve corregido un defecto relevante.
2. Los contratos afectados corrieron (>0) y protegen al menos un fallo real; los omitidos no se disfrazan.
3. Todas las UAT/E2E/AAT del ciclo están borradas o promovidas justificadamente; ningún QA de ciclo se empaqueta.
4. Se recortó QA obsoleta si existía, y no se dejó una sustituta igual de compleja.
5. Commits, integración y RC según autoridad; `CANDIDATE_PUBLISHED` separado de `CERTIFIED`.
