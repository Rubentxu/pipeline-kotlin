# Estado actual y deuda que V2 debe resolver

> Snapshot de referencia: rama `main`, 2026-08-21.

## Observaciones

### Kotlin
`settings.gradle.kts` configura actualmente Kotlin/KSP en la línea **2.2.0**. V2 propone elevar la línea certificada inicial a **2.4.10**.

### Core parcialmente excluido
`core/build.gradle.kts` mantiene una lista extensa de `exclude(...)` sobre DSL, engines, execution, compilation, security, plugins, libraries, steps, modelos, context, events y Jenkins. El compiler plugin de `@Step` aparece deshabilitado por errores IR.

### Testing incompleto
Hay tests y módulos de testing deshabilitados o filtrados. M0 debe recuperar una baseline honesta antes de ampliar producto.

### Generaciones arquitectónicas superpuestas
Coexisten distintos enfoques para context, DSL, runners, step discovery, compiler plugin, sandbox y eventos. V2 no debe intentar fusionarlos todos; usará **Strangler Fig interno**.

## Deuda conceptual

1. Dependencias escondidas vía Service Locator.
2. Koin visible en abstracciones del core.
3. Closures ejecutables almacenadas como modelo de pipeline.
4. Runtime y construcción del DSL mezclados.
5. Reflexión + string dispatch como mecanismo primario de Steps.
6. `Map<String, Any>`/`Any?` en contratos susceptibles de cruzar procesos.
7. Eventos in-memory sin journal duradero.
8. Sandbox basada en Security Manager de Java.
9. Classpath completo del host expuesto al script.
10. Compiler internals en el camino crítico.
11. Tests excluidos como estado estable.
12. Documentación que afirma capacidades sin evidencia CI/UAT.

## Clasificación de migración

Cada componente V1 se etiquetará:

- **KEEP**: reutilizable sin contaminar V2.
- **ADAPT**: útil detrás de un port.
- **REWRITE**: semántica aprovechable, implementación inadecuada.
- **RETIRE**: duplicado o incompatible con V2.
- **SPIKE**: exploración sin compromiso de producción.
