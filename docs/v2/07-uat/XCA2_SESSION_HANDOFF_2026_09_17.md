# XCA-2 session handoff — cierre 2026-09-17

> Documento de continuacion. Si lo lees sin contexto, empieza por aqui.

## Donde estamos en el ciclo

Sesion cerrada por el usuario a las 22:30 hora local. Estado real del repo,
verificado en ese momento:

```
rama    cycle/wu-g5b
head    74b309dc   (local, sin pushear)
origin  3ebc4a3b   (el checkpoint auditado de las 18:13)
arbol   limpio, 0 cambios
commits desde el checkpoint : 18
commits sin pushear al origin: 18
```

Los 18 commits son **TODO el trabajo XCA-2 que la sesion ha producido**.
Quedan en local, sin pushear, con autorizacion explicita del usuario para
push/tag en la fase `release`. La regla "release no es autocerrado" se
mantiene: el push espera decision.

## Campana XCA: estado real al cerrar

| Sub | Titulo                                                | Estado                              |
| --- | ----------------------------------------------------- | ----------------------------------- |
| XCA-0   | auditor con guards G1..G4                         | cerrado (commit `dd03a19d`)         |
| XCA-1A  | honestidad del ledger, 13 claims falsos eliminados  | cerrado (`0f74f992`)                |
| XCA-1B  | reconciliacion de inventario                        | cerrado (`6803a3a1`, `48d4d5c8`)    |
| XCA-1C  | recon + 1C.1 (5 fixtures) + 1C.2 (overloads String) | cerrado (`bf8cf0fd`, `97c54030`)    |
| XCA-GOV | autoridad duplicada eliminada                       | cerrado (`95188c44`)                |
| XCA-YAML| ledger reparado, parseable                          | cerrado (`a1e35345`)                |
| XCA-1D  | decision D: reclasificar (ADR-0050 intacto)         | cerrado (`0d682820`, `89fe0dd2`)    |
| XCA-STACK-REVIEW-FIX | dos defectos cross-commit         | cerrado (`659b59c0`)                |
| XCA-2A  | A1..A5 (fitness, ADTs, adaptador mecanico)          | cerrado (`d6df5f72`)                |
| XCA-2B  | primer vertical runtime real                        | cerrado (`9e293b49`)                |
| XCA-2 apply       | B.4/B.5/B.6 + C + D + E (4 commits)        | cerrado (kikazaru)                  |
| XCA-2 verify      | falsificacion RED/GREEN + demote CERTIFIED | cerrado (retriever)            |
| XCA-2 debt-verify | 9 hallazgos, H1+H7 BLOCK                     | cerrado (cactus)                |
| XCA-2 apply-fix   | cierra H1+H7+H8, declara H5/H6/H9          | cerrado (clover)                 |
| XCA-2 verify-fix  | detecta regresiones H1-PATH-BUG + H7-ERRORCLASS + H9-DOC | cerrado (sunflower) |
| XCA-2 apply-fix 2 | cierra las 3 regresiones nuevas           | cerrado (hibiscus)               |
| XCA-2 verify-fix 2| testigo M3 sobre los 4 commits de hibiscus | **EN CURSO** (blossom)           |

## Lo que esta en juego ahora mismo

`session_blossom_1789684205920_f86cd4e7d6d26c71` (M3) esta validando los
4 commits de apply-fix 2:

```text
d29b65f6  fix(H1-PATH-BUG): resolve ledger path from repo root
cb011ebd  fix(H7-ERRORCLASS-REGEX): extend regex with Violation
3cf22d59  fix(H9-DOC): remove false KDoc-points-to-SHAs claim
74b309dc  docs(XCA2_FIX_VERIFY): close H1/H7/H9 from apply-fix-2
```

Posibles salidas del envelope de blossom:

```text
1. CONFIRMA los 3 fixes, sin hallazgos nuevos
   -> siguiente paso: debt-verify 2 (glm-5-turbo), release, archive

2. ENCUENTRA 1+ hallazgos
   -> aplicar opcion A o B (ver "Decision taxonomy" abajo)
```

## Cadena de custodia demostrada (no asumida)

Cada fix de clover e hibiscus lleva RED->GREEN con dos SHAs distintos:

```
falsificacion del guard isObserved (eb40d5cb -> 7d725aca):
  RED  : isObserved = status.isTerminal        failures=1 (RUNNING no es terminal)
  GREEN: isObserved = status != PENDING        failures=0
```

