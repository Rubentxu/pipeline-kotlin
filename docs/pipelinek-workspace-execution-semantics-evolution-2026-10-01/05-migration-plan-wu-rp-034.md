# 05 — WU-RP-034 · Workspace & Execution Location Semantic Remediation

## 1. Posición

**Tipo:** remediation post-RP3.  
**Bloquea:** siguiente certificación/release que pretenda declarar local-first coherente.  
**No invalida:** receipts históricos en su SHA.

## 2. Principios de ejecución

- No big-bang.
- RED discriminante antes de cada cambio de semántica.
- Tests quirúrgicos durante cada slice.
- Full suite/corpus/distribución sólo al integrar/certificar el train.
- Commits atómicos y Conventional Commits.
- Ningún Step nuevo como distracción.
- No reescribir receipts anteriores; crear evidence nueva.
- No introducir un segundo executor shell.
- No cambiar `controlRoot` para resolver el problema.

## 3. Inventario actual relevante

Búsqueda sobre `main @ 754ddda0...` encuentra producción directa relacionada con `WorkspaceResolver`/workspace en, entre otros:

### Application/runtime

- `WorkspaceOperations.kt`
- `CompositionRoot.kt`
- `durable/WorkspaceResolver.kt`
- `durable/CanonicalRuntimeContext.kt`
- `durable/CanonicalRuntimeCapabilityAccess.kt`
- `durable/CanonicalDurableRunCoordinator.kt`
- `durable/ShExecution.kt`
- `durable/DeleteDirOperationsAdapter.kt`
- `durable/CleanWsOperationsAdapter.kt`
- `StashOperationsAdapter.kt`
- `ArchiveArtifactsOperations.kt`
- `PublishHtmlOperationsAdapter.kt`
- `CorePwdStep.kt`
- `CoreStepRegistryFactory.kt`

### SDK/substrates

- `pipeline-step-sdk/files/FileWriteExecutor.kt`
- `FileReadExecutor.kt`
- `FileExistsExecutor.kt`
- `DeleteDirExecutor.kt`
- `CleanWsExecutor.kt`
- `pipeline-step-sdk/workflow-control/DirExecutor.kt`
- SCM Git StepDefinition/executors
- utilities Steps que consumen `WORKSPACE_IDENTITY_CAPABILITY`
- JUnit StepDefinition

El inventario exacto debe regenerarse al iniciar RP034-A y guardarse como evidence del SHA de trabajo.

## 4. Slices

### RP034-A — Characterisation freeze (NO production)

**Goal:** demostrar el fallo y congelar leyes antes de diseñar adapters.

Tests mínimos:

1. CLI sin `--workspace` sobre fixture Gradle reproduce scratch actual.
2. `--workspace .` ejecuta correctamente el fixture.
3. `dir { sh/pwd/writeFile/readFile/fileExists }` registra si todos comparten base.
4. nested `dir` (dos niveles).
5. parallel scopes no contaminan cwd.
6. delete/clean sobre root attached actual vs scratch.
7. inventory machine-readable de consumidores.
8. differential fixtures de `archiveArtifacts/publishHTML/unstash/junit` cuando aplique.

**Exit:** receipts + Step→PathAnchor matrix actualizada; cero cambios de producción.

### RP034-B — Domain model additive

Añadir:

- `WorkspaceRequest`;
- `WorkspaceLease`;
- `WorkspaceRoot`;
- `WorkingDirectory`;
- `ExecutionLocation`;
- allocation policy mínima;
- errores tipados;
- funciones puras de decision/resolution.

No cambiar aún el default CLI.

**Tests:** HF0/property tests de invariantes y tabla CLI.

### RP034-C — Runtime authority bridge

- añadir `EXECUTION_LOCATION_CAPABILITY`;
- `CanonicalRuntimeContext` transporta la authority nueva;
- bridge legado de `WorkspaceIdentity` apunta sólo al root real;
- `ShOptions` sigue existiendo temporalmente, derivado desde `ExecutionLocation`;
- añadir fitness para prohibir nuevos fallbacks a `user.dir`/control root.

