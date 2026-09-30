# Gate Teeth — canarios obligatorios

## GT-ASX-01 — Legacy `sh` fingerprint

**Ley:** nuevas features no cambian `core.sh` legacy.  
**Mutación:** alterar encoder/payload/fingerprint.  
**RED esperado:** compatibility fixture falla.

## GT-ASX-02 — Legacy `withCredentials`

**Ley:** binding Jenkins sigue proyectando env como antes.  
**Mutación:** enrutar legacy binding por planner auto.  
**RED:** exact legacy scenario cambia y falla.

## GT-ASX-03 — Minimum posture

**Ley:** no degradar silenciosamente.  
**Mutación:** hacer que planner acepte RAW para request STRONG.  
**RED:** admission test.

## GT-ASX-04 — ASV no-get-secret

**Ley:** adapter agent-facing no ofrece raw secret retrieval.  
**Mutación:** añadir método/protocolo prohibido.  
**RED:** structural/API fitness scan.

## GT-ASX-05 — Redaction before filters

**Mutación:** mover filter antes de `RedactingEventSink`/sanitizer.  
**RED:** canary visible al filtro.

## GT-ASX-06 — Overlay cleanup

**Mutación:** omitir delete en timeout.  
**RED:** residual file assertion.

## GT-ASX-07 — Registry authority

**Ley:** inline Step no usa handler privilegiado.  
**Mutación:** bypass directo para un Step conocido.  
**RED:** vendor Step parity/fitness test.

## GT-ASX-08 — Single execution spine

**Ley:** command V1 termina en `core.sh`/ShellOperations canonical path.  
**Mutación:** introducir ProcessBuilder directo en CLI module.  
**RED:** architecture fitness source/dependency rule.

## GT-ASX-09 — No repo litter

**Mutación:** default state root = project cwd.  
**RED:** clean working-tree UAT detecta archivos.

## GT-ASX-10 — Profile secrets forbidden

**Mutación:** permitir campo `password/token/value` serializable dentro de profile schema.  
**RED:** schema/structural test.

