#!/bin/sh
# install-pipelinek-curl.sh — bootstrap POSIX para `curl … | sh`
#
# Autoridad:
#   - docs/v2/05-roadmap/DISTRIBUTION_ROADMAP.md §2 (DIST-2) — que la CLI se
#     pueda instalar desde la red es parte de la distribución, no un extra.
#   - scripts/install-pipelinek.sh — instalador bash AUTORIDAD. Este fichero NO
#     reimplementa la instalación: la descarga, verifica y delega.
#   - scripts/test_install_pipelinek.py — contrato del instalador bash.
#   - Contrato de este bootstrap: python3 scripts/test_install_pipelinek_curl.py
#
# Por qué existe
# --------------
# El instalador bash real necesita bash 4+ (arrays asociativos, `[[ ]]`) y lee
# su propio nombre de una variable específica de bash que queda VACÍA cuando el
# script llega por la entrada estándar. Con la tolerancia activa a variables sin
# definir, `curl … | sh` aborta siempre, y `curl … | bash` sólo funciona con
# bash >= 4.4. Un instalador que sólo funciona con un bash concreto no es un
# instalador: es una limitación del transporte.
#
# Este fichero resuelve SOLO el transporte POSIX. Deliberadamente NO hay aquí
# una segunda autoridad de instalación: si este script y el instalador bash
# divergieran sobre una versión, un digest o un destino, habría dos verdades.
# La política de instalación vive en un único sitio (scripts/install-pipelinek.sh)
# y este bootstrap sólo responde a tres preguntas — de dónde sale el instalador,
# — qué bytes son esos, y — ¿son demasiado recientes?
#
# Uso:
#   curl -fsSL <url-publica-de-este-fichero> | sh
#   sh install-pipelinek-curl.sh install 0.47.0
#   sh install-pipelinek-curl.sh doctor
#
# Sin argumentos imprime su propio uso y termina con EXIT_USAGE. Cualquier otro
# argumento se delega VERBATIM en el instalador bash, incluido `help`: este
# bootstrap no se apropia de ninguna palabra del subcomando.
#
# Variables de entorno
#   PIPELINEK_INSTALLER_VERSION            Fija la etiqueta del instalador bash
#                                         (v0.47.0 o 0.47.0). Sin ella se
#                                         resuelve "latest" por redirección.
#   PIPELINEK_INSTALLER_SHA256             Digest SHA-256 esperado del
#                                         instalador bash. Si está fijado y no
#                                         coincide, se aborta.
#   PIPELINEK_REQUIRE_PINNED_INSTALLER     "1" obliga a que el digest esté
#                                         fijado (uso en CI). Sin digest, aborta.
#   PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS  Antigüedad mínima en horas de la
#                                         release resuelta. Por defecto 24,
#                                         el mismo criterio que
#                                         MISE_SELF_UPDATE_MINIMUM_RELEASE_AGE.
#   PIPELINEK_BOOTSTRAP_REPO_URL           Repositorio del que se baja el
#                                         instalador bash. Por defecto el
#                                         canónico. Acepta hosts de loopback
#                                         (instalación air-gapped y pruebas
#                                         herméticas); cualquier otro host debe
#                                         estar en la allowlist.
#   PIPELINEK_HOME                         Raíz de instalación; sólo se usa
#                                         para el aviso del PATH final.
#
# Pre-condiciones:
#   - /bin/sh POSIX (dash, ash, busybox sh, bash --posix…)
#   - curl o wget en el PATH
#   - bash en el PATH: lo exige el instalador real, no este bootstrap

set -eu

# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------

# El nombre del script es una CONSTANTE, no `$0`: con `curl … | sh` el `$0` es
# el intérprete y usarlo daría mensajes como "sh install". No hay ninguna
# lectura del nombre propio de bash en este fichero, y ésa es la razón de que
# el transporte por entrada estándar funcione.
SCRIPT_NAME='install-pipelinek-curl.sh'

