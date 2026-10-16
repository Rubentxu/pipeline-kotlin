---
type: adr
id: ADR-PK-QV-001
title: Functional decisions and isolated effect interpretation
status: accepted
date: 2026-10-15
accepted: 2026-10-16
deciders: Rubentxu
related:
  - ADR-0070
  - ADR-0103
  - docs/v2/03-specifications/STEP_CONSTITUTION.md
---

# ADR-PK-QV-001 — Núcleo funcional y fronteras de efectos

## Contexto

El runtime durable, CLI, SDK y handlers combinan decisiones, estado e I/O en algunas clases grandes. Esto dificulta probar una condición sin un subprocesso o un Gradle build completo. No se concluye de ello que el engine esté roto; es un objetivo de mejora **por vertical**.

## Decisión propuesta

1. Cada capacidad modificada tendrá un *propietario* de decisión identificable; su función `decide(state,input): Decision` será total y sin side effects. Inputs son snapshots tipados.
2. Los efectos se modelarán como puertos/interpreters fuera del dominio y se validarán en un nivel de fidelidad independiente. Reloj, filesystem, Git, proceso y storage se inyectan en el borde.
3. Emplear ADTs `sealed` existentes; crear un tipo nuevo solo si expresa diferencia semántica verificable. Un `when` exhaustivo rechaza estados desconocidos, no introduce defaults de silencio.
4. No se duplican replay authorities, secret-redaction authority, ownership de filesystem ni outcome canónico. Reutilizar ADR-0103 y definiciones de `pipeline-domain`/`pipeline-runtime-api`.
5. Extender DSL y plugin registry sin `when` sobre Step keys concretos: estructura cerrada, extensiones abiertas (ADR-0070).

## Alternativas

- *Mock todo el runtime:* rápido pero no comprueba fronteras reales.
- *Refactorización masiva:* alto riesgo y escaso valor de producto inmediato.
- *Introducir librería FP general:* nueva dependencia y ontologías equivalentes sin beneficio demostrado.

## Consecuencias y compatibilidad

Refactorizar gradualmente bajo una funcionalidad visible, con tests de propiedades y testigo de distribución/consumidor si afecta frontera. No cambiar serialización durable, eventos ni ABI sin la autorización y migración normativa vigentes.

## Comprobación

Mutación de una rama pura ⇒ contrato falla; mal wiring/dispatch ⇒ suite de frontera falla; replay no duplica side effects; fallos desconocidos no mutan estado. Aceptación **pendiente del owner**.
