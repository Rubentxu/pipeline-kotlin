# UAT propuesta — Release y canales SDKMAN / mise / asdf

**Estado:** PLANNED / NOT_RUN. Complementa, NO sustituye, `CERTIFICATION_PROTOCOL.md`, `PRODUCTION_READY_UAT_MATRIX.md` y `UAT_DISTRIBUTION_SDKMAN.md`. **Fase:** RP-6 después de RP-5, WU-RP-060..065. No marcar PASS sin runner, commit, asset, SHA y log/resultado de instalación real.

| ID | Caso / estímulo real | Oráculo de aceptación | WU |
|---|---|---|---|
| REL-001 | Instalar un runner `pipelinek` estable anterior desde ZIP público en entorno limpio | versión y digest fijados; runtime ejecuta script de release del tag sin recompilar el runner | 060 |
| REL-002 | Runner anterior no entiende el DSL de `ci/release.pipeline.kts` | rechazo antes de efectos; runner alternativo certificado o DSL de publicador reducido, sin fallback silencioso al binario candidato | 060 |
| REL-003 | Entradas iguales (tag/commit/artefacto/checksums) | plan e identidad de release reproducibles; `runnerVersion != targetVersion` distinguibles | 060 |
| REL-004 | Tag de formato inválido/movido, commit diferente al certificado | STOP sin release ni operaciones con credenciales | 061 |
| REL-005 | Tag válido con un test de producto o fallo intencional en rojo | GitHub Release NO se crea; external gate conserva evidencia de error | 061 |
| REL-006 | Build A/B desde mismo SHA y toolchain; instalar ZIP candidato | ZIP byte-idéntico, SHA-256/SBOM/manifest coherentes, fixtures validate/run success/failure observables | 061 |
| REL-007 | Repetir workflow contra un asset GitHub ya publicado con digest diferente | STOP; no sustituir el asset ni mover tag ni reconstruir silenciosamente | 061 |
| REL-008 | `pipeline.kts` utiliza `whenCondition` no certificado para publicar | autorización y secretos bloqueados externamente cuando gates fallan; cero publicación | 061 |
| REL-009 | mise limpio instala versión fijada vía backend GitHub del ZIP concreto | `pipelinek version/doctor/validate/run` reales PASS; root/permiso ejecutable correctos | 062 |
| REL-010 | mise encuentra ZIP/SBOM/sidecar en release | selecciona exclusivamente el ZIP solicitado; jamás interpreta SBOM/sidecar como binario | 062 |
| REL-011 | mise recibe versión desconocida, ZIP corrupto o checksum desigual | instalación rechazada, sin ejecutar binario no verificado | 062 |
| REL-012 | asdf plugin por URL en entorno limpio, `list-all` y `install` | instalador detecta release estable, verifica checksum, bin ejecutable y script Kotlin real PASS | 063 |
| REL-013 | asdf con ZIP 404, path traversal, descarga truncada o digest incorrecto | falla sin dejar instalación aparentemente válida | 063 |
| REL-014 | Nueva release sin nuevo commit del plugin asdf | versión se descubre e instala; evidencia de compatibilidad con versión asdf soportada | 063 |
| REL-015 | Sin candidato SDKMAN o sin credenciales | `BLOCKED_EXTERNAL` registrado, no `SDKMAN_READY`; GH/mise/asdf continúan | 064 |
| REL-016 | Fixtures SDKMAN del DSL original `steps { echo(...) }` | UAT corregida utiliza script compatible, ejecutada contra ZIP canónico local antes del canal público | 064 |
| REL-017 | Inicializar `sdk` desde shell hija en un runner nuevo | `sdk install pipelinek VERSION` REAL contra catálogo oficial PASS, sin depender del entorno padre | 064 |
| REL-018 | Vendor API devuelve éxito HTTP pero versión nunca visible | fallo vinculante tras sondeo acotado; sin mensaje falso de publicación ni default | 064 |
| REL-019 | SDKMAN publicación aceptada, instalación oficial falla | NO se llama a `PUT /default`; `SDKMAN_READY` sigue false/no certificado | 064 |
| REL-020 | SDKMAN instalación oficial y validate/run PASS, default PUT | consulta del catálogo confirma la versión exacta; receipts atan asset SHA + runner + commit | 064 |
| REL-021 | Release publicada, mise PASS, asdf FAIL, SDKMAN bloqueado externamente | estados independientes; reintento asdf no reconstruye ZIP ni repite canales ya verificados | 065 |
| REL-022 | Dos ejecuciones concurrentes del mismo tag/canal | no duplican publicación/default ni sustituyen el ZIP; resultado y recibo coherentes | 065 |
| REL-023 | Ejecutar distro workflow desde fork/PR o tag no autorizado | no obtiene secretos ni publica en SDKMAN/GitHub | 065 |
| REL-024 | Candidato publicado con ZIP diferente al manifest/certification tuple | STOP antes de distribuidores; hashes y URI verificables desde fuera de `pipelinek` | 065 |

## Evidencias mínimas por canal

- **GitHub:** tag-object/commit, runner version+SHA256, CI run por SHA, ZIP nombre/digest, release URL, SBOM/manifest y receipt de PRODUCT-GATE.
- **mise:** version/config y backend verificados, stdout/stderr redactados, exit codes, `pipelinek version/doctor/validate/run`, digest del ZIP descargado.
- **asdf:** repo/commit de plugin, versión del gestor, comandos `list-all`/download/install, SHA del ZIP, rutas seguras, run real.
- **SDKMAN:** onboarding/credenciales como estado sin material secreto, respuesta Vendor API redactada, presencia en API oficial, `sdk install` real, default posterior a UAT y consulta posterior.
- **Fallo parcial:** resultado por canal con motivo/fecha/artefacto y operación de reintento limitada a los pendientes.

Un PASSED del mirror local SDKMAN NO satisface REL-017..020. Una versión instalada directamente desde ZIP NO satisface REL-009 o REL-012. Un workflow no ejecutado se marca NOT_RUN.
