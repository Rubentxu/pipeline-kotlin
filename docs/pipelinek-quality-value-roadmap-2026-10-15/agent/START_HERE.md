# TODO de arranque para el agente (un hito completo por release)

- [ ] **BQ1: Verdad de selección/evidencia.** Implementar decider puro de cambio→contratos y XML→veredicto. Demostrar rechazo de 0 tests, skipped total, SHA ajeno y recursos desconocidos; borrar probes UAT/AAT antes de integrar; `main`+RC.
- [ ] **BQ2: Desarrollo realmente rápido.** Separar tests afectados rápidos de `installDist`/plugins/restart; medir cinco iteraciones comparables; trasladar solo fixtures y contratos necesarios; borrar probes antes de integrar; `main`+RC.
- [ ] **BQ3: Una mejora funcional de runtime/DSL.** Seleccionar defecto real; ADT y función pura + interpreter único, no segundo replay owner; caso usuario demostrable y mutante negativo; borrar probes antes de integrar; `main`+RC.
- [ ] **BQ4: Lifecycle de UAT/E2E/AAT de ciclo.** Preparar/ejecutar/verificar/promover/borrar probes con manifiesto y guard fail-closed; demostrar rechazo y seguridad; limpiar probes; `main`+RC.
- [ ] **BQ5: Reducir suites heredadas de manera segura.** Censo completo, clasificación por comportamiento, equivalencia externa, enmienda al protocolo histórico si hay aprobación, eliminación puntual y ahorro medido; limpiar probes; `main`+RC.

Cada hito requiere: referencias exactas a specs existentes, dueño de cada contrato, pruebas del cambio y negativas, costes reales, cambio en función útil, integración remota, tag de RC alcanzable desde `main`, ZIP+manifest+SBOM+SHA256SUMS, y estado de certificación externa sin fingir PASS.

**Mandato detallado:** `MANDATO.md`.
**Roadmap canónico (adenda):** `overlay/docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md`.
**Si un bloque falla por dependencia externa:** declarar `BLOCKED_EXTERNAL`, no reportar el milestone como completo ni inventar release.
