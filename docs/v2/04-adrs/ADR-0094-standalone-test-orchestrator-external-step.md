---
type: adr
id: ADR-0094
title: "Motor Kotlin independiente para selección y UAT; Step externo de pipelinek"
status: proposed
date: 2026-09-21
deciders: "Rubentxu, pipeline-kotlin"
related: [ADR-0070, ADR-0072, ADR-0074, ADR-0082]
---

# ADR-0094 — Intelligent Test Orchestrator (ITO)

## Contexto
En septiembre de 2026, el carril application-focused no termina de modo fiable; la batería amplia y opaca dificulta diagnosticar el proceso/test que no progresa. AGENTS.md ya establece selección progresiva pero just changed propone módulos completos, y :pipeline-application:test arrastra installDist y construcción del plugin externo. Un cambio mínimo puede disparar una verificación costosa. No afirmar que se ha demostrado un test colgado concreto sin identificarlo con evidencia. Este ADR no sustituye el trabajo RP-0/RP-1.

## Decisión propuesta
1. Crear un motor Kotlin autónomo fuera del build V2, usable como CLI incluso si pipelinek no compila. Kernel puro de impacto y planes; adaptadores de Git, YAML, proceso, frameworks de testing y evidencia.
2. Integrarlo **después** como OFFICIAL_PLUGIN externo mediante StepDefinitionContributor/Step SDK público, no como Step core. El plugin usa el mismo kernel por API Kotlin, sin invocar la CLI si está en la misma JVM y sin introducir rutas per-Step en coordinator/compiler.
3. Reutilizar runners reales (Gradle/JUnit y command primero; Maven, pytest, Jest/Vitest, Cargo y Go por demanda). El núcleo no reinventa assertions de los frameworks. Adaptador tipado debe conocer granularidad de selección y el formato de reporte; no traducir selectores incompatibles a ciegas.
4. Política YAML versionada en Git del proyecto, de tamaño mínimo. Git change snapshot + ownership + contratos/consumidores inversos + riesgo producen selección explicable; impacto UNKNOWN amplía alcance conservadoramente. Histórico observado sirve para **ordenar** pruebas y optimizar lotes, nunca para omitir obligaciones.
5. Datos operativos, logs, cache, XML, BD y temporales fuera del repositorio por defecto (XDG Linux y convenciones macOS/Windows); modos ephemeral/local/ci. Solo configuración y fixtures/versionados necesarios pertenecen al proyecto.
6. Dev/verify admiten pruebas acotadas y evidencia local reutilizable con fingerprint conservador; integración y release ejecutan el conjunto obligatorio íntegro sobre SHA/artefacto candidato, con evidencia fresca según gate normativo. Ningún resultado omitido o cacheado de dev certifica release.
7. UAT como escenarios declarativos de setup → execute → assert → cleanup con oráculos reales, aislamiento proporcional al riesgo y evidencia tipada. HF0..HF6 = fidelidad; T0..T5 del CERTIFICATION_PROTOCOL = capas de prueba; alcance/selección es otro eje. No renombrar taxonomías ni eliminar UAT V2.
8. Supervisión de procesos: drenar stdout/stderr antes y durante wait, deadline monotónico, cancelación y terminación de descendientes, diagnósticos, resultados tipados y no-0-tests falso PASS. @Timeout JUnit no sustituye al cleanup real.

## Dependencias, límites y consecuencias
- RP-0 WU-RP-005 corrige y caracteriza hang con herramientas existentes; RP-1 permanece prioritario; motor CLI opt-in entra en RP-4 solo después de hitos anteriores; plugin externo en RP-7, tras RP-5. No abrir una cola de Steps en paralelo ni cambiar required checks de CI por promesas del selector.
- ADR-0070/STEP_PLUGIN_SDK/STEP_ECOSYSTEM_POLICY prohíben bypass del registro. STEP_PLUGIN_CERTIFICATION C01..C19 y G0..G8 + Event Harness obligatorios para el plugin; cualquier carencia del SDK requiere seam genérica y segunda prueba consumidora, no excepción de testing.
- La ejecución de tests produce efectos y depende de entradas externas; ReplayPolicy, intentos, cancelación y no-duplicación deben definirse y comprobarse antes de certificar el Step. Un resultado histórico memoized no equivale a una certificación del nuevo SHA.
- No prometer aislamiento de código no confiable por usar un worktree/tempdir. Para ese perfil hacen falta OS sandbox, permisos, egress y UAT de seguridad.
- Alternativas descartadas: ejecutar siempre ./gradlew check; meter el motor en pipeline-application; framework propio que reemplace JUnit/pytest; estado en .testing bajo Git; seleccionar solo por nombre de fichero; lanzar tests y declarar PASS sin XML/oráculo requerido; activar plugin antes del cierre RP-5.
- Estado: **PROPOSED, no implementado**; contratos aceptados, protecciones y autorizaciones previas continúan vigentes. Detalle: INTELLIGENT_TEST_ORCHESTRATOR.md; plan: INTELLIGENT_TESTING_ROADMAP.md; UAT: INTELLIGENT_TEST_ORCHESTRATOR_UAT.md.