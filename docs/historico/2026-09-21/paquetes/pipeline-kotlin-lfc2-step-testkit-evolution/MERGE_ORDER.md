# Merge order

## 1. Decisiones

Añadir:

- ADR-LFC-018 — Closed execution structure, open Step registry.
- ADR-LFC-019 — Core and external Steps share one path.
- ADR-LFC-020 — Examples are executable specifications.
- ADR-LFC-021 — Layered Test Harness fidelity.
- ADR-LFC-022 — Block Steps re-enter engine through BodyInvoker.
- ADR-LFC-023 — Step Done means Certified.

## 2. Especificaciones

Añadir SPEC-LFC-016..021.

## 3. Roadmap

Fusionar el contenido de `docs/v2/05-roadmap/ROADMAP_DELTA.md` en el roadmap LFC canónico.

La secuencia no es accidental:

`Constitution → Harness → Generic atomic seam → core proof → external plugin proof → certification → strict DSL → bodies → retry/timeout → parallel → scripted runtime values → conditions/post → formal scripting → closure`.

No mover la prueba de plugin externo al final. Si esperamos hasta tener decenas de Steps migrados podemos consolidar de nuevo una falsa extensibilidad.

## 4. Backlog y UAT

Fusionar los delta de backlog y UAT respetando dependencias y gates.

## 5. AGENTS.md

Insertar `integration/AGENTS_STEP_PLUGIN_CONSTITUTION.md` tras la sección actual de Step semantics.

Los ADR/SPEC son autoridad arquitectónica; `AGENTS.md` es la traducción operativa diaria.

## 6. Examples

No hacer un rename masivo inicial.

Primero:
- ScenarioRunner soporta layout plano actual.
- luego sidecar manifests;
- después bundles para escenarios complejos.

## 7. Conflictos con cambios posteriores al baseline

Si el repo avanzó:

1. conservar comportamiento ya cerrado;
2. no resucitar rutas legacy;
3. re-mapear IDs si colisionan;
4. fusionar semántica en tipos canónicos existentes;
5. no crear una segunda autoridad paralela;
6. mantener los mismos exit criteria aunque cambien nombres de tipos.