CANONICAL_REPO_URL='https://github.com/Rubentxu/pipeline-kotlin'
INSTALLER_ASSET_NAME='install-pipelinek.sh'
DEFAULT_MINIMUM_AGE_HOURS=24

# La allowlist NO se amplía respecto de scripts/install-pipelinek.sh. Cubre las
# dos etapas de la descarga de una release: github.com responde y
# objects.githubusercontent.com sirve el asset. Tampoco se usa la API de GitHub:
# la redirección de /releases/latest es la misma señal que ya usa `mise run` y no
# exige un endpoint nuevo ni un token.
URL_ALLOWLIST_HOSTS='github.com objects.githubusercontent.com'

# El instalador bash real usa arrays asociativos (bash 4+) y `[[ ]]`, y `shopt`
# con coreutils. 4.4 es el umbral que se AVISA, no el que se aborta: abortar
# aquí convertiría un aviso en una incompatibilidad que no está medida.
BASH_RECOMMENDED_MINIMUM='4.4'

HTTP_TIMEOUT_SECONDS=120

# ---------------------------------------------------------------------------
# Códigos de salida
#
# Cada forma de fallo es un CASO con su propio código, no un "1" genérico: un
# código de salida ambiguo obliga al operador a releer el texto para saber si
# puede reintentar, fijar una versión o investigar la red. La única excepción
# intencionada es la delegación, donde el código del instalador real se
# propaga sin traducir (ver `main`).
# ---------------------------------------------------------------------------

EXIT_OK=0
EXIT_USAGE=2
EXIT_NO_FETCHER=3
EXIT_NO_BASH=4
EXIT_URL_REFUSED=5
EXIT_LATEST_UNRESOLVED=6
EXIT_PIN_REQUIRED=7
EXIT_DIGEST_INVALID=8
EXIT_DIGEST_MISMATCH=9
EXIT_AGE_UNVERIFIABLE=10
EXIT_RELEASE_TOO_NEW=11
EXIT_DOWNLOAD_FAILED=12
# EXIT_INSTALLER_FAILED no es una constante: es el código del instalador bash.

# ---------------------------------------------------------------------------
# Registro
# ---------------------------------------------------------------------------

log_info()  { printf '[INFO]  %s\n' "$*" >&2; }
log_warn()  { printf '[WARN]  %s\n' "$*" >&2; }
log_error() { printf '[ERROR] %s\n' "$*" >&2; }

die() {
  log_error "$2"
  exit "$1"
}

usage() {
  cat >&2 <<EOF
${SCRIPT_NAME} — bootstrap POSIX para instalar PipelineK

Uso:
  curl -fsSL <esta-url> | sh
  sh ${SCRIPT_NAME} install <versiona>
  sh ${SCRIPT_NAME} doctor

Sin argumentos imprime este uso y termina con ${EXIT_USAGE}. Cualquier otro
argumento se delega verbatim en ${INSTALLER_ASSET_NAME}, que es la autoridad de
la instalación (necesita bash ${BASH_RECOMMENDED_MINIMUM}+ recomendado, 4+
imprescindible).

Entorno:
  PIPELINEK_INSTALLER_VERSION            Fija la etiqueta del instalador bash.
  PIPELINEK_INSTALLER_SHA256             Digest SHA-256 esperado del instalador.
  PIPELINEK_REQUIRE_PINNED_INSTALLER=1   Exige digest fijado (CI). Aborta si falta.
  PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS  Antigüedad mínima de la release
                                         (por defecto ${DEFAULT_MINIMUM_AGE_HOURS}).
  PIPELINEK_BOOTSTRAP_REPO_URL           Repositorio del instalador bash.
EOF
}

# ---------------------------------------------------------------------------
# Temporal y limpieza
# ---------------------------------------------------------------------------

TMP_DIR=''

# Invocada por el `trap` de más abajo; shellcheck no rastrea las trampas.
# shellcheck disable=SC2329
cleanup() {
  if [ -n "${TMP_DIR}" ] && [ -d "${TMP_DIR}" ]; then
    rm -rf "${TMP_DIR}"
  fi
}

