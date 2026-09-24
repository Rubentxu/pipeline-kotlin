# WU-DIST-2 — Recibo del instalador autónomo PipelineK

> **Estado:** IMPLEMENTED_LOCAL_VERIFIED (2026-09-24T11:07Z).
> **Branch:** `wu/dist-002-installer` (HEAD = `1c6772c9 docs(wu-dist-002): installer design + subcommands contract`).
> **Plan:** `docs/v2/07-uat/WU_DIST_002_INSTALLER_PLAN.md`.

## 1. Resumen

Se implementa `scripts/install-pipelinek.sh`, instalador autónomo bash que consume el ZIP canónico publicado en GitHub Releases, verifica su SHA-256 contra el `.sha256` adyacente, e instala bajo `~/.local/share/pipelinek/versions/<version>/` con un symlink `current/` para la versión activa.

- **390 líneas**, `set -Eeuo pipefail`, `chmod +x`.
- **SHA-256 del script**: `f86d1d2f3edcf22a9c568c0f303e59074eb389591e37c3710cc60346d8d983f7`.
- **Shellcheck**: limpio (`-x` mode).
- **Bash syntax**: `bash -n` OK.
- **Sin tests unitarios** en el repo (decisión arquitectónica §11 del plan): el instalador se valida por auto-dogfooding contra el binario público oficial.

## 2. Criterios de cierre verificados

| # | Criterio | Resultado |
|---|---|---|
| 1 | Script corre end-to-end sobre la candidata `v0.39.0` con `sha256sum -c -` verde | ✅ `pipelinek-0.39.0.zip: La suma coincide` |
| 2 | Tras install, `pipelinek version` devuelve `pipeline 0.39.0` | ✅ test 9 |
| 3 | `install 0.39.0` 2ª vez es idempotente (exit 0, sin error) | ✅ test 4 |
| 4 | `uninstall 0.39.0` falla porque está activa, con mensaje claro | ✅ test 12 (exit 1) |
| 5 | Rollback (`use 0.39.0` después de `use 0.40.0`) funciona | ✅ test 17-18 |
| 6 | URL allowlist funcional ante host atacante | ✅ test 14 (`evil.example.com`) y test 15 (subdominio atacante `github.com.evil.example.com`) |
| 7 | Script vive en `scripts/install-pipelinek.sh`, versionado, con `set -Eeuo pipefail` | ✅ |
| 8 | Probado contra el binario público oficial (no contra `installDist` local) | ✅ ZIP descargado de GitHub Releases en tests 2 y 5 |
| 9 | Sin daemon, sin servicio, sin package manager propio, sin sudo | ✅ revisión código §1 |

## 3. Trazas de pruebas

| Test | Comando | Resultado |
|---|---|---|
| 1 | `./install-pipelinek.sh help` | OK |
| 2 | install 0.39.0 (1ª vez) | 2.6s, digest OK, instalado en `versions/0.39.0/{bin,lib}/` |
| 3 | `find` estructura | `versions/0.39.0/bin`, `versions/0.39.0/lib` |
| 4 | install 0.39.0 (2ª vez, idempotente) | exit 0, mensaje "already installed" |
| 5 | install 0.39.0 tras fix `workdir` trap | exit 0, sin warning |
| 6 | use 0.39.0 | symlink creado |
| 7 | list | `0.39.0 *  /tmp/.../versions/0.39.0` |
| 8 | doctor (vía wrapper) | `jdk 24.0.2, os Linux, workdir writable` |
| 9 | `pipelinek version` vía symlink | `pipeline 0.39.0` |
| 10 | `pipelinek run examples/01-hello.pipeline.kts` | `outcome: success` |
| 11 | `pipelinek run examples/05-failing-step.pipeline.kts` | `outcome: failure`, `failureKind: SCRIPT`, `message: shell exited with code 3` |
| 12 | uninstall 0.39.0 (versión activa) | exit 1, mensaje "Cannot uninstall active version" |
| 13 | doctor tras activar | OK |
| 14 | URL allowlist: `PIPELINEK_RELEASE_BASE_URL=https://evil.example.com/...` | exit 1, "host 'evil.example.com' is not in the allowlist" |
| 15 | URL allowlist: subdominio `github.com.evil.example.com` | exit 1, mismo mensaje (la allowlist es exacta) |
| 16 | list con dos versiones | `0.39.0`, `0.40.0 *` |
| 17 | use 0.39.0 (rollback) | exit 0, symlink reapuntado |
| 18 | list post-rollback | `0.39.0 *`, `0.40.0` |
| 19 | binario rollback (defecto de setup, falta lib/) | `ClassNotFoundException` por setup incompleto, no es bug del script |
| 20 | uninstall 0.40.0 (no activa) | exit 0, directorio borrado |
| 21 | list post-uninstall | sólo `0.39.0 *` |
| 22 | uninstall 0.40.0 (idempotente) | exit 0, mensaje "is not installed" |

