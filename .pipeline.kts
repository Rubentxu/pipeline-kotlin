// .pipeline.kts — WU-RP-043 dogfooding canario
//
// OBJETIVO:
//   Demostrar que el CLI pipelinek instalado en el repo ejecuta comprobaciones
//   reales (no descubre archivos ni imprime comandos). Sirve como prueba de
//   fuego del L5 verde: si este pipeline falla, no hay dogfooding.
//
// FORMA:
//   - assertBuildGood: ejecuta ./gradlew :good:buildJar sobre el fixture
//     UAT-RP-019 (proyecto good con build SUCCESS). El Step sh propaga el
//     exit code, así que un fallo de gradle (exit != 0) aborta el run.
//   - assertCanDetectFailure: inyectable vía PIPELINEK_FORCE_FAIL=1. Si
//     está definida, este step ejecuta `false` (que siempre retorna 1) y
//     el run termina con StepFailed visible en el JSON event-stream.
//
// WORKSPACE:
//   scripts/run-pipelinek pasa --workspace ${REPO_ROOT} automáticamente. Por
//   eso las rutas en este pipeline son RELATIVAS al repo, no absolutas.
//
// ESTADO INTERNO:
//   Todo el estado vive bajo XDG (--db y --control-root los inyecta
//   scripts/run-pipelinek). El repo no se contamina con journals ni caches.
//
// EJECUCIÓN:
//   scripts/run-pipelinek run .pipeline.kts            # SUCCESS esperado
//   PIPELINEK_FORCE_FAIL=1 scripts/run-pipelinek run .pipeline.kts   # FAILURE detectable
//
// REFERENCIAS:
//   - ROADMAP §7.1: política CI local con pipelinek.
//   - WU-RP-046: UAT-RP-019 Gradle real, fuente del fixture.
//   - AGENTS.md §"POLÍTICA DE CI: 100% LOCAL CON PIPELINEK".

pipeline {
    stages {
        // ─── Caso PASS real: ejecuta gradle :good:buildJar ────────────
        // Es una comprobación real: invoca gradle, que escribe un jar
        // con bytes a disco. No es descubrir archivos ni imprimir comandos.
        // Usa ${GRADLE_BIN:-gradle} para que el fixture funcione tanto en
        // un entorno con wrapper (./gradlew) como con gradle global PATH.
        stage("assertBuildGood") {
            sh("cd v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/gradle && \"\${GRADLE_BIN:-gradle}\" :good:buildJar --console=plain --no-daemon")
            sh("test -s v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/gradle/good/build/libs/good.jar")
        }

        // ─── Caso FAIL inyectable: detecta fallo en tiempo real ────────
        // Si PIPELINEK_FORCE_FAIL=1, este step ejecuta `false` (que
        // siempre retorna 1). El step propaga el exit code y el run
        // termina con StepFailed visible en el JSON event-stream.
        stage("assertCanDetectFailure") {
            sh("test -f v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/gradle/settings.gradle.kts")
            if (System.getenv("PIPELINEK_FORCE_FAIL") == "1") {
                sh("false")
            }
        }

        // ─── N2 DEV profile: ejecuta tests reales vía Gradle ────────────
        // No imprime comandos: invoca ./gradlew y propaga su exit code.
        // Las clases UatLocal005* (env vars, banned imports, corpus
        // coverage, teardown hygiene) y UatDsl001* (Jenkins familiarity)
        // son la base mínima de UAT que el motor + DSL deben pasar para
        // considerar el canario como CI local útil. Salida no PASS aborta.
        //
        // Tiempo esperado: ~60s con daemon caliente, ~5min en frío.
        // Perfil VERIFY/RELEASE (./gradlew check) NO se ejecuta aquí;
        // queda para la frontera de integración, conforme a AGENTS.md.
        stage("runDevSuite") {
            sh("cd v2 && ./gradlew :pipeline-application:test --tests 'UatLocal005*' --tests 'UatDsl001*' --console=plain --no-daemon")
            sh("test -s v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatDsl001JenkinsFamiliarityTest.xml")
            sh("test -s v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.UatLocal005BannedImportsTest.xml")
        }
    }
}
