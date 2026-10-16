# Integración sobre el roadmap actual (sin autoridad paralela)

## Anclajes que ya existen

- `AGENTS.md` — política operativa, control de ciclo y release.
- `docs/v2/05-roadmap/ROADMAP.md` — autoridad activa de secuenciación RP0..RP9, B0..B7.
- `docs/v2/05-roadmap/MILESTONES.md`, `IMPLEMENTATION_BACKLOG.md` y `RESPONSIBILITY_MIGRATION_ROADMAP.md`.
- `docs/v2/03-specifications/PIPELINE_TEST_HARNESS.md`, `EXECUTABLE_SCENARIO_CORPUS.md`, `STEP_CONSTITUTION.md`, `STEP_PLUGIN_CERTIFICATION.md`, `DISTRIBUTION_RELEASE_SPEC.md`.
- `docs/v2/04-adrs/ADR-0071-executable-scenarios-are-specifications.md`, `ADR-0072-layered-test-harness-fidelity.md`, `ADR-0074-step-done-means-certified.md`, `ADR-0099-main-authority-release-candidates.md`, `ADR-0103-one-replay-authority.md`, `ADR-0105-admission-check-publication-authority.md`.
- `docs/v2/06-quality/TEST_STRATEGY.md` y `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`, `CERTIFICATION_PROTOCOL.md`.

## Reglas de precedencia

1. Código verificado + contratos publicados/ADRs **aceptados** + `AGENTS.md` > este paquete.
2. Roadmap activo dirige secuencia y release; `QUALITY_VALUE_ADDENDUM.md` aporta *técnica y evidencias*, no una nueva cola de features.
3. Los ADRs del paquete son `proposed`; solo pasan a `accepted` tras revisión del dueño y resolución de contradicciones.
4. El sistema externo de certificación permanece dueño de pruebas de distribución final, de checks y de promoción estable; no recrearlo aquí.
5. Este paquete no autoriza eliminar tests existentes porque se llamen UAT/E2E/AAT; exige demostrar sustitución en frontera equivalente.
6. Evitar `docs/historico` como autoridad para decisiones nuevas. No crear archivos de estado operativo dentro del repo.
7. Nuevas interfaces públicas, esquemas durables y reglas de release requieren autorización según `AGENTS.md` y ADRs existentes.

## Procedimiento de aplicación

1. `git status --porcelain` → limpio; `git rev-parse HEAD`; `git fetch origin main` y crear rama `feat/bq1-...` desde `origin/main`.
2. Comprobar existencia de todas las referencias en este documento y revisar `git log -n 20` / cambios recientes. Si hay un ADR supersedido, no interpretarlo como vigente.
3. Copiar **solo** `overlay/docs` en el primer PR como borrador de propuesta; no proclamar su aceptación con el copy.
4. Añadir a `docs/v2/05-roadmap/ROADMAP.md` **un único enlace** a la adenda aprobada, en sección de ejecución o índice. El roadmap normativo no se reescribe.
5. Implementar BQ1..BQ5 uno por uno. Cada bloque incluye capacidad observable, test permanente mínimo, ensayos UAT/AAT efímeros y retirada de efímeros antes del SHA final.
6. Publicar prerelease de acuerdo con los comandos y procedimientos reales del repositorio y el veredicto aplicable; no inventar un `./gradlew release` si ese task no existe.

## Fronteras de responsabilidad

`pipeline-kotlin`: código de producto, ADTs puros, selección de pruebas, contratos internos, compilación, `distZip`, RC, integración a `main`, recibo local.
`pipelinek-release-harness`: ejecución de distribución instalada, sandbox y consumidor real, evidencias de certificación, checks externos, decisión de estabilidad.
`pipelinek-fabric`: workers remotos, Jenkins, Kubernetes/control plane. No ampliar su implementación aquí.

## Tratamiento del conflicto de retirada heredada

`RESPONSIBILITY_MIGRATION_ROADMAP.md` especifica `@Disabled("migrated...")` y prohibición de borrar antes de equivalencia. La aspiración de este paquete es **limpiar por completo fuentes heredadas** *después* de esa equivalencia y de aprobar el ADR propuesto `ADR-PK-QV-003`. Si la aprobación no existe: se conserva el archivo con la disposición marcada, se **eliminan solo los probes del ciclo**, se publica una RC únicamente si sus gates de producto son válidos, y el hito de eliminación queda pendiente. Nada se declara `DELETE_READY` por conveniencia.

## No hacer

- No ejecutar limpieza automática de nombres `Uat*`, `*E2E*` o `*AAT*`.
- No reemplazar tests de contratos por documentación.
- No proclamar que una UAT externalizada está probada sin `CandidateId`, escenario y veredicto externo.
- No forzar `git push`, `merge` o tag si el gate está rojo; preservar historia de main y no hacer squash/force-push de candidatas publicadas.
- No añadir GitHub Actions como vía de escape de los gates ya acordados.
