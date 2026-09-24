# HARNESS PROMPTS — Para enviar al agente del operador en `pipelinek-release-harness`

> **Estado:** REESCRITOS (2026-09-24T11:26Z) tras directiva del operador sobre el estado real del harness.
> **Generados por:** sesión AUTO de pipeline-kotlin.
> **Cambio importante vs versión previa:** el operador confirma que el harness YA TIENE clasificación de fallos, publicación/actualización de issues, deduplicación, registro local de reverificación y CLI (`run`, `publish-issue`, `reverify-cycle`, `certify`). Por tanto, los prompts NO son de inicialización, sino de **activación del circuito ejecutable H0.3** sobre lo que ya existe.
> **Uso:** el operador pega cada bloque a su agente del harness. NO se ejecutan desde aquí.

---

## Prompt A — Cerrar H0.3: demostrar circuito ejecutable con v0.39.0 ya publicada

**Para:** el agente que opera en `Rubentxu/pipelinek-release-harness`.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: cerrar H0.3 — demostrar el circuito ejecutable end-to-end entre
pipeline-kotlin y este repositorio. NO empezar todavía con cinco lenguajes,
benchmarks, ni publicación de releases.

Estado actual del harness (confirmado por el operador 2026-09-24T11:26Z):
- Clasificación de fallos: IMPLEMENTADA.
- Publicación / actualización de issues: IMPLEMENTADA.
- Deduplicación por huella estable: IMPLEMENTADA.
- Registro local de incidencias pendientes de reverificación: IMPLEMENTADO.
- CLI con subcomandos: `run`, `publish-issue`, `reverify-cycle`, `certify`.
- Perfil Base: CERTIFICADO.
- Perfil Real ampliada: NO cerrado.
- Perfil Certificación completa: NO cerrado.

Por tanto, este prompt NO es para diseñar ni implementar nada desde cero.
Es para VALIDAR que las piezas existentes componen un circuito real y
demostrarlo con la candidata v0.39.0 ya publicada.

Candidata a usar:
- Tag: v0.39.0
- Commit certificado: 951b3cb5695ecc46c877776e330266e4bd44aa9e
- ZIP URL: https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.39.0/pipelinek-0.39.0.zip
- ZIP SHA-256: 385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8
- Binary SHA-256: 92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee

Tareas concretas:

1. Lanzar `pipelinek-harness run v0.39.0` con la CLI existente. Que descargue
   el ZIP desde GitHub Releases, verifique SHA-256, y arranque el binario
   en Podman. NO usar installDist local.

2. Confirmar que el run produjo un resultado estructurado consultable desde
   el repositorio de origen (pipeline-kotlin). El resultado debe estar
   enlazado al commit 951b3cb5695ecc46c877776e330266e4bd44aa9e.

3. Si el run es PASS: ejecutar `pipelinek-harness certify v0.39.0` y publicar
   el recibo en evidence/<candidate>/.

4. Si el run descubre un defecto reproducible del motor: ejecutar
   `pipelinek-harness publish-issue v0.39.0 --huella "<contrato>+<escenario>+<tipo>+<causa>"`
   y abrir la issue en Rubentxu/pipeline-kotlin (NO en este repo).

5. Demostrar la consulta desde el lado de pipeline-kotlin: que un agente de
   pipeline-kotlin pueda recuperar el resultado del run desde GitHub
   (recibo + estado + commit enlazado) sin acceso al filesystem de este repo.

6. Documentar el cierre de H0.3 en docs/v2/07-uat/H0_3_CLOSURE_RECEIPT.md
   con:
   - SHA-256 del ZIP usado.
   - Comando exacto ejecutado.
   - Resultado (PASS o FAIL con huella).
   - Evidencia de la consulta cross-repo (URL al recibo en este repo +
     URL a la issue si aplica).

NO toques pipeline-kotlin directamente. NO hagas PR cross-repo. Si necesitas
algo del repo de origen, abre una issue con la huella estable y avisa al operador.

