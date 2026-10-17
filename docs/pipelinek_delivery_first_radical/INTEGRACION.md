# Integrar el ZIP en workspace sin pisar autoridad normativa

1. Extraer ZIP **fuera del repositorio** (por ejemplo `/tmp/pipelinek-radical-pack`) y leer `README.md`, `INVENTARIO_Y_CORTES.md`, `PLAN_DE_PODA_EJECUTABLE.md`.
2. Recuperar estado SDDK y `git fetch origin main`, comparar HEAD actual con `1c21f303...`; no asumir igual. Ejecutar el inspector **desde fuera del Git**:

```bash
python3 /tmp/pipelinek-radical-pack/tools/test_portfolio.py --repo /ruta/pipeline-kotlin --base 0253d3d670602273915387ef5aac0f4b14d74556 --out /tmp/pipelinek-candidatos.tsv
```

3. Copiar **solo** `overlay/docs/v2/{03-specifications,04-adrs,05-roadmap}/...` con `cp -n`/`rsync --ignore-existing` y revisar `git diff`; estos nombres son nuevos respecto a HEAD consultado. No pegar `tools/` ni `PROMPT_AGENTE.md` en `src/main` o `docs/` del proyecto. No añadir otra skill.
4. `ADR-PK-DF-002` queda `status: proposed`. Presentar al operador las enmiendas explícitas de `AGENTS.md`, `ROADMAP.md`, `RESPONSIBILITY_MIGRATION_ROADMAP.md` y specs que implican relajar `check` por merge o borrar UAT heredadas. **No cambiar una ADR aceptada sin autorización.**
5. Una vez ratificadas las enmiendas, integrar roadmap con **un solo enlace** desde `ROADMAP.md` §13, manteniendo único backlog/SDDK. Para cada hito, ejecutor usa su WorkUnit del plan, actualiza la autoridad existente con código real; nada de segundo estado.
6. Iniciar H0/R0 con poda de tests históricos deshabilitados y H0/R1 dependencia `:test-harness`; no ampliar harness para retirar el harness. H1 corrige OBS y entrega feature observable. Cada bloque productivo culmina en integración/push/tag RC según permisos y `AGENTS.md` vigentes; sin falsear `CERTIFIED`.
7. Al cerrar cada sesión borrar UAT/E2E/AAT temporales y material `/tmp` propio, conservar únicamente una mínima evidencia en SDDK/PR. Confirmar `git status --short`, evidencia test ejecutado >0 y classpath del ZIP.

**Permisos:** este paquete no ejecuta escrituras a Git/GitHub. La integración y operaciones destructivas requieren la autorización efectiva del usuario y normas del proyecto. No ejecutar scripts de poda automáticos; `tools/test_portfolio.py` jamás borra.
