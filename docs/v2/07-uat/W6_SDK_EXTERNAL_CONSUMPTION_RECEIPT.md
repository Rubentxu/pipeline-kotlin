# W6 — SDK consumible desde fuera: cierre del bloque

- **Source commit:** `f2477bf8a828f37820f016af7a79791e7207b6b4`
- **ProductVersion:** `0.48.0` (autoridad única: `v2/build.gradle.kts` → `rootProject.version`)
- **CandidateId:** `sha256:a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93`
- **CandidateId = SHA-256 de** `pipelinek-0.48.0.zip`, verificado por coincidencia literal.
- **Arbol:** limpio. **Remoto:** `origin/s6-plugin-sdk` = mismo SHA.

Este recibo certifica **W6** (congelación funcional de S6 y SDK consumible). No certifica W7: la
candidata aún no está integrada en `main`, no existe tag y no hay publicación.

---

## 1. Exit gate

> *Un autor puede desarrollar, compilar y ejecutar un plugin externo sin modificar el motor,
> utilizando únicamente capacidades admitidas.*

**CUMPLIDO, con la evidencia por forma de contribución abajo.** El motor no cambió: los tres
plugins externos se construyen como proyectos Gradle independientes contra el SDK publicado, y
ninguna de las filas de este recibo exigió tocar `pipeline-application`, el compilador o el
coordinador.

---

## 2. Las cuatro formas de contribución, ejecutadas

Todas sobre el binario **instalado** (`installDist`), bifurcado en su propio proceso (HF2), no
in-process. Ninguna fila se apoya en un mensaje de descubrimiento: cada positivo tiene su negativo
de aislamiento, y el negativo afirma **cero efectos**, no una cadena de log.

| Forma | Testigo discreto | Con JAR | Sin JAR |
|---|---|---|---|
| Step atómico | `StepStarted` del Step del plugin | exit 0 | n/a (fixture no compila) |
| **Block Step** | `StepStarted` del `sh` interno, ×3 exactos | exit 0 | exit 1, cero `StepStarted` |
| **Directive** | `DirectiveAdmitted` → `EchoOutputCaptured` | exit 0 | exit 1, `DirectiveDenied`, **0** `StageStarted` |
| Event + capability | `example.uppercase.applied` emitido por el handler | exit 0 | — |

**Los dos negativos fallan por mecanismos distintos, y eso es deliberado.** Sin su JAR, el fixture de
Block no puede resolver `example.block.repeatBlock` y muere en **compilación**; el de Directive
compila igual (la clave es un string en tiempo de composición) y muere en **admisión**. Se
afirman por separado porque fusionarlos ocultaría cuál mecanismo se ejercitó.

Detalle que la evidencia obligó a corregir: `directive(key) { }` **no** es un wrapper lambda. La
primera versión del fixture lo usó así y el compilador la rechazó con
`Unresolved reference 'directive'` — que es exactamente el fail-closed que el contrato promete. La
forma real es una declaración dentro de `directives { }` con los steps como hermanos
(`StageScope.kt:28`, `:720-745`).

---

## 3. Las nueve negativas de admisión

Auditadas por medición, no por lectura de nombres de fichero. Siete están probadas y verdes; dos
**no son conceptos de este dominio**, y esa es la hallazgo.

| # | Negativa | Estado | Dónde |
|---|---|---|---|
| 1 | Ausencia del JAR | probada | aislamiento Block/Directive |
| 2 | Manifiesto inválido | probada | `PluginManifestCodecFidelityTest` (JSON truncado, codec desconocido, schema futuro) |
| 3 | `apiRange` incompatible | probada | `BuiltPluginManifestArtifactTest` — artefactos reales rechazados en `0.60.0` contra `[0.47.0, 0.49.0)` |
| 4 | Identidad duplicada | probada | StepKey duplicado no decodificable; `DuplicateIdentity` nombra ambos |
| 5 | Digest incorrecto | probada | `Mismatch`, nunca pass |
| 6 | Registro no autorizado | **no es concepto** | ver abajo |
| 7 | Contribución declarada inexistente | probada | `admitContributions`, pasada 2 |
| 8 | Capability ausente | probada | `StepRegistryTest` "missing capability fails before handler runs"; `CoreEchoSeamTest` |
| 9 | Código en classpath sin admisión | probada | `PluginAdmissionPreLoadOrderingTest`, con centinela de no-vacuidad |

### Por qué 6 y 9 no son defectos

`PluginManifestRejection` es un conjunto **cerrado de exactamente seis** casos: `MalformedDocument`,
`UnsupportedSchema`, `UnknownCodec`, `DuplicateIdentity`, `IncompatibleApiRange`,
`InvalidManifest`. No existe "registro no autorizado" ni "contribución declarada ausente".

Eso es arquitectónico, no un olvido. `PluginAdmission.admit` es una decisión pura sobre un
documento, una identidad medida, una versión de runtime y el conjunto ya admitido; **nunca toca un
classpath ni un contributor**. No hay lista blanca porque la autorización *es* el manifiesto: el
plugin se declara y el runtime verifica la declaración contra los bytes medidos. La admisión es de
dos pasadas precisamente para que la pasada 1 pueda admitir *rutas* sin `ClassLoader` — porque
`ServiceLoader` **instancia** providers mientras itera, y poner la puerta ahí cargaría código del
plugin antes de la decisión que debe precederlo.

Escribir tests para 6 y 9 habría significado inventar un requisito, no caracterizar uno real.

---

## 4. ABI y BOM

