# Próximos pasos — sugerencias para cerrar el ciclo

Tres opciones en orden de mi preferencia. Todas parten de HEAD actual
`57d5e52e` sobre `cycle/sh-var-scope-contract-s1` (9 commits limpios,
producción intacta).

---

## A. Merge + cierre de ciclo (recomendado)

El ciclo está terminado. F1 = CLOSED_GREEN, F2 = no abierto. El branch
ya cuenta con la trazabilidad completa (9 commits, 5 contract test
classes, 11 sub-cases GREEN, sha256s anclados). Lo correcto es
publicarlo a `main`.

### Comandos

```bash
# 1. Pre-merge dry-run
git checkout main
git pull origin main
git merge --no-ff cycle/sh-var-scope-contract-s1 -m "Merge sh-var-scope-contract F1 (9 commits, contract test surface, no production changes)"

# 2. Push
git push origin main

# 3. Tag del release (opcional, solo si quieres fijar este contrato
#    como anchor para F2 cuando se abra)
git tag -a sh-var-scope-contract-f1 -m "SH-VAR-SCOPE-CONTRACT F1 — measured contract for core.sh variable scope; F2 (offset map) gated by UX judgement" 57d5e52e
git push origin sh-var-scope-contract-f1

# 4. Archive cycle
#    (delegación sddk-archive: sync delta specs, preserve artifacts)
```

### Lo que se gana

- `main` queda con un contrato verificable para `core.sh`.
- Cualquier persona que lea `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md`
  tiene una referencia única y probada.
- F2 (offset map en `mapDiagnostic`) queda con su F2_TRIGGER_DATA
  capturado y listo para abrirse cuando el operador decida.

### Coste

- 1 merge commit + (opcional) 1 tag.
- 0 producción modificada.

---

## B. Merge + cerrar F2 en el mismo ciclo (NO recomendado ahora)

Abrir F2 —añadir un offset map en `Kotlin24ScriptingHost.mapDiagnostic`
para corregir los +5 chars de drift en diagnósticos cuando hay
`withCredentials` activos— requeriría:

1. Un nuevo OpenSpec change `sh-var-scope-contract-f2-offset-map`.
2. Decisión de UX del operador (¿es +5 chars fricción perceptible?).
3. Modificación de `Kotlin24ScriptingHost.mapDiagnostic` (producción).
4. L5 (`./gradlew -p v2 check`) — porque sí tocamos producción.

No hay trigger para F2: el dato capturado (Gap #5) está a la espera de
una decisión UX que solo tú puedes tomar. Mezclar F1 + F2 aquí
rompería la disciplina del cycle (un cycle, un exit criterion).

---

## C. NO mergear y abrir más gaps antes de cerrar (NO recomendado)

Quedan huecos no cubiertos por F1 que un próximo ciclo podría atacar:

- **Gap #7 (potencial).** El contrato actual no aborda el caso
  `withEnv` anidado dentro de `withCredentials` (¿orden de
  protección? ¿qué se gana/pierde?).
- **Gap #8 (potencial).** Tampoco aborda `sh` con `set -u` y cómo
  interactúa con `$VAR` no definidas.
- **Gap #9 (potencial).** El contrato no menciona explícitamente
  el caso `cmd("$VAR")` — ¿hay diferencias con `sh("$VAR")`?

Cada uno de estos sería un nuevo cycle bajo su propio GO. Intentarlos
ahora mismo diluiría F1 y rompería el "one exit criterion per cycle".

---

## Mi sugerencia: **A**

- El trabajo del cycle está cerrado.
- El contrato se sostiene byte-level.
- La trazabilidad está completa (9 commits, sha256s, receipt).
- F2 está con su trigger data; la decisión UX es legítima y no debe
  automatizarse.

Si me das luz verde, ejecuto los pasos de A:

```text
1. Checkout main
2. Pull origin main
3. Merge --no-ff
4. Push
5. Tag sh-var-scope-contract-f1
6. Archive via sddk-archive
```

Dime "merge" o equivalente y procedo.
