# ASX-000 (B5, primera unidad) — Baseline de caracterización del payload de credenciales

**Fecha:** 2026-10-08 · **SHA base:** `863e0a8d` (rama `s6-plugin-sdk`)
**Entregable:** `v2/pipeline-application/src/test/kotlin/.../credentials/CredentialBindingsPayloadCharacterizationTest.kt`
**Cambio en producción:** **ninguno** (ASX-000 es tests/docs únicamente, por definición del propio plan)

---

## 1. Qué pide ASX-000, y qué no

De `docs/proposals/pipelinek-agent-secretless/04-roadmap/WORK-UNITS.md`:

```text
## ASX-000 — Characterization baseline
Cambios: tests/docs únicamente.
Pruebas: legacy DSL, core.sh payload/fingerprint/events, replay, withCredentials.
STOP: cualquier fixture actual no reproducible.
```

Es la unidad que B5 exige **primero**, y es la única del bloque ejecutable sin aceptar ningún ADR:
congela el comportamiento que la migración de credenciales no puede romper. Las unidades
ASX-001..013 cambian semántica pública de credenciales (ADR-PKAS-005 **congela** el
`withCredentials` legacy, ADR-PKAS-002 separa uso de secreto de recuperación), así que necesitan
los ADR aceptados: eso es una decisión, no una tarea.

## 2. Lo que se midió antes de escribir nada

El área de credenciales **ya está densamente cubierta**, y decirlo evita vender como nuevo lo que
ya existía. 25 clases XML, todas verdes, entre ellas: `Credential sealed hierarchy` (12),
`CredentialBindingSpec sealed hierarchy` (15), `DefaultCredentialProjector per-kind projections`
(13), `CredentialMaterializer tests — CR-MZ-001..012` (16), `PipelineDsl withCredentials tests` (16),
`WithCredentialsCompileIntegrationTest` (6) y `Credential envelope shape is stable across the
on-disk format` (4).

Pero **ningún test fijaba los bytes exactos** que produce `CredentialBindingsPayload.encode`, y esos
bytes son material de huella: son el payload del nodo `withCredentials` compilado, así que dos
payloads distintos son dos operaciones distintas para el motor durable. Los tests llamaban a
`encode(...)` y llevaban el resultado al coordinador, lo que prueba que el códec **funciona** y no
prueba **qué produce**. Una migración que reordenara un campo habría cambiado la identidad de toda
la historia `withCredentials` existente **en silencio**.

## 3. El pin, y cómo se derivó (predecir, observar, comparar)

Las 9 cadenas esperadas se escribieron **leyendo el encoder**, no copiando su salida, y coincidieron
con la observación **a la primera**. Que la predicción venga del código y la observación del
ejecutable es lo que hace que el pin sea una afirmación y no una transcripción.

```text
string                {"kind":"string","credentialsId":"cid","variable":"VAR"}
usernamePassword      {"kind":"usernamePassword","credentialsId":"cid","usernameVariable":"U","passwordVariable":"P"}
ssh (opcionales no)   {"kind":"sshUserPrivateKey","credentialsId":"cid","keyFileVariable":"K"}
ssh (opcionales sí)   ...,"keyFileVariable":"K","passphraseVariable":"PP","usernameVariable":"U"
file                  {"kind":"file","credentialsId":"cid","variable":"VAR"}
certificate (no)      {"kind":"certificate","credentialsId":"cid","keystoreVariable":"KS"}
certificate (sí)      ...,"credentialsId":"cid","keystoreVariable":"KS","aliasVariable":"A","passwordVariable":"P"
zip                   {"kind":"zip","credentialsId":"cid","variable":"VAR"}
usernameColonPassword {"kind":"usernameColonPassword","credentialsId":"cid","variable":"VAR"}
```

Lo que el pin captura y ningún lector podría adivinar: las clases de dominio usan **"Jenkins
verbatim field order"** (`StringBindingSpec` construye `(credentialsId, variable)` y `ZipBindingSpec`
construye `(variable, credentialsId)`), mientras que el **encoder emite `credentialsId` primero en
todos los tipos** — incluido `certificate`, cuyo constructor pone `keystoreVariable` delante. Es
deliberado y sólo se ve en los bytes.

