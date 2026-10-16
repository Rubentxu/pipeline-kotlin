# Política de pruebas: pruebas efímeras, garantías permanentes

**Estado:** PROPUESTA. Complementa `TEST_STRATEGY.md` y `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`; no rebaja ninguna matriz normativa.

## Tres objetos que no se confunden

1. **Especificación/ley:** afirmación inmutable con `ContractId`, `capability`, caso positivo/negativo y frontera de fidelidad. Vive en versión controlada.
2. **Prueba ejecutable:** implementación concreta de la ley. Puede ser persistente (unit/property/fitness) o de sesión (UAT/E2E/AAT). Un objeto test no es autoridad para cambiar lo prometido.
3. **Evidencia:** observación de una ejecución contra `commit`, `tree`, `CandidateId`, `artifact sha256`, `JDK/OS`, suite y método. Inmutable por ciclo; un resultado anterior no sirve como evidencia de HEAD nuevo sin regla explícita de impacto.

**AAT = Agent Acceptance Test:** ensayo adversarial del *trabajo del agente* (por ejemplo, que su plan rechaza una selección incompleta, que el script de poda no borra sin prueba). **UAT = User Acceptance Test:** ejemplo de comportamiento útil para el consumidor. **E2E = End-to-End:** recorre frontera real completa. Son etiquetas de intención, no niveles de fidelidad.

## Tabla de destino

| Clase | Durante feature | Integración en `main` | Candidato de release | Al terminar feature |
|---|---|---|---|---|
| Ley pura / ADT / codec | tests locales afectados | siempre si impacta | parte del check | **PERSISTE** |
| Fitness arquitectónico | si cambia frontera | obligatorio cuando impacta | parte del check | **PERSISTE** |
| Harness/plan guard | rápido y negativo | obligatorio | obligatorio si cambió harness | **PERSISTE** |
| UAT de feature exploratoria | solo para ciclo | nunca como nueva carga estable | evidencia antes del cierre | **SE BORRA** tras promoción |
| AAT de tarea adversarial | mutación / fail-closed | se sustituye por fitness permanente | evidencia de ciclo | **SE BORRA** tras promoción |
| E2E de la feature | real si frontera exige HF2..HF6 | según impacto | certifier externo | **SE BORRA** si hay garantía equivalente |
| UAT heredada instalada | aún en repo (deuda de migración) | plan vigente hasta reemplazo | harness externo al migrarse | NO borrar sin equivalencia + autorización |

## Excepciones y no negociables

- Que una clase tenga `@Tag("release-scale")` no significa que pueda eliminarse. Que esté `@Disabled` no cuenta como cobertura.
- Un smoke permanente de contrato puede ejecutar frontera externa en su dueño adecuado; no cambiar nombres para esconder UAT/E2E.
- Una prueba temporal **jamás** se promociona por copiar su cuerpo de forma mecánica. Trasladar la **propiedad** al nivel más bajo que la puede demostrar y usar la frontera superior solo donde no es simulable.
- La retirada se bloquea si mutaciones del defecto histórico sobreviven, si no se sabe qué valida la prueba, o si el equivalente corre un binario/commit distinto.
- Retirada de fuentes viejas no implica eliminar los escenarios `.pipeline.kts` normativos del corpus. Los ejemplos de producto versionados sobreviven (ADR-0071).

## Decisión de retirada

`CandidateForRetirement` → `EquivalencePending` → `ProofPresent` → `DeleteReady` → `Deleted`, o `KeepProtected`/`Blocked`. `ProofPresent` requiere `contractId`, `replacementLocation`, `sameFailureFingerprint`, `observedArtifact`, `verdict`, `sourceSHA`; ninguna transición es automática por nombre.

## No vacuidad de evidencia

- JUnit: `tests > 0`, `failures=0`, `errors=0`, `skipped < tests`, archivo de esta ejecución y clase/paquete completos.
- No `@Disabled` como PASS, no `assumeTrue(false)` como éxito, no XML del run anterior.
- Una mutación debe provocar fallo del contrato correcto: si falla por timeout global o fixture inexistente, evidencia inválida.
- Cambios de firma pública, wire durable, secretos o replay demandan matriz/admisión completa; no sustituir por una muestra unit.
