# HARNESS PROMPTS — Para enviar al agente del operador en `pipelinek-release-harness`

> **Estado:** REESCRITOS (2026-09-24T11:46Z) tras directiva del operador que confirma el SHA actual del harness y la posición de pipeline-kotlin.
> **Generados por:** sesión AUTO de pipeline-kotlin.
> **SHA actual del harness (verificado por el operador)**: `ca2a91c0` en `Rubentxu/pipelinek-release-harness/main`. El agente del harness debe referenciar su SHA real en cada artefacto publicado, NO un genérico.
> **SHA actual de pipeline-kotlin (post-#85)**: `d5eb9781`. PR #76 sigue ABIERTA por motivos del motor, no del circuito.
> **Uso:** el operador pega cada bloque a su agente del harness. NO se ejecutan desde aquí.

---

## Prompt A — Cerrar H0.3 sin ceremonia; ejecutar circuito real con v0.39.0

**Para:** el agente que opera en `Rubentxu/pipelinek-release-harness`.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: cerrar H0.3 — demostrar el circuito ejecutable end-to-end entre
pipeline-kotlin y este repositorio.

Estado actual del harness (verificado por el operador 2026-09-24T11:46Z):
- **HEAD actual**: `ca2a91c0` en `Rubentxu/pipelinek-release-harness/main`.
- CLI con subcomandos: `run`, `publish-issue`, `reverify-cycle`,
  `candidates`, `certify`.
- Interfaz REAL de `run`: `run --image ... --project ... --scenario ...`
  (NO `run v0.39.0`).
- Clasificación de fallos: IMPLEMENTADA.
- Publicación / actualización de issues: IMPLEMENTADA.
- Deduplicación por huella estable: IMPLEMENTADA.
- Registro local de reverificación: IMPLEMENTADO.
- Cola persistente de reverificación: IMPLEMENTADA.
- Perfil Base: CERTIFICADO.
- Perfil Real ampliada: NO cerrado.
- Perfil Certificación completa: NO cerrado.

Tareas concretas:

1. INSPECCIÓN: revisa el historial de runs H0.3 en este repo y el último
   recibo válido. Si H0.3 ya está cerrado con una ejecución real válida
   (ZIP instalado, escenarios ejecutados, recibos inmutables), NO lo repitas
   por ceremonia. Identifica el siguiente requisito pendiente y arranca
   directamente con él.

2. EJECUCIÓN (sólo si H0.3 sigue abierto o se decide re-ejecutar): lanza
   `pipelinek-harness run --image <imagen OCI> --project <proyecto>
   --scenario <escenario>` con la CLI existente. Usa la distribución oficial
   `v0.39.0` de pipeline-kotlin:
   - Tag: v0.39.0
   - Commit certificado: 951b3cb5695ecc46c877776e330266e4bd44aa9e
   - ZIP URL: https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.39.0/pipelinek-0.39.0.zip
   - ZIP SHA-256: 385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8
   - Binary SHA-256: 92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee

3. El `run` debe verificar el SHA-256 del ZIP descargado contra el publicado.
   La imagen OCI con pipelinek debe estar construida sobre el ZIP verificado,
   no sobre installDist local.

4. Ejecuta al menos DOS escenarios sobre la distribución instalada:
   a. **Éxito controlado**: un escenario que sabemos que pasa (por ejemplo,
      ejecutar `examples/01-hello.pipeline.kts` de pipeline-kotlin).
   b. **Fallo controlado**: un escenario que sabemos que falla (por ejemplo,
      `examples/05-failing-step.pipeline.kts` con código de salida 1).

5. COMPROBACIÓN DESDE FUERA: el exit code, el resultado tipado y los efectos
   reales deben ser consultables desde FUERA de este repo. El recibo vive
   en este repo (evidence/<candidate>/), pero la consulta debe poder hacerse
   vía GitHub sin acceso al filesystem del harness. Cada artefacto debe
   referenciar el SHA del harness con el que se ejecutó (`ca2a91c0` o el
   HEAD actualizado cuando se arranque).

6. FILTRO DE ISSUES: los errores de contenedor, de la CLI o del propio
   harness NO deben generar issues de producto en Rubentxu/pipeline-kotlin.
   Sólo los defectos reproducibles del motor de pipeline-kotlin generan
   issues de producto.

7. Documentar el cierre en docs/v2/07-uat/H0_3_CLOSURE_RECEIPT.md con:
   - SHA-256 del ZIP usado.
   - Comando exacto ejecutado (con --image, --project, --scenario reales).
   - Exit code y resultado tipado de cada escenario.
   - URL al recibo en este repo + URL a cualquier issue generada.

NO toques pipeline-kotlin directamente. NO hagas PR cross-repo. Si necesitas
algo del repo de origen, abre una issue con la huella estable.

No confundir lo que YA EXISTE en el harness con la AUTOMATIZACIÓN NO
IMPLEMENTADA. H0.3 es exactamente la prueba de extremo a extremo del circuito
mínimo: SHA, ZIP, instalación, escenarios, recibos, issues legítimos,
consulta cross-repo.
```

---

## Prompt B — publish-issue con incidencia de ensayo; comportamiento sin credenciales

**Para:** el agente del harness cuando se quiera validar el camino `publish-issue`
sin generar issues reales en pipeline-kotlin.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: ejecutar pruebas controladas de `publish-issue` que demuestren:
(a) búsqueda de incidencia equivalente por huella estable, (b) creación o
actualización en Rubentxu/pipeline-kotlin, (c) deduplicación, (d) enlace
al recibo. NO abras una incidencia real inventando un defecto de pipeline-kotlin.

Estado actual del harness:
- CLI: `publish-issue`.
- Mecánica: huella estable = `<contrato>+<escenario>+<tipo>+<causa>`.

Tareas concretas:

1. PRUEBA CONTROLADA: usa una incidencia de ensayo identificada y autorizada
   por el operador, o un adaptador de pruebas. La huella debe ser ficticia
   pero explícita (por ejemplo, `ensayo-H0.3+publish-issue-control+ci+test+2026-09-24`).

2. Ejecuta `pipelinek-harness publish-issue v0.39.0 --huella "<huella ensayo>"`.
   Demuestra que el agente:
   - Busca la huella en la lista de issues abiertas de Rubentxu/pipeline-kotlin.
   - Si encuentra una equivalente, actualiza con el enlace al recibo.
   - Si no encuentra, abre una nueva issue con cuerpo que incluye:
     - Huella estable.
     - SHA-256 de la candidata (ZIP y binary).
     - URL al verdict.json local del harness.
     - Comando que reproduce el defecto (si lo hay).

3. DEDUPLICACIÓN: ejecuta el mismo `publish-issue` dos veces seguidas con la
   misma huella. La segunda llamada debe ser idempotente (no crea issue
   duplicada, actualiza la existente o sale con código "ya creada").
   El SHA del harness que publica debe quedar registrado en el cuerpo de
   la issue (no en el SHA de la candidata, per contrato cross-repo).

4. COMPORTAMIENTO SIN CREDENCIALES DE ESCRITURA: ejecuta
   `pipelinek-harness publish-issue` con credenciales revocadas o en modo
   dry-run. El resultado debe quedar como `ISSUE_PENDING`, NO como una
   incidencia publicada. El agente NO debe fallar con stack trace; debe
   registrar el estado pendiente en el ledger local y notificar al operador.

5. FILTRO DE DEFECTOS: verifica que los errores del propio harness (CLI,
   container, fallo de red) NO generan issues de producto. Sólo defectos
   reproducibles del motor de pipeline-kotlin clasificados como producto.

6. Documentar las pruebas en docs/v2/07-uat/PUBLISH_ISSUE_RECEIPT.md con:
   - Huella usada (de ensayo, no real).
   - Resultado de cada prueba (éxito, deduplicación, ISSUE_PENDING).
   - SHA-256 del verdict.json publicado.
   - URL a la issue si fue creada en modo ensayo, o al estado local si fue
     pendiente.

NO abras issues reales en Rubentxu/pipeline-kotlin. Si necesitas salir del
modo ensayo, avisa al operador.
```

---

## Prompt C — reverify-cycle con candidata correctora real o controlada

**Para:** el agente del harness, tras Prompt A y tras detectar un defecto real
o controlado.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: ejecutar `reverify-cycle` validando que la corrección llega como
candidata nueva, se ejecuta realmente, y produce evidencia fresca.

CRÍTICO: reutilizar un recibo anterior sin ejecutar la nueva distribución
NO certifica la corrección. La reverificación debe ejecutar el escenario
afectado sobre la candidata correctiva INSTALADA, no contra el recibo previo.

Estado actual del harness:
- CLI: `reverify-cycle`.

Tareas concretas:

1. DETECCIÓN: el agente consulta `pipelinek-harness candidates` para listar
   candidatas nuevas de pipeline-kotlin. Compara con el último SHA procesado
   en evidence/ledger.jsonl. Si hay tag nuevo, lo procesa.

2. ESPERA DE CORRECCIÓN: tras Prompt B (o tras una issue real), espera a
   que pipeline-kotlin publique una candidata correctora. No adivines;
   sólo reacciona a candidatas reales con tag nuevo.

3. EJECUCIÓN REAL: cuando aparezca la candidata correctora (por ejemplo,
   v0.39.1 con un commit que cierre la issue), ejecuta
   `pipelinek-harness reverify-cycle v0.39.1 --huella "<huella>"
   --image <imagen OCI>`.

4. El reverify-cycle debe:
   - Descargar el ZIP de la candidata correctora.
   - Verificar SHA-256.
   - Construir la imagen OCI sobre el ZIP verificado (NO sobre installDist).
   - Reproducir el escenario que falló en la candidata original con --db
     y --control-root idénticos.
   - Clasificar el resultado: PASS si el defecto ya no se reproduce,
     FAIL con huella si persiste.
   - Registrar el SHA del harness que ejecuta la reverificación en el
     nuevo recibo (no reutilizar SHA de la candidata original).

5. EVIDENCIA FRESCA: el recibo de reverificación debe generarse en esta
   ejecución. NO reutilizar el recibo de la candidata original. Cada
   SHA de candidata tiene su propio evidence/<candidate>/.

6. Si PASS: ejecuta `pipelinek-harness publish-issue v0.39.1 --huella "..."
   --close` para cerrar la issue original en pipeline-kotlin, enlazando
   el SHA de la candidata correctiva y el recibo de reverificación.

7. Si FAIL: actualiza la issue en pipeline-kotlin con nueva evidencia
   (recibo de reverificación). NO abras issue duplicada (dedupe por huella).
   NO asciendas el roadmap de pipeline-kotlin — es problema del producto.

8. Si no hay candidata correctora real disponible, usa una PRUEBA DE
   INTEGRACIÓN CONTROLADA: simula el flujo con una candidata local marcada
   como "ensayo" en el ledger. El agente debe distinguir ensayo de real
   en todos los artefactos publicados.

9. Documentar el ciclo en docs/v2/07-uat/REVERIFY_CYCLE_CLOSURE_RECEIPT.md:
   - SHA-256 candidata original con defecto.
   - SHA-256 candidata correctiva (o hash del ensayo si fue controlado).
   - Comando reverify-cycle ejecutado.
   - Resultado (PASS/FAIL).
   - URL a la issue en pipeline-kotlin y su estado final.

NO toques pipeline-kotlin directamente. NO modifiques el ZIP ni el manifiesto.
Tu trabajo es observar, ejecutar y dejar rastro público en GitHub.
```

---

## Prompt D — Primer proyecto externo pequeño: Spring REST en Podman

**Para:** el agente del harness, después de cerrar H0.3 y validar el circuito
publicación/reverificación.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: incorporar el primer proyecto externo pequeño — Spring REST —
como escenario de certificación. Demostrar compilación, tests y un fallo
intencionado en Podman.

Tareas concretas:

1. CREAR o IDENTIFICAR un proyecto Spring REST externo pequeño (un servicio
   REST con Gradle, ~3 endpoints, ~10 tests JUnit). El proyecto debe vivir
   FUERA de este repo. Si no hay uno, clona uno público existente o crea
   uno en un repo separado del harness.

2. CONFIGURAR el escenario en el harness como un nuevo proyecto:
   `pipelinek-harness candidates add spring-rest --source <repo>
   --type gradle-spring-boot`.

3. EJECUTAR éxito: `pipelinek-harness run --image <imagen OCI>
   --project spring-rest --scenario success`. Esto debe:
   - Compilar el proyecto (./gradlew build).
   - Ejecutar los tests JUnit.
   - Generar el JAR.
   - Salir con código 0.

4. EJECUTAR fallo intencionado: añadir un escenario `--scenario
   failing-build` que introduzca un error de compilación (por ejemplo,
   renombrar un archivo o cambiar una firma) y demostrar que el harness:
   - Detecta el fallo con exit code != 0.
   - Clasifica correctamente como fallo del proyecto (no como defecto del
     motor pipeline-kotlin).
   - NO abre issue de producto en Rubentxu/pipeline-kotlin.

5. PODMAN: verificar que las ejecuciones se hacen en un contenedor Podman,
   no en el host. El Dockerfile o Containerfile debe estar en este repo
   bajo `images/spring-rest/Containerfile`.

6. RECIBO: el run produce evidence/<candidate>/spring-rest-{success,fail}.log
   con la traza de Podman (no la traza del host).

7. DESPUÉS: añade Docker y los demás lenguajes progresivamente, sin
   bloquear esta entrega vertical. Es decir, NO esperes a tener 5 lenguajes
   antes de certificar Spring REST.

8. Documentar en docs/v2/07-uat/SPRING_REST_EXTERNAL_RECEIPT.md:
   - URL del proyecto externo.
   - Imagen OCI usada.
   - Salida de success y fail.
   - Verificación de Podman (no host).
   - Confirmación de que NO se generó issue de producto.

NO toques pipeline-kotlin. NO modifiques el ZIP ni el manifiesto. Tu trabajo
es certificación externa; si encuentras un defecto real del motor, abre issue
con huella estable en Rubentxu/pipeline-kotlin.
```

---

## Notas operativas para el operador

- **Orden sugerido**: Prompt A (H0.3 con v0.39.0) → Prompt B (publish-issue controlado) → Prompt C (reverify-cycle) → Prompt D (Spring REST).
- **Decisión clave**: Prompt A usa los argumentos REALES de la CLI (`run --image ... --project ... --scenario ...`, NO `run v0.39.0`). NO reinventes la interfaz.
- **Si H0.3 ya está cerrado**: Prompt A indica "no lo repitas por ceremonia; continúa con el siguiente requisito pendiente". El agente del harness debe primero INSPECCIONAR el historial.
- **Filtros críticos**:
  - Errores del harness NO generan issues de producto.
  - publish-issue sin credenciales = `ISSUE_PENDING`, no stack trace.
  - reverify-cycle con recibo anterior = NO certifica; debe ejecutar la nueva distribución.
- **PR #76 sigue abierta**: pipeline-kotlin la mantiene sin merge. El harness trabaja con v0.39.0 sin esperar.
- **No inventar infraestructura nueva**: reutiliza el estado y los contratos existentes; no crees un ledger paralelo.
- **No reenviar handoffs manualmente**: candidatas, recibos e issues deben quedar accesibles vía GitHub y los artefactos estructurados del harness. El operador NO debe pegar manualmente el resultado del harness en pipeline-kotlin; la consulta debe ser por URL.
