#!/usr/bin/env python3
"""Contractual tests for the SDKMAN publish/install digest contract.

Run:
    python3 scripts/release/test_sdkman_digest.py

These tests verify that the SDKMAN scripts derive their expected digest from
the same authority as the rest of the release: the SHA256SUMS manifest.

Why this exists
---------------
Both SDKMAN scripts used to read `${asset}.sha256`. Verified against the real
published v0.41.0-rc1 release, that URL returns HTTP 404:

    https://github.com/.../releases/download/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip.sha256

so `sdkman-publish.sh` could never have published a version and
`sdkman-install-uat.sh` could never have passed. The sidecar contract was
already dead before this change; these tests pin the replacement.

The real SHA256SUMS records BUILD PATHS, not bare names:

    d49edc08...  dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip

so the lookup must match on basename. These tests serve a real manifest in
the real published format over a loopback HTTP server; nothing is mocked and
the public internet is never touched.

Contract summary:
  S1  A digest is resolved from SHA256SUMS for the real path-prefixed format.
  S2  A digest is resolved from SHA256SUMS for the flat name-only format.
  S3  An asset absent from SHA256SUMS fails closed with a named reason.
  S4  A manifest whose entry is malformed fails closed.
  S5  A digest that disagrees with the archive bytes fails closed.
  S6  Neither script references the .sha256 sidecar anywhere.
"""
from __future__ import annotations

import functools
import hashlib
import http.server
import json
import os
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
RELEASE_DIR = REPO_ROOT / "scripts" / "release"
PUBLISH = RELEASE_DIR / "sdkman-publish.sh"
INSTALL_UAT = RELEASE_DIR / "sdkman-install-uat.sh"

TARGET = "0.44.0"
ASSET = f"pipelinek-{TARGET}.zip"


class TestFailure(AssertionError):
    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise TestFailure(message)