```
H1-PATH-BUG (sunflower detecto, hibiscus cerro) (d29b65f6):
  RED  : core.pwd -> CERTIFIED en canonica     B.5 FAIL (line 326, expected: <false>)
  GREEN: core.pwd -> STOPPED_G7 (revertido)    B.5 PASS
```

```
H7-ERRORCLASS-REGEX (sunflower detecto, hibiscus cerro) (cb011ebd):
  ANTES: regex `(?:Exception|Error)`            [errorClass] no aparece para 12/21
  DESPU: regex `(?:Exception|Error|Violation)`  [EngineInvariantViolation] aparece
```

```
H9-DOC (sunflower detecto, hibiscus cerro) (3cf22d59):
  ANTES: apply-receipt afirma "KDoc apunta a los SHAs"  (grep = 0 matches)
  DESPU: frase borrada, reemplazada por el hecho OBSERVED
```

Esto cumple la ley metodologica que llevo combatiendo toda la sesion:
**todo guard que pretende evitar falsos verdes ha demostrado primero que el
puede producir un rojo por la causa exacta que afirma detectar**. Un guard
no esta falsificado porque algo de exit 1: debe compilar, alcanzar el guard
y fallar por la ley concreta.

## Deuda declarada, NO arreglada (parte del cierre)

H5, H6, H9-deuda quedan como **declaradas** en el receipt de cierre
(`docs/v2/07-uat/XCA2_FIX_VERIFY_RECEIPT.md` y siguientes). Esto es
intencional y NO es regresion:

```text
H5  guard permisivo en reconcile() sin else           declarado
H6  vocabulario del ledger v2 fuera de la progresion  declarado
H9  mutante del brief no esta como test separado     declarado
```

Justificacion: el brief de apply-fix 2 pidio a hibiscus NO arreglar esos 3
y declarar su estado en el receipt. El sistema sigue siendo defendible
mientras la deuda este visible y trazable.

## Tres invariantes irrenunciables (siguen vigentes)

```text
I1 el exit code del CLI NUNCA es evidencia de ejecucion.
I2 la presencia en el fuente NUNCA es evidencia de ejecucion.
I3 la expectativa del ledger NUNCA es evidencia de ejecucion.
```

Estas leyes estan en AGENTS.md del repo y se aplican a cualquier fase del
ciclo. debt-verify 2 debera cazarlas si aparecen violadas.

## Decision taxonomy para retomar la sesion

Cuando el operador vuelva a abrir sesion y pida "continua", el orquestador
debe seguir este orden estricto, validando cada fase antes de delegar la
siguiente:

```text
1. Validar envelope de blossom (M3) sobre los 4 commits de hibiscus.
   Si CONFIRMA, lanzar debt-verify 2 con glm-5-turbo.
   Si HALLAZGO, parar y consultar al operador.

2. Validar envelope de debt-verify 2.
   Si pasa sin BLOCK, lanzar release.
   Si BLOCK, parar y consultar al operador.

3. Release (M2.7): push + tag.
   PREGUNTAR al operador el nombre del tag antes de ejecutar.
   Sugerencia actual: xca2-audit-2026-09-17
   (cambia de "closed" a "audit" porque H5/H6/H9-deuda siguen declaradas
   y el tag no debe sobreafirmar el cierre).

4. Archive (M2.7): sincronizar deltas. No es destructivo.

5. Al cerrar el ciclo:
   - actualizar ledger con los nuevos IMPLEMENTED_UNCERTIFIED
   - actualizar TESTING-STATE.md si aplica
   - emitir release-receipt final
```

## Estado de los agentes swarm al cerrar

```text
session_kikazaru_1789670418210_04a6f84f46ae0a10  M2.7  apply       cerrado (ready/idle)
session_retriever_1789672843806_16a56f4c31516d96  M3   verify      cerrado (ready/idle)
session_cactus_1789675916677_0289744a3f51739f     glm-5 debt-verify 1  cerrado (ready/idle)
session_clover_1789677971555_da0ddf7fd2efd5b2     M2.7 apply-fix   cerrado (ready/idle)
session_sunflower_1789680871891_56fece85b6aa2ed1  M3   verify-fix  cerrado (ready/idle)
session_hibiscus_1789683390892_6c582b52fcfcce8f   M2.7 apply-fix 2 cerrado (ready/idle)
session_blossom_1789684205920_f86cd4e7d6d26c71    M3   verify-fix 2 EN CURSO (running/busy)
```

