# Roadmap — PipelineK V2

Vigente desde el **2026-10-19** sobre la rama `refactor/reset-testing-and-docs`
(tag base `pre-testing-reset-20261018`). Este fichero **sustituye** al roadmap
anterior, que describía un régimen (`CycleRunner`, `installedContractTest`, vías
`fast`/`integration`/`candidate`, harness externo) que `AGENTS.md` §0 reemplazó
de forma explícita y que el RESET borró del árbol. Sus referencias a
`*_SPEC.md`, `PIPELINE_TEST_HARNESS.md` y los ADR de ese régimen apuntan a
ficheros que ya no existen: ninguna tarea de aquí depende de ellos.

Toda cifra de este documento está medida sobre el árbol en el momento de
escribirlo, con su denominador nombrado. Una afirmación sin denominador no es
una medición.

---

## 1. Estado medido

| Métrica | Valor | Fuente |
|---|---|---|
| SHA | `e7d18e14` | `git rev-parse HEAD` |
| Módulos declarados | 27 | `v2/settings.gradle.kts` |
| Módulos con test unitario permanente | **5** | `src/test/kotlin` presente |
| Módulos sin test | 22 | ídem |
| Tests unitarios ejecutados | **106** (19 informes XML, 0 fallidos, 0 saltados) | etapa 2 del gate |
| Escenarios E2E | 35 (34 pasan, **1 `NOT_RUN`**) | `v2/examples/run.sh` |
| `VERIFY_LOC / PRODUCT_LOC` | **2267 / 61319 = 0.037** (límite 2) | etapa 4 del gate |
| `VERIFY_ALL_WALL_CLOCK` | **peor de tres ensayos = 212 s** (209 / 212 / 211) (límite 900 s) | etapa 5 del gate, `§4` de `AGENTS.md` |
| Certificador externo | **`BLOCKED_EXTERNAL`** | no existe en este repositorio ni en remoto |

### Los 5 módulos con test

`pipeline-domain`, `pipeline-events-store`, `pipeline-step-sdk/runtime`,
`pipeline-credentials-api`, `pipeline-binding-factory`.

### El `NOT_RUN` que no es verde

`v2/examples/security/14-credentials-bindings.pipeline.kts` está marcado
`//@ requires-seeded-credentials`. El único escritor del almacén es
`pipelinek credentials add`, que llama `System.console()?.readPassword() ?: error("no TTY available")`.
No existe vía no interactiva. El launcher no debe sintetizar un almacén
cifrado a mano — eso probaría el fichero fabricationado, no el producto. Se
mantiene en el inventario, declarando su hueco, con el criterio de borrado
escrito en la cabecera del propio escenario.

---

## 2. Deuda técnica abierta

### 2.1 Defectos materiales corregidos en este ciclo

Cada uno con test de regresión mutation-verificado (el test muere al revertir
el arreglo; los demás siguen verdes).

- **Fuga de secretos.** `SecretPatternRegistry.buildUrlEncodedVariant` descartaba
  todo carácter no reservado, así que `p@ss w0rd` codificaba a `%40%20`. La
  variante no casaba ni con la forma codificada (fuga) ni con nada legítimo, y
  sí casaba con texto ajeno que tuviera esas dos codificaciones contiguas
  (sobre-redacción). La guarda `urlEncoded != secret` era inalcanzable mientras
  tanto, y es la prueba de la intención del autor.
- **`PATH+=` prependía el NOMBRE de la variable.** `EnvModel.apply` tomaba el
  grupo de captura de la regex, que es el nombre, no el valor: `PATH+MAVEN` con
  valor `/opt/maven/bin` inyectaba un directorio llamado `MAVEN`. Además
  iteraba en orden inverso al declarado y **descartaba las entradas enmascaradas**
  del entorno del hijo (`isMasked` significa «no redactar», no «no pasar»). Y le
  faltaba la guarda de entrada vacía que el overload hermano sí tenía.
- **La puerta no ejecutaba nada.** `scripts/verify-all.sh` pedía `detekt` (que en
  detekt 2.x no tiene source set por defecto y analiza cero ficheros), traducía
  rutas de módulo de disco a rutas de proyecto Gradle sin cambiar `/` por `:`, y
  contaba XML JUnit de suites borradas por el RESET. Los 17 ficheros de build que
  afirmaban que la raíz fijaba `failOnNoDiscoveredTests` describían una bandera
  que **no existe en Gradle 8.14.5**.

### 2.2 Lo que queda abierto, por valor operativo

**a) 22 módulos sin test unitario permanente.** No es un objetivo de paridad.
`AGENTS.md` §2 manda por riesgo, no por cuota. El orden de ataque es:

| Prioridad | Módulo | Riesgo que justifica el test |
|---|---|---|
| 1 | `pipeline-credentials-local` | Red de seguridad de materialización y borrado de secretos. Ninguna prueba que observe fugas de credenciales puede eliminarse sin cobertura equivalente. |
| 2 | `pipeline-output-store` | Continuación del Output Plane por offset comprometido. Un segmento mal dirigido duplica o pierde bytes ya emitidos. |
| 3 | `pipeline-step-sdk/http` | Red, reintentos, códigos no-2xx. Adaptador con pocas reglas puras extraíbles. |
| 4 | `pipeline-runtime-api` | `RuntimeRecoverDecision` es un decider puro y público: la misma clase que `RunExecutionLease`, coste de test bajo y valor alto. |
| — | `pipeline-events` | Su único candidato (`EventViewProjection`) no tiene consumidor: ver 2.2 b. Sin consumidor, un test es coste sin destinatario. |