# `0` es la salida normal y también la salida por error; las señales se
# limpian y además reanudan con el código convencional de la señal.
trap 'cleanup' 0
trap 'cleanup; exit 129' 1
trap 'cleanup; exit 130' 2
trap 'cleanup; exit 143' 15

# ---------------------------------------------------------------------------
# Utilidades puras (deciden; no interpretan)
#
# `local` no es POSIX. Para no depender de él, cada función que necesita una
# variable de trabajo usa un nombre propio y único en todo el fichero, de modo
# que ninguna sobrescribe el estado de otra. Es más largo que `local`, y es la
# única forma de que el estado compartido sea visible a ojo en vez de estar
# repartido por el intérprete.
# ---------------------------------------------------------------------------

# Devuelve 0 si MAJOR.MINOR de $1 es menor que el de $2, sin aritmética de
# punto flotante (4.10 < 4.4 sería falso con coma flotante).
version_lt() {
  awk -v a="$1" -v b="$2" 'BEGIN {
    split(a, x, ".")
    split(b, y, ".")
    for (i = 1; i <= 2; i++) {
      if ((x[i] + 0) != (y[i] + 0)) {
        exit ((x[i] + 0) < (y[i] + 0)) ? 0 : 1
      }
    }
    exit 1
  }'
}

# Valida una etiqueta de release vMAJOR.MINOR.PATCH[-pre]. Rechaza cualquier
# cosa que no sea eso, y con ella las barras, los espacios y el `..` que
# podrían cambiar el camino de la URL construida.
is_valid_tag() {
  printf '%s' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+([.+-][0-9A-Za-z][0-9A-Za-z.+-]*)?$'
}

# Imprime el valor de la ÚLTIMA cabecera con ese nombre, sin distinguir
# mayúsculas. Se toma la última porque una redirección puede repetirse: la
# que interesa es el destino final. Devuelve 1 si no aparece.
header_value() {
  awk -v want="$1" '
    {
      colon = index($0, ":")
      if (colon == 0) next
      name = tolower(substr($0, 1, colon - 1))
      gsub(/[ \t\r]/, "", name)
      if (name != want) next
      value = substr($0, colon + 1)
      gsub(/^[ \t]+/, "", value)
      gsub(/[ \t\r]+$/, "", value)
      last = value
    }
    END {
      if (last == "") exit 1
      print last
    }
  '
}

# Extrae el host de una URL: quita esquema, credenciales, puerto y camino.
url_host() {
  printf '%s' "$1" | sed -E 's|^[a-zA-Z]+://||; s|^[^@/]*@||; s|[:/?#].*$||'
}

# Allowlist sin arrays. El bucle no necesita comillas en la lista porque es un
# literal del propio script: no hay ningún dato del usuario en ella.
is_allowed_host() {
  case "$1" in
    127.*|localhost|::1) return 0 ;;
  esac
  for h in ${URL_ALLOWLIST_HOSTS}; do
    if [ "${h}" = "$1" ]; then
      return 0
    fi
  done
  return 1
}