Los 6 cerrados pueden quedarse en `ready/idle` indefinidamente. No hace
falta matarlos: el daemon los limpia por antiguedad. **No lanzar el mismo
spawn dos veces** (mismo agente, mismo modelo) en una sesion nueva: el
nuevo swarm deberia partir de cero con `swarm spawn` desde esta misma.

## Configuracion del entorno (no se mueve al cambiar sesion)

```text
framework SDDK   1.169.71          /home/rubentxu/.local/share/sddk/framework/1.169.71
sddk adopt      status: complete   p-733fb505b5a6bd2d
modo SDDK       no-declarado       (aun no escrito en el indice)
index modo      ~/.local/share/sddk/mode-index   0 entradas activas
overlay jcode   ~/.jcode/prompt-overlay.md       L1..L8 + O1..O6 congeladas
scripts         ~/.jcode/bin/sddk-mode            get/set/clear
                ~/.jcode/bin/sddk-mode-selftest   14 ok, 0 fail
```

**Punto explicito**: el modo SDDK no se escribio durante la sesion.
La regla O6 dice "un cambio explicito rige en sesiones nuevas; NO se
fingir modificar el prompt ya capturado". El operador decidio al final
de la sesion seguir con A (no parar), pero no decidio a que nivel escribir
el modo. Eso queda como decision explicita para la proxima sesion.

```text
pendiente para el operador:
  - nivel del modo SDDK (project vs workspace)
  - nombre del tag del release (sugerencia actual: xca2-audit-2026-09-17)
  - resolucion del duplicado del ledger (H1 ya cerro, pero queda la copia
    v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml que NO es
    autoridad pero sigue existiendo; si quieres, se borra o se reubicica)
```

## Fuentes de verdad en el repo

```text
docs/v2/07-uat/CAMPAIGN_XCA_MANDATE.md              <-- mandato de la campana
docs/v2/07-uat/XCA2A_JOURNAL_CHARACTERIZATION.md    <-- B.2..B.6 fundamentos
docs/v2/07-uat/XCA2B_FIRST_RUNTIME_VERTICAL.md      <-- vertical runtime real
docs/v2/07-uat/XCA_CHECKPOINT_AUDITADO_2026_09_17.md<-- checkpoint auditable 1
docs/v2/07-uat/XCA2_VERIFY_RECEIPT.md               <-- envelope verify 1 (M3)
docs/v2/07-uat/XCA2_DEBT_VERIFY_RECEIPT.md          <-- envelope debt-verify 1 (glm-5)
docs/v2/07-uat/XCA2_FIX_RECEIPT.md                  <-- apply-fix 1 (clover)
docs/v2/07-uat/XCA2_FIX_VERIFY_RECEIPT.md           <-- verify-fix 1 (sunflower)
                                                     <-- FIXTURE: apply-fix 2 lo actualiza
docs/v2/status/step-certification.yaml              <-- autoridad unica del ledger
v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml  <-- copia renombrada (NO autoridad)
```

## Lo que NO se debe hacer al retomar

```text
- NO saltar validate-envelope entre fases; cada una tiene un envelope que
  el orquestador DEBE leer antes de delegar la siguiente.
- NO aceptar "PASS" o "CERTIFIED" sin que el estado este en el ledger
  canónico (docs/v2/status/step-certification.yaml). Cualquier otro sitio
  es decorativo o deprecado.
- NO pushear antes de release. La autorizacion del operador cubre
  release+archive, pero el push concreto va con el tag y eso se pregunta.
- NO escribir el modo SDDK sin que el operador decida a que nivel.
- NO tocar la copia v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml
  sin haber preguntado; es deuda declarada pero con archivo fisico.
```

## Forma de retomar

El operador deberia decir literalmente: "continua con la sesion" o
"ejecuta la siguiente fase". El orquestador:

```text
1. Lee este fichero.
2. Comprueba git status (debe ser limpio) y HEAD (74b309dc).
3. Llama swarm status sobre session_blossom para ver si termino.
4. Si termino, lee su envelope y sigue la cadena.
5. Si no termino, espera a que termine (subscribe a #blossom o polling).
```

Si en 30 minutos blossom no se mueve, hay que pedirle report y
diagnosticar.

---

Cerrado a las 22:30 hora local del 2026-09-17.
18 commits sin pushear. 1 agente en curso. 2 decisiones explicitas pendientes.
