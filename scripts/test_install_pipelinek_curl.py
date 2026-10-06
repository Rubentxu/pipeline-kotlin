#!/usr/bin/env python3
"""Contractual tests for install-pipelinek-curl.sh (bootstrap POSIX de `curl | sh`).

Run:
    python3 scripts/test_install_pipelinek_curl.py

Estos tests fijan el contrato del bootstrap POSIX. El instalador bash real
(`scripts/install-pipelinek.sh`) NO se reimplementa aquí ni se modifica: es la
autoridad de la instalación y su propio contrato vive en
`scripts/test_install_pipelinek.py`.

Por qué existe este bootstrap
-----------------------------
El instalador bash necesita bash 4+ y lee su propio nombre de una variable
específica de bash que queda vacía cuando el script llega por la entrada
estándar. Con la tolerancia a variables sin definir activa, `curl … | sh` aborta
siempre y `curl … | bash` sólo funciona con bash >= 4.4. El problema no es la
instalación: es el transporte.

Contrato verificado
-------------------
  B1  El bootstrap es POSIX puro: sin arrays, sin `[[`, sin tolerancia a
      fallos en tuberías, sin la variable de nombre propio de bash. Se declara
      `#!/bin/sh` y, si `shellcheck` está disponible, pasa con severidad por
      defecto como shell `sh`.
  B2  Las cuatro variables de entorno del contrato existen con su nombre
      exacto: PIPELINEK_INSTALLER_SHA256, PIPELINEK_REQUIRE_PINNED_INSTALLER,
      PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS y PIPELINEK_INSTALLER_VERSION.
  B3  "latest" se resuelve por la redirección HTTP de
      `/releases/latest` (el patrón de `mise run`), NO por api.github.com, y la
      allowlist NO se amplía: el único host no-loopback citado es github.com,
      con objects.githubusercontent.com como destino de la descarga.
  B4  `PIPELINEK_REQUIRE_PINNED_INSTALLER=1` sin digest fijado aborta ANTES de
      cualquier petición de red (probado con dobles de `curl`/`wget` en el PATH
      que registran cualquier invocación).
  B5  Un digest fijado que no coincide aborta y el instalador real NO se
      ejecuta.
  B6  Una release más reciente que el umbral de antigüedad aborta.
  B7  Si la respuesta no trae `last-modified`, la puerta de antigüedad ABORTA
      en vez de asumir que la release es antigua.
  B8  Extremo a extremo sin versión fijada: resuelve la redirección, descarga,
      verifica el digest, delega los argumentos y propaga el código de salida
      del instalador real sin traducirlo.
  B9  Con la versión fijada por el operador la puerta de antigüedad NO se
      aplica (la autoridad pasa a ser el digest). Es una decisión de diseño
      explícita y este test la fija.
  B10 Sin argumentos imprime su uso y termina con EXIT_USAGE; cualquier otro
      argumento se delega verbatim, sin reescribir sus fronteras.
  B11 El aviso del PATH es idempotente: con el directorio ya presente en el
      PATH no imprime la orden de exportación.
  B12 El bootstrap funciona cuando LLEGA POR LA ENTRADA ESTÁNDAR, que es la
      forma exacta (`curl … | sh`) que hoy falla.

Hermeticidad
------------
  NINGÚN test hace red. Las pruebas que necesitan "descargar" sirven la
  fixture desde un servidor HTTP de LOOPBACK que reproduce la forma exacta de
  una release de GitHub (302 a /releases/tag/<etiqueta> y el asset con
  `Last-Modified`). Dos propiedades hacen que eso sea una garantía y no una
  intención:

    HERMETICIDAD  `assert_hermetic` falla si el bootstrap menciona el host
                  canónico mientras se le ha apuntado al stub: una regresión
                  hacia la red real no puede pasar en silencio.

    SIN RED DE VERDAD  Las rutas que deben abortar antes de cualquier efecto
                  (B4) se ejecutan con dobles de `curl` y `wget` en el PATH que
                  dejan un testigo si se los invoca. El testigo ausente es la
                  prueba de que no hubo ni un intento de descarga.

  La fecha de antigüedad se controla con `Last-Modified`, no con reloj ni con
  `sleep`: el test pone la fecha que quiere en la respuesta y el bootstrap
  decide sobre ella. No hay temporizadores, ni esperas, ni orden en el tiempo.
"""

from __future__ import annotations

import email.utils
import hashlib
import http.server
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BOOTSTRAP = REPO_ROOT / "scripts" / "install-pipelinek-curl.sh"
INSTALLER_NAME = "install-pipelinek.sh"

# ---------------------------------------------------------------------------
# Códigos de salida del bootstrap.
#
# Cada forma de fallo es un caso con su propio código, y eso es parte del
# contrato: un "1" genérico obliga al operador a releer el texto para saber si
# puede reintentar. Estos valores espejan las constantes del propio script; si
# se mueven, este fichero falla y hay que mover las dos cosas a la vez.
# ---------------------------------------------------------------------------