# Convierte la fecha IMF-fixdate de HTTP ('Tue, 06 Oct 2026 06:09:19 GMT') a
# epoch. Se prueban primero las coreutils de GNU y después el `date` de BSD
# (macOS), que necesita el formato explícito. La zona viene en la propia cadena,
# así que el epoch es el mismo sea cual sea la zona local del operador.
http_date_to_epoch() {
  if date -d "$1" +%s >/dev/null 2>&1; then
    date -d "$1" +%s
    return 0
  fi
  if date -j -f '%a, %d %b %Y %H:%M:%S %Z' "$1" +%s >/dev/null 2>&1; then
    date -j -f '%a, %d %b %Y %H:%M:%S %Z' "$1" +%s
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# Adaptadores de efecto (interpretan)
# ---------------------------------------------------------------------------

FETCHER=''

# curl primero porque es el que la documentación del proyecto ya da por
# requerido; wget es el respaldo. Ninguno de los dos lee de la entrada
# estándar: con `curl … | sh` la entrada estándar transporta el propio script y
# consumirla sería ejecutar basura.
fetch_headers() {
  if [ "${FETCHER}" = 'curl' ]; then
    curl -fsSLI --max-time "${HTTP_TIMEOUT_SECONDS}" "$1" </dev/null
  else
    wget --spider --server-response --timeout="${HTTP_TIMEOUT_SECONDS}" \
      "$1" </dev/null 2>&1
  fi
}

download_to() {
  if [ "${FETCHER}" = 'curl' ]; then
    curl -fsSL --retry 3 --retry-delay 2 --max-time "${HTTP_TIMEOUT_SECONDS}" \
      -o "$2" "$1" </dev/null
  else
    wget -q --timeout="${HTTP_TIMEOUT_SECONDS}" -O "$2" "$1" </dev/null
  fi
}

# Elige el binario de resumen. Se exige sólo cuando hay digest que verificar:
# una instalación sin digest fijado no puede verificar nada, y exigir la
# herramienta en ese caso sólo añadiría una incompatibilidad sin ganancia.
hasher_bin() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'sha256sum'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    printf 'shasum'
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# Detección
# ---------------------------------------------------------------------------

require_fetcher() {
  if command -v curl >/dev/null 2>&1; then
    FETCHER='curl'
    return 0
  fi
  if command -v wget >/dev/null 2>&1; then
    FETCHER='wget'
    log_warn "curl no está disponible; se usará wget."
    return 0
  fi
  die "${EXIT_NO_FETCHER}" "Hace falta curl o wget para descargar el instalador. Aborting."
}

# bash no es opcional: lo necesita el instalador al que se delega. Lo que sí
# es opcional es la AVISIÓN de versión baja, porque el umbral exacto no está
# medido y un aviso informa sin dejar de instalar.
require_bash() {
  if ! command -v bash >/dev/null 2>&1; then
    die "${EXIT_NO_BASH}" "bash no está en el PATH. El instalador ${INSTALLER_ASSET_NAME} lo necesita (4+). Aborting."
  fi
  bash_version_line="$(bash --version 2>/dev/null | head -n 1)"
  bash_version_number="$(printf '%s\n' "${bash_version_line}" \
    | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+\.[0-9]+/) { print $i; exit } }')"
  if [ -z "${bash_version_number}" ]; then
    log_warn "No se pudo leer la versión de bash ('${bash_version_line}'). Se continúa con la delegación; si el instalador falla por sintaxis, actualiza bash."
    return 0
  fi
  if version_lt "${bash_version_number}" "${BASH_RECOMMENDED_MINIMUM}"; then
    log_warn "bash ${bash_version_number} es anterior a ${BASH_RECOMMENDED_MINIMUM}."
    log_warn "El instalador real necesita bash 4+ y puede fallar al delegar. Considera actualizar bash."
  fi
}

# ---------------------------------------------------------------------------
# Validación de la entrada (pura, sin red)
# ---------------------------------------------------------------------------

# Devuelve 0 o escribe el motivo y devuelve 1. Los validadores no abortan: la
# función que decide el código de salida es la que sabe qué caso es.
validate_repo_url() {
  host="$(url_host "$1")"
  if [ -z "${host}" ]; then
    printf 'la URL no tiene host: %s\n' "$1"
    return 1
  fi
  if ! is_allowed_host "${host}"; then
    printf "el host '%s' no está en la allowlist (%s)\n" "${host}" "${URL_ALLOWLIST_HOSTS}"
    return 1
  fi
  return 0
}

validate_digest() {
  case "$1" in
    *[!0-9A-Fa-f]*|'') return 1 ;;
  esac
  [ "${#1}" -eq 64 ] || return 1
  return 0
}

validate_minimum_age() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
  esac
  return 0
}

