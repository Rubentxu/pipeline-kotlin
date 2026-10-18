# Documentos activos y autoridad de V2

**Actualizado:** 2026-09-26 (TRAIN-0 cutover). **Estado:** índice de lectura ACTIVO.

## Autoridad operativa

```text
Git + ADRs/contratos publicados + evidencia externa (harness, GitHub)
      → autoridad efectiva
ROADMAP / ACTIVE_DOCUMENTS / ADR / specs / UAT
      → autoridad de producto/documental
.agent/SESSION_POINTER.md, .agent/WORK_JOURNAL.md, .agent/TECH_DEBT_BACKLOG.md
      → proyección humana opcional / histórico; NO autoridad
```

> **RESUELTO 2026-10-18.** Esta cadena nombraba `SDDK` como primer peldaño. Medido ese día,
> `command -v sddk` no devuelve nada y no hay `sddk` en `~/.local/bin`, `/usr/local/bin` ni
> `/opt`. Un peldaño que no se puede observar no puede gobernar, y un protocolo obligatorio cuya
> primera orden falla no es un protocolo: un operador que lo lea, lo ejecute y encuentre
> `command not found` no puede deducir qué hacer. `SDDK` sale de la cadena vigente y entra
> `Git + ADRs + ROADMAP`, que sí existen. Si SDDK volviera a instalarse, se reintroduce aquí por
> decisión registrada, no por inercia. La decisión y su razonamiento están en la nota SDDK de
> `AGENTS.md` (2026-10-18); el texto anterior se conserva arriba únicamente como historia.

> Cualquier discrepancia entre el contenido de `.agent/*` y el estado real (Git + ADRs) se resuelve a favor del estado real.

## Empezar siempre aquí

1. **El estado real se lee de Git y de las ADRs**, que son los peldaños que existen: `git status --short`, `git rev-parse HEAD`, `git log -1`, la rama, y el campo `status:` de cada ADR implicada. Un estado que nadie puede observar se escribe `NOT_RUN`; nunca se afirma. (El paso que antes ocupaba este número, `sddk status --cycle`, se retiró por la razón del bloque anterior; el histórico de ciclos vive hoy en el propio ROADMAP y en sus recibos, no en un planner.)
2. docs/v2/05-roadmap/ROADMAP.md — única secuencia de trabajo y exit criteria actuales.
3. docs/v2/07-uat/CERTIFICATION_PROTOCOL.md y PRODUCTION_READY_UAT_MATRIX.md — qué significa certificar.
4. docs/v2/01-product/STEP_REGISTRY_PLAN.md — cola histórica/constitución técnica del ecosistema; reconciliar su inventario con el estado medido (Git + ADRs) antes de ejecutar cualquier WU. (Esta línea decía «con el estado SDDK»; un planificador ausente no puede ser el estado contra el que reconciliar. Misma decisión y mismo reasoning que el bloque de autoridad de arriba.)
5. docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md, ADR-0070..0099 y AGENTS.md — límites normativos que un roadmap no revocar. ADR-0099 fija la autoridad de `main` para candidatos y releases y supersede ADR-0091.
   > **RESUELTO 2026-10-18 por [`ADR-PK-REG-001`](../04-adrs/ADR-PK-REG-001-adr-status-registry.md).** Esta línea ya no afirma que el rango 0070..0099 esté «aceptado» como bloque: **el registro de verdad es el campo `status:` de cada fichero**, y un rango no es una unidad de estado. El conjunto cerrado es `{proposed, accepted, superseded}` y `AdrStatusRegistryFitnessTest` lo hace cumplir con fallo cerrado. Para saber si una ADR concreta está en vigor, se lee su campo; este índice ya no lo afirma por rango.
   > Las dos mediciones del conflicto registrado eran de un **parseo**, no del corpus: sólo aceptaba `- **Status:** v` en línea, de modo que contaba 61 ADR «sin campo» que sí lo tienen en otra de las tres formas legales, y contaba 8 grafías que son casing, negrita y backticks sobre 3 valores. Cerrado también en `CONFLICT-ADR-STATUS-REGISTRY.md`, que se conserva por trazabilidad.
6. ADR-0105 — la publicación del check de admisión G10 pertenece a `pipelinek-release-harness`. Sin check publicado para el SHA candidato, G10 es `NOT_RUN` y el veredicto global es `RP-5 PRODUCT_GATE_STOP`; la ausencia nunca es `PASS`, y este repositorio no reintroduce GitHub Actions para cerrar esa fila.

## Proyección humana opcional (no autoridad)

- `.agent/SESSION_POINTER.md` — primera WU operativa, bloqueos, último commit de código conocido y primer comando. Consulta histórica, no autoridad.
- `.agent/WORK_JOURNAL.md` — diario append-only de decisiones y handoffs; no sustituye a Git ni a CI. Histórico, no autoridad.
- `.agent/TECH_DEBT_BACKLOG.md` — registro de deuda. Histórico, no autoridad.
- `.agent/TESTING-STATE.md` — topología/comandos conocidos. Histórico, no autoridad.

## Precedencia y resolución de conflictos

Contratos publicados + ADRs aceptados + especificaciones normativas > normas de seguridad/autorización de AGENTS.md e INITIATIVE_LPR_001 > roadmap activo (prioridades y gates) > matriz UAT vigente > puntero de sesión (estado) > receipts inmutables (evidencia del SHA histórico) > propuestas y planes anteriores. Si existen documentos normativos contradictorios, NO inventar un ganador: registrar el conflicto y requerir ADR. Un roadmap puede sustituir la SECUENCIA anterior, no los requisitos de seguridad.

El documento docs/v2/00-governance/DOCUMENT_AUTHORITY.md (2026-09-03) conserva el marco ADR-0064; esta actualización de 2026-09-21 fija la prioridad de producto conforme al cambio LPR de 2026-09-18 y al roadmap activo. No vuelve a abrir M3/ML/EM/EVT ya cerrados.

## Archivado vs. vigente

docs/historico/INDEX.md contiene el inventario de documentos trasladados. Los paquetes originales docs/pipeline-kotlin-*/ son propuestas empaquetadas y procedencia histórica; NO constituyen una cola adicional. El viejo roadmap cronológico y el primer roadmap LPR siguen accesibles allí. NO modificar recibos de certificación publicados para alterar su significado: emitir un nuevo recibo o una fe de erratas con referencia al SHA original.

## Norma de navegación y porcentajes

Señalar siempre SHA, fecha y origen al presentar un estado. Distinción obligatoria: REGISTERED, IMPLEMENTED_UNCERTIFIED, CERTIFIED_AT_SHA, RELEASED_ARTIFACT, VERIFIED_ON_CURRENT_HEAD y BLOCKED. Un recuento antiguo de 16, 19 o 20 Steps no reemplaza un inventario nuevo. Sin denominador comprobado no se publica % global.
