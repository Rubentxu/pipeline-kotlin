# Architecture Fitness Functions

## F-ARCH-001 — Domain framework-free
Fail si `pipeline-domain` importa Jenkins, Kubernetes client, Koin, Docker client o concrete DB packages.

## F-ARCH-002 — Application depends inward
Application sólo depende de domain + approved language/runtime abstractions.

## F-ARCH-003 — Experimental scripting containment
Imports `kotlin.script.experimental` sólo en `pipeline-scripting-kotlin*` y tests de compatibilidad.

## F-ARCH-004 — No compiler plugin requirement
Sample V2 completo compila/ejecuta con FIR/IR custom plugin disabled.

## F-ARCH-005 — No arbitrary wire types
Protobuf/event DTOs no contienen Java serialization blobs, `Any`, `Map<String, Any>` o Throwable serializado.

## F-ARCH-006 — No secret event fields
Schema scanner bloquea nombres/tipos catalogados como secret material en EventEnvelope payloads salvo referencias opacas.

## F-ARCH-007 — Graph rebuild
Borrar materialized graph y re-proyectar event log produce snapshot equivalente.

## F-ARCH-008 — Idempotent reducer
Aplicar dos veces el mismo eventId no cambia estado tras la primera.

## F-ARCH-009 — Fencing
Evento con token menor al owner actual nunca modifica run state.

## F-ARCH-010 — Explicit plugin metadata
Stable plugin build falla si Step carece de effects/replay/capabilities/schema metadata.

## F-ARCH-011 — V2 no compile excludes
No se permiten `compileKotlin.exclude` sobre sources V2. Un archivo inacabado vive en source set/spike separado.

## F-ARCH-012 — Documentation examples compile
Code snippets marcados como executable example se extraen/compilan en CI.