# ---------------------------------------------------------------------------
# Puerta de antigüedad
#
# QUÉ COMPRUEBA: que la release resuelta no sea más reciente que
# PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS (24 por defecto). El criterio es el de
# MISE_SELF_UPDATE_MINIMUM_RELEASE_AGE: una release recién publicada es una
# señal, y una release que ya ha reposado unas horas ha dejado de serlo.
#
# QUÉ NO PUEDE VERIFICAR, y por qué es deliberado:
#   1. La redirección de /releases/latest NO lleva marca de tiempo. No hay
#      ninguna fecha de publicación en lo que esta puerta consulta, así que no
#      se está comprobando la antigüedad de la RELEASE sino la antigüedad que
#      declara la cabecera `Last-Modified` del propio asset. Son dos cosas
#      distintas y aquí no se confunden.
#   2. `Last-Modified` es la señal más simple disponible sin inventar un
#      endpoint. `mise` resuelve el mismo problema con un índice publicado
#      (releases.tsv con un epoch por release); PipelineK no publica ese índice y
#      esta puerta NO lo da por creado.
#   3. Si esa cabecera no aparece, la puerta ABORTA en lugar de continuar: sin
#      señal de antigüedad, "continuar y ya" es dejar pasar justo lo que la
#      puerta existe para detener. La salida es EXIT_AGE_UNVERIFIABLE, que dice
#      "no pude comprobar", no "es antiguo".
#   4. Con la versión fijada por el operador la puerta NO se aplica (igual que
#      `mise` con MISE_VERSION): elegir versión es una decisión explícita y la
#      autoridad pasa a ser el digest fijado.
# ---------------------------------------------------------------------------

enforce_minimum_age() {
  if ! headers="$(fetch_headers "$1")"; then
    die "${EXIT_AGE_UNVERIFIABLE}" "No se pudo consultar ${1} para comprobar su antigüedad. Las causas habituales son que la release no publique ${INSTALLER_ASSET_NAME} como asset o un error de red; sin esta señal no se puede decidir y se aborta en vez de asumirla."
  fi
  if ! last_modified="$(printf '%s\n' "${headers}" | header_value last-modified)"; then
    die "${EXIT_AGE_UNVERIFIABLE}" "La respuesta de ${1} no trae cabecera last-modified: no se puede comprobar la antigüedad y se aborta en vez de asumirla."
  fi
  if ! published_epoch="$(http_date_to_epoch "${last_modified}")"; then
    die "${EXIT_AGE_UNVERIFIABLE}" "No se pudo interpretar la fecha '${last_modified}' de ${1}. Aborting."
  fi
  now_epoch="$(date +%s)"
  cutoff_epoch="$(( now_epoch - MINIMUM_AGE_HOURS * 3600 ))"
  if [ "${published_epoch}" -gt "${cutoff_epoch}" ]; then
    age_hours="$(( (now_epoch - published_epoch) / 3600 ))"
    die "${EXIT_RELEASE_TOO_NEW}" "La release ${1} es demasiado nueva: ${last_modified} (hace ${age_hours}h, umbral ${MINIMUM_AGE_HOURS}h). Espera o fija PIPELINEK_INSTALLER_VERSION. Aborting."
  fi
  log_info "Antigüedad aceptada: ${last_modified} (umbral ${MINIMUM_AGE_HOURS}h)."
}

# ---------------------------------------------------------------------------
# Resolución
# ---------------------------------------------------------------------------

REPO_URL="${PIPELINEK_BOOTSTRAP_REPO_URL:-${CANONICAL_REPO_URL}}"
MINIMUM_AGE_HOURS="${PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS:-${DEFAULT_MINIMUM_AGE_HOURS}}"
EXPECTED_SHA256="${PIPELINEK_INSTALLER_SHA256:-}"
REQUIRE_PINNED="${PIPELINEK_REQUIRE_PINNED_INSTALLER:-}"

# Imprime la etiqueta en stdout; los motivos van a stderr con estado 1. Nunca
# aborta desde dentro de una sustitución de comandos: el código de salida lo
# elige el marco que decide la política.
resolve_pinned_tag() {
  case "$1" in
    v*) pinned_tag="$1" ;;
    *)  pinned_tag="v$1" ;;
  esac
  if ! is_valid_tag "${pinned_tag}"; then
    printf 'PIPELINEK_INSTALLER_VERSION no es una etiqueta vMAJOR.MINOR.PATCH válida: %s\n' "$1" >&2
    return 1
  fi
  printf '%s\n' "${pinned_tag}"
}

