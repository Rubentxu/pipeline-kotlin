# B4.4 — Guía de autores de plugins, construida ejecutando el ejemplo

**Fecha:** 2026-10-08 · **SHA base:** `fe499f00` (rama `s6-plugin-sdk`)
**Entregable:** `examples/example-uppercase-plugin/README.md`

---

## 1. Qué se hizo

La guía de autores de plugins externos vive **junto al plugin de referencia**, no en un documento
aparte, y se escribió ejecutando el ejemplo: cada comando y cada salida que aparecen en ella se
corrieron en esta revisión. No es documentación de progreso; es la plantilla que un autor copia y
el procedimiento para comprobarla.

Estructura: qué demuestra el artefacto, cómo ejecutarlo, el par de aislamiento, los 17 pasos del
camino con el fichero real de al lado, el Step mínimo citado, los cuatro descriptores ServiceLoader,
el manifiesto real, la frontera de responsabilidades núcleo/plugin, las reglas duras, la
verificación y las limitaciones.

## 2. El par de aislamiento, con salida real

```text
CON --plugin-jar
  Discovered external Step plugins: scm-git, http, junit, utilities, example.uppercase
  Discovered external directive plugins: example.uppercase.UppercaseDirectiveContributor
  Discovered external event definitions: example.uppercase.applied
  StepStarted/StepFinished  external/registrystep-0   stepType=example
  StageFinished outcome=success · RunFinished outcome=success · Pipeline finished with SUCCESS
  EXIT 0

SIN --plugin-jar
  Discovered external Step plugins: scm-git, http, junit, utilities        <- example.uppercase NO
  CompilationFinished diagnostics: Unresolved reference 'example'. (line 1 col 8)
                                   Unresolved reference 'uppercase'. (line 6 col 13)
  Pipeline finished with FAILURE: Unresolved reference 'uppercase'.   EXIT 1
```

La mitad negativa es la que importa: sin el JAR el plugin no está *a medias*, no está, y falla
cerrado nombrando la referencia que falta. Esa asimetría detecta una fuga de classpath.

## 3. Evidencia del artefacto

```text
el JAR lleva META-INF/pipelinek/plugin-manifest.json, emitido derivando la declaración real
  apiRange [0.47.0, 0.49.0) · releaseDigest sha256:4fbd537b… · delivery EXTERNAL_REFERENCE
  steps con declaredCapabilities por step:  [] / [case-table] / [event-emission] / ambos
  5 entradas META-INF/services (los 4 SPI + el directorio)
```

`declaredCapabilities` por step es donde "capacidad declarada == capacidad usada" deja de ser una
promesa y pasa a ser un dato comprobable por el host.

## 4. Comandos verificados en esta rebanada

```text
cd v2 && ./gradlew publishSdkForExternalPlugin buildExamplePlugin :pipeline-application:installDist
  EXIT 0

./gradlew :pipeline-application:test --tests '*ExternalPluginFourFamilyAdmissionTest*' \
                                    --tests '*StepContractSuite*'
  EXIT 0 · 20 clases · 380 tests · 0 fallos   (canarios borrados antes)

$BIN version -> "pipeline 0.47.0"
$BIN run --plugin-jar <jar> <script>   EXIT 0      $BIN run <script>   EXIT 1
```

## 5. Defectos encontrados y corregidos

```text
D1  examples/README.md documentaba el binario como
      v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
    y el applicationName real es `pipelinek`: la ruta no existe. `examples/run.sh` ya lo tenía
    correcto y hasta comenta el error; sólo la prosa había quedado atrás. Corregida.
    (Encontrado porque el primer intento de ejecutar la guía falló con EXIT 127.)

D2  examples/README.md declaraba `pipeline validate` / `pipeline run`, sin `--plugin-jar`.
    Corregido al nombre real y al contrato real, verificado en CliParser:
      "--plugin-jar" -> state.pluginJars += value     (acumula: es repetible, List<String>)

D3  La guía que yo mismo escribí citaba `./gradlew :pipeline-step-sdk:runtime:test
    --tests '*StepContractSuite*'`, tomado de la prosa de AGENTS.md. Se ejecutó para verificarlo y
    la tarea no existía: `StepContractSuite` NO es una clase del repositorio, la implementación
    son suites POR STEP (`Core*StepContractSuiteTest`). Corregido a los comandos que sí corren, y
    la discrepancia queda declarada en la sección de limitaciones de la guía en vez de borrada.
```

D3 es exactamente la clase de defecto contra la que advierte AGENTS.md: una afirmación escrita que
ninguna evidencia sostiene, apuntando al lector a algo que no existe. La encontré porque convertí
la afirmación en un comando en vez de en un párrafo.

## 6. Limitaciones, dichas en la propia guía

```text
- `-PsdkVersion` es obligatorio, sin default, por diseño (un default sólo podría ser uno que no
  resuelve). Verificado leyendo el build, que falla nombrando la causa.
- Las dependencias del SDK son compileOnly: el JAR lleva sólo sus clases.
- El sdk-repo se resuelve como SNAPSHOT con TTL de caché a cero.
- NO se documenta marketplace, hot reload, resolución de dependencias, firma, repositorio remoto
  ni gestor de ciclo de vida: nada de eso está construido.
- `pipeline-events` se declara compileOnly a propósito: un plugin que tuviera que empaquetar el
  Event Plane podría montar un SEGUNDO registro junto al del host.
```

---

## Cierre — protocolo de investigación de referencia

```text
Implementacion de referencia consultada: el propio examples/example-uppercase-plugin (CERTIFIED)
                                         y examples/sdk-external-execution (B2)
Comportamiento adoptado:                 los comandos de la guía son los que la cadena del build ya
                                         usa; la guía se derivó ejecutándolos
Desviaciones intencionadas:              guía en el directorio del ejemplo, no en docs/ (un autor
                                         mira ahí; el código manda sobre el documento)
Implicaciones de seguridad revisadas:    n/a (no se ejecuta nada con credenciales)
Tests que demuestran el contrato:        ExternalPluginFourFamilyAdmissionTest (fila CONTROL +
                                         mutantes por familia), Core*StepContractSuiteTest
```