Los 18 restantes son adaptadores finos o módulos sin clases
(`pipeline-sdk-bom`). No se les asigna test por asignar.

Ratios por módulo, medidos (VERIFY / PRODUCT): `pipeline-binding-factory`
123/289 = 0.426, `pipeline-domain` 518/6262 = 0.083, `pipeline-credentials-api`
62/751 = 0.083, `pipeline-events-store` 288/4534 = 0.064, `pipeline-step-sdk:runtime`
98/2092 = 0.047. Ninguno se acerca al límite 2; el presupuesto no es lo que
frena la cobertura, es la elección de qué riesgo merece un test.

**b) `EventViewProjection` sin consumidor — MEDIDO, no citado.** Un barrido de
todo `src/main` del repositorio encuentra exactamente dos referencias fuera del
propio fichero, y ninguna es una llamada:

- `pipeline-application/.../Main.kt:314` — un **comentario** que menciona
  «deprecated EventViewProjection.CONSOLE»;
- `pipeline-events/.../EventInspection.kt:9,21` — dos enlaces de KDoc, que además
  dicen que `EventInspection` **no** es sustituto suyo.

Es decir: la ruta de lectura viva es `EventInspection`, y `EventViewProjection`
(`object` + `enum ViewMode` + `enum OutputFormat`, con un modo `CONSOLE` que su
propio KDoc llama «el tipo peligroso de error») es código muerto.

**No se borra en este ciclo, y el motivo es concreto:** `:pipeline-events` es un
**contrato publicado**. Retirar un `object` público de un artefacto publicado es
una ruptura de ABI, y `AGENTS.md` §1 la prohíbe sin una ruptura versionada
explícitamente autorizada. Tampoco se le añade un test permanente: verificar
código que nadie llama es coste sin destinatario.

Queda como decisión de producto, con dos salidas — darle un consumidor real, o
una ruptura versionada que lo retire. Es lo único de esta lista que bloquea con
una decisión, no con trabajo.

**c) `~/.worktrees/` — 10 worktrees obsoletos, ~8000 ficheros Kotlin fantasma.**
Ninguno está contenido en esta rama y cada uno lleva commits únicos: borrarlos
destruye historia sin fusionar. **No se tocan sin confirmación explícita.**

**d) `docs/` sin seguimiento.** Contiene Markdown versionado que `AGENTS.md` §6
prohíbe bajo `docs/`. No está en Git, así que borrarlo es **irrecuperable**:
requiere confirmación explícita antes de hacerlo.

**e) ~27 citas `design.md §` colgando.** Routes a documentos borrados por el
RESET. Son referencias rotas en KDoc y comentarios, no enlaces que alguien
pueda seguir.

**f) `EffectReplayPolicy`**, KDoc que cita una clase de test eliminada.

**g) El digest de `Fingerprint` cambió** al corregir la igualdad de `Map`, lo
que altera cualquier digest emitido antes. Consignado, no revertido: el
comportamiento anterior era incorrecto.

### 2.3 Cierre de ciclo

- `PRODUCT-GATE` (certificador externo, RC publicada) = **`BLOCKED_EXTERNAL`**.
  No hay CI remoto en este repositorio. La ausencia de CI **nunca** se declara
  `PASS`.
- **Presupuesto de tiempo: CERRADO.** Tres ensayos limpios consecutivos de
  `scripts/verify-all.sh` sobre el mismo SHA `e7d18e14`, con cachés de
  dependencias locales ya pobladas y sin red: **209 s, 212 s, 211 s**. El peor
  de los tres es **212 s** frente al límite de 900 s, que es lo que `AGENTS.md`
  §4 exige para poder declarar conformidad estable. Los tres corrieron sobre
  salidas de compilación reales (no `UP-TO-DATE` servido de caché) y los tres
  imprimes `PASS` con `executed=106 failed=0 skipped=0` leído del XML de JUnit,
  no del código de salida.
- **Presupuesto de LOC: CERRADO** con holgura de dos órdenes de magnitud
  (`0.037` sobre un límite de `2`).

---

## 3. Decisiones tomadas en este ciclo, con su porque

- **No** crear un arnés de pruebas. El código de verificación vive en
  `scripts/verify-all.sh` (un entrypoint) y `scripts/check-budgets.py` (un
  script stdlib). Nada más.
- **No** probar `EventViewProjection`: su alcanzabilidad desde producción es
  cero, y un test permanente para código que nadie llama es verificación de
  coste sin consumidor.
- **Mutación antes que confianza.** Cada test de regresión de este ciclo se
  comprobó revirtiendo el arreglo. Un test que sobrevive a su propia mutación
  no prueba nada, y se dice antes de llamarlo garantía.
- **Los `NOT_RUN` se declaran, no se esconden.** Un escenario que no se ejecutó
  es un tercer estado, y el gate lo imprime.

---

## 4. Referencias

- `AGENTS.md` — política vigente; manda sobre cualquier documento histórico.
- `scripts/verify-all.sh` — la única puerta local.
- `scripts/check-budgets.py` — el único script de presupuestos.
- `v2/examples/run.sh` — el único lanzador E2E.
- Historia anterior: tag `pre-testing-reset-20261018`. No se resucita en el árbol
  activo.