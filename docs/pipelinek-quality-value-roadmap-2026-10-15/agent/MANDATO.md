# Mandato operativo para agente — PipelineK BQ1…BQ5

Trabaja sobre `Rubentxu/pipeline-kotlin`, `main` real, no sobre la foto fechada del paquete. Lee primero `AGENTS.md`, `docs/v2/05-roadmap/ROADMAP.md`, `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` y esta adenda; identifica decisiones aceptadas y cualquier código que ya resuelva el problema. Mantén SDDK si el repositorio lo exige. No agregues morralla al repo ni guardes los artefactos efímeros bajo `src/test` permanente.

## Objetivo funcional, no burocrático

Elige el **primer BQ incompleto**. Implementa una capacidad real observable por un consumidor, no una sucesión de 15 microgates y recibos. Cierra el bloque con un único incremento vertical, cambios Kotlin acotados y un prerelease real publicado tras integración en `main`. Una corrección de harness no justifica cambios masivos en el engine.

## Flujo repetible POR CADA bloque

1. **Re-grounding**: confrontar HEAD local/remoto, código real, dependencias Gradle, especificaciones aceptadas y hallazgos. Registrar alcance y no repetir trabajo ya hecho.
2. **Dominio**: describir los tipos algebraicos (casos cerrados exhaustivos), funciones puras, invariantes y su propietario, sin efectos I/O dentro del decider. Aprovechar ADTs ya existentes: no crear `Result2`, `Status2`, `RunOutcome2` ni un registry paralelo.
3. **Implementación**: una rebanada vertical y puertos bien delimitados, decisiones puras, shell con reloj/proceso/Git/JUnit/filesystem inyectados. Transformar datos solo en la frontera. `when` exhaustivo; desconocido durable ⇒ rechazo explícito.
4. **Desarrollo**: cambios pequeños ⇒ compilación y tests afectados (HF0/HF1) + fitness crítico. No ejecutar toda la matriz tras cada cambio.
5. **Pruebas temporales**: crear manifiesto de ciclo en workspace, UAT y AAT adversariales temporales, ejecutar antes de decidir equivalencia y recoger evidencia íntegra de caso positivo/negativo y mutación relevante; guardar en directorio temporal ignorado por Git.
6. **Promoción**: toda garantía que deba sobrevivir al ciclo se convierte a contrato/unit/property/fitness persistente o se demuestra como canario de `pipelinek-release-harness`. Nunca eliminar un test y su único oráculo a la vez.
7. **Retirada de temporales**: ejecutar `tooling/retirement_guard.py` con el manifiesto y evidencia; eliminar archivos UAT/E2E/AAT del ciclo y sus fixtures no compartidos; verificar no quedan referencias, ni `@Disabled` nuevo que oculte deuda. Los tests heredados requieren equivalencia y ADR aceptado.
8. **Cierre**: correr el gate de integración pertinente, después el gate de prerelease completo/externo exigido por nivel; integrar en `main` preservando la historia; obtener el SHA final limpio; construir/validar artefacto reproducible; publicar **RC inmutable** con tag alcanzable desde `main`; push commits/tags y enlace a GitHub Release. Actualizar receipt con SHA, digest, XML y decisión.

## STOP obligatorio

- Contrato nuevo sin owner, o `when` no exhaustivo sobre valor durable.
- Evidencia con pruebas skipped/0 tests o que no corresponde a HEAD/artefacto.
- UAT externalizada sin garantía equivalente demostrada (mismo defecto y misma frontera).
- Secreto en logs, alteración destructiva insegura, pérdida de bytes/eventos o side effects repetidos.
- `main` divergente sin reconciliación, SHA de tag fuera de `main`, ZIP regenerado después de verificar, fallo de gate declarado PASS.
- Ausencia de habilitación legal/técnica para tag/publicación: queda `BLOCKED`, jamás simular que se publicó.

## Entregable por hito

| Campo | Requisito |
|---|---|
| Valor | CLI/API/Step observable por consumidor y ruta de reproducción |
| Arquitectura | tabla tipo → módulo propietario → función pura → puerto de efectos |
| Tests que quedan | nombres de contratos permanentes + mutaciones que detectan |
| Temporales borrados | rutas + fingerprint de ciclo + oráculo promovido |
| UAT heredadas | cada ID `KEEP`, `MIGRATE_PENDING` o `DELETE_READY` con justificación |
| Gate | comando, SHA, tests/fails/skips, costo wall-time, resultado real |
| Entrega Git | PR/commits integrados, SHA `main`, tag y URL de RC, hashes de assets |
| Certificación externa | veredicto del harness o `BLOCKED_EXTERNAL` explícito |

No abrir BQ siguiente como sustituto de cerrar honestamente el actual. Si hay un impedimento externo: comunicarlo con evidencia y seguir solo trabajo independiente autorizado, sin falsificar DONE.
