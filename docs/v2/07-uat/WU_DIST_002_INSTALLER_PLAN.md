# WU-DIST-2 — Plan del instalador autónomo PipelineK

> **Estado:** PLAN ACTIVO (2026-09-24T11:03Z).
> **Branch:** `wu/dist-002-installer` (HEAD base = `12371ca0`, el nuevo main post-merge de #73).
> **Authority:** `docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md` §2 (DIST-2).
> **Cierre previsto cuando:** el script `scripts/install-pipelinek.sh` cumple los criterios §6.

## 1. Objetivo

Proveer a los usuarios de PipelineK un instalador autónomo basado en Bash que:

1. Descargue una versión concreta del ZIP canónico desde GitHub Releases.
2. Verifique su SHA-256 contra el manifiesto del release (digest publicado).
3. Instale bajo `~/.local/share/pipelinek/versions/<version>/` (sin sudo).
4. Permita seleccionar una versión activa y volver a una anterior.
5. Compruebe la JVM compatible y ejecute `pipelinek doctor`.

El instalador NO es otro canal de compilación: consume el ZIP, no reconstruye. Es la única ruta que da el operador para "instalación cómoda desde GitHub Releases" sin esperar a SDKMAN.

## 2. Comandos (subcomandos del script)

```text
install-pipelinek install <version>     # descarga + verifica + instala
install-pipelinek use <version>         # cambia symlink current/<version>
install-pipelinek list                  # versiones instaladas + activa
install-pipelinek uninstall <version>   # elimina una versión (no la activa)
install-pipelinek doctor                # invoca pipelinek doctor de la activa
install-pipelinek help                  # ayuda
```

## 3. Estructura del filesystem

```text
~/.local/share/pipelinek/
├── versions/
│   ├── 0.39.0/
│   │   ├── bin/pipelinek
│   │   ├── bin/pipelinek.bat
│   │   └── lib/...
│   └── 0.40.0/...
└── current -> versions/0.39.0/   # symlink a la activa
```

El script no escribe en `~/.local/bin/`. Eso lo decide el usuario:

```bash
# Manual
export PATH="$HOME/.local/share/pipelinek/current/bin:$PATH"
```

## 4. Algoritmo install <version>

1. Validar `<version>` con regex `^[0-9]+\.[0-9]+\.[0-9]+$` (fail-closed).
2. Construir URL canónica:
   `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip`
3. **Allowlist de URL**: el script debe rechazar cualquier URL que no
   coincida con la ruta anterior. Falla cerrada si la variable
   `PIPELINEK_RELEASE_BASE_URL` está fuera de la allowlist.
4. Descargar ZIP con `curl -fL` a un scratch tmpdir.
5. **Verificar SHA-256**: `sha256sum -c -` contra un digest que el
   script obtiene de GitHub Releases (asset `.sha256` adyacente) o
   de una variable `PIPELINEK_SHA256_<VERSION>` pasada al script.
   **Fail-closed si no hay digest o si no coincide.**
6. Descomprimir en `~/.local/share/pipelinek/versions/<version>/`.
7. Idempotente: si la versión ya está instalada, abortar con exit 0
   y mensaje informativo.
8. `pipelinek version` debe ejecutarse; si falla, el instalador
   también falla y limpia el directorio recién creado.

## 5. Algoritmo use <version>

1. Validar `<version>` instalado en `~/.local/share/pipelinek/versions/`.
2. Reapuntar el symlink `current` a `versions/<version>`.
3. Si la versión activa es la misma, exit 0 sin cambios.

## 6. Algoritmo list

```text
VERSION     ACTIVE  PATH
0.39.0      *       /home/user/.local/share/pipelinek/versions/0.39.0
0.40.0              /home/user/.local/share/pipelinek/versions/0.40.0
```

## 7. Algoritmo uninstall <version>

1. Validar `<version>` instalado.
2. **No** permite desinstalar la versión activa. Si coincide, sugerir
   `use <otra>` antes.
3. Borrar `~/.local/share/pipelinek/versions/<version>/`.
4. Idempotente: si no existe, exit 0 con mensaje informativo.

## 8. Algoritmo doctor

1. Resolver la versión activa (`current` symlink).
2. Si no hay activa, error con exit 2.
3. Invocar `~/.local/share/pipelinek/current/bin/pipelinek doctor`.
4. Propagar exit code.

## 9. Criterios de cierre

- ✅ El script corre end-to-end sobre la candidata `v0.39.0` con `sha256sum -c -` verde.
- ✅ Después de instalar, `~/.local/share/pipelinek/current/bin/pipelinek version` devuelve `pipeline 0.39.0`.
- ✅ `install 0.39.0` (segunda vez) es idempotente (exit 0, sin error).
- ✅ `uninstall 0.39.0` falla porque está activa, con mensaje claro.
- ✅ Rollback (`use 0.39.0` después de instalar 0.40.0) funciona.
- ✅ URL allowlist funcional: `PIPELINEK_RELEASE_BASE_URL=https://evil.example.com` falla cerrada.
- ✅ El script vive en `scripts/install-pipelinek.sh`, versionado, con `set -Eeuo pipefail`.
- ✅ El instalador se prueba contra el binario público oficial (no contra `installDist` local).
- ✅ Sin daemon, sin servicio, sin package manager propio, sin sudo.

## 10. Patrón de código

Sigue el patrón de los scripts en `scripts/release/` del repo:
- `set -Eeuo pipefail`.
- Docstring al inicio con Authority / Usage / Pre-conditions / Idempotencia.
- Funciones pequeñas y testables.
- Mensajes claros al usuario (sin `-q` ni `-s` por defecto).

## 11. Out of scope (no entra en esta WU)

- Instalación interactiva con barra de progreso.
- Actualización automática a la última versión (`upgrade`).
- Instalación desde un mirror local.
- Detección de versión "stable" desde un canal.
- Tests unitarios bats/expect; esta WU se valida con ejecución
  manual contra el binario público. Si más adelante se necesitan
  tests, abren una WU aparte (DIST-7 o equivalente).

## 12. Próximo paso

Commit atómico de implementación:

```bash
# crear scripts/install-pipelinek.sh
git add scripts/install-pipelinek.sh
git commit -m "feat(dist): add install-pipelinek.sh installer (DIST-2)

Implements the autonomous installer per DISTRIBUTION_ROADMAP §2:
- Subcommands: install, use, list, uninstall, doctor, help.
- URL allowlist fail-closed (github.com/Rubentxu/pipeline-kotlin).
- SHA-256 verification via sha256sum -c -.
- Idempotent install; rollback via use.
- No sudo, no daemon, no package manager.
- Pattern aligned with scripts/release/*.sh.
- Verified against binario público oficial v0.39.0."
```

Tras commit, **recibo DIST-2**:

`docs/v2/07-uat/WU_DIST_002_INSTALLER_RECEIPT.md` con:

- SHA-256 del script.
- Trazas de las pruebas (install, use, list, uninstall, doctor, rollback).
- SHA-256 verificado de la candidata v0.39.0.
- Confirmación de URL allowlist funcionando.

Si todo verde, push de la rama + PR documental/funcional contra `main`.