- `apiCheck` verde sobre los seis módulos con control ABI.
- La allowlist `publishedContractModules` es el mecanismo fail-closed de publicación, y coincide
  exactamente con las cuatro coordenadas de W6.4. Los dos módulos con control ABI que **no**
  publican (`pipeline-step-sdk:api`, `pipeline-credentials-api`) lo hacen por esa allowlist, no por
  ausencia de plugin — una diferencia real que un `grep` de `maven-publish` no distingue, porque
  las menciones son comentarios.
- **W6-UAT-07 verificado por medición:** el BOM publicado fija exactamente
  `pipeline-domain`, `pipeline-scripting-api`, `pipeline-events` y `pipeline-output`, todos a
  `0.48.0`, sin quinta coordenada.

---

## 5. Round gate

Round gate real en este SHA, con veredicto inequívoco y canarios regenerados:

```text
BUILD SUCCESSFUL in 3m 24s   (exit 0)
325 actionable tasks: 51 executed, 274 up-to-date
0 errores de compilación · 0 tareas FAILED
```

Agregado **run-scoped** (solo XML de esta ejecución, no el directorio completo):

| Métrica | Valor |
|---|---|
| tests | **4 716** |
| failures | **0** |
| errors | **0** |
| skipped | **134** |
| clases | 756 |
| módulos | 18 |

Los 134 skips se reparten en `pipeline-application` (123), `pipeline-architecture-tests` (10) y
`pipeline-release` (1). La clasificación ya vive en
`W5_SKIP_CLASSIFICATION_AND_GATE_PROVENANCE.md`; aquí sólo se registra el delta de esta ejecución.

### Dos ejecuciones descartadas, y por qué

Se documentan porque son el tipo de falso verde que este bloque existe para evitar:

1. **`check` incremental devolvió `BUILD SUCCESSFUL in 4s`** con 24 tareas de test `UP-TO-DATE` y
   **0 ejecutadas**. El canario sí se regeneró, lo que lo hacía parecer válido; 0 tests ejecutados
   no prueban nada. No cuenta como verde.
2. **Dos intentos de `--rerun-tasks` fueron matados por el propio presupuesto.** El primero usó
   900 s, derivados de los 3m28s del gate *incremental* de W5; `--rerun-tasks` es de la clase de
   ~977 s. El presupuesto era incorrecto y el resultado no era un fallo del producto.

Además, `grep 'BUILD SUCCESSFUL'` devuelve líneas de builds **anidados** lanzados por tareas `Exec`
(`BUILD SUCCESSFUL in 1s`, `5 actionable tasks: 5 up-to-date`), y la cadena `FAILURE` aparece 6
veces dentro de tests **que pasan**, donde `StepFailed ... shell exited with code 1 ... PASSED` es el
resultado esperado. Ambas cosas habrían hecho trivial un verde falso.

---

## 6. Mutación que mata

Cada fila ejecutada tiene una mutación atribuida 1:1.

| Fila | Mutación | Resultado observado |
|---|---|---|
| Block ejecuta el cuerpo N veces | `repeatBlock(3)` → `repeatBlock(2)` | `expected: <3> but was: <2>` |

La fixture se restauró y se verificó por hash:
`f30d680ff03c83fd37ffd32cbfd7728427a6d17373f376b25abb98ce7d1abb4f`.

**Defecto real encontrado por esa mutación.** Los fixtures son entradas de **runtime**, así que
nada los declaraba a Gradle: editar `repeatBlock(3)` a `(2)` dejaba `:test UP-TO-DATE` y la suite
reportaba verde ejecutando los scripts de la ejecución anterior. Es la clase de defecto que W5
prohibe ("un gate usa resultados que no son de su propia ejecución"), reproducida en el trabajo
encargado de cerrar el bloque. Corregido con `inputs.files`, y probado porque la mutación ahora sí
re-ejecuta.

También se observó un RED legítimo antes: el testigo contaba la subcadena `"/sh-0"` y falló con
`expected 3 but was 6`, porque `StepStarted` y `StepFinished` llevan ambos el nombre. Conta ahora
sólo registros `StepStarted`, de modo que la fila también fallaría si el motor dejara de reportar
finalizaciones.

---

## 7. Lo que este recibo NO certifica

- **W7.** La candidata no está integrada en `main`, no hay tag `v0.48.0-rc1` ni publicación. El
  exit gate de W7 sigue sin abrir.
- **PRODUCT-GATE.** Sigue `BLOCKED_EXTERNAL`: no hay superficie de CI en este repositorio desde
  `754ddda0`. "CI verde" no es una evidencia disponible aquí, y su ausencia no se registra como
  verde por construcción ni como `NOT_RUN` disfrazado de `PASS`.
- **W8.** `publishSdkForExternalPlugin` publica a un repositorio Maven **local**
  (`v2/build/sdk-repo`), que no es distribución pública. Ese directorio además **acumula versiones
  previas** (`0.42.0-rc1` … `0.47.0` junto a `0.48.0`); inocuo para la verificación local del SDK,
  pero relevante para el requisito de W8 de coordenadas inmutables sin sobrescritura.
- **Certificación del harness.** `RUNTIME_VERSION` y `MANIFEST_VERSION` no son derivables del ZIP;
  las aporta el harness externo. Por eso la admisión reporta `identity INCOMPLETE` en esas dos
  superficies, lo cual está registrado como **por diseño** desde W4 y no es una regresión.
- **S7/S8.** Sin empezar; pertenece a W10.