EXIT_USAGE = 2
EXIT_NO_FETCHER = 3
EXIT_NO_BASH = 4
EXIT_URL_REFUSED = 5
EXIT_LATEST_UNRESOLVED = 6
EXIT_PIN_REQUIRED = 7
EXIT_DIGEST_INVALID = 8
EXIT_DIGEST_MISMATCH = 9
EXIT_AGE_UNVERIFIABLE = 10
EXIT_RELEASE_TOO_NEW = 11
EXIT_DOWNLOAD_FAILED = 12

# Etiqueta y rutas que usa el stub de loopback; reproduyen las de la release
# real observada (`.../releases/latest` responde 302 a `.../releases/tag/v0.47.0`).
REPO_PATH = "/Rubentxu/pipeline-kotlin"
TAG = "v0.47.0"
LATEST_PATH = f"{REPO_PATH}/releases/latest"
TAG_PATH = f"{REPO_PATH}/releases/tag/{TAG}"
ASSET_PATH = f"{REPO_PATH}/releases/download/{TAG}/{INSTALLER_NAME}"

CANONICAL_REPO_URL = "https://github.com/Rubentxu/pipeline-kotlin"

# Instante FIJO para las cabeceras `Last-Modified` de las releases VIEJAS. El
# test pone la fecha que quiere en la respuesta y el bootstrap decide sobre
# ella; ningún test duerme ni depende del orden en que corren las cosas.
NOW = 1_760_000_000

# Umbral por defecto del contrato; debe coincidir con el del bootstrap (B2b).
DEFAULT_MINIMUM_AGE_HOURS = 24

# Una release "recién publicada" sólo puede describirse respecto al reloj: por
# eso ese único fixture sí se ancla en el reloj. El reloj sólo sirve para
# AUTORAR la fecha de la cabecera; la aserción sigue siendo discreta (el
# código de salida del caso), nunca una medición de duración.
import time


def now_epoch() -> int:
    return int(time.time())

# Digest deliberadamente equivocado: 64 hexadecimales que no son los del asset.
WRONG_SHA256 = "f" * 64


# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------


class TestFailure(AssertionError):
    pass


class Skip(Exception):
    """Una comprobación no aplicable en esta máquina, con su motivo."""

    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise TestFailure(message)


def assert_hermetic(result, base: str) -> None:
    """Falla si el bootstrap alcanzó el host canónico en vez del stub.

    Sin esto, un bootstrap que ignorase PIPELINEK_BOOTSTRAP_REPO_URL se
    descargaría una release real de 90 MB y el test seguiría "pasando" con un
    resultado que nada tiene que ver con el fixture.
    """
    combined = result.stdout + result.stderr
    check(
        "github.com/Rubentxu" not in combined,
        "el bootstrap alcanzó la URL canónica de GitHub; se ignoró el stub "
        f"(ejecución no hermética):\n{combined[:800]}",
    )
    check(
        base in combined,
        f"la salida nunca menciona el stub {base}; se usó otra fuente:\n{combined[:800]}",
    )


# ---------------------------------------------------------------------------
# Release fixture: el instalador bash real, sustituido por un doble
# ---------------------------------------------------------------------------

# El doble reproduce el contrato observable del instalador real (se ejecuta con
# `bash` desde un fichero, recibe los argumentos del usuario y devuelve un
# código) y además deja constancia de lo recibido. Si se ejecutara, el test lo
# vería; por eso la ausencia del fichero de registro es prueba de que el
# bootstrap se detuvo ANTES de delegar.
FAKE_INSTALLER = """#!/usr/bin/env bash
set -Eeuo pipefail

if [ -n "${PIPELINEK_TEST_RECORD:-}" ]; then
  : > "${PIPELINEK_TEST_RECORD}"
  for arg in "$@"; do
    printf '%s\\n' "${arg}" >> "${PIPELINEK_TEST_RECORD}"
  done
fi

printf 'instalador-real-invocado argc=%s\\n' "$#"
exit "${PIPELINEK_TEST_EXIT:-0}"
"""


def installer_digest() -> str:
    return hashlib.sha256(FAKE_INSTALLER.encode()).hexdigest()


def http_date(epoch: int) -> str:
    """Fecha IMF-fixdate, la misma forma que emite GitHub en `Last-Modified`."""
    return email.utils.formatdate(epoch, usegmt=True)


# ---------------------------------------------------------------------------
# Stub de release en loopback
# ---------------------------------------------------------------------------