**Exit:** comportamiento observable bit-equivalent fuera de los tests explícitos del nuevo seam.

### RP034-D — Core current-directory vertical

Migrar conjuntamente porque forman la ley básica de `dir`:

1. `dir`;
2. `sh`;
3. `pwd`;
4. `writeFile`;
5. `readFile`;
6. `fileExists`;
7. `deleteDir` (subdir semantics, todavía sin flip CLI).

Eliminar reconstrucción independiente de workspace para estas rutas.

**Required RED→GREEN:**

```kotlin
dir("a") {
    sh("pwd")
    writeFile("x.txt", "x")
    check(fileExists("x.txt"))
    check(readFile("x.txt") == "x")
    check(pwd().endsWith("/a"))
}
```

### RP034-E — Extended workspace consumers

Migrar por pequeñas verticales:

- stash/unstash;
- checkout/SCM;
- junit;
- utilities read/write/find/zip/unzip/hash;
- load si consume rutas workspace-relative.

No migrar `archiveArtifacts/publishHTML` hasta cerrar su differential si sigue ambiguo.

### RP034-F — Root-scoped + ambiguous families

- `cleanWs`: anchor `WORKSPACE_ROOT`;
- `archiveArtifacts`: aplicar resultado differential;
- `publishHTML`: aplicar resultado differential;
- stores durable: asegurar separación source vs storage.

Crear fitness de clasificación de anchors.

### RP034-G — Ownership/destructive safety

- `WorkspaceLease.Attached` protege root;
- `WorkspaceLease.Managed` permite lifecycle cleanup;
- `ProjectCheckoutDetector` deja de ser autoridad;
- root `deleteDir/cleanWs` attached produce error tipado;
- subdir cleanup sigue funcionando;
- symlink/traversal adversarial suite.

**Gate obligatorio antes de RP034-H.**

### RP034-H — CLI local-first + `--isolated`

1. añadir/confirmar parse de `--isolated`;
2. mutual exclusion con `--workspace`;
3. cambiar no-flag => `AttachInvocationDirectory`;
4. preservar `--workspace .`;
5. preservar old scratch => `--isolated`;
6. actualizar `--help`, quickstart y migration guide;
7. dogfood root `pipeline.kts` **sin `--workspace .`** como witness principal.

### RP034-I — Cleanup + certification

Sólo después de las verticales verdes:

- eliminar fields/aliases obsoletos;
- reducir usos directos de `WorkspaceResolver` a allocator/adapters autorizados;
- deprecar/retirar `WorkspaceIdentity` si BC lo permite;
- retirar `ProjectCheckoutDetector` si ya no tiene uso legítimo;
- full suite `./gradlew -p v2 check` según comando vigente del repo;
- compatibility corpus;
- installDist/distZip;
- UAT Gradle/Maven/Node;
- fresh/replay/resume;
- `--isolated` y attached;
- candidate receipt.

## 5. Commit plan sugerido

No es una obligación de número exacto, pero la granularidad recomendada es:

```text
test(workspace): freeze execution-location path semantics
feat(domain): add workspace lease and execution location ADTs
feat(runtime): expose typed execution-location capability
fix(workspace): align core cwd-aware steps under dir
fix(workspace): migrate stash scm junit and utilities path context
fix(workspace): align root-scoped and differential steps
fix(workspace): protect attached workspace root by ownership
feat(cli): make invocation directory the default workspace
refactor(workspace): retire legacy workspace path authorities
cert(workspace): close WU-RP-034 installed-distribution gate
```

## 6. STOP conditions

Detener la slice, no maquillar tests, si ocurre cualquiera:

- un Step necesita saber su `StepKey` en coordinator para resolver path;
- se propone mutar `System.user.dir`;
- se requiere `chdir` global;
- una ruta de usuario vuelve a resolverse desde controlRoot;
- un RED esperado pasa porque el fixture no discrimina;
- root attached puede borrarse antes del gate de safety;
- replay cambia cwd sin divergence explícita;
- una API pública necesita breaking removal no cubierta por ADR/release plan.
