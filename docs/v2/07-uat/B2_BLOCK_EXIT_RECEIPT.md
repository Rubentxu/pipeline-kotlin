# B2 — cierre del bloque: plataforma de plugins consumible desde fuera

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08
**Rebanadas:** B2a (`1378eb47`), verificación B2.2 (`164dd11e`), y este cierre.
**Estado:** sustancia entregada y verificada. Dos residuos nombrados, ninguno disfrazado de hecho.

---

## 1. Lo que B2 entrega de nuevo

```text
BOM         :pipeline-sdk-bom — `java-platform` con un constraint `api` por contrato publicado,
            publicado por la MISMA tarea al MISMO sdk-repo. Deliberadamente FUERA de
            `publishedContractModules`: no tiene clases, ni `src/`, ni ABI, ni dump que congelar,
            y ponerlo ahí sería declarar una superficie que no existe.
EJECUCIÓN   examples/sdk-external-execution — build INDEPENDIENTE (settings propio, cero
            `project(...)`) que resuelve por `platform(...)` con las coordenadas SIN versión,
            construye un plugin contra el SDK publicado y **ejecuta la distribución instalada**
            con `--plugin-jar`.
```

El witness no es el exit 0: se afirma el **payload propio del handler** (`"v1:5:5"`, el códec del
plugin sobre `"hello" -> "HELLO"`), que sólo puede existir si el handler corrió sobre una composición
que admitió el plugin. Mi mutación cambiando la entrada a `"hi"` lo pone en RED; fixture restaurado a
`f436a26f…`.

## 2. Lo que B2 verifica (y por tanto NO reimplementa)

Verificado fila por fila en `B2_CHECKLIST_VERIFICATION_RECEIPT.md`, con XML fresco mío:

```text
apiRange / versión de motor     YA ESTABA. PluginAdmission.kt:71 rechaza con IncompatibleApiRange en
                                la pasada previa a la carga. La prueba no dice "rechazado": dice que
                                el CÓDIGO DEL PLUGIN NUNCA CORRIÓ (sin centinela), y trae su control
                                de no-vacuidad al lado.   5 tests, 0 fallos
plugins incompatibles /         2 tests por el BINARIO INSTALADO real (sin manifest; manifest que
errores de admisión             miente sobre sus Steps)
cargas duplicadas               DuplicateIdentity nombra al recién llegado Y al incumbente
repositorios parametrizables    -PsdkRepo / -PsdkVersion; el default frágil se retiró en f1770e37
metadatos de artefacto          manifest dentro del JAR real + propiedades + digest (B0.2)
ServiceLoader / metadata        cuatro adaptadores por familia; KSP retirado (sin 2ª autoridad)
```

Y el conjunto contractual por plugin, **medido** en esta rebanada (558 tests, 0 fallos):

```text
UppercaseStepContractSuiteTest            14   en pipeline-application (el plugin EXTERNO)
CoreUtilsStepContractSuiteTest           113   en el propio módulo del plugin
CoreScmGitCheckoutStepContractSuiteTest   20   en el propio módulo del plugin
```

Que la suite viva en el módulo del plugin es lo correcto: el plugin posee su contrato.

## 3. Residuos, nombrados

```text
B2.4  El conjunto de fixtures contractuales que consumirá S7. Las suites por plugin EXISTEN y
      cubren identidad, contrato, codecs, envelope, capacidad, success, durables, replay y
      divergencia; lo que no existe es el arnés que las convierta en veredictos con vocabulario
      cerrado, testigos exigidos por mutación y niveles HF. Eso es B3, y aquí se declara como
      preparación pendiente en vez de darlo por hecho.
B2-h  Disposición del puerto `BranchInvoker`: sin llamante de producción. `parallel` SÍ ejecuta
      (medido con el binario: dos branches con identidad durable, `ParallelBranchStarted/Finished`,
      join y stage siguiente) mediante `ParallelStageEngine`. Es higiene de diseño — mantener con
      llamante, mantener como seam documentado, o retirar — y no un defecto de producto.
```

## 4. Gate y estado

`check` completo verde sobre el árbol de B1+ (24m41s, 5195 tests, 0 fallos, 140 skipped) más, encima,
las corridas acotadas de esta rebanada. Los cambios de B2 son aditivos: un módulo `java-platform`, un
task raíz, un ejemplo externo y un dump de API que se registró cuando el módulo EXPERIMENTAL añadió una
propiedad de extensión.

```text
commits locales: 16   push: cero   contratos publicados rotos: cero
```

## 5. Lo que este cierre NO hace

```text
- No declara el PRODUCT-GATE verde: G10 sigue BLOCKED_EXTERNAL (ADR-0105).
- No certifica ningún SHA ni sustituye la certificación del harness externo.
- No convierte los residuos en PASS: B2.4 y B2-h quedan abiertos y con dueño.
- No toca el remoto.
```
