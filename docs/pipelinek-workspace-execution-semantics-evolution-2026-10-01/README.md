# PipelineK — Workspace & Execution Location Semantic Evolution

**Fecha:** 2026-10-01  
**Repositorio auditado:** `Rubentxu/pipeline-kotlin`  
**Snapshot de referencia:** `main @ 754ddda0bc3b7c35619a67f80f241691d78536d5`  
**Naturaleza:** especificación + ADRs + roadmap de remediación + UAT. **No contiene cambios de producción.**

## Objetivo

Cerrar de forma coherente el problema de semántica de rutas de PipelineK sin destruir la compatibilidad Jenkins que motivó el producto y sin introducir otra arquitectura paralela.

El problema ya no es solamente que el usuario olvide `--workspace .`. El runtime tiene varias autoridades de ruta que se solapan:

- raíz autorizada del workspace;
- directorio efectivo (`cwd`) de la ejecución actual;
- `controlRoot` de journal/locks/artefactos/estado;
- ubicación del fichero `.pipeline.kts`;
- directorio desde el que el usuario invoca `pipelinek`;
- política de asignación/compartición del workspace.

La solución propuesta hace estas dimensiones explícitas y tipadas.

## Decisiones principales

1. **`workspace.root` y `cwd` son conceptos distintos.**
2. **`cwd` nunca es nullable.** Al comenzar una ejecución: `cwd == workspace.root`.
3. **`dir(...)` sólo deriva un nuevo `cwd`; nunca redefine `workspace.root`.**
4. **`controlRoot` no participa en la resolución de rutas indicadas por el usuario.**
5. **`pipelinek run pipeline.kts` evoluciona a modo local-first:** el workspace por defecto es el directorio desde el que se invoca PipelineK.
6. El comportamiento temporal actual se conserva explícitamente con **`--isolated`**.
7. `--workspace <path>` sigue existiendo y significa **workspace adjunto explícito**.
8. `--workspace` y `--isolated` son mutuamente excluyentes.
9. Origen del workspace y política de sharing dejan de inferirse mediante `Path?`.
10. Las operaciones destructivas distinguen `Attached` de `Managed`: un checkout del usuario no puede borrarse por accidente.
11. Los Steps resuelven rutas mediante una única autoridad (`ExecutionLocation` + `PathAnchor`), no instanciando cada uno su propio `WorkspaceResolver`.
12. La migración es vertical, RED→GREEN y por familias; no hay big-bang.

## Encaje en el roadmap

Este paquete propone **WU-RP-034 — Workspace & Execution Location Semantic Remediation**.

No reescribe `RP3_EXIT_REVIEW` ni receipts históricos. Es una remediación post-exit causada por evidencia real posterior: el self-hosting y los smokes han demostrado que omitir `--workspace` ejecuta los comandos en un scratch temporal donde no existe el proyecto consumidor.

La WU debe cerrar **antes de recertificar el siguiente gate local/release** porque afecta:

- dogfooding;
- `sh`;
- `dir` / `pwd`;
- filesystem Steps;
- SCM / testing / utilities dependientes de workspace;
- seguridad de `cleanWs` / `deleteDir`;
- reproducibilidad y replay de rutas.

## Estructura del paquete

- `01-problem-and-goals.md` — diagnóstico y scope.
- `02-normative-semantics.md` — contrato normativo de rutas y CLI.
- `03-domain-model-and-architecture.md` — ADTs, puertos y flujo funcional.
- `adrs/ADR-0100-workspace-lease-and-execution-location.md`.
- `adrs/ADR-0101-local-first-workspace-cli.md`.
- `adrs/ADR-0102-path-anchors-and-destructive-safety.md`.
- `04-step-path-anchor-matrix.md` — mapa Step → anchor y gaps de compatibilidad.
- `05-migration-plan-wu-rp-034.md` — cortes incrementales de implementación.
- `06-uat-certification-matrix.md` — UAT/fitness/replay/distribución instalada.
- `07-breaking-change-and-compatibility.md` — riesgo y transición.
- `08-roadmap-integration-patch.md` — texto listo para integrar en ROADMAP.
- `09-implementation-blueprint.md` — ficheros, tipos y secuencia técnica sugerida.
- `10-research-and-references.md` — referencias externas y conclusiones adoptadas.
- `11-agent-adoption-prompt.md` — prompt operativo para adoptar el evolutivo.

## Orden recomendado de adopción

1. Integrar documentación y aceptar/rechazar ADR-0100..0102.
2. Añadir `WU-RP-034` al ROADMAP activo.
3. Ejecutar `RP034-A` (caracterización) antes de tocar producción.
4. Implementar `RP034-B..H` con tests quirúrgicos por vertical.
5. Ejecutar `RP034-I` como gate de integración/release con distribución instalada y suite completa.

## Resultado esperado

Después de la migración, estos dos usos serán de primera clase:

```bash
# Local CI / task runner: trabaja sobre el proyecto actual.
cd my-project
pipelinek run pipeline.kts

# Jenkins-like scratch: PipelineK posee y gestiona el workspace.
pipelinek run --isolated pipeline.kts
```

Y dentro del DSL:

```kotlin
pipeline {
    stages {
        stage("build") {
            sh("./gradlew build")
            dir("backend") {
                sh("./gradlew test")
                writeFile("marker.txt", "ok")
                check(fileExists("marker.txt"))
                echo(pwd())
            }
        }
    }
}
```

Todos los Steps del bloque deben observar el mismo `cwd`, mientras la frontera de seguridad continúa siendo `workspace.root`.
