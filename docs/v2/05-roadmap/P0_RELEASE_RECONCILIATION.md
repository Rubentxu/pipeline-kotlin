# P0 — Reconciliación de release: hallazgos medidos (2026-10-08)

Recopilado con Git y `gh`. Nada de este documento reescribe historia ni tags.

## P0.1 Estado observado

| Dato | Valor observado |
|---|---|
| Rama activa | `s6-plugin-sdk` |
| HEAD | `52994e5e96f1cc786b6b5f9ec2aa12398896c09d` |
| `origin/main` | `b66bf7c796db28dabf3e13df9784844e7a59aeda` |
| Divergencia | `origin/main...HEAD` = `0 77` — main no tiene commits que falten aquí, la rama va **77 por delante** |
| `rootProject.version` | `0.47.0` (`v2/build.gradle.kts:75`) |
| PR #99 | `OPEN`, base `main`, head `fix/reproducible-directive-plugin-jar`, `MERGEABLE`, `mergeStateStatus: CLEAN` |

## P0.2 La desviación de `v0.47.0` — medida, con su consecuencia

```text
v0.47.0 -> 3ec99a4cb9059b1d7c7fb5902f90ac5dc1b697c4
           "fix(example-plugin): make the directive plugin jar byte-reproducible"
```

Ese commit:

- **NO** es ancestro de `origin/main`;
- vive únicamente en `origin/fix/reproducible-directive-plugin-jar`;
- `origin/main..3ec99a4c` contiene exactamente **1** commit: el propio.

**Interpretación contra ADR-0099 D4** ("el tag inmutable de candidata DEBE resolver a un commit
alcanzable desde `main`"): el tag `v0.47.0` existe y es inmutable, pero **no cumple D4**. La
candidata `0.47.0` está construida y etiquetada sobre una línea que nunca llegó a `main`.

**Consecuencia para este plan, y es la parte que importa:**

1. `ProductVersion = 0.47.0` es hoy la versión del build, pero **no hay candidata `0.47.0`
   integrada en `main`**. No puede citarse como precedente de integración.
2. P3 construye `0.48.0-rc1` desde esta rama, no desde `0.47.0`. El precedente correcto de
   integración es el cierre del ciclo S5 (`b66bf7c7`), que sí está en `main`.
3. **`v0.47.0` no se corrige.** El tag es inmutable por ADR-0099 y por la política de
   candidatas inmutables. Corregirlo exigiría mover un tag publicado, que está prohibido. Lo que
   sí se puede, y es lo que se hará, es **mergear PR #99** para que el commit alcanza `main`; el
   tag seguiría apuntando al commit original, que **sí** sería alcanzable desde `main` después
   del merge, porque un merge conserva los commits.
4. Por tanto la desviación **se resuelve sola** al integrar PR #99, sin reescribir nada. El
   merge está bloqueado por autorización operativa (ver P0.5).

**Lo que NO se hace, explícitamente:** no se mueve `v0.47.0`, no se reescribe `main`, no se hace
squash ni rebase de historia publicada.

## P0.3 La contradicción documental — resuelta, y era más pequeña de lo que parecía

`ROADMAP.md` §13.5 decía:

```text
- No publicar ni promocionar releases desde este repositorio: la autoridad es el harness externo.
```

Eso contradecía a ADR-0099 D3/D4/D6 (que asignan aquí integración, tag y prerelease) y al
objetivo del propio plan. **ADR-0099 y ADR-0105 nunca se contradijeron entre sí**: reparten
objetos distintos. El error estaba solo en la redacción genérica de un roadmap subordinado.

La tabla de responsabilidades y los cuatro estados (`CANDIDATE_PUBLISHED` / `CERTIFIED` /
`STABLE_PROMOTED` / `BLOCKED_EXTERNAL`) están ahora en `ROADMAP.md` §13.5.

## P0.4 Frontera de release `0.48.0-rc1` añadida antes de S7

`0.48.0-rc1` se corta al cierre funcional de S6, antes del desarrollo completo de S7. El
prerelease declara explícitamente que está pendiente de certificación externa y **nunca** afirma
`PRODUCT_GATE_GO`.

## P0.5 Bloqueos externos registrados

| Bloqueo | Estado medido | Quién lo resuelve |
|---|---|---|
| Check de admisión G10 | `.github/workflows/` vacío; `754ddda0` retiró los 4 workflows tras 60 runs cancelados | harness, por ADR-0105 D3 |
| Push / merge a `main` | sin autorización operativa | persona |
| Tag y prerelease GitHub | sin autorización operativa | persona |
| Credenciales Maven remoto | no inyectadas | persona |
| Acceso a repositorio Nexus/GitHub Packages | URL no suministrada | persona |
| Veredicto de certificación | el harness no publica check-runs ni commit statuses | harness |

Consecuencia de gate: **`PRODUCT-GATE = BLOCKED_EXTERNAL`** para todo SHA. `STEP-CERT` es lo
único que este repositorio puede emitir, y no se escribe como `PRODUCT-GATE`.

## P0 Gate — estado

| Criterio | Estado |
|---|---|
| `main` es la autoridad de la candidata según ADR-0099 | OBSERVED: tabla de responsabilidades escrita, desviación medida |
| Se distingue CANDIDATE_PUBLISHED / CERTIFIED / STABLE_PROMOTED sin ambigüedad | OBSERVED: tabla en §13.5 |
| Ningún resultado local simula veredicto del harness | OBSERVED: `BLOCKED_EXTERNAL` declarado |
| El plan permite continuar S7 en paralelo | OBSERVED: P1 no depende del harness |
| `v0.47.0` resuelto sin reescribir historia | **PENDING de autorización**: se resuelve al mergear PR #99 |