# 06 — UAT y matriz de certificación

## 1. Capas

| Tier | Objetivo |
|---|---|
| T0 | funciones puras / ADTs / path decisions |
| T1 | handlers/adapters in-process |
| T2 | architecture fitness + no duplicate authority |
| T3 | distribución instalada / CLI real |
| T4 | replay/restart/concurrency/security adversarial |
| T5 | clean checkout + release candidate + dogfooding real |

## 2. UAT CLI

### WS-UAT-001 — attached current directory default

```bash
cd <gradle-fixture>
pipelinek run pipeline.kts
```

Sin `--workspace`.

**Assert:** `sh("./gradlew build")` encuentra wrapper y build pasa.

### WS-UAT-002 — explicit workspace compatibility

```bash
pipelinek run --workspace . pipeline.kts
```

Debe producir el mismo root que UAT-001.

### WS-UAT-003 — script externo

```bash
cd <project>
pipelinek run <outside>/ci.pipeline.kts
```

**Assert:** workspace sigue siendo `<project>`, no `<outside>`.

### WS-UAT-004 — isolated

```bash
pipelinek run --isolated pipeline.kts
```

**Assert:** `pwd()` no es el invocation directory y un archivo del proyecto no aparece salvo que el pipeline lo cree/checkout.

### WS-UAT-005 — flags incompatibles

`--workspace . --isolated` => exit de uso inválido antes de efectos.

## 3. UAT `dir` y current directory

### WS-UAT-010 — cross-step coherence

Dentro de `dir("a")`:

- `sh("pwd")`;
- `pwd()`;
- `writeFile`;
- `readFile`;
- `fileExists`;

observan `/workspace/a`.

### WS-UAT-011 — nested dirs

`dir("a") { dir("b") { ... } }` => `/workspace/a/b`; al salir, contextos padre restaurados por valor.

### WS-UAT-012 — no context leak in parallel

Branches A/B usan cwd distintos y no contaminan padre ni hermano.

### WS-UAT-013 — relative traversal

`dir("../escape")` => typed rejection; cero directorio creado fuera.

### WS-UAT-014 — absolute dir

`dir("/tmp/escape")` => typed rejection.

### WS-UAT-015 — symlink escape

Workspace contiene `link -> /outside`; una operación workspace-scoped sobre `link/x` falla antes de I/O externo.

## 4. Destructive safety

### WS-UAT-020 — attached root protected

En default attached:

```kotlin
deleteDir()
```

en root => `ProtectedWorkspaceRoot` y checkout intacto.

### WS-UAT-021 — attached cleanWs protected

`cleanWs()` root => fail-closed; `.git`, source y pipeline sobreviven.

### WS-UAT-022 — attached subdir allowed

```kotlin
dir("build") { deleteDir() }
```

elimina sólo `build`.

### WS-UAT-023 — managed root cleanup

En `--isolated`, lifecycle cleanup root permitido según outcome/policy.

## 5. Stores

### WS-UAT-030 — stash cwd source

Stash dentro de `dir("producer")` guarda paths relativos a `producer`, no a root completo.

### WS-UAT-031 — unstash cwd target

Unstash dentro de `dir("consumer")` restaura allí.

### WS-UAT-032 — durable store outside workspace survives

Stash/archive durable storage permanece bajo su store/control plane aunque el scratch workspace se limpie.

## 6. Plugins / utilities

### WS-UAT-040 — JUnit under dir

Reporte en `module/build/...`; Step con path relativo dentro de `dir("module")` lo encuentra.

### WS-UAT-041 — SCM under dir

Checkout target relativo dentro de `dir("src")` aterriza en el cwd autorizado.

### WS-UAT-042 — zip/unzip under dir

Source/destination relativos respetan cwd y confinement.

## 7. Differential

### WS-UAT-050 — archiveArtifacts

Resultado comparado con Jenkins fixture congelado. Debe existir una decisión explícita `CURRENT_DIRECTORY` o `WORKSPACE_ROOT`, nunca accidental.

### WS-UAT-051 — publishHTML

Mismo criterio.

## 8. Replay/durability

### WS-UAT-060 — same location replay

Fresh + replay con misma lease/location no diverge.

### WS-UAT-061 — location mismatch

Si un operation fingerprint/contract exige la misma ubicación y el run se reanuda con una raíz incompatible, debe fallar por divergence o usar la semántica durable definida; nunca caer a launcher cwd.

### WS-UAT-062 — `pwd` typed result replay

Fresh observa cwd; replay reproduce según su ReplayPolicy sin re-etiquetar root/cwd.

## 9. Real projects

Obligatorios en T3/T5:

- Gradle wrapper project;
- Maven project;
- Node project;
- pipeline self-hosted del propio repo.

Para cada uno:

1. default attached;
2. explicit `--workspace .`;
3. isolated con checkout/setup apropiado o failure esperado explícito.

## 10. Architecture fitness

### FIT-WS-001

Cero nuevos reads de `System.getProperty("user.dir")` en runtime Step path resolution.

### FIT-WS-002

Cero nuevos `WorkspaceResolver(...)` dentro de handlers migrados.

### FIT-WS-003

Cero `controlDirRoot.resolve(userInput)`.

### FIT-WS-004

`ExecutionLocation.cwd` no nullable.

### FIT-WS-005

`WorkspaceIdentity.workspaceRoot` legado, mientras exista, siempre deriva del root real y nunca de cwd.

### FIT-WS-006

No central `when(stepKey)` para path semantics.

### FIT-WS-007

Los Steps workspace-aware certificados tienen clasificación de anchor o port equivalente testable.

## 11. Exit gate

WU-RP-034 sólo puede cerrarse cuando:

- todos los UAT obligatorios del scope pasan sobre el mismo SHA;
- distribución instalada es la ejecutada;
- default sin `--workspace` está probado en checkout real;
- `--isolated` preserva scratch;
- attached root no puede destruirse por default;
- corpus/architecture/full suite están verdes según el protocolo vigente;
- no quedan dos autoridades activas para cwd/root en producción.
