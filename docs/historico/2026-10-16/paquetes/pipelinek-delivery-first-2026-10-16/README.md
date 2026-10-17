# PipelineK — entrega de valor con calidad (paquete integrable, 2026-10-16)

**Destino:** `Rubentxu/pipeline-kotlin`, `main@5de40c45d7c4c75db3ca7799c89fbee6fb6c5f13` (lectura remota). **No se han ejecutado los tests ni alterado el repositorio remoto.** Propuesta operativa, no prueba de cierre.

## Objetivo y jerarquía

Este paquete NO sustituye `docs/v2/05-roadmap/ROADMAP.md`, `QUALITY_VALUE_ADDENDUM.md`, `AGENTS.md`, ADRs aceptados, ni el `pipelinek-release-harness`. Sus archivos se añaden como *deltas de implementación* de BQ1–BQ5, respetan las prioridades RP y las decisiones normativas. El *router* de comprobaciones es la skill `delivery-first-testing` en `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`: no se copia ni se bifurca su implementación. Las lenses se seleccionan por intención, riesgo y evidencia vigente; la ausencia de la skill instalada se declara, no se simula. La navegación `affordances.py` **sugiere acciones**; jamás certifica.

## Estructura

- `overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md`: cinco hitos grandes, un entregable usable por bloque, aceptación y publicación.
- `overlay/docs/v2/03-specifications/DELIVERY_FIRST_IMPLEMENTATION_DELTA.md`: cambios concretos en el código con ADTs y bordes de efecto; remite a specs existentes, no crea semántica paralela.
- `overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md`: **PROPOSED**; decide cómo conciliar el contrato de cinco assets con PR #142. No se aplica antes de aceptación.
- `overlay/docs/v2/07-uat/DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md`: catálogo mínimo UAT/E2E/AAT efímeras, oráculos, retiro seguro, contratos permanentes.
- `overlay/docs/v2/05-roadmap/DELIVERY_FIRST_AGENT_TEAMS.md`: delegación, ownership de ficheros, integración serial, límites, budgets y handoffs.
- `AGENT_PROMPT.md`: mandato listo para pegar al agente.
- `tools/integrate.py`: **solo modifica documentación** bajo el repo destino y añade dos enlaces a autoridades existentes sin reescribirlas. Dry-run por defecto.
- `tools/validate_package.py`: comprueba nombres, estructura, enlaces normativos y marcadores.

## Uso inmediato

```bash
unzip pipelinek-delivery-first-2026-10-16.zip -d /tmp/
cd /ruta/a/pipeline-kotlin
python3 /tmp/pipelinek-delivery-first-2026-10-16/tools/integrate.py --repo . --dry-run
python3 /tmp/pipelinek-delivery-first-2026-10-16/tools/integrate.py --repo . --apply
# revisar git diff y respetar el orden de ROADMAP.md; commit/docs PR si procede
```

La aplicación del paquete **no ejecuta tests, no publica, no crea tag, no hace push, no borra pruebas y no toca archivos productivos**. `integrate.py` rechaza cambios divergentes en los archivos nuevos existentes; los documentos aceptados nunca se sobrescriben. Las referencias a SHAs/release en los documentos son una fotografía inicial; **actualizar hechos tras `git fetch` y antes de cada bloque**. `ADR-PK-DF-001` es propuesta y necesita decisión de propietario si altera §8 de `AGENTS.md`; mientras tanto prima la regla de cinco assets.

## Hechos de partida / no confundir estados

- `main` observado `5de40c45`, versión declarada `0.62.0`; `v0.61.0` GA presente; `v0.62.0-rc5` candidata publicada con ZIP/SBOM/checksums y assets internos, sin SDK ZIP/BOM. Los commits posteriores a esa candidata construyen un SDK ZIP con layout relativo y prueba de extracción de consumidor.
- PR #142 (assets SDK/JAR-first) y PR #140 (inventario de tests) abiertas en la revisión; conciliarlas, no duplicarlas.
- El gate para SDK **local** no prueba resolución de SDK desde Maven remoto. `SDK_PUBLISHED` exige repositorio remoto de destino autorizado y consumidor limpio.
- `CANDIDATE_PUBLISHED`, `SDK_PUBLISHED`, `CERTIFIED` y `STABLE_PROMOTED` son veredictos separados. Si falla uno, no inferir el siguiente.
- Se detectó que `isProductionPath` ignora `v2/test-harness/` y scripts de release, que `:test` depende de builds de plugin, que `installedContractTest` selecciona un tag sin clases marcadas observadas, que `:test-harness:cycle` ignora errores, y que el script publicador y §8 discrepan. **Revalidar cada hallazgo en SHA activo** antes de modificarlo.

## DoD global: cero tests efímeros en la release

En cada hito: producto utilizable → tests específicos y pruebas de falsación → decisión sobre regresión (`PROMOTE/REPLACE/RETIRE/DEFER`) → conservar prueba permanente **mínima** de garantía crítica → eliminar SOLO ficheros de ciclo propios y fixtures exclusivas → comprobar `git status`, `git ls-files` → volver a verificar el commit limpio definitivo → integrar PR en `origin/main` → publicar **nueva** RC con tag/sha y assets reales → comprobación remota de completitud, integridad y uso. Si una garantía no tiene sustituto o hay un `BLOCKED`, no borrar la prueba ni publicar afirmaciones falsas. El hito se reporta `BLOCKED` con causa, mientras trabajos independientes pueden continuar.
