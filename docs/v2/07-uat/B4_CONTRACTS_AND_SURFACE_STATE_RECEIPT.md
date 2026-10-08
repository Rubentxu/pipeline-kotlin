# B4 — estado verificado de S8 (contratos y compatibilidad), y su hueco accionable

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08 · **Base:** `d5e0ff4a`
**Método:** ejecutar los fitness que protegen las clasificaciones y **leer** el contenido real de sus autoridades, en vez de citarlas de memoria.

---

## 1. B4.1 — clasificación de contratos: existe, está protegida y está verde

```text
P3-E E5 — published contract maturity     16 tests  0 fail  0 err   (12:49:31Z)
FArchS0SurfaceManifestTest                11 tests  0 fail  0 err   (12:49:29Z)
```

Clasificación de módulo publicados (`v2/contract/published-contract-maturity.json`):

```text
pipeline-domain  EXPERIMENTAL   pipeline-scripting-api  EXPERIMENTAL
pipeline-events  EXPERIMENTAL   pipeline-output         EXPERIMENTAL
```

Cuatro módulos, cuatro clasificaciones, y un fitness que **falla el build** cuando un módulo publicado
no tiene entrada. La taxonomía es cerrada (STABLE / PARTIAL / EXPERIMENTAL / DEPRECATED /
UNSUPPORTED_FAIL_CLOSED) y el fichero declara además que **una excepción no puede invocar una madurez
más laxa que la efectiva en su superficie** — es decir, la clasificación tiene dientes, no es una
etiqueta.

Nota de coherencia con lo ya entregado: que `pipeline-domain` sea EXPERIMENTAL es lo que permitió, en
B1b, añadir `CredentialBindingSpec.boundPurpose` como cambio **aditivo sin excepción registrada**. No
inventé una excepción porque la madurez no la exigía.

## 2. B4.3 — superficies del DSL: verificadas, con estados que llevan su propia evidencia

`DSL_SURFACE_MANIFEST.md` (184 líneas) es un inventario de **conjunto cerrado** verificado por
reflexión contra el código compilado: lista todo builder público de `StageScope` y todo subtipo de
`StepSpec`, y **un constructo no listado hace fallar el check**. No hay cajón UNKNOWN.

Estado de las superficies que B4.3 nombra, leído del manifiesto:

```text
environment  STABLE    witness S3.2; la mutación M-s3-2 lo volvió ambiente y puso los testigos de
                       aislamiento en ROJO (revertida, nunca commiteada)
options      STABLE    carrier tipado (StageOption sellado); reemplazó a OptionSpec(String,String)
post         STABLE    PostPlanner por StageOutcome; outcome desconocido falla CERRADO
agent        STABLE    carrier ExecutionTargetRequirement, el motor lee la POLÍTICA, no la clave;
                       Remote se CARGA y se RECHAZA (no hay allocator hasta RP-8)
agentAny     STABLE
agentWithCapabilities  PARTIAL   <- ver §3
when*/deleteDir/cleanWs/milestone/dir/withEnv/withCredentials/timeout/retry/waitUntil/parallel
             STABLE
load         UNSUPPORTED_FAIL_CLOSED   core.load no tiene handler; la admisión lo rechaza
catchError   DEPRECATED    el código lleva @Deprecated(LFC1-007) desde siempre y la fila decía
                           STABLE: contradicción corregida en P3-E E6
script       DEPRECATED
pwd/isUnix   SCRIPTED_RUNTIME_CALL, STABLE, MAY_DISCARD (descartar es legítimo: el step se emite)
retry (retrofit) / retry conditions   UNSUPPORTED_FAIL_CLOSED
```

Que `catchError` esté en DEPRECATED *porque el código lleva la anotación* es la señal de que el
manifiesto se corrige contra el código y no al revés. Eso es lo que B4.1 pide.

## 3. El hueco accionable: `agentWithCapabilities` en PARTIAL

Es el único estado PARTIAL del manifiesto, y su condición de promoción está escrita con precisión:

```text
carrier, codec, resolver y rechazo fail-closed: existen y están probados.
lo que falta: en la composición actual el conjunto de capacidades CONCEDIDAS está VACÍO (el default
de `targetResolver` en `BeforeStageDirectiveEngine`), así que NO hay camino de producción en el que
este constructo llegue a tener éxito. STABLE reclamaría un camino positivo que sólo un test produce.
promoción a STABLE: componer de forma genérica el conjunto realmente concedido. Una tabla estática
de "capacidades que PipelineK puede proveer" está EXPLÍCITAMENTE rechazada como sustituto, porque
es justo la invención que `LocalExecutionTargetResolver` existe para evitar.
```

No es un drop silencioso — falla cerrado con diagnóstico — pero un constructo que siempre rechaza no
ha demostrado la superficie que anuncia. **No lo he promovido**, porque la promoción exige el trabajo
que su propia fila describe, no un cambio de etiqueta.

## 4. Lo que B4 sigue necesitando

```text
B4.2  Caracterización de compatibilidad por separado: fuente, binaria, wire/schema, semántica y
      durable/replay. Exige consumidores compilados contra versiones anteriores, que hoy no hay
      producidos en este repositorio. Es el hueco grande de B4.
B4.3-resto  `agentWithCapabilities` (§3), y la verificación de que ningún miembro público se retiró
      sin deprecación: los dos DEPRECATED del manifiesto declaran su frontera de retirada.
B4.4  Guía de autores de plugins construida sobre un ejemplo ejecutable. El material existe
      (`examples/example-uppercase-plugin` + el consumidor externo de B2a), la guía no.
B4.5  Candidata según el modelo vigente. La producción y certificación pertenecen al harness
      (ADR-0105), así que esta fila no se cierra desde aquí.
```

## 5. Corrección de un método mío

Antes de leer el manifiesto intenté **contar sus categorías por ocurrencias de substring** en el
fichero. Eso no es un recuento del contenido: da 62 en las cinco categorías y 14 en
UNSUPPORTED_FAIL_CLOSED, cifras que no significan nada porque cuentan también las menciones en prosa
y en tablas. **No las he reportado como el contenido del manifiesto.** La evidencia válida es el
fitness que lo verifica por reflexión, y por eso está arriba y no un número inventado por mí.

## 6. Verificación ejecutada

```text
cd v2 && timeout 900 ./gradlew :pipeline-architecture-tests:test \
        --tests '*PublishedContractMaturity*' --tests '*SurfaceManifest*' --rerun-tasks
EXIT 0    P3-E E5 maturity 16/0/0 · FArchS0SurfaceManifestTest 11/0/0
canario: XML borrados antes de la corrida y regenerados con timestamp fresco
```

## 7. Lo que este recibo NO hace

```text
- No declara B4 cerrado: verifica B4.1 y la parte de B4.3 que ya existía, y nombra lo que falta.
- No promueve `agentWithCapabilities`: la promoción exige el trabajo, no el cambio de estado.
- No produce consumidores antiguos para B4.2: sin ellos, esa fila queda OPEN y así se dice.
- No toca producción ni el remoto.
```
