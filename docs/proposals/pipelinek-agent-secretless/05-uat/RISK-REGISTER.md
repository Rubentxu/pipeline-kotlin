# Risk register

| Riesgo | Severidad | Mitigación |
|---|---:|---|
| Divergir `command` y `sh` | crítica | single execution spine + fitness |
| Cambiar fingerprints legacy | crítica | characterization + GT-ASX-01 |
| Romper `withCredentials` Jenkins | alta | payload congelado + nuevo IR |
| Claim secretless falso | crítica | explicit posture + UAT proceso/env |
| ASV convertido en raw secret provider | crítica | no-get-secret structural gate |
| Fuga en filtros | crítica | redaction before extension |
| Overlay residual | alta | lifecycle + timeout/cancel UAT |
| Plugin tercero roba secreto | crítica | sensitive extensions out-of-process |
| Tool auto-detection incorrecta | media | explicit override + fail-loud |
| Profile inheritance amplía permisos | alta | conservative merge laws |
| Quoting argv shell-backed | alta | property tests + POSIX scope inicial |
| Cross-platform inconsistency | media | platform certification separated |
| Same-UID ASV memory exposure | alta/known limitation | posture/docs + dedicated UID hardening when available |
| Exact redaction bypass por transform | alta | no usar redaction como confinement |
| Cache de Gradle/Maven degradado por overlays | media | config/cache separation |
| Estado creado en repos | media | XDG roots + UAT clean tree |
| Roadmap paralelo/document drift | alta | integrate as RP-7 subsection only |