No confundir lo que YA EXISTE en el harness con la AUTOMATIZACIÓN NO IMPLEMENTADA.
Los commits, PR y recibos de pipeline-kotlin ya ofrecen trazabilidad, pero NO
debemos dar por hecho que existe un servicio que detecta candidatas, crea issues
y promociona releases hasta que sus pruebas de extremo a extremo lo demuestren.
H0.3 es exactamente eso: la prueba de extremo a extremo del circuito mínimo.
```

---

## Prompt B — Reverificación: el agente de origen corrige un defecto, el harness lo verifica

**Para:** el agente del harness, sólo después de cerrar H0.3 con éxito (Prompt A).

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: ejecutar la reverificación de un defecto que pipeline-kotlin ya
corrigió. Esto valida la mitad inferior del circuito: corrección → nueva
candidata → reverificación → cierre de issue.

Estado actual esperado:
- H0.3 cerrado (ver Prompt A). El circuito base funciona.
- Hay una issue ABIERTA en Rubentxu/pipeline-kotlin con huella estable
  <contrato>+<escenario>+<tipo>+<causa>, abierta por el harness en el run de H0.3.

Tareas concretas:

1. Esperar (por polling o consulta) a que pipeline-kotlin publique una nueva
   candidata que afirme corregir el defecto. No adivines; sólo reacciona a
   candidatas reales con tag nuevo.

2. Cuando aparezca una candidata v0.39.1 o v0.40.0 o vX.Y.Z que afirme
   cerrar la issue, ejecutar `pipelinek-harness reverify-cycle <candidata>`.

3. El reverify-cycle debe:
   - Descargar el nuevo ZIP.
   - Verificar SHA-256.
   - Reproducir el escenario que falló en H0.3 (mismo --db, mismo
     --control-root, mismas flags).
   - Clasificar el resultado: PASS si el defecto ya no se reproduce,
     FAIL con huella si persiste.

4. Si PASS: ejecutar `pipelinek-harness publish-issue <candidata> --huella "..."
   --close` para cerrar la issue original en pipeline-kotlin, enlazando
   el SHA de la candidata correctiva y el recibo de reverificación.

5. Si FAIL: actualizar la issue en pipeline-kotlin con nueva evidencia.
   NO abrir issue duplicada (deduplicación por huella). NO ascender a
   candidato bloqueante el roadmap de pipeline-kotlin — es problema del
   producto, no del proceso.

6. Documentar el ciclo en docs/v2/07-uat/REVERIFY_CYCLE_CLOSURE_RECEIPT.md
   con:
   - SHA-256 de la candidata original que tenía el defecto.
   - SHA-256 de la candidata correctiva.
   - Comando reverify-cycle ejecutado.
   - Resultado (PASS/FAIL).
   - URL a la issue en pipeline-kotlin y su estado final.

NO toques pipeline-kotlin directamente. NO modifiques el ZIP ni el manifiesto.
Tu trabajo es observar, verificar y dejar rastro público en GitHub.
```

---

## Prompt C — Activar el circuito GitHub sin esperar al operador

**Para:** el agente del harness, cuando el operador no esté disponible y el harness
necesite seguir procesando candidatas de forma autónoma.

**Cuerpo del prompt (literal, listo para pegar):**

```text
Trabajo: este prompt NO es para correr. Es la POLÍTICA que el agente del
harness debe seguir cuando opera en modo autónomo entre rondas del operador.

Reglas:

1. **Detección de candidatas**: el agente consulta
   https://github.com/Rubentxu/pipeline-kotlin/releases cada vez que arranca
   un ciclo o al despertar. Compara el tag más reciente con el último SHA
   procesado en evidence/ledger.jsonl. Si hay tag nuevo, lo procesa.

2. **Deduplicación**: antes de abrir o actualizar una issue, el agente busca
   por huella estable. Si existe, actualiza; si no, crea. NO duplicar.

3. **Issue por defecto**: la issue va en Rubentxu/pipeline-kotlin, NO en
   este repo. El cuerpo lleva la huella + SHA de candidata + recibo + URL
   al verdict.json local del harness.

4. **Veredicto estructurado**: cada run produce un verdict.json en
   evidence/<candidate>/verdict.json con:
     {
       "candidate": "vX.Y.Z",
       "candidate_sha256_zip": "...",
       "candidate_sha256_binary": "...",
       "harness_commit": "...",
       "podman_image": "...",
       "scenarios_run": [...],
       "result": "PASS|FAIL|UNKNOWN",
       "failure_huella": "contrato+escenario+tipo+causa" (si FAIL),
       "receipt_urls": [...]
     }

5. **Recibo inmutable**: el recibo del run (NDJSON + verdict + artifacts)
   vive en este repo, NO en pipeline-kotlin. La consulta desde pipeline-kotlin
   es por URL de GitHub, no por filesystem compartido.

6. **Sin PR cross-repo**: el harness NO abre PR en pipeline-kotlin. Si la
   corrección del defecto vive en este repo, es un defecto del harness, no
   del producto. Los defectos del producto generan issue en pipeline-kotlin
   y se cierra el ciclo cuando la corrección llega como candidata nueva.

7. **Registro append-only**: evidence/ledger.jsonl es append-only. Cada
   línea es un run, con su SHA-256 zip + verdict + timestamp. NO se borra
   ni se sobreescribe.

8. **Operador como árbitro**: ante duda sobre si un defecto es del producto
   o del harness, escalar al operador vía issue con la duda, NO decidir
   unilateralmente.

NO ejecutes este prompt. Es la política que tu propio loop debe implementar.
```

---

## Notas operativas para el operador

- **Orden sugerido**: Prompt A (H0.3 con v0.39.0) → Prompt B (reverificación tras corrección) → Prompt C (política autónoma).
- **Decisión recomendada**: arrancar Prompt A con la candidata v0.39.0 ya publicada. No hace falta esperar a PR #76 de pipeline-kotlin. v0.39.0 es la candidata válida para H0.3.
- **Issue de coordinación**: cada prompt referencia la issue #2 del harness como punto de coordinación con pipeline-kotlin. Si la issue no existe, créala al ejecutar Prompt A.
- **PR #76 sigue abierta**: pipeline-kotlin la mantiene sin merge. El harness trabaja con v0.39.0 sin esperar.
- **No bloquear pipeline-kotlin**: si surge una necesidad de cambio en pipeline-kotlin durante cualquiera de estos prompts, **escalar al operador** vía issue con huella estable. NO hacer PR cross-repo directamente.
- **Distinción crítica que el operador subraya**: los commits, PR y recibos de pipeline-kotlin ya ofrecen trazabilidad, pero NO debemos dar por hecho que existe un servicio que detecta candidatas, crea issues y promociona releases hasta que sus pruebas de extremo a extremo lo demuestren. Prompt A es exactamente esa prueba.