# Patrón de `mise run`: `curl -fsSLI` contra /releases/latest y lectura de la
# cabecera location. Es la misma señal que usa el bootstrapping de mise y no
# exige ningún endpoint nuevo ni un token de API.
resolve_latest_tag() {
  latest_url="${REPO_URL}/releases/latest"
  if ! latest_headers="$(fetch_headers "${latest_url}")"; then
    printf 'No se pudo consultar %s\n' "${latest_url}" >&2
    return 1
  fi
  if ! latest_location="$(printf '%s\n' "${latest_headers}" | header_value location)"; then
    printf '%s no respondió con una cabecera location; no se puede resolver "latest"\n' "${latest_url}" >&2
    return 1
  fi
  case "${latest_location}" in
    */releases/tag/*) latest_tag="${latest_location##*/releases/tag/}" ;;
    *)
      printf 'location inesperado (%s); se esperaba .../releases/tag/<etiqueta>\n' "${latest_location}" >&2
      return 1
      ;;
  esac
  if ! is_valid_tag "${latest_tag}"; then
    printf 'La redirección resolvió una etiqueta no válida: %s\n' "${latest_tag}" >&2
    return 1
  fi
  printf '%s\n' "${latest_tag}"
}

# ---------------------------------------------------------------------------
# Verificación del digest
# ---------------------------------------------------------------------------

verify_installer_digest() {
  hasher="$(hasher_bin)" || die "${EXIT_NO_FETCHER}" "Hace falta sha256sum o shasum para verificar el digest fijado. Aborting."
  if [ "${hasher}" = 'shasum' ]; then
    observed_sha256="$(shasum -a 256 "$1" | awk '{ print $1; exit }')"
  else
    observed_sha256="$(sha256sum "$1" | awk '{ print $1; exit }')"
  fi
  observed_sha256="$(printf '%s' "${observed_sha256}" | tr '[:upper:]' '[:lower:]')"
  if [ "${observed_sha256}" != "${EXPECTED_SHA256}" ]; then
    log_error "Digest del instalador incorrecto"
    log_error "  esperado ${EXPECTED_SHA256}"
    log_error "  observado ${observed_sha256}"
    die "${EXIT_DIGEST_MISMATCH}" "SHA-256 incorrecto para ${INSTALLER_ASSET_NAME}. No se ejecuta nada."
  fi
  log_info "Digest del instalador verificado: ${observed_sha256}"
}

# ---------------------------------------------------------------------------
# Aviso del PATH (idempotente)
#
# Un instalador que dice "export PATH=..." cada vez es ruido que acaba en el
# `.bashrc`. Se comprueba con coincidencia EXACTA de línea (`grep -Fxq`), que es
# lo que evita que un directorio prefijo de otro cuente como el mismo.
# ---------------------------------------------------------------------------

advise_path() {
  pipelinek_home="${PIPELINEK_HOME:-${HOME}/.local/share/pipelinek}"
  pipelinek_bin="${pipelinek_home}/current/bin"
  if printf '%s\n' "${PATH:-}" | tr ':' '\n' | grep -Fxq "${pipelinek_bin}"; then
    log_info "${pipelinek_bin} ya está en el PATH; no hace falta exportar nada."
    return 0
  fi
  log_info "Para usar 'pipelinek' en esta sesión:"
  # El `$PATH` va sin expandir a propósito: se imprime la orden que el
  # operador evalúa en SU shell, no su valor actual.
  # shellcheck disable=SC2016
  printf '  export PATH="%s:$PATH"\n' "${pipelinek_bin}"
}

# ---------------------------------------------------------------------------
# Programa principal
# ---------------------------------------------------------------------------