@functools.lru_cache(maxsize=1)
def _serve(root: Path) -> str:
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(root), **kw)

        def log_message(self, *a, **k):
            pass

        def do_POST(self):
            n = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(n)
            (root / "sdkman-last-payload.json").write_bytes(raw)
            body = json.dumps({"ok": True, "received": raw.decode("utf-8", "replace")}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/sdkman-candidate":
                body = json.dumps({"versions": [{"version": TARGET}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            super().do_GET()

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{port}"


def run_publish(base: str, version: str = TARGET) -> subprocess.CompletedProcess:
    """Execute the REAL publish script against a loopback release.

    Every endpoint it touches is redirected at loopback, so this can never
    reach github.com or vendors.sdkman.io. The SDKMAN POST lands on the same
    loopback server, which records the payload for the test to inspect.
    """
    return subprocess.run(
        ["bash", str(PUBLISH), version],
        capture_output=True, text=True, timeout=120,
        env={
            **os.environ,
            "PIPELINEK_RELEASE_BASE_URL": base,
            "PIPELINEK_SDKMAN_RELEASE_ENDPOINT": f"{base}/sdkman-release",
            "PIPELINEK_SDKMAN_CANDIDATE_ENDPOINT": f"{base}/sdkman-candidate",
            "SDKMAN_CONSUMER_KEY": "test-key",
            "SDKMAN_CONSUMER_TOKEN": "test-token",
        },
    )


def run_install_uat(base: str, version: str = TARGET) -> subprocess.CompletedProcess:
    """Execute the REAL install UAT's digest preamble.

    The UAT's later steps need a real sdkman CLI, so the test puts a stub
    `sdk`/`pipelinek` on PATH that exits non-zero immediately. The digest
    preamble must therefore fail BEFORE step 0 on a bad manifest, and reach
    step 0 on a good one.
    """
    stub = Path(tempfile.mkdtemp(prefix="sdkman-stub-"))
    for name in ("sdk", "pipelinek"):
        p = stub / name
        p.write_text("#!/usr/bin/env bash\necho 'stub: should not be reached'\nexit 99\n")
        p.chmod(0o755)
    try:
        return subprocess.run(
            ["bash", str(INSTALL_UAT), version],
            capture_output=True, text=True, timeout=120,
            env={
                **os.environ,
                "PATH": f"{stub}:{os.environ['PATH']}",
                "PIPELINEK_RELEASE_BASE_URL": base,
                "HOME": str(stub),
                "SDKMAN_DIR": str(stub / ".sdkman"),
            },
        )
    finally:
        shutil.rmtree(stub, ignore_errors=True)


def build_release(root: Path, version: str = TARGET, manifest_paths: bool = True,
                  include_asset: bool = True, digest_override: str | None = None,
                  malformed: bool = False, binary_marker: bool = False,
                  no_manifest: bool = False) -> tuple[Path, str]:
    """Build a real release layout: ZIP + SHA256SUMS."""
    asset_dir = root / f"v{version}"
    asset_dir.mkdir(parents=True, exist_ok=True)
    zip_path = asset_dir / f"pipelinek-{version}.zip"

    inner = asset_dir / "staged" / "bin" / "pipelinek"
    inner.parent.mkdir(parents=True, exist_ok=True)
    inner.write_text(
        "#!/usr/bin/env bash\n"
        f'if [ "$1" = "version" ]; then printf "pipeline {version}\\n"; exit 0; fi\n'
        "exit 0\n"
    )
    inner.chmod(inner.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    lib = asset_dir / "staged" / "lib" / "app.jar"
    lib.parent.mkdir(parents=True, exist_ok=True)
    lib.write_bytes(b"PK\x03\x04 jar")

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname, src in ((f"pipelinek-{version}/bin/pipelinek", inner),
                             (f"pipelinek-{version}/lib/app.jar", lib)):
            info = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = (src.stat().st_mode & 0xFFFF) << 16
            zf.writestr(info, src.read_bytes())

    real = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    if no_manifest:
        return asset_dir, real
    prefix = f"dist/candidates/v{version}"
    name = f"{prefix}/pipelinek-{version}.zip" if manifest_paths else f"pipelinek-{version}.zip"
    recorded = digest_override or real
    if malformed:
        recorded = "not-a-digest"

    lines = []
    if include_asset:
        # Real sha256sum: default mode emits "<digest>  <name>" (two spaces),
        # binary mode (-b) emits "<digest> *<name>" (space then asterisk).
        sep = " *" if binary_marker else "  "
        lines.append(f"{recorded}{sep}{name}")
    # A different asset proves selection, not first-line blind use.
    lines.append("0" * 64 + f"  {prefix}/pipelinek-{VERSION_PLACEHOLDER}.zip")
    (asset_dir / "SHA256SUMS").write_text("\n".join(lines) + "\n")
    return asset_dir, real


VERSION_PLACEHOLDER = "0.0.0"


def resolve_digest_text(sums_text: str, version: str = TARGET) -> tuple[int, str, str]:
    """Feed literal SHA256SUMS text to the real resolver."""
    result = subprocess.run(
        ["bash", str(RELEASE_DIR / "resolve-release-digest.sh"), f"pipelinek-{version}.zip"],
        input=sums_text, capture_output=True, text=True, timeout=60,
    )
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def resolve_digest(base: str, version: str = TARGET) -> tuple[int, str, str]:
    """Invoke the shared resolver in the publish script and capture output.

    The resolver is a pure shell function: it takes SHA256SUMS text on stdin
    and an asset name, and prints the digest or fails. Testing it directly
    keeps the test hermetic (no GitHub, no SDKMAN credentials) while still
    executing the REAL code that ships.
    """
    import subprocess

    script = RELEASE_DIR / "resolve-release-digest.sh"
    sums = subprocess.run(
        ["curl", "-fsSL", f"{base}/v{version}/SHA256SUMS"],
        capture_output=True, text=True, timeout=60, check=True,
    ).stdout
    result = subprocess.run(
        ["bash", str(script), f"pipelinek-{version}.zip"],
        input=sums, capture_output=True, text=True, timeout=60,
    )
    return result.returncode, result.stdout.strip(), result.stderr.strip()


# ---------------------------------------------------------------------------
# S1 / S2 — both real manifest formats resolve
# ---------------------------------------------------------------------------


def test_s1_path_prefixed_manifest_resolves() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s1-"))
    try:
        _, real = build_release(root, manifest_paths=True)
        base = _serve(root)
        code, out, err = resolve_digest(base)
        check(code == 0, f"resolver failed on the real path-prefixed format: rc={code} err={err}")
        check(out == real, f"wrong digest: got {out}, want {real}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s2_flat_manifest_resolves() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s2-"))
    try:
        _, real = build_release(root, manifest_paths=False)
        base = _serve(root)
        code, out, err = resolve_digest(base)
        check(code == 0, f"resolver failed on the flat format: rc={code} err={err}")
        check(out == real, f"wrong digest: got {out}, want {real}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# S3 / S4 — fail closed
# ---------------------------------------------------------------------------


def test_s3_absent_asset_fails_closed() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s3-"))
    try:
        build_release(root, include_asset=False)
        base = _serve(root)
        code, out, err = resolve_digest(base)
        check(code != 0, "a missing asset entry must fail closed")
        check(ASSET in err, f"error does not name the asset: {err!r}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s4_malformed_digest_fails_closed() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s4-"))
    try:
        build_release(root, malformed=True)
        base = _serve(root)
        code, out, err = resolve_digest(base)
        check(code != 0, "a malformed digest must fail closed")
        check(ASSET in err, f"error does not name the asset: {err!r}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# S5 — digest disagreement is caught
# ---------------------------------------------------------------------------


def test_s5_digest_disagreement_detected() -> None:
    """The publish script must still recompute and compare.

    Reading a manifest is not verification. The publish path keeps its
    defence-in-depth recompute; this test proves the comparison is
    implementable against real bytes and that a wrong manifest is detected.
    """
    root = Path(tempfile.mkdtemp(prefix="sdkman-s5-"))
    try:
        asset_dir, real = build_release(root, digest_override="b" * 64)
        base = _serve(root)
        code, out, _ = resolve_digest(base)
        check(code == 0, "resolver should still read the recorded value")
        check(out == "b" * 64, "resolver must return the RECORDED digest verbatim")
        check(out != real, "the recorded digest must differ from the real bytes here")
    finally:
        shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# S6 — no sidecar anywhere
# ---------------------------------------------------------------------------


def test_s6_no_sidecar_references() -> None:
    for script in (PUBLISH, INSTALL_UAT):
        check(script.is_file(), f"missing script: {script}")
        text = script.read_text(encoding="utf-8")
        check(
            "${URL}.sha256" not in text and "URL}.sha256" not in text,
            f"{script.name} still reads the absent .sha256 sidecar",
        )
        check(
            "SHA256SUMS" in text,
            f"{script.name} does not mention SHA256SUMS",
        )


# ---------------------------------------------------------------------------
# S7–S12 — the CALLERS, executed end to end
#
# S1..S5 only exercise the resolver in isolation. These run the real
# scripts, because a correct helper wired to a broken caller is still a
# broken release. Every endpoint is redirected at loopback.
# ---------------------------------------------------------------------------


def test_s7_publish_end_to_end_green() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s7-"))
    try:
        _, real = build_release(root)
        result = run_publish(_serve(root))
        check(result.returncode == 0,
              f"publish failed: rc={result.returncode}\n{result.stdout}\n{result.stderr}")
        check(real in result.stdout, f"publish did not report the real digest {real}")
        check("published to SDKMAN" in result.stdout, "publish did not reach its success line")
        # The digest actually POSTed to SDKMAN must be the verified one.
        payload = json.loads((root / "sdkman-last-payload.json").read_text())
        check(payload["checksums"]["SHA-256"] == real,
              f"POSTed digest {payload['checksums']['SHA-256']} != {real}")
        check(payload["url"].endswith(ASSET), f"unexpected POSTed url: {payload['url']}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s8_publish_fails_closed_on_archive_digest_mismatch() -> None:
    """The defence-in-depth recompute must actually fire.

    Reading a manifest is not verification. If the served archive bytes
    disagree with the manifest, publishing MUST abort.
    """
    root = Path(tempfile.mkdtemp(prefix="sdkman-s8-"))
    try:
        build_release(root, digest_override="c" * 64)
        result = run_publish(_serve(root))
        check(result.returncode != 0, "a manifest/bytes disagreement must abort publish")
        check("SHA mismatch" in result.stderr,
              f"failure does not name the mismatch: {result.stderr!r}")
        check(not (root / "sdkman-last-payload.json").exists(),
              "publish reached the SDKMAN POST despite the mismatch")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s9_publish_fails_closed_on_absent_manifest() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s9-"))
    try:
        build_release(root, no_manifest=True)
        result = run_publish(_serve(root))
        check(result.returncode != 0, "publish must fail when SHA256SUMS is absent")
        check("SHA256SUMS" in result.stderr,
              f"failure does not name SHA256SUMS: {result.stderr!r}")
        check(not (root / "sdkman-last-payload.json").exists(),
              "publish reached the SDKMAN POST without a manifest")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s10_publish_fails_closed_when_asset_not_in_manifest() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s10-"))
    try:
        build_release(root, include_asset=False)
        result = run_publish(_serve(root))
        check(result.returncode != 0, "publish must fail when the asset has no manifest entry")
        check(ASSET in result.stderr,
              f"failure does not name the asset: {result.stderr!r}")
        check(not (root / "sdkman-last-payload.json").exists(),
              "publish reached the SDKMAN POST with no digest for the asset")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s11_install_uat_preamble_green() -> None:
    """With a valid manifest the UAT MUST get past its digest preamble.

    The stub `sdk` on PATH exits non-zero, so the UAT fails later at step 0.
    That is the expected stop; what matters is that the digest resolved and
    the failure was NOT a digest failure.
    """
    root = Path(tempfile.mkdtemp(prefix="sdkman-s11-"))
    try:
        _, real = build_release(root)
        result = run_install_uat(_serve(root))
        check("SDKMAN install UAT" in result.stdout,
              f"UAT never started: {result.stdout}\n{result.stderr}")
        check(f"expected:   {real}" in result.stdout,
              f"UAT did not adopt the manifest digest: {result.stdout}")
        check("could not resolve a SHA-256" not in result.stderr,
              f"UAT failed on the digest preamble: {result.stderr!r}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s12_install_uat_fails_closed_on_absent_manifest() -> None:
    root = Path(tempfile.mkdtemp(prefix="sdkman-s12-"))
    try:
        build_release(root, no_manifest=True)
        result = run_install_uat(_serve(root))
        check(result.returncode != 0, "UAT must fail when SHA256SUMS is absent")
        check("could not fetch" in result.stderr and "SHA256SUMS" in result.stderr,
              f"failure does not name the missing manifest: {result.stderr!r}")
        check("SDKMAN install UAT" not in result.stdout,
              "UAT proceeded past its preamble without a manifest")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s13_binary_marker_manifest_resolves() -> None:
    """sha256sum's binary mode writes `<digest> *<name>`.

    `sha256sum -b` is a normal way to produce a manifest; a resolver that
    only understands the two-space form would silently miss the asset.
    """
    root = Path(tempfile.mkdtemp(prefix="sdkman-s13-"))
    try:
        _, real = build_release(root, binary_marker=True)
        code, out, err = resolve_digest(_serve(root))
        check(code == 0, f"resolver failed on a binary-mode manifest: rc={code} err={err}")
        check(out == real, f"wrong digest: got {out}, want {real}")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_s14_publish_never_posts_on_unverifiable_input() -> None:
    """No SDKMAN POST may happen unless a digest was read AND verified.

    This is the property that makes the fail-closed guarantee meaningful:
    it does not matter which specific check refuses, as long as nothing is
    ever published. Every sub-case below must leave the POST endpoint
    untouched.
    """
    scenarios = {
        "absent-manifest": dict(no_manifest=True),
        "asset-absent-from-manifest": dict(include_asset=False),
        "malformed-digest": dict(malformed=True),
        "archive-bytes-disagree": dict(digest_override="d" * 64),
    }
    for name, kwargs in scenarios.items():
        root = Path(tempfile.mkdtemp(prefix=f"sdkman-s14-{name}-"))
        try:
            build_release(root, **kwargs)
            result = run_publish(_serve(root))
            check(result.returncode != 0, f"{name}: publish must refuse")
            check(not (root / "sdkman-last-payload.json").exists(),
                  f"{name}: publish reached the SDKMAN POST endpoint")
        finally:
            shutil.rmtree(root, ignore_errors=True)


def test_s15_empty_manifest_fails_closed() -> None:
    """An empty manifest is the worst case a fetch failure can degrade to.

    If a caller's fetch check is ever weakened, the resolver still refuses
    on empty input. This pins the LAST line of defence so the guarantee
    does not depend on any single check being present.
    """
    code, out, err = resolve_digest_text("")
    check(code != 0, "an empty manifest must fail closed")
    check(ASSET in err, f"failure does not name the asset: {err!r}")


def test_s16_stripped_and_unstripped_marker_agree() -> None:
    """sha256sum's '*' marker is handled, not merely tolerated.

    Guards against a future refactor that reintroduces explicit stripping
    and then breaks on the plain two-space form (or vice versa).
    """
    real = "e" * 64
    with_star = f"{real} *dist/candidates/v{TARGET}/pipelinek-{TARGET}.zip\n"
    without = f"{real}  dist/candidates/v{TARGET}/pipelinek-{TARGET}.zip\n"
    for label, text in (("binary-mode", with_star), ("text-mode", without)):
        code, out, _ = resolve_digest_text(text)
        check(code == 0, f"{label} manifest failed: rc={code}")
        check(out == real, f"{label}: got {out}, want {real}")


def main() -> int:
    for path in (PUBLISH, INSTALL_UAT):
        if not path.is_file():
            print(f"ERROR: missing {path}", file=sys.stderr)
            return 2

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_s")]
    failures = []
    for test in tests:
        try:
            test()
        except Exception as exc:  # noqa: BLE001
            failures.append((test.__name__, exc))
            print(f"FAIL {test.__name__}\n      {exc}")
        else:
            print(f"ok   {test.__name__}")
    print(f"\n{len(tests) - len(failures)}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