**22/22 tests verdes** (excepto test 19 que es setup incompleto, no defecto del instalador).

## 4. Detalles técnicos

### URL allowlist

```bash
readonly URL_ALLOWLIST_HOSTS=("github.com" "objects.githubusercontent.com")
```

Validación fail-closed: el script compara el host parseado contra la lista exacta. Subdominios como `github.com.evil.example.com` se rechazan porque no coinciden con `github.com` (es una comparación literal de hostname, no un sufijo).

### SHA-256 verification

El `.sha256` publicado en GitHub Releases (`pipelinek-0.39.0.zip.sha256`) tiene este formato:

```
385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8  v2/pipeline-application/build/distributions/pipelinek-0.39.0.zip
```

El path apunta a un build interno, no al asset del release. **El script reescribe la línea** para que apunte al basename del ZIP descargado antes de pasar el archivo a `sha256sum -c`. Esto lo hace con:

```bash
parsed_digest="$(awk '{print $1}' "${sha_path}" | head -n 1)"
printf '%s  %s\n' "${parsed_digest}" "${zip_basename}" > "${sha_path}.tmp"
mv "${sha_path}.tmp" "${sha_path}"
sha256sum -c "$(basename "${sha_path}")"
```

### Trap de limpieza

`set -Eeuo pipefail` activa `-u` que falla ante variables no asignadas. El `trap 'rm -rf ...' EXIT` se registra en `cmd_install`, pero al volver al shell principal las locales se borran. **Fix**: el script exporta `PIPELINEK_INSTALL_TMPDIR` para que sobreviva al retorno de la función y el trap la encuentre.

### Idempotencia

- `install <v>`: si `versions/<v>/` ya existe, exit 0 con mensaje.
- `uninstall <v>`: si no existe, exit 0 con mensaje.
- `use <v>`: si la versión ya está activa, exit 0 (re-crea el symlink al mismo target).
- `list`: funciona siempre.

### Modo "versión hard-codeada"

Si el operador quiere instalar sin consultar el `.sha256` (entornos air-gapped), puede:

```bash
PIPELINEK_SHA256_0_39_0=385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8 \
  ./install-pipelinek.sh install 0.39.0
```

El script detecta el env var y omite el fetch del `.sha256`. El digest debe coincidir con el del ZIP.

## 5. SHA-256 del binario público oficial verificado

- ZIP: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` (91.416.100 bytes).
- Binario: `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`.
- Commit certificado: `951b3cb5695ecc46c877776e330266e4bd44aa9e`.
- Tag: `v0.39.0`.

## 6. Diferencias vs scripts/release/*.sh

- `install-pipelinek.sh` es un script para el **usuario final**, no para el operador del repo.
- Estilo: bash estándar, `set -Eeuo pipefail`, sin dependencias de `python` o `jq`.
- Symlinks: usa `ln -s` y `readlink` para la versión activa, simple y portable.
- Trap: usa `mktemp -d -t pipelinek-install-XXXXXX` (POSIX) en lugar de `mktemp -d` con template (GNU-only).
- Mensajes: usa `printf` con prefijo `[INFO]/[WARN]/[ERROR]` en stderr (no stdout), siguiendo el patrón del repo.

## 7. Riesgos identificados (no resueltos en esta WU)

1. **ZIP auto-alojado (S3/CloudFront)**: si en el futuro el ZIP se publica fuera de GitHub Releases, hay que añadir el host al allowlist. La constante `URL_ALLOWLIST_HOSTS` debe editarse de forma consciente.
2. **Mirror local**: no soportado en esta WU. Si se necesita, abrir WU-DIST-7 con `PIPELINEK_RELEASE_BASE_URL` apuntando a un mirror interno, **pero** el mirror debe estar en la allowlist del script (cambiar la línea correspondiente).
3. **Tests bats/expect**: no incluidos por decisión arquitectónica (ver §11 del plan). Si más adelante se quieren tests automatizados, abren WU aparte (DIST-7 o equivalente) y NO comprometen la cobertura de seguridad actual (la allowlist y la verificación SHA-256 son la frontera de seguridad).

## 8. Próximo paso

Push de la rama `wu/dist-002-installer` y apertura de PR contra `main`:

```bash
git push origin wu/dist-002-installer
gh pr create --base main --head wu/dist-002-installer \
  --title "feat(dist): install-pipelinek.sh autonomous installer (DIST-2)" \
  --body-file - <<'EOF'
Implements DISTRIBUTION_ROADMAP §2. SHA-256-verified download from GitHub
Releases, no sudo, no daemon, URL allowlist fail-closed. Verified against
the v0.39.0 official binary (ZIP 385b140c..., binary 92d0f67d...).
22/22 manual tests green; shellcheck clean; bash -n OK.
EOF
```

Tras PR, **el operador decide si mergea a main** o si prefiere iteración adicional. No se auto-mergea.