main() {
  if [ "$#" -eq 0 ]; then
    usage
    exit "${EXIT_USAGE}"
  fi

  # Validación pura ANTES de cualquier efecto: un digest mal formado o una
  # etiqueta inválida no se descubren después de haber descargado nada.
  if ! url_reason="$(validate_repo_url "${REPO_URL}")"; then
    die "${EXIT_URL_REFUSED}" "PIPELINEK_BOOTSTRAP_REPO_URL rechazado: ${url_reason}. Aborting."
  fi
  if ! validate_minimum_age "${MINIMUM_AGE_HOURS}"; then
    die "${EXIT_USAGE}" "PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS debe ser un entero >= 0, no '${MINIMUM_AGE_HOURS}'."
  fi
  if [ -n "${EXPECTED_SHA256}" ] && ! validate_digest "${EXPECTED_SHA256}"; then
    die "${EXIT_DIGEST_INVALID}" "PIPELINEK_INSTALLER_SHA256 no es un SHA-256 (64 hexadecimales). Aborting."
  fi
  if [ "${REQUIRE_PINNED}" = '1' ] && [ -z "${EXPECTED_SHA256}" ]; then
    die "${EXIT_PIN_REQUIRED}" "PIPELINEK_REQUIRE_PINNED_INSTALLER=1 exige PIPELINEK_INSTALLER_SHA256 fijado. Aborting."
  fi

  require_fetcher
  require_bash

  # Dos caminos explícitos, no una bandera booleana: con versión fijada la
  # autoridad es el digest; sin versión fijada la puerta de antigüedad decide
  # si lo resuelto es demasiado reciente.
  if [ -n "${PIPELINEK_INSTALLER_VERSION:-}" ]; then
    tag="$(resolve_pinned_tag "${PIPELINEK_INSTALLER_VERSION}")" \
      || die "${EXIT_USAGE}" "No se pudo usar PIPELINEK_INSTALLER_VERSION. Aborting."
    log_info "Versión del instalador fijada por el operador: ${tag}"
  else
    tag="$(resolve_latest_tag)" \
      || die "${EXIT_LATEST_UNRESOLVED}" "No se pudo resolver la release más reciente. Fija PIPELINEK_INSTALLER_VERSION o revisa la red. Aborting."
  fi

  installer_url="${REPO_URL}/releases/download/${tag}/${INSTALLER_ASSET_NAME}"

  if [ -z "${PIPELINEK_INSTALLER_VERSION:-}" ]; then
    enforce_minimum_age "${installer_url}"
  fi

  TMP_DIR="$(mktemp -d)"
  installer_path="${TMP_DIR}/${INSTALLER_ASSET_NAME}"

  log_info "Descargando el instalador: ${installer_url}"
  if ! download_to "${installer_url}" "${installer_path}"; then
    die "${EXIT_DOWNLOAD_FAILED}" "No se pudo descargar ${installer_url}. Si la release no publica ${INSTALLER_ASSET_NAME} como asset, ese es el motivo. Aborting."
  fi

  if [ -n "${EXPECTED_SHA256}" ]; then
    verify_installer_digest "${installer_path}"
  else
    log_info "PIPELINEK_INSTALLER_SHA256 sin fijar: los bytes NO se verifican contra ninguna autoridad. Fija el digest si necesitas reproducibilidad."
  fi

  log_info "Delegando en el instalador oficial ${INSTALLER_ASSET_NAME} con los argumentos: $*"
  # Se ejecuta desde un FICHERO, nunca por entrada estándar: es exactamente lo
  # que fallaba en el instalador real cuando llegaba por tubería. Y con la
  # entrada estándar cerrada, para que el instalador delegado no consuma el
  # script que aún queda por leer de la tubería.
  if bash "${installer_path}" "$@" </dev/null; then
    installer_status=0
  else
    installer_status=$?
  fi

  if [ "${installer_status}" -ne 0 ]; then
    # El código del instalador real se propaga SIN traducir: quien llama ya
    # conoce esos códigos, y remapearlos aquí escondería su causa.
    log_error "El instalador oficial terminó con código ${installer_status}; se propaga sin traducir."
    exit "${installer_status}"
  fi

  advise_path
  exit "${EXIT_OK}"
}

main "$@"