def serve_release(last_modified_epoch: int | None) -> tuple[str, list[str], callable]:
    """Sirve la forma exacta de una release de GitHub desde loopback.

    Devuelve (base_url, rutas_servidas, apagar). `rutas_servidas` es una lista
    viva: permite afirmar QUÉ se pidió (por ejemplo que se consultó
    `/releases/latest`) y no sólo que el comando terminó bien.

    `last_modified_epoch=None` omite la cabecera, que es el caso que B7 exige
    que cierre en vez de continuar.
    """
    served: list[str] = []

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"

        def log_message(self, *args):  # silencia el log del servidor
            pass

        def _reply(self, with_body: bool) -> None:
            path = self.path.split("?")[0]
            served.append(path)
            if path == LATEST_PATH:
                # La redirección que resuelve "latest", como la de GitHub.
                self.send_response(302)
                self.send_header("Location", f"{self.server.base_url}{TAG_PATH}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if path == TAG_PATH:
                # Destino final de la redirección; el cuerpo no importa, pero
                # tiene que existir para que curl deje de seguir.
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if path == ASSET_PATH:
                self.send_response(200)
                self.send_header("Content-Type", "text/x-shellscript")
                if last_modified_epoch is not None:
                    self.send_header("Last-Modified", http_date(last_modified_epoch))
                body = FAKE_INSTALLER.encode()
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if with_body:
                    self.wfile.write(body)
                return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            self._reply(True)

        def do_HEAD(self):
            self._reply(False)

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.base_url = f"http://127.0.0.1:{port}"
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()

    def shutdown() -> None:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)

    return httpd.base_url, served, shutdown


def repo_url(base: str) -> str:
    """URL del repositorio tal y como la construiría el bootstrap.

    El stub sirve las rutas CON el prefijo del repositorio
    (/Rubentxu/pipeline-kotlin/...), igual que GitHub, así que la variable de
    entorno debe apuntar al repositorio y no a la raíz del servidor.
    """
    return f"{base}{REPO_PATH}"


# ---------------------------------------------------------------------------
# Doubles de curl/wget: prueban que no hubo ninguna petición
# ---------------------------------------------------------------------------

STUB_FETCHER = """#!/bin/sh
# Doble de red: si el bootstrap lo invoca, deja testigo y falla.
if [ -n "${PIPELINEK_TEST_FETCH_WITNESS:-}" ]; then
  printf '%s\\n' "$0 $*" >> "${PIPELINEK_TEST_FETCH_WITNESS}"
fi
exit 97
"""


def fetcher_stub_dir(directory: Path) -> Path:
    bin_dir = directory / "stub-bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    for name in ("curl", "wget"):
        stub = bin_dir / name
        stub.write_text(STUB_FETCHER)
        stub.chmod(0o755)
    return bin_dir


# ---------------------------------------------------------------------------
# Ejecución del bootstrap
# ---------------------------------------------------------------------------

# Se limpian del entorno heredado: la hermeticidad no puede depender de que el
# desarrollador no tenga un PIPELINEK_* exportado en su sesión.
SCRUBBED_ENV = (
    "PIPELINEK_BOOTSTRAP_REPO_URL",
    "PIPELINEK_INSTALLER_SHA256",
    "PIPELINEK_INSTALLER_VERSION",
    "PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS",
    "PIPELINEK_REQUIRE_PINNED_INSTALLER",
    "PIPELINEK_HOME",
    "PIPELINEK_MIRROR_BASE_URL",
    "PIPELINEK_RELEASE_BASE_URL",
    "PIPELINEK_TEST_RECORD",
    "PIPELINEK_TEST_EXIT",
    "PIPELINEK_TEST_FETCH_WITNESS",
    # Un BASH_ENV del desarrollador se cargaría en el `sh` no interactivo y
    # podría cambiar el comportamiento bajo los pies del test.
    "BASH_ENV",
    "ENV",
)


def run_bootstrap(args: list[str], overrides: dict[str, str], path: str | None = None):
    env = dict(os.environ)
    for name in SCRUBBED_ENV:
        env.pop(name, None)
    env.update(overrides)
    if path is not None:
        env["PATH"] = path
    return subprocess.run(
        ["sh", str(BOOTSTRAP), *args],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
        stdin=subprocess.DEVNULL,
    )


def run_bootstrap_on_stdin(overrides: dict[str, str]):
    """Ejecuta el bootstrap por la entrada estándar: la forma `curl … | sh`."""
    env = dict(os.environ)
    for name in SCRUBBED_ENV:
        env.pop(name, None)
    env.update(overrides)
    return subprocess.run(
        ["sh"],
        input=BOOTSTRAP.read_text(),
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )


def tempdir(name: str) -> Path:
    return Path(tempfile.mkdtemp(prefix=f"curlboot-{name}-"))


# ---------------------------------------------------------------------------
# B1 - el bootstrap es POSIX puro
# ---------------------------------------------------------------------------

