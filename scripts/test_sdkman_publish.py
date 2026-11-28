#!/usr/bin/env python3
"""Contract test of scripts/release/sdkman-publish.sh against a loopback stub.

Nothing leaves the machine: every endpoint the script uses is overridden to a
local server. A canary token stands in for the credentials and the test asserts
where it may and may not appear.
"""
import hashlib
import http.server
import os
import re
import secrets
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "release" / "sdkman-publish.sh"
# Random per run so no other process (a shell echoing this file, say) carries it.
CANARY_KEY = "canary-key-" + secrets.token_hex(8)
CANARY_TOKEN = "canary-token-" + secrets.token_hex(8)
VERSION = "0.0.1"
ZIP_BYTES = b"not a real archive"
HOLD_SECONDS = 2.0


class Stub(http.server.ThreadingHTTPServer):
    def __init__(self, fail_release):
        super().__init__(("127.0.0.1", 0), Handler)
        self.fail_release = fail_release
        self.release_headers = None
        self.release_body = None
        self.holding = threading.Event()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, body, ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/SHA256SUMS"):
            digest = hashlib.sha256(ZIP_BYTES).hexdigest()
            self._send(200, f"{digest}  dist/pipelinek-{VERSION}.zip\n".encode())
        elif self.path.endswith(".zip"):
            self._send(200, ZIP_BYTES)
        elif self.path == "/candidate":
            self._send(200, f'{{"versions":[{{"version":"{VERSION}"}}]}}'.encode(),
                       "application/json")
        else:
            self._send(404, b"no")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.server.release_body = self.rfile.read(length)
        self.server.release_headers = dict(self.headers.items())
        # Keep the connection open so curl is alive while the test samples argv.
        self.server.holding.set()
        time.sleep(HOLD_SECONDS)
        if self.server.fail_release:
            self._send(500, b"refused")
        else:
            self._send(200, b'{"status":"ok"}', "application/json")


def argv_leaks_canary(stop):
    """Scan every process's argv until `stop` is set; return a leaking argv."""
    leaks = []
    while not stop.is_set():
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                argv = (entry / "cmdline").read_bytes().decode("utf-8", "replace")
            except OSError:
                continue
            if CANARY_KEY in argv or CANARY_TOKEN in argv:
                leaks.append(argv.replace("\0", " "))
        time.sleep(0.02)
    return leaks


def run_script(script, fail_release, shell_args=()):
    stub = Stub(fail_release)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{stub.server_port}"
    with tempfile.TemporaryDirectory() as tmp:
        scratch = Path(tmp) / "scratch"
        scratch.mkdir()
        env = {
            "PATH": os.environ["PATH"],
            "HOME": tmp,
            "TMPDIR": str(scratch),
            "SDKMAN_CONSUMER_KEY": CANARY_KEY,
            "SDKMAN_CONSUMER_TOKEN": CANARY_TOKEN,
            "PIPELINEK_RELEASE_BASE_URL": base + "/dl",
            "PIPELINEK_SDKMAN_RELEASE_ENDPOINT": base + "/release",
            "PIPELINEK_SDKMAN_CANDIDATE_ENDPOINT": base + "/candidate",
        }
        stop = threading.Event()
        leaks = []
        sampler = threading.Thread(target=lambda: leaks.extend(argv_leaks_canary(stop)))
        sampler.start()
        try:
            proc = subprocess.run(["bash", *shell_args, str(script), VERSION], env=env,
                                  capture_output=True, text=True, timeout=60)
        finally:
            stop.set()
            sampler.join()
            stub.shutdown()
            stub.server_close()
        return {
            "proc": proc,
            "argv_leaks": leaks,
            "headers": stub.release_headers,
            "body": stub.release_body,
            "scratch_left": sorted(p.name for p in scratch.iterdir()),
        }


def mutant_with_argv_headers(directory):
    """The script as it was: credentials as `-H` arguments of curl."""
    text = SCRIPT.read_text(encoding="utf-8")
    config_form = "curl --config - -fsS"
    assert config_form in text, "the script no longer has the stdin-config form"
    argv_form = ('curl -H "Consumer-Key: ${SDKMAN_CONSUMER_KEY}" '
                 '-H "Consumer-Token: ${SDKMAN_CONSUMER_TOKEN}" -fsS')
    mutant = Path(directory) / "release"
    mutant.mkdir()
    shutil_copy = SCRIPT.parent / "resolve-release-digest.sh"
    (mutant / "resolve-release-digest.sh").write_bytes(shutil_copy.read_bytes())
    path = mutant / "sdkman-publish.sh"
    path.write_text(text.replace(config_form, argv_form), encoding="utf-8")
    return path


class SdkmanPublishCredentialsTest(unittest.TestCase):
    def assert_no_canary_in_output(self, result):
        for stream in (result["proc"].stdout, result["proc"].stderr):
            self.assertNotIn(CANARY_KEY, stream)
            self.assertNotIn(CANARY_TOKEN, stream)

    def test_headers_reach_the_server_and_never_the_argv(self):
        result = run_script(SCRIPT, fail_release=False)
        self.assertEqual(0, result["proc"].returncode, result["proc"].stderr)
        self.assertEqual(CANARY_KEY, result["headers"].get("Consumer-Key"))
        self.assertEqual(CANARY_TOKEN, result["headers"].get("Consumer-Token"))
        self.assertEqual("application/json", result["headers"].get("Content-Type"))
        self.assertIn(f'"version":   "{VERSION}"', result["body"].decode())
        self.assertEqual([], result["argv_leaks"])
        self.assert_no_canary_in_output(result)
        self.assertIn("version visible: True", result["proc"].stdout)

    def test_xtrace_does_not_print_the_credentials(self):
        result = run_script(SCRIPT, fail_release=False, shell_args=("-x",))
        self.assertEqual(0, result["proc"].returncode, result["proc"].stderr)
        self.assertEqual([], result["argv_leaks"])
        self.assert_no_canary_in_output(result)

    def test_a_refused_publication_fails_and_leaves_nothing_behind(self):
        result = run_script(SCRIPT, fail_release=True, shell_args=("-x",))
        self.assertNotEqual(0, result["proc"].returncode)
        self.assertEqual([], result["argv_leaks"])
        self.assert_no_canary_in_output(result)
        self.assertEqual([], result["scratch_left"])

    def test_mutant_with_header_argv_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = run_script(mutant_with_argv_headers(tmp), fail_release=False)
        self.assertEqual(CANARY_TOKEN, result["headers"].get("Consumer-Token"))
        self.assertTrue(result["argv_leaks"],
                        "the canary was not seen in argv: the oracle is blind")

    def test_a_version_that_is_not_a_release_version_is_refused(self):
        env = {"PATH": os.environ["PATH"], "SDKMAN_CONSUMER_KEY": "k",
               "SDKMAN_CONSUMER_TOKEN": "t"}
        for bad in ("1.0.0'+exit()+'", "1.0", "$(id)", "1.0.0 2"):
            proc = subprocess.run(["bash", str(SCRIPT), bad], env=env,
                                  capture_output=True, text=True, timeout=30)
            self.assertEqual(2, proc.returncode, bad)
            self.assertRegex(proc.stderr, re.escape("is not a release version"))


if __name__ == "__main__":
    unittest.main()
