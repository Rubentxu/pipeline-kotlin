# WU-RP-041 — Aislamiento del runner local: modelo de amenazas y perfiles de confianza

**Estado:** OPEN (S1 CLOSED, S2-S4 en curso)
**Criterio (ROADMAP §6):** probar aislamiento del runner local (filesystem, proceso,
límites de CPU/memoria/tiempo, egress y secretos); declarar claramente el modelo de
amenazas y diferenciar ejecución confiable de multi-tenant. Cualquier capacidad
best-effort queda FUERA del perfil de ejecución no confiable.
**Base:** main @ 7c54ddc1 (S1 incluido). ADR de referencia: ADR-0016 (M5/M9),
ADR-0048 (sandbox LOCAL), ADR-0047 (FAILED_TIMEOUT), ADR-0075/0076 (control rows,
cancelación).

## 1. Modelo de amenazas (declaración)

Amenazado: el host del runner (filesystem, procesos, secretos del entorno, red).
Agente amenazante: el SCRIPT del pipeline (`core.sh` y cualquier Step con
`EXECUTES_SUBPROCESS`), no el motor ni los plugins instalados (esos son código
confiable de la distribución). El pipeline kts compilado se considera semi-confiable
(código del usuario): errores de programa fallan tipado; código malicioso del script
NO está contenido salvo donde se indique lo contrario.

```text
Vector                        | Superficie                          | Contención actual
------------------------------|-------------------------------------|--------------------------------
Fuga de escritura fs          | core.sh, writeFile, archive         | LOCAL: cwd=workspace, denegación
                              |                                     | NO-POR-TIPOS: report best-effort
Escalada de proceso           | subprocesos hijos, kill/resume      | process-tree kill (cookie scan),
                              |                                     | watchdog flag-then-kill, setsid
Fuga de secretos              | SecretHandle env, transcript        | typed channel, redacción chunk-
                              |                                     | boundary-safe en at-rest y consola
Agotamiento de recursos       | CPU/RAM del host                    | NO CONTENIDO en L3 (M5/M9) ->
                              |                                     | FUERA del perfil no confiable
Tiempo excesivo               | child sin terminar                  | CONTENIDO: timeoutMs watchdog,
                              |                                     | FAILED_TIMEOUT determinista
Egress de red                 | red del host desde el script        | NO CONTENIDO en L3 ->
                              |                                     | FUERA del perfil no confiable
Lectura de env del host       | pb.environment() heredado           | LOCAL: deny-list + PATH normalise
```

## 2. Perfiles de confianza (ADT nuevo: `RunnerTrustProfile`)

Diferenciación tipada entre lo que el runner puede garantizar y lo que no. Un perfil
NO confiable que dependiera de una capacidad best-effort sería un estado ilegal:
el ADT lo hace irrepresentable (fail-closed en construcción, ley 8 de typed design).

```text
TRUSTED_SINGLE_TENANT   : runner propio, el pipeline y sus scripts son del mismo
                          dueño que el host. Perfiles válidos: NONE, LOCAL.
                          Capacidades garantizadas: proceso (kill/watchdog),
                          tiempo (timeout), secretos (typed channel + redacción).
MULTI_TENANT_CONSTRAINED: scripts de terceros. Requiere OS-level (jail fs,
                          límites CPU/mem, egress block). NO INSTANCIABLE en L3:
                          su constructor lanza SandboxProfileUnsupportedException
                          citando ADR-0016 M5/M9, igual que SandboxProfile.OS().
```

Cualquier capacidad marcada BEST_EFFORT en la tabla §1 (fs jail, CPU/mem, egress)
queda EXCLUIDA de `MULTI_TENANT_CONSTRAINED` hasta M5/M9: hoy no existe perfil
no confiable ejecutable, y así se declara.

## 3. Evidencia por superficie (existente + nueva en esta WU)

| Superficie | Evidencia existente | Nueva en WU-RP-041 |
|---|---|---|
| Proceso/kill | DurableShellExecutor watchdog (flag-then-kill, cookie scan), SB-S-007 | - |
| Tiempo | UatTimeoutBlockDurableTest WL-T1..T3, FAILED_TIMEOUT | S1: parity en cuerpos externos (7c54ddc1) |
| Filesystem | SB-S-001/002/008 (cwd workspace, report best-effort, branches aislados) | S3: pin de ley "escape reportado ≠ contenido" |
| Env/secretos | SB-S-003/004/005/009, Lpr011/011r2 redacción consola/at-rest | S3: pin ADT trust profile |
| CPU/mem/egress | ninguno (no contenido) | S2: el ADT lo documenta como FUERA de perfil |
| Resume/re-play de perfil | SB-S-010, SB-S-010 resume none→local | - |

## 4. Fuera de alcance declarado

- OS-level sandbox (jail, seccomp, cgroups): M5/M9 (ADR-0016), no se implementa aquí.
- SSO/egress proxy: sin contenido en L3; fuera del perfil no confiable.
- Cambios de contrato público o nuevo core Step (NO_GO vigente).