# Motivo de cada prohibición: son construcciones de bash o de GNU que un
# /bin/sh POSIX no garantiza, y cuya presencia aquí es exactamente el defecto
# que este bootstrap viene a arreglar.
BASH_ONLY = (
    (r"BASH_SOURCE", "leer el nombre propio de bash queda vacío por entrada estándar"),
    (r"\[\[", "condición doble entre corchetes"),
    (r"\bdeclare\s+-A\b", "array asociativo"),
    (r"\btypeset\s+-A\b", "array asociativo"),
    (r"\bpipefail\b", "tolerancia a fallos en tuberías, que no es POSIX"),
    (r"\bmapfile\b|\breadarray\b", "lectura de array"),
    (r"\$\{[^}]*,,|\$\{[^}]*\^\^", "conversión de mayúsculas/minúsculas"),
    (r"[<>]\(", "sustitución de procesos"),
    (r"\$\(<", "sustitución de procesos"),
    (r"&>", "redirección de ambos descriptores"),
    (r";;&", "continuación de `case`"),
    (r"(?m)^\s*(local|declare|typeset)\s+-a\b", "array indexado declarado"),
    (r"(?m)^\s*local\s+\w", "`local`, que no es POSIX"),
    (r"(?m)^\s*function\s+\w+", "palabra clave `function`"),
    (r"\bsource\s+", "`source`, que no es POSIX"),
    (r"(?m)^\s*echo\s+-[ne]", "`echo -e/-n`, que no es POSIX"),
)


def strip_shell_comments(source: str) -> str:
    """Quita las líneas que son comentario entero.

    Se usa SÓLO para buscar construcciones no POSIX: un comentario no cambia el
    comportamiento, y este fichero documenta a propósito las construcciones que
    el bootstrap no usa ("`[[ ]]`", "arrays asociativos"). Comprobar también la
    prosa obligaría a silenciar la única explicación de por qué existe el
    fichero. La comprobación de URLs, en cambio, se hace sobre el fichero
    ENTERO: ahí la prosa sí es una promesa que el siguiente lector copiará.
    """
    return "\n".join(
        line for line in source.splitlines() if not line.lstrip().startswith("#")
    )


def test_b1_bootstrap_is_pure_posix() -> None:
    source = BOOTSTRAP.read_text()
    code = strip_shell_comments(source)

    check(
        source.startswith("#!/bin/sh\n"),
        f"el bootstrap debe declarar #!/bin/sh, primera línea: {source.splitlines()[0]!r}",
    )
    check(
        os.access(BOOTSTRAP, os.X_OK),
        f"el bootstrap no tiene permiso de ejecución: {BOOTSTRAP}",
    )
    for pattern, reason in BASH_ONLY:
        match = re.search(pattern, code)
        check(
            match is None,
            f"construcción no POSIX en el bootstrap: {match.group(0) if match else ''!r} "
            f"({reason})",
        )


