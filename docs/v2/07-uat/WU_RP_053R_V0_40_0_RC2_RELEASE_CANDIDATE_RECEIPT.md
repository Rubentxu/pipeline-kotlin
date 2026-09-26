# RP-053R RC2 Candidate Receipt: v0.40.0-rc2

**Estado:** `READY_FOR_HARNESS_INTAKE`  
**Candidata:** `0.40.0-rc2`  
**SHA de código exacto:** `165b6f9ad2242ac5336e660e7516f88f1af90d96`  
**Rama:** `wu/rp-053r-red-fixtures`  
**Base observada:** `origin/main` = `acc903875d70f939713786d71a6331bb6ccf7dc9`  
**Fecha de emisión:** `2026-09-26`

## Alcance

Esta candidata materializa el estado actual de PipelineK para su intake por
`pipelinek-release-harness`. No es una release estable y no autoriza merge a
`main`. El producto usa Gradle como autoridad de versión:
`v2/build.gradle.kts`, `rootProject.version = "0.40.0-rc2"`.

No se creó `Cargo.toml`, `Cargo.lock` ni ningún manifiesto falso. El fallo
Cargo/Rust del planner genérico de SDDK pertenece al framework externo y queda
clasificado como limitación de tooling. La excepción operativa está documentada
en `AGENTS.md` y no relaja RP-5, el harness ni la protección de `main`.

## Material inmutable

Directorio local ignorado por Git:

```text
dist/candidates/v0.40.0-rc2/
├── pipelinek-0.40.0-rc2.zip
├── pipelinek-0.40.0-rc2.sbom.json
├── SHA256SUMS
└── release-manifest.json
```

| Material | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.40.0-rc2.zip` | 92,082,095 | `45f79479ded7d476e160829a8f2f4582c0a261c2d784f0b7f2642b87dd1dd3d5` |
| `pipelinek-0.40.0-rc2.sbom.json` | 17,557 | `0de67693521514cab9c64fe6cb458e20728357ce7ae812c7efa1ca2c246963ac` |
| `SHA256SUMS` | 244 | `13da690761ce4380a7a76ea23ec40d4763309c9b9c6c7c02373cde11910fc40c` |
| `release-manifest.json` | 7,994 | `51445347ef87b1775de421334406af90a3e106f771e65b78c0da70b93d62423f` |

El manifiesto tiene 17 claves de nivel superior y fija la identidad del ZIP al
SHA exacto de este commit. El SBOM es CycloneDX 1.5 y contiene 41 componentes.

## Batería ejecutada

### Build y análisis estático

```text
argv:
  timeout 900 ./gradlew :pipeline-application:distZip \
    :pipeline-step-sdk:scm-git:detekt \
    :pipeline-application:detekt --console=plain
exit_code: 0
observed: BUILD SUCCESSFUL in 16s; 50 actionable tasks: 16 executed, 34 up-to-date
output_digest:
  bb436c30ae79efeb705545e6b10fd73fb8bd6cccf85edf093a3fde21ae0b3f57
```

### Integridad del material

```text
argv: unzip -t -q dist/candidates/v0.40.0-rc2/pipelinek-0.40.0-rc2.zip
exit_code: 0
observed: No errors detected in compressed data

argv: sha256sum --strict -c dist/candidates/v0.40.0-rc2/SHA256SUMS
exit_code: 0
observed: ZIP and SBOM sums coincide

argv: python3 -m json.tool dist/candidates/v0.40.0-rc2/release-manifest.json
exit_code: 0
observed: valid JSON; 17 top-level keys
```

### Smoke de distribución instalada

La primera ejecución del canary terminó con `126` antes de lanzar PipelineK
porque el workspace temporal no tenía `.tool-versions`. Ese resultado no se
clasifica como fallo del producto. Se repitió el mismo canary con el JDK fijado
por el proyecto, `temurin-24.0.2+12`.

| Comando | Exit code | Evidencia |
|---|---:|---|
| `pipelinek version` | 0 | `pipeline 0.40.0-rc2`; log SHA `20126b4a...` |
| `pipelinek doctor` | 0 | JDK 24.0.2, Linux, workspace writable; log SHA `2e4b351f...` |
| `pipelinek run canary.pipeline.kts` | 0 | log SHA `80285115a07602e7e3b3196d7051bf08ba3000ed2085be195097c7968378600a` |

El canary ejecutado fue:

```kotlin
pipeline {
    stages {
        stage("smoke-dir-sh") {
            dir("scratch") {
                sh("printf 'hello-from-rc2\\n' > greeting.txt && cat greeting.txt")
            }
        }
    }
}
```

El log contiene `RunFinished outcome=success`, además de los eventos
`DirEntered`, `StepFinished` y `DirExited`. El fichero de entrada tiene SHA
`c019039a265c80139841a1fc7a13ccbbdfc4617556fab3fb7627d6643deec736`.

## Gates y límites

| Gate | Estado | Motivo |
|---|---|---|
| Version identity | PASS | Gradle, ZIP y `pipelinek version` coinciden en `0.40.0-rc2` |
| ZIP/SBOM/checksum/manifest | PASS | hashes y estructura comprobados desde disco |
| Detekt de módulos afectados | PASS | ejecución exacta documentada arriba |
| Smoke instalado version/doctor/dir+sh | PASS | ejecución real con JDK fijado |
| `check` completo | NOT_RUN | fuera de la batería quirúrgica de esta candidata |
| RP-5 | NOT_RUN | requiere gates externos y matriz completa |
| release harness | NOT_RUN | intake pendiente |
| publicación GitHub | NOT_RUN | no se publicó una release externa |
| integración en `main` | BLOCKED | requiere PASS externo, CI y protección de rama |

La existencia del artefacto local no equivale a certificación de producto. La
promoción sólo puede seguir después del veredicto del harness y del gate RP-5
sobre los bytes de este ZIP, sin reconstruirlos.

## SDDK y continuidad

Ciclo SDDK: `p-733fb505b5a6bd2d/rp-053r-rc2-candidate`  
Path: `A-lite`  
Fase al emitir este receipt: `Build`  

El siguiente paso operativo es registrar este receipt como evidencia de
implementación, avanzar a Verify y ejecutar sólo los gates que correspondan al
ciclo. El planner tipado de release puede seguir bloqueado por su precondición
Cargo/Rust. En ese caso se conserva el `argv`, `exit_code` y digest de salida y
se usa la transición manual autorizada, sin saltarse ninguna certificación.

## Cierre de work unit

- **Reference implementation consulted:** receipt de RC1 y especificación de distribución/candidata de `docs/v2/07-uat/`; no se copió código de otra implementación.
- **Behaviour adopted:** candidata reproducible con ZIP Gradle canónico, checksum, SBOM CycloneDX, manifiesto y smoke instalado.
- **Intentional deviations:** no se creó Cargo.toml y no se publicó ni integró en `main` porque el harness y RP-5 siguen pendientes.
- **Security implications reviewed:** integridad por SHA-256, SBOM de los componentes empaquetados, no se introdujeron credenciales ni dependencias nuevas.
- **Tests demonstrating the contract:** `scripts/release/sbom-cyclonedx.py`; batería Gradle de detekt/distZip; smoke instalado `version`, `doctor` y `dir` + `sh` documentado arriba.
