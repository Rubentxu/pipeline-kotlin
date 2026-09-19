#!/usr/bin/env python3
"""Minimal SDKMAN protocol mirror for spike WU-LPR-090.

Serves only the endpoints required by `sdk install pipelinek 0.39.0` and
`sdk list pipelinek` against the real SDKMAN client (sdkman-cli 5.23.0).

Endpoints served:
  GET /healthcheck
  GET /candidates/list
  GET /candidates/default/<candidate>
  GET /candidates/validate/<candidate>/<version>/<platform>
  GET /candidates/<candidate>/<platform>/versions/list?current=&installed=
  GET /download/<candidate>/<version>/<platform>
  GET /hooks/post/<candidate>/<version>/<platform>

The served archive is the certified ZIP for pipelinek 0.39.0
(SHA-256 385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8).

The hook script transforms the served '.bin' to a '.zip' by copying
bytes: the served artifact IS already the canonical ZIP.

The mirror does NOT authenticate, does NOT persist, listens ONLY on
127.0.0.1, and is bound to the lifetime of this process. It is a
fixture for the spike, not a candidate for deployment.
"""
from __future__ import annotations

import argparse
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# pipelinek 0.39.0 canonical ZIP
CANONICAL_ZIP_PATH = os.environ.get(
    "WU_LPR_090_ZIP",
    "/home/rubentxu/.local/share/jcode/wu-lpr-090/pipelinek-0.39.0.zip",
)
EXPECTED_SHA256 = "385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8"

CANDIDATE = "pipelinek"
VERSION = "0.39.0"
PLATFORM = "UNIVERSAL"


def sha256_of(path: str) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def assert_canonical_zip() -> None:
    if not os.path.isfile(CANONICAL_ZIP_PATH):
        print(f"FATAL: canonical ZIP not found at {CANONICAL_ZIP_PATH}", file=sys.stderr)
        sys.exit(2)
    actual = sha256_of(CANONICAL_ZIP_PATH)
    if actual != EXPECTED_SHA256:
        print(f"FATAL: ZIP SHA mismatch.\n  expected {EXPECTED_SHA256}\n  actual   {actual}", file=sys.stderr)
        sys.exit(2)
    print(f"OK: canonical ZIP verified at {CANONICAL_ZIP_PATH}", file=sys.stderr)
    print(f"  sha256 = {actual}", file=sys.stderr)


class SdkmanMirrorHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # concise stderr logging
        sys.stderr.write("[mirror] %s - %s\n" % (self.address_string(), format % args))

    def _send(self, status: int, body: bytes, headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        base = {"Content-Length": str(len(body)), "Cache-Control": "no-store"}
        if headers:
            base.update(headers)
        for k, v in base.items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        path = self.path
        # Strip query
        if "?" in path:
            path = path.split("?", 1)[0]

        if path == "/healthcheck":
            return self._send(200, b"ok")

        if path == "/candidates/list":
            return self._send(200, b"pipelinek\n")

        if path == f"/candidates/default/{CANDIDATE}":
            return self._send(200, VERSION.encode())

        if path == f"/candidates/validate/{CANDIDATE}/{VERSION}/{PLATFORM}":
            return self._send(200, b"valid")

        if path == f"/candidates/{CANDIDATE}/{PLATFORM}/versions/list":
            # SDKMAN expects: "  0.39.0\n > ...\n  * installed...\n" format
            body = (
                "--------------------------------------------------------------------------------\n"
                f"  {VERSION}\n"
                "--------------------------------------------------------------------------------\n"
                " * - installed                                                                   \n"
                "> - currently in use                                                            \n"
                "--------------------------------------------------------------------------------\n"
            )
            return self._send(200, body.encode())

        if path == f"/download/{CANDIDATE}/{VERSION}/{PLATFORM}":
            with open(CANONICAL_ZIP_PATH, "rb") as f:
                data = f.read()
            # SDKMAN reads these headers (see __sdkman_checksum_zip)
            headers = {
                "X-Sdkman-Checksum-Sha256": EXPECTED_SHA256,
                "Content-Type": "application/zip",
            }
            return self._send(200, data, headers)

        if path == f"/hooks/post/{CANDIDATE}/{VERSION}/{PLATFORM}":
            # Hook: define __sdkman_post_installation_hook which transforms
            # the served '.bin' into the final '.zip'. The SDKMAN client
            # sources this script then calls the function explicitly.
            # The hook MUST NOT contain `exit` (it would kill the caller
            # shell, since it is sourced, not executed). The SDKMAN client
            # convention is: declare __sdkman_post_installation_hook only.
            hook = (
                "#!/usr/bin/env bash\n"
                "# WU-LPR-090 mirror hook: identity transform (.bin is the .zip).\n"
                "function __sdkman_post_installation_hook() {\n"
                "    cp -f \"${binary_input}\" \"${zip_output}\"\n"
                "}\n"
            )
            return self._send(200, hook.encode(), {"Content-Type": "text/x-shellscript"})

        # Unknown
        self._send(404, b"not found")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=9999)
    args = ap.parse_args()

    assert_canonical_zip()

    httpd = ThreadingHTTPServer((args.bind, args.port), SdkmanMirrorHandler)
    sys.stderr.write(f"WU-LPR-090 mirror listening on http://{args.bind}:{args.port}\n")
    sys.stderr.write(f"  candidate={CANDIDATE}  version={VERSION}  platform={PLATFORM}\n")
    sys.stderr.flush()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write("\nmirror stopped.\n")


if __name__ == "__main__":
    main()
