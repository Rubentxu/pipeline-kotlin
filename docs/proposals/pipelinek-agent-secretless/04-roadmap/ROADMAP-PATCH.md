# Fragmento propuesto para `docs/v2/05-roadmap/ROADMAP.md`

> **Uso:** integrar manualmente dentro de RP-7 tras reconciliar el estado SDDK actual. No reemplazar el roadmap ni copiar IDs provisionales de ADR/WU sin reservarlos.

## RP-7.x — Agent-first credential capabilities and secretless execution

**Objetivo:** convertir el runtime local en una fachada segura para operaciones humanas/agénticas que necesitan identidad, manteniendo una única autoridad de ejecución durable. Profiles, credential capabilities, tool projections y output policy deben reutilizar `StepRegistry`, capabilities, coordinator y `core.sh`; no se introduce un segundo executor/journal para procesos.

**Leyes de compatibilidad:**

1. pipelines existentes que no usen las nuevas APIs conservan payload/fingerprint/replay/outcome de `core.sh`;
2. `withCredentials(CredentialsBinding...)` conserva semántica Jenkins-compatible;
3. la nueva forma profile/capability usa un RegistryBlock versionado separado;
4. redaction obligatoria precede cualquier filtro/render extensible;
5. integrations declaran `STRONG_SECRETLESS | SHORT_LIVED_EXPOSURE | ISOLATED_PROCESS_EXPOSURE | RAW_PROCESS_EXPOSURE | UNSUPPORTED` y admission falla si no alcanza `minimumPosture`;
6. Agent Secretless Vault se integra por sesiones/capabilities, no mediante una API genérica de lectura de secretos;
7. config sensible de herramientas se proyecta en overlays efímeros owner-only; no se modifican dotfiles persistentes por defecto;
8. plugins de Steps ordinarios mantienen el seam `StepDefinitionContributor`; extensiones de terceros que posean secretos usan frontera out-of-process/versionada salvo built-in auditado.

**Secuencia sugerida:** ASX-0 compatibility fortress → ASX-1 credential capability kernel/profiles → ASX-2 credential scope DSL → ASX-3 providers (local/Secret Service/ASV) → ASX-4 typed tool projections → ASX-5 inline command/step + output policy → ASX-6 ephemeral flow/MCP → ASX-7 installed/adversarial certification.

**Inline command:** la primera versión argv-friendly baja a `core.sh` mediante encoder canónico; `core.exec`/un argv executor propio queda fuera de alcance. Una futura extracción `DurableProcessRuntime` requiere un programa independiente con characterization y equivalence gates de `sh`.

**Gate de salida:** UAT agent-secretless completa sobre distribución instalada, compatibilidad legacy verde, canarios de fuga/admission falsificables, cleanup en success/failure/timeout/cancel, mínimo dos repos dogfooding y cero claims de posture superiores a la exposición realmente observada.