def test_b1b_shellcheck_passes_as_posix_sh() -> None:
    """Si hay shellcheck, el bootstrap tiene que pasar limpio como shell `sh`.

    Es la comprobación estática que sustituye a un intérprete POSIX real: en una
    máquina sin dash ni busybox no hay forma de ejecutar el fichero con un sh
    que no sea bash, y un bash aceptaría cualquiera de las construcciones que B1
    prohíbe. Se marca como SKIP, nunca como PASS, si la herramienta no está.
    """
    binary = shutil.which("shellcheck")
    if binary is None:
        raise Skip("shellcheck no está instalado en esta máquina")

    result = subprocess.run(
        [binary, "-s", "sh", str(BOOTSTRAP)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    check(
        result.returncode == 0,
        f"shellcheck -s sh no pasa (exit {result.returncode}):\n{result.stdout}{result.stderr}",
    )


# ---------------------------------------------------------------------------
# B2 - las cuatro variables de entorno del contrato
# ---------------------------------------------------------------------------


def test_b2_declares_the_four_contract_variables() -> None:
    source = BOOTSTRAP.read_text()
    required = (
        "PIPELINEK_INSTALLER_SHA256",
        "PIPELINEK_REQUIRE_PINNED_INSTALLER",
        "PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS",
        "PIPELINEK_INSTALLER_VERSION",
    )
    for name in required:
        check(name in source, f"falta la variable de entorno del contrato: {name}")
        # Documentada y leída: una variable que sólo aparece en un comentario
        # documenta una puerta que nadie ha implementado.
        occurrences = len(re.findall(re.escape(name), source))
        check(
            occurrences >= 2,
            f"{name} aparece una sola vez: documentada pero no usada como puerta real",
        )


def test_b2b_minimum_age_defaults_to_24_hours() -> None:
    """24h es el criterio que ya usa MISE_SELF_UPDATE_MINIMUM_RELEASE_AGE."""
    source = BOOTSTRAP.read_text()
    match = re.search(r"DEFAULT_MINIMUM_AGE_HOURS=(\d+)", source)
    check(match is not None, "no se declara DEFAULT_MINIMUM_AGE_HOURS")
    check(
        match.group(1) == str(DEFAULT_MINIMUM_AGE_HOURS),
        f"el umbral por defecto no es {DEFAULT_MINIMUM_AGE_HOURS}h: {match.group(1)}",
    )


# ---------------------------------------------------------------------------
# B3 - resolución por redirección, sin api.github.com, allowlist sin ampliar
# ---------------------------------------------------------------------------


def test_b3_latest_resolved_by_http_redirect() -> None:
    source = BOOTSTRAP.read_text()
    check(
        "api.github.com" not in source,
        "el bootstrap usa api.github.com; el contrato exige la redirección de "
        "/releases/latest, que no necesita ni token ni endpoint nuevo",
    )
    check(
        "/releases/latest" in source,
        "el bootstrap no resuelve 'latest' contra /releases/latest",
    )
    check(
        "header_value location" in source,
        "el bootstrap no lee la cabecera location de la redirección",
    )
    check(
        "fsSLI" in source,
        "el bootstrap no consulta la redirección con una petición de cabeceras (curl -fsSLI)",
    )


def test_b3b_allowlist_is_not_widened() -> None:
    """Todo host no-loopback citado por el bootstrap está en la allowlist.

    Se comprueba sobre TODO el fichero, no sólo sobre la constante: una
    URL de fallback escrita en un comentario sería igual de una ampliación en
    la práctica, porque el siguiente que lea el fichero la copiará.
    """
    source = BOOTSTRAP.read_text()
    allowed = {"github.com", "objects.githubusercontent.com"}

    hosts = set()
    for match in re.finditer(r"https?://([^\s/'\"$)]+)", source):
        host = match.group(1).split("@")[-1].split(":")[0]
        if host:
            hosts.add(host)

    check(hosts, "no se encontró ninguna URL absoluta en el bootstrap")
    check(
        hosts <= allowed,
        f"el bootstrap cita hosts fuera de la allowlist publicada {sorted(allowed)}: "
        f"{sorted(hosts - allowed)}",
    )
    check(
        "objects.githubusercontent.com" in source,
        "la allowlist debe conservar el destino real de la descarga de assets",
    )


# ---------------------------------------------------------------------------
# B4 - digest obligatorio en CI, y sin haber tocado la red
# ---------------------------------------------------------------------------


def test_b4_require_pinned_without_digest_aborts_before_any_fetch() -> None:
    work = tempdir("b4")
    try:
        witness = work / "fetch-witness.txt"
        stub_dir = fetcher_stub_dir(work)
        # El PATH lleva primero los dobles: si el bootstrap intentara descargar
        # algo, dejaría testigo. La ausencia del testigo es la prueba de que la
        # validación ocurre ANTES de cualquier efecto.
        path = f"{stub_dir}{os.pathsep}{os.environ.get('PATH', '')}"

        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_REQUIRE_PINNED_INSTALLER": "1",
                "PIPELINEK_TEST_FETCH_WITNESS": str(witness),
            },
            path=path,
        )
        combined = result.stdout + result.stderr

        check(
            result.returncode == EXIT_PIN_REQUIRED,
            f"se esperaba el caso 'digest no fijado' (exit {EXIT_PIN_REQUIRED}), "
            f"hubo {result.returncode}:\n{combined}",
        )
        check(
            "PIPELINEK_REQUIRE_PINNED_INSTALLER" in combined,
            f"el diagnóstico no nombra la variable que se exigía:\n{combined}",
        )
        check(
            "PIPELINEK_INSTALLER_SHA256" in combined,
            f"el diagnóstico no dice qué variable falta:\n{combined}",
        )
        check(
            not witness.exists(),
            "se intentó descargar algo antes de comprobar el digest exigido; "
            f"testigo: {witness.read_text() if witness.exists() else ''}",
        )
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B5 - digest fijado que no coincide
# ---------------------------------------------------------------------------


