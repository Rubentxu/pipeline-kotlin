# WU-RP-042 S1 — Reproducible distZip + zero-install + real-toolchain UAT (partial WU slice)

Fecha: 2026-09-22T19:55Z · Base: 736fb320 (RP-041 audit) · Modo: AUTO

## Alcance ejecutado en este slice

1. **Reproducibilidad del distZip (doble build bit-a-bit)**
   - Build incremental (1 tarea ejecutada) y `--rerun-tasks` (48 tareas ejecutadas)
     sobre el mismo HEAD producen bytes idénticos:
     `sha256 = 06c88aaf71b116e46b531df75533e16ef9a1aa13ecad2bbf53790b2baaf42348`
   - Los flags `isPreserveFileTimestamps=false` + `isReproducibleFileOrder=true`
     (root `v2/build.gradle.kts:113`, WU-LPR-070) están activos y verificados
     (todas las entradas del ZIP con fecha 1980-02-01).
   - El ZIP antiguo certificado en 951b3cb5 difiere (`385b140c…`) — esperado:
     el release law exige publicar el ZIP del SHA probado, no reutilizar el antiguo.

2. **Instalación de cero desde el ZIP exacto**
   - `unzip pipelinek-0.39.0.zip` en directorio limpio → `pipelinek version`
     → `pipeline 0.39.0`; `pipelinek doctor` → exit 0, workdir writable.

3. **Proyectos reales (distribución instalada, no installDist local)**
   - Gradle: `integration/gradle-demo` → `GRADLE-DEMO-OK`, outcome=success,
     jar producido (`test -f build/libs/gradle-demo.jar` green).
   - Maven: `integration/maven-demo` → `MAVEN-DEMO-OK`, exit 0.
   - Node: `integration/node-demo` → `NODE-DEMO-OK`, exit 0.
   - **Fallo de compilación del proyecto real**: `pipeline-fail.kts` (test roto
     `-Pdemo.broken=true`) → outcome=failure, failureKind=SCRIPT, exit 1.
   - **Fallo de compilación del script**: `retry(2)` dentro de `options{}`
     (root pipeline.kts) no compila → VALIDATION FAILED, exit 2, diagnóstico
     con línea/columna. (Hallazgo colateral: root pipeline.kts NO es ejecutable
     por la distribución actual; ver R1 abajo.)

4. **Restart/rollback**
   - `--resume` sobre run completado: evento-stream idéntico byte a byte en
     timestamps y secuencias (replay desde journal, 0 re-ejecuciones;
     `operation_journal` intacto: 3 filas SUCCEEDED; `replay_cursor` preservado).
   - `--resume` sin run previo → typed rejection, exit 2 (WU-LPR-011 F4).
   - `--rerun` sobre run fallido → re-ejecuta y clasifica failure de nuevo (exit 1).

5. **CLI inspect**
   - `pipeline events --db <db> <runId> [--kind K]` lista el journal durable.
   - `pipeline events verify --contract <events.yaml>` disponible.
   - Corpus: 31/31 fixtures `v2/compatibility/*.pipeline.kts` validan OK
     (`pipeline validate`), 0 fallos.

6. **Credenciales (uso real) — DEFECTO ENCONTRADO Y CORREGIDO**
   - **Defecto**: `pipeline credentials add --kind secret-text <id>` fallaba SIEMPRE
     con `CredentialsId value must not be blank`: los 7 readers construyen el
     credential con placeholder `CredentialsId("")`, y el `init{ require(non-blank) }`
     de `CredentialsId` (presente desde T1, 9c6181e7) lo rechaza. La CLI nunca fue
     ejecutable end-to-end; ninguna UAT anterior ejercitó `add` real (sólo el path
     runtime con stores pre-creados). El orden del fallo (tras derivar KEK y leer
     el secreto) explica el spinner ">...." observado.
   - **Fix (7 sitios, MainCredentialsCli.kt)**: placeholder typed no-blank
     `CredentialsId("<pending-store-id>")` — la clave de entrada del store es el
     `id` argumento de `SecretStore.add(id, credential)`, no `credential.id`;
     el placeholder nunca se persiste como entry key.
   - **Circuito completo verificado tras el fix** (distribución reinstalada):
     `credentials add ci-token` → "stored successfully"; `credentials list` →
     `ci-token SecretText GLOBAL`; pipeline `credtest.kts` con
     `withCredentials(string("ci-token","API_KEY"))` →
     `CredentialBound` → `sh` con inyección verificada (`CRED-BOUND-OK`,
     valor comparado) → `CredentialUsed` → `CredentialUnbound`; outcome=success.
   - **Fail-closed**: sin store → `No WithCredentialsExecutor configured`
     (INFRASTRUCTURE, exit 1) — la goesla no se salta.
   - **Redacción**: 0 leaks del valor secreto en consola; evento-stream persiste
     sólo ids (`ci-token`). NOTA: 1 coincidencia en el journal SQLite es el
     **comando del propio script** (el UAT compara `$API_KEY` contra el literal),
     no un leak del canal de secretos; rediseñar el fixture para no escribir el
     literal en futuras corridas de caracterización.

## Cambios de producción

- `MainCredentialsCli.kt`: 7 placeholders `CredentialsId("")` →
  `CredentialsId("<pending-store-id>")` + comentario de causa. Cero cambios de
  contrato público; el ADT `CredentialsId` y `SecretStore` intactos.

## Tests/evidencia

- Evidencia de shell (comandos + logs bajo /tmp/rp042-*.log): doble build SHA
  idéntico; tres runs de proyectos reales; resume byte-idéntico; add/list/run
  de credenciales; corpus 31/31 validate.
- **Pendiente para cierre de WU (R2)**: test JUnit de regresión del fix CLI
  (add via consola simulada), re-validación T2 de los módulos tocados, gate L5,
  y re-evaluación formal UAT-RP-005 inv 3 / ADR-0095 antes de release.

## Hallazgos clasificados (para el charter de la WU)

- R1 (nuevo, menor): root `pipeline.kts` usa `retry(2)` en `options{}` que el
  compilador rechaza (receiver); el script de release NO es ejecutable hoy.
  El DSL de `OptionsScope` sólo soporta `timeout(seconds)`. Decisión requerida:
  ampliar `OptionsScope` con `retry(count)` o corregir el script. Documentado,
  no bloquea este slice.
- R2 (proceso): CLI `credentials` ignora `PIPELINE_CREDENTIALS_STORE`
  (usa `~/.pipeline/credentials.bin` fijo) mientras el runtime sí lo honra.
  Divergencia de contrato documental a resolver en la misma WU.