15 filas: 9 de bytes exactos, orden y forma del payload (incluido `{"bindings":[]}` para la lista
vacía), simetría `decode(encode(x)) == x` sobre los 7 tipos con ambas formas opcionales, el
vocabulario cerrado de 7 discriminadores (más el rechazo de un octavo inventado **nombrando el
tipo**), y 2 filas de fallo cerrado (campo requerido ausente nombrando campo y tipo; raíz sin
`bindings` rechazada en vez de tratada como vacía).

## 4. Autoridad productiva cruzada y mutación

La clase llama a `CredentialBindingsPayload`, que **es** la autoridad: no hay adaptador en medio ni
se simula ninguno. Nivel HF0 (contrato puro), que es el nivel correcto porque la autoridad es
precisamente ese códec.

```text
M  intercambiar el orden de emisión de los dos campos opcionales del ssh en el encoder
   -> ROJO: exactamente 1 fila de 15 ("sshUserPrivateKey: exact bytes with optional fields
      PRESENT, in encoder order"); las otras 14 siguen verdes
   el rojo es la fila cuya afirmación la mutación falsifica, y sólo esa
sha256 previo y posterior de CredentialBindingsPayload.kt == ba3759d654246612...  (IDENTICO)
git status de v2/pipeline-application/src/main/ -> sin cambios: la produccion quedo intacta
```

Que la mutación mate **una** fila y no el bloque entero es lo que demuestra que el pin es
específico. Una mutación que tiñera 15 rojos probaría que existe un pin, no que cada fila afirma
algo propio.

## 5. Verificación ejecutada (XML fresco, canarios borrados antes)

```text
cd v2 && timeout 600 ./gradlew :pipeline-application:test \
    --tests '*CredentialBindingsPayloadCharacterizationTest*' \
    --tests '*B1aBodyExecutionEngineCredentialPurposeTest*' \
    --tests '*CanonicalDurableRunCoordinatorTest*'
  EXIT 0   CredentialBindingsPayloadCharacterizationTest 15/0
           B1aBodyExecutionEngineCredentialPurposeTest    3/0
           CanonicalDurableRunCoordinatorTest            26/0
  TOTAL 44 tests, 0 fallos, 0 errores — sobre el arbol RESTAURADO (hash verificado)
```

## 6. Alcance, sin sobreafirmar

```text
CUBIERTO en esta rebanada:  el payload del nodo withCredentials (bytes, orden, simetria,
                            vocabulario cerrado, fallo cerrado) — la mitad de ASX-000 que es
                            material de huella y la que ADR-PKAS-005 congela.
NO CUBIERTO por esta rebanada, y sigue siendo ASX-000:  legacy DSL, core.sh payload/fingerprint/
                            events, y replay. Parte de ello ya tiene caracterizacion de B1a y de
                            B4.2 (la huella de `sh` consume `wireToken`), y se citara en la fila
                            de bloque en lugar de duplicarse.
```

## 7. Estado de SDDK, registrado porque es real

`sddk cycle status` responde `no active cycle found for project p-1f3622e11c093341`, mientras el
gate de git resuelve sin problema el WorkItem `f8fc07e6`. La rebanada se commitea bajo ese WorkItem.
No se abre un ciclo nuevo para B5 en esta sesión: hacerlo es una decisión estructural y el roadmap
dice que los bloques se abren como WorkItem al iniciarse, no que haya que inventar ciclo por
rebanada. Lo que **sí** queda dicho y no puede confundirse: B5 no tiene hoy WorkItem propio, y sus
unidades ASX-001..013 esperan la aceptación de ADR-PKAS-001..010.

---

## Cierre — protocolo de investigación de referencia

```text
Implementacion de referencia consultada: docs/proposals/pipelinek-agent-secretless/04-roadmap/
                                         WORK-UNITS.md (ASX-000) y el propio CredentialBindingsPayload
Comportamiento adoptado:                 congelar bytes como caracterizacion, al estilo de la
                                         caracterizacion G0 que el repositorio ya usa en las
                                         migraciones de Step
Desviaciones intencionadas:              ninguna
Implicaciones de seguridad revisadas:    el pin NO contiene valores de secreto: solo nombres de
                                         variables de entorno y el CredentialsId no secreto, que es
                                         exactamente lo que el payload puede llevar por diseno
Tests que demuestran el contrato:        CredentialBindingsPayloadCharacterizationTest (15),
                                         B1aBodyExecutionEngineCredentialPurposeTest,
                                         CanonicalDurableRunCoordinatorTest
```