def test_b5_digest_mismatch_aborts_without_delegating() -> None:
    work = tempdir("b5")
    base, served, shutdown = serve_release(last_modified_epoch=None)
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                # Versión fijada: así la puerta de antigüedad no se aplica y lo
                # único que puede abortar es el digest.
                "PIPELINEK_INSTALLER_VERSION": TAG,
                "PIPELINEK_INSTALLER_SHA256": WRONG_SHA256,
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        assert_hermetic(result, base)

        check(
            result.returncode == EXIT_DIGEST_MISMATCH,
            f"se esperaba el caso 'digest incorrecto' (exit {EXIT_DIGEST_MISMATCH}), "
            f"hubo {result.returncode}:\n{combined}",
        )
        # Motivo, no sólo código de salida: una descarga fallida daría el mismo
        # código si el script estuviera mal escrito.
        check(
            WRONG_SHA256 in combined,
            f"el diagnóstico no enseña el digest esperado:\n{combined}",
        )
        check(
            installer_digest() in combined,
            f"el diagnóstico no enseña el digest observado:\n{combined}",
        )
        check(
            not record.exists(),
            "el instalador real se ejecutó pese al digest incorrecto",
        )
        check(
            ASSET_PATH in served,
            f"no se llegó a pedir el asset; rutas servidas: {served}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


def test_b5b_matching_digest_is_verified_before_delegating() -> None:
    """El camino del digest correcto verifica y SÍ delega."""
    work = tempdir("b5b")
    base, served, shutdown = serve_release(last_modified_epoch=None)
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_VERSION": TAG,
                "PIPELINEK_INSTALLER_SHA256": installer_digest(),
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        assert_hermetic(result, base)

        check(result.returncode == 0, f"la delegación debía tener éxito:\n{combined}")
        check(
            installer_digest() in combined,
            f"no se informó del digest verificado:\n{combined}",
        )
        check(record.exists(), "el instalador real no llegó a ejecutarse")
        check(record.read_text().splitlines() == ["install", "0.47.0"], "argumentos erróneos")
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B6 / B7 - la puerta de antigüedad
# ---------------------------------------------------------------------------


def test_b6_fresh_release_is_refused_by_the_age_gate() -> None:
    """Un asset de hace 0h no puede pasar un umbral de 24h."""
    work = tempdir("b6")
    published = now_epoch()
    base, served, shutdown = serve_release(last_modified_epoch=published)
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        assert_hermetic(result, base)

        check(
            result.returncode == EXIT_RELEASE_TOO_NEW,
            f"una release recién publicada debe abortar con {EXIT_RELEASE_TOO_NEW}, "
            f"hubo {result.returncode}:\n{combined}",
        )
        # La fecha observada viene de la cabecera que envió el servidor: si el
        # bootstrap la fabricara, esta comprobación no podría pasar. Prueba que
        # la señal medida es la de la respuesta y no una suposición.
        check(
            http_date(published) in combined,
            f"el diagnóstico no enseña la fecha que declaró la respuesta:\n{combined}",
        )
        check(
            f"umbral {DEFAULT_MINIMUM_AGE_HOURS}h" in combined,
            f"el diagnóstico no menciona el umbral aplicado:\n{combined}",
        )
        check(not record.exists(), "una release demasiado nueva llegó a delegar")
        check(
            LATEST_PATH in served,
            f"no se resolvió 'latest' por redirección; rutas: {served}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


def test_b6b_age_threshold_is_honoured() -> None:
    """Con un umbral de 0h, la misma release pasa: la puerta lee la variable."""
    work = tempdir("b6b")
    base, served, shutdown = serve_release(last_modified_epoch=now_epoch())
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS": "0",
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        check(
            result.returncode == 0,
            f"con umbral 0h la release debe pasar la puerta:\n{combined}",
        )
        check(record.exists(), "no se delegó tras pasar la puerta")
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


def test_b7_missing_last_modified_fails_closed() -> None:
    """Sin señal de antigüedad, la puerta ABORTA: no puede asumir nada.

    Este es el caso que distingue una puerta de verdad de un aviso: el
    comportamiento por defecto ante la duda es negarse.
    """
    work = tempdir("b7")
    base, served, shutdown = serve_release(last_modified_epoch=None)
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        assert_hermetic(result, base)

        check(
            result.returncode == EXIT_AGE_UNVERIFIABLE,
            f"sin last-modified debe abortar con {EXIT_AGE_UNVERIFIABLE}, hubo "
            f"{result.returncode}:\n{combined}",
        )
        check(
            "last-modified" in combined.lower(),
            f"el diagnóstico no nombra la señal que faltaba:\n{combined}",
        )
        check(
            not record.exists(),
            "sin señal de antigüedad se delegó igualmente",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B8 - extremo a extremo sin versión fijada
# ---------------------------------------------------------------------------


def test_b8_end_to_end_resolves_verifies_delegates_and_propagates_exit_code() -> None:
    """El camino completo, con la release fijada por el servidor de stub.

    Se cubren de una vez las tres cosas que sólo se ven juntas: la redirección
    resuelve el nombre del asset, el digest se verifica antes de ejecutar, y el
    código de salida del instalador real llega intacto al operador (aquí, 7).
    """
    work = tempdir("b8")
    two_days_ago = NOW - 48 * 3600
    base, served, shutdown = serve_release(last_modified_epoch=two_days_ago)
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_SHA256": installer_digest(),
                "PIPELINEK_TEST_RECORD": str(record),
                "PIPELINEK_TEST_EXIT": "7",
            },
        )
        combined = result.stdout + result.stderr
        assert_hermetic(result, base)

        check(
            result.returncode == 7,
            f"el código del instalador real debe propagarse sin traducir; hubo "
            f"{result.returncode}:\n{combined}",
        )
        check(record.exists(), "el instalador real no se ejecutó")
        check(
            record.read_text().splitlines() == ["install", "0.47.0"],
            "los argumentos no llegaron verbatim al instalador",
        )
        # La resolución por redirección, probada por sus efectos: el asset se
        # pidió con la etiqueta que salió de la cabecera location.
        check(LATEST_PATH in served, f"no se consultó /releases/latest; rutas: {served}")
        check(TAG_PATH in served, f"no se siguió la redirección; rutas: {served}")
        check(ASSET_PATH in served, f"no se pidió el asset resuelto; rutas: {served}")
        check(
            installer_digest() in combined,
            f"no se verificó el digest antes de delegar:\n{combined}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B9 - con versión fijada, la puerta de antigüedad no aplica
# ---------------------------------------------------------------------------


def test_b9_pinned_version_skips_the_age_gate() -> None:
    """Decisión de diseño fijada: elegir versión es una decisión explícita.

    Con la versión fijada la autoridad es el digest, y una release de hace 0h que
    el operador ha pedido nominalmente no debe ser bloqueada por una puerta que
    existe para las versiones que el bootstrap elige solo. Es el mismo criterio
    que `mise` aplica con MISE_VERSION.
    """
    work = tempdir("b9")
    base, served, shutdown = serve_release(last_modified_epoch=now_epoch())
    record = work / "record.txt"
    try:
        result = run_bootstrap(
            ["install", "0.47.0"],
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_VERSION": TAG,
                "PIPELINEK_INSTALLER_SHA256": installer_digest(),
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        check(
            result.returncode == 0,
            f"una versión fijada no debe pasar por la puerta de antigüedad:\n{combined}",
        )
        check(record.exists(), "no se delegó tras la versión fijada")
        check(
            LATEST_PATH not in served,
            f"con versión fijada no hay que resolver 'latest'; rutas: {served}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


def test_b9b_pinned_version_is_validated_before_being_used() -> None:
    work = tempdir("b9b")
    try:
        for bad in ("1.2.3/../otro", "v1.2.3 con espacio", "latest", "v1.2"):
            result = run_bootstrap(
                ["install", "0.47.0"],
                {
                    "PIPELINEK_INSTALLER_VERSION": bad,
                    "PIPELINEK_BOOTSTRAP_REPO_URL": CANONICAL_REPO_URL,
                },
            )
            combined = result.stdout + result.stderr
            check(
                result.returncode == EXIT_USAGE,
                f"la etiqueta fijada {bad!r} debería rechazarse antes de nada "
                f"(exit {EXIT_USAGE}), hubo {result.returncode}:\n{combined}",
            )
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B10 - uso sin argumentos y delegación verbatim
# ---------------------------------------------------------------------------


def test_b10_without_arguments_prints_usage() -> None:
    result = run_bootstrap([], {})
    combined = result.stdout + result.stderr
    check(
        result.returncode == EXIT_USAGE,
        f"sin argumentos se esperaba exit {EXIT_USAGE}, hubo {result.returncode}:\n{combined}",
    )
    check(
        "install-pipelinek.sh" in combined,
        f"el uso no dice a qué instalador delega:\n{combined}",
    )
    for name in (
        "PIPELINEK_INSTALLER_SHA256",
        "PIPELINEK_REQUIRE_PINNED_INSTALLER",
        "PIPELINEK_INSTALLER_MINIMUM_AGE_HOURS",
        "PIPELINEK_INSTALLER_VERSION",
    ):
        check(name in combined, f"el uso no documenta {name}:\n{combined}")


def test_b10b_user_arguments_are_delegated_verbatim() -> None:
    """Un argumento con espacios sigue siendo UN argumento al llegar abajo."""
    work = tempdir("b10b")
    base, served, shutdown = serve_release(last_modified_epoch=None)
    record = work / "record.txt"
    args = ["install", "0.47.0", "--opt", "valor con espacios", "siguiente"]
    try:
        result = run_bootstrap(
            args,
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_VERSION": TAG,
                "PIPELINEK_INSTALLER_SHA256": installer_digest(),
                "PIPELINEK_TEST_RECORD": str(record),
            },
        )
        combined = result.stdout + result.stderr
        check(result.returncode == 0, f"la delegación debía tener éxito:\n{combined}")
        check(
            record.read_text().splitlines() == args,
            f"los argumentos no llegaron con sus fronteras intactas: {record.read_text()!r}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B11 - el aviso del PATH es idempotente
# ---------------------------------------------------------------------------


def test_b11_path_hint_is_idempotent() -> None:
    work = tempdir("b11")
    home = work / "home"
    bin_dir = home / "current" / "bin"
    base, served, shutdown = serve_release(last_modified_epoch=None)
    try:
        common = {
            "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
            "PIPELINEK_INSTALLER_VERSION": TAG,
            "PIPELINEK_INSTALLER_SHA256": installer_digest(),
            "PIPELINEK_HOME": str(home),
            "PIPELINEK_TEST_RECORD": str(work / "record.txt"),
        }

        absent = run_bootstrap(
            ["doctor"], dict(common), path=os.environ.get("PATH", "")
        )
        combined = absent.stdout + absent.stderr
        check(absent.returncode == 0, f"la delegación debía tener éxito:\n{combined}")
        check(
            f'export PATH="{bin_dir}:$PATH"' in combined,
            f"sin el directorio en el PATH debe indicar cómo añadirlo:\n{combined}",
        )

        present = run_bootstrap(
            ["doctor"],
            dict(common),
            path=f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}",
        )
        combined = present.stdout + present.stderr
        check(present.returncode == 0, f"la delegación debía tener éxito:\n{combined}")
        check(
            "export PATH=" not in combined,
            f"el aviso del PATH se repitió aunque el directorio ya estaba:\n{combined}",
        )
        check(
            "PATH" in combined,
            f"no se informó de que ya estaba en el PATH:\n{combined}",
        )

        # Un PATH que contiene un directorio cuyo nombre EXTIENDE al del
        # instalador no lo contiene. Es la diferencia real entre `grep -Fxq` y
        # un `grep -q` de subcadena, y la razón de que el patrón opencode sea
        # el de coincidencia exacta de línea.
        prefixed = run_bootstrap(
            ["doctor"],
            dict(common),
            path=f"{bin_dir}-extra{os.pathsep}{os.environ.get('PATH', '')}",
        )
        combined = prefixed.stdout + prefixed.stderr
        check(prefixed.returncode == 0, f"la delegación debía tener éxito:\n{combined}")
        check(
            f'export PATH="{bin_dir}:$PATH"' in combined,
            f"un directorio con el mismo prefijo se tomó por el del instalador:\n{combined}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# B12 - la forma que hoy falla: el script llega por la entrada estándar
# ---------------------------------------------------------------------------


def test_b12_runs_when_the_script_arrives_on_stdin() -> None:
    """`curl … | sh` es el caso que este bootstrap viene a arreglar.

    Se ejecuta el bootstrap por la entrada estándar, contra el mismo stub de
    loopback, y se exige la delegación completa. Si el script consumiera su
    propia entrada estándar (un `read`, un `curl` sin `</dev/null`), el
    instalador real no llegaría a ejecutarse y este test lo vería.
    """
    work = tempdir("b12")
    two_days_ago = NOW - 48 * 3600
    base, served, shutdown = serve_release(last_modified_epoch=two_days_ago)
    record = work / "record.txt"
    try:
        env = dict(os.environ)
        for name in SCRUBBED_ENV:
            env.pop(name, None)
        env.update(
            {
                "PIPELINEK_BOOTSTRAP_REPO_URL": repo_url(base),
                "PIPELINEK_INSTALLER_SHA256": installer_digest(),
                "PIPELINEK_TEST_RECORD": str(record),
                "PIPELINEK_TEST_EXIT": "0",
            }
        )
        result = subprocess.run(
            # El programa llega por la tubería; los argumentos van en la línea
            # de órdenes, que es exactamente lo que hace `curl … | sh doctor`.
            ["sh", "-s", "doctor"],
            input=BOOTSTRAP.read_text(),
            capture_output=True,
            text=True,
            env=env,
            timeout=60,
        )
        combined = result.stdout + result.stderr
        check(
            result.returncode == 0,
            f"el bootstrap debe funcionar por entrada estándar (esto es `curl | sh`):\n{combined}",
        )
        check(
            record.exists() and record.read_text().splitlines() == ["doctor"],
            f"la instalación no llegó a delegar por la entrada estándar:\n{combined}",
        )
    finally:
        shutdown()
        shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


def main() -> int:
    if not BOOTSTRAP.is_file():
        print(f"ERROR: bootstrap no encontrado en {BOOTSTRAP}", file=sys.stderr)
        return 2

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_b")]
    failures = []
    skipped = []
    for test in tests:
        name = test.__name__
        try:
            test()
        except Skip as exc:
            skipped.append((name, str(exc)))
            print(f"SKIP {name}\n      {exc}")
        except Exception as exc:  # noqa: BLE001 - informa de cada fallo
            failures.append((name, exc))
            print(f"FAIL {name}\n      {exc}")
        else:
            print(f"ok   {name}")

    passed = len(tests) - len(failures) - len(skipped)
    print(f"\n{passed}/{len(tests)} passed" + (f", {len(skipped)} skipped" if skipped else ""))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())