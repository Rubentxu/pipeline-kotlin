---
id: ADR-PK-DF-001
title: "Transición de los assets de aplicación y SDK sin dos autoridades de publicación"
status: proposed
date: 2026-10-16
supersedes: none
decision_owner: "product owner — pendiente"
related:
  - AGENTS.md
  - docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md
  - docs/v2/05-roadmap/P2_MAVEN_PUBLICATION.md
  - docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md
  - docs/v2/04-adrs/ADR-PK-QV-004-release-after-ephemeral-cleanup.md
---

# ADR-PK-DF-001 — Una autoridad sobre el conjunto de assets público

## Problema observado en código

En `main@5de40c45`, `AGENTS.md` §8 ordena exactamente cinco assets (ZIP runtime, SBOM, SDK ZIP, BOM JSON, SHA256SUMS); `scripts/release-pipelinek.sh` sube otros cinco (ZIP, SBOM, HANDOFF.md, git-head.txt, SHA256SUMS) y solo detecta *faltantes* de su propia lista. PR #142 pretende publicar tres assets de aplicación y trasladar librerías SDK a repositorio Maven. Ambas direcciones pueden ser válidas como **modelos de distribución distintos**, pero no pueden regir simultáneamente sin decisión/contrato actualizado. GitHub crea enlaces Source code automáticos; no se consideran assets aportados por PipelineK ni una vía de instalación.

## Opciones

- **A (compatible hoy):** mantener exactamente cinco assets §8. Arreglar el publisher y validar multiset remoto; SDK ZIP y BOM JSON son consumidores contractuales de la release aunque el Maven remoto siga bloqueado.
- **B (preferida *después de aceptación*, sujeta a consumidores):** separar canal APP = runtime ZIP + SBOM + SHA256SUMS y canal SDK = cinco coordenadas JAR+POM+.module + `pipeline-sdk-bom` desde un repositorio Maven remoto autorizado; eliminar SDK ZIP/BOM JSON de la release APP solo tras verificar que instalador, SDKMAN, certifier y autores de plugins no dependen de ellos. Fuente única de asset-set para cada canal. El manifest puede seguir registrando ambos canales, sin convertir el JSON interno en asset obligatorio.
- **C (descartada):** publicación oportunista 3 o 5 según qué tarea acabó. Permite releases incompletas con validación de hashes engañosa.

## Propuesta de decisión pendiente del propietario

1. **AHORA: Opción A** como ley ejecutable hasta que el propietario acepte B y modifique `AGENTS.md` §8, `DISTRIBUTION_RELEASE_SPEC.md`, publicador, instalador, certifier y consumidores.
2. `CandidateManifest` (o modelo ya existente) es única autoridad del **set esperado**, generada por build. El publicador ni las pruebas mantienen una lista divergente; test de mutación `missing/extra/duplicate` para set remoto exacto.
3. La biblioteca SDK es un producto diferente de la app; `SDK_PUBLISHED` requiere resolución remota y compilación consumidor independiente. Maven local o un ZIP generado no satisfacen ese estado.
4. No mover ni reescribir tags/release existentes. Publicar una RC nueva del protocolo aceptado, construir y probar exactos bytes; si el destino remoto no existe, `BLOCKED_EXTERNAL` para SDK. Nunca atribuir `CERTIFIED` al productor.
5. Los **assets** de GitHub Releases no incluyen source ZIP voluntarios, reportes, HANDOFF.md o git-head.txt. Estos últimos pueden ser notas o evidencias del certifier.

## Condiciones de aceptación de Opción B (antes de aplicarla)

- Decisión registrada por propietario; ADR cambia a `accepted` con fecha y scope exacto.
- Consumidor real instala ZIP APP descargado y verifica `version --json`, SHA, root y checksum.
- Maven remoto resuelve las seis coordenadas (cinco librerías + BOM) con metadata/ABI, sin `sdk-repo` ni `includeBuild`, desde checkout limpio.
- Certifier/instalador/SDKMAN confirman no depender de SDK ZIP o BOM JSON en APP.
- Prueba remota descubre missing, extra y duplicados y rechaza release antes de afirmar éxito.
- Actualización atómica de `AGENTS.md` §8 y specs para evitar normativa contradictoria.

## Consecuencias

Positivas: binarios reales diferenciados, velocidad de releases, sin residuos en assets, una sola autoridad sobre la integridad y completitud. Coste: migration pactada con consumidores y repositorio Maven autorizado; la rama de PR #142 no debe ser merged mientras mantenga discrepancia normativa.

**Esta ADR es una propuesta, no un mandato de cambiar automáticamente la regla aceptada §8.** Si el propietario elige A definitiva, cerrar ADR como `rejected` sin cambiar consumidores.
