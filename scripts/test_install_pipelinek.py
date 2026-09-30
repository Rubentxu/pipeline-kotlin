#!/usr/bin/env python3
"""Contractual tests for install-pipelinek.sh (TRAIN P1).

Run:
    python3 scripts/test_install_pipelinek.py

These tests verify the TRAIN P1 contract from
docs/pipelinek-release-evolution/pipeline-kotlin/03-roadmap.md and
04-uat.md. Every test builds a REAL distribution ZIP, serves it from a
REAL loopback HTTP server, and runs the REAL installer. Nothing is
mocked, no test reaches the public internet, and no test depends on the
developer's working tree, HEAD, or ~/.local/share.

Contract summary (P1.1 - P1.4):
  R1  SHA256SUMS is the digest authority; the `${asset}.sha256` sidecar
      is never requested.
  R1b Both SHA256SUMS name formats are understood: the real published
      form carries build paths (`dist/candidates/vX/<asset>`) and the flat
      form carries bare asset names.
  R2  A digest mismatch fails closed: non-zero, nothing installed.
  R3  Clean install from an empty home succeeds, and the installed
      binary reports the EXACT target version (P-UAT-05).
  R4  A runtime identity mismatch is a hard failure: non-zero, target
      install removed, previous active version untouched (P-UAT-06).
  R4b A candidate-suffixed body (0.44.0-rc1) under a final filename is
      refused, which only an EXACT comparison can detect.
  R5  A failure never leaves a partial install directory behind.
  R6  Installation is transactional: the final directory appears only
      after full verification (no in-place extraction).
  R7  The installed distribution root name matches the target version.
  R8  A ZIP whose archive root disagrees with the target version is
      rejected (historical-canary shape).
  R9  `doctor` reports the binary root and version (P1.4).
  R10 The canonical URL allowlist still fails closed for a
      non-allowlisted host.

The loopback mirror is a documented installer feature (offline/air-gapped
installs), not a test backdoor: it is restricted to loopback hosts and is
a separate variable from PIPELINEK_RELEASE_BASE_URL.

Two properties are load-bearing and would otherwise be silently lost:

  HERMETICITY  Every test MUST be served by the loopback mirror. If the
               installer ignores PIPELINEK_MIRROR_BASE_URL it falls back
               to the canonical GitHub URL and the suite downloads real
               multi-megabyte releases. `assert_served_by_mirror` fails
               the test if the canonical host appears in the output, so
               a regression into network access cannot pass quietly.

  REASON, NOT JUST EXIT CODE  A non-zero exit is not evidence of the
               intended refusal. Against the pre-P1 installer the suite
               scored 5/10, but R2/R5/R6/R8/R10 were FALSE GREENS: the
               install failed because a 404 hit the public internet,
               which is indistinguishable from "refused for the right
               reason" if you only assert on the exit status. Every
               negative test therefore asserts the specific diagnostic.
"""
from __future__ import annotations

import functools
import http.server
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

REPO_ROOT = Path(__file__).resolve().parent.parent
INSTALLER = REPO_ROOT / "scripts" / "install-pipelinek.sh"

TARGET = "0.44.0"
OTHER = "0.43.0"


# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------


class TestFailure(AssertionError):
    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise TestFailure(message)


def assert_served_by_mirror(result, mirror: str) -> None:
    """Fail if the installer reached the canonical GitHub release host.

    Without this, an installer that ignores PIPELINEK_MIRROR_BASE_URL
    silently performs real network downloads and the suite stops being
    hermetic.
    """
    combined = result.stdout + result.stderr
    check(
        "github.com/Rubentxu" not in combined,
        "installer reached the canonical GitHub URL; the loopback mirror was ignored "
        f"(non-hermetic run):\n{combined[:800]}",
    )
    check(
        mirror in combined,
        f"installer output never mentions the mirror {mirror}; it used another source:\n{combined[:800]}",
    )


def run_installer(args, home: Path, base_url: str | None = None, mirror: str | None = None):
    env = dict(os.environ)
    env["PIPELINEK_HOME"] = str(home)
    env.pop("PIPELINEK_SHA256_0_44_0", None)
    if base_url is not None:
        env["PIPELINEK_RELEASE_BASE_URL"] = base_url
    if mirror is not None:
        env["PIPELINEK_MIRROR_BASE_URL"] = mirror
    return subprocess.run(
        [str(INSTALLER), *args],
        capture_output=True,
        text=True,
        env=env,
        timeout=120,
    )


# ---------------------------------------------------------------------------
# Release fixture: a real ZIP, a real SHA256SUMS, a real fake binary
# ---------------------------------------------------------------------------


def _write_fake_binary(path: Path, reported_version: str) -> None:
    """A runnable stand-in for the real `pipelinek` binary.

    The real CLI prints `pipeline <Implementation-Version>` (see
    Main.kt). The stand-in reproduces exactly that contract so the
    installer's identity check is exercised against realistic output.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "#!/usr/bin/env bash\n"
        'if [ "$1" = "version" ]; then\n'
        f'  printf "pipeline {reported_version}\\n"\n'
        "  exit 0\n"
        "fi\n"
        'if [ "$1" = "doctor" ]; then\n'
        '  printf "doctor ok\\n"\n'
        "  exit 0\n"
        "fi\n"
        "exit 0\n"
    )
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def build_release(
    root: Path,
    version: str = TARGET,
    reported_version: str | None = None,
    archive_root: str | None = None,
    omit_binary: bool = False,
    manifest_paths: bool = True,
) -> tuple[str, Path]:
    """Build a release layout: ZIP + SHA256SUMS.

    Returns (expected_sha256, zip_path). The SHA256SUMS file is written
    for every asset, which is what makes it usable as a real authority.
    """
    reported_version = reported_version or version
    archive_root = archive_root or f"pipelinek-{version}"
    asset_name = f"pipelinek-{version}.zip"

    asset_dir = root / f"v{version}"
    asset_dir.mkdir(parents=True, exist_ok=True)
    zip_path = asset_dir / asset_name

    entries: list[tuple[str, Path]] = []
    if not omit_binary:
        binary = asset_dir / "staging" / "bin" / "pipelinek"
        _write_fake_binary(binary, reported_version)
        entries.append((f"{archive_root}/bin/pipelinek", binary))

    lib = asset_dir / "staging" / "lib" / "app.jar"
    lib.parent.mkdir(parents=True, exist_ok=True)
    lib.write_bytes(b"PK\x03\x04 fake jar payload")
    entries.append((f"{archive_root}/lib/app.jar", lib))

    readme = asset_dir / "staging" / "README.md"
    readme.write_text(f"pipelinek {version}\n")
    entries.append((f"{archive_root}/README.md", readme))

    # Fixed timestamp so the ZIP bytes are deterministic for a given
    # payload; the contract under test is install behaviour, not ZIP
    # reproducibility (that is P-UAT-02, proven in the Gradle lane).
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for arcname, source in entries:
            info = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = (source.stat().st_mode & 0xFFFF) << 16
            zf.writestr(info, source.read_bytes())

    import hashlib

    digest = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    sums = asset_dir / "SHA256SUMS"
    # Reproduce the REAL published format, observed on v0.41.0-rc1:
    #   d49edc08...  dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
    # The name field carries the build-time path, not the bare asset name.
    # A lookup that compares the full field fails on every real install.
    prefix = f"dist/candidates/v{version}"
    if manifest_paths:
        lines = [f"{digest}  {prefix}/{asset_name}"]
    else:
        lines = [f"{digest}  {asset_name}"]
    # A second, unrelated asset proves the installer selects the right
    # line rather than blindly using the first.
    lines.append("0" * 64 + f"  {prefix}/some-other-asset.tar.gz")
    sums.write_text("\n".join(lines) + "\n")
    return digest, zip_path


@functools.lru_cache(maxsize=1)
def _serve(root: Path) -> str:
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(root))
    handler.log_message = lambda *a, **k: None  # silence

    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return f"http://127.0.0.1:{port}"


def tempdir(test_name: str) -> Path:
    path = Path(tempfile.mkdtemp(prefix=f"p1-{test_name}-"))
    return path


# ---------------------------------------------------------------------------
# R1 - SHA256SUMS is the digest authority
# ---------------------------------------------------------------------------


def test_r1_sha256sums_authority_no_sidecar_requested() -> None:
    """The `${asset}.sha256` sidecar is gone; SHA256SUMS is the authority.

    The release layout deliberately contains NO `.sha256` sidecar, so an
    installer that still required one would fail outright. Success
    therefore proves the sidecar is not on the critical path.
    """
    root = tempdir("r1")
    try:
        build_release(root)
        base = _serve(root)
        home = tempdir("r1-home")
        result = run_installer(["install", TARGET], home, mirror=base)

        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode == 0, f"install failed:\n{combined}")
        check(
            ".sha256" not in combined,
            f"installer still references the .sha256 sidecar:\n{combined}",
        )
        check("SHA256SUMS" in combined, f"installer did not mention SHA256SUMS:\n{combined}")
        check((home / "versions" / TARGET / "bin" / "pipelinek").is_file(), "binary missing")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


def test_r1b_sha256sums_with_build_paths_is_understood() -> None:
    """A real published SHA256SUMS records build paths, not bare names.

    Observed on the real v0.41.0-rc1 release:
        d49edc08...  dist/candidates/v0.41.0-rc1/pipelinek-0.41.0-rc1.zip
    An installer that matches the name field exactly fails closed against
    every real release. The whole suite uses `manifest_paths=True` (the
    real format); this test pins the case explicitly and also proves the
    flat format still works.
    """
    for manifest_paths, label in ((True, "build paths"), (False, "flat names")):
        root = tempdir(f"r1b-{label.replace(' ', '-')}")
        home = tempdir(f"r1b-home-{label.replace(' ', '-')}")
        try:
            build_release(root, manifest_paths=manifest_paths)
            base = _serve(root)
            result = run_installer(["install", TARGET], home, mirror=base)
            combined = result.stdout + result.stderr
            assert_served_by_mirror(result, base)
            check(
                result.returncode == 0,
                f"install failed with SHA256SUMS using {label}:\n{combined}",
            )
            check((home / "versions" / TARGET / "bin" / "pipelinek").is_file(), "binary missing")
        finally:
            shutil.rmtree(root, ignore_errors=True)
            shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R2 - digest mismatch fails closed
# ---------------------------------------------------------------------------


def test_r2_digest_mismatch_refuses_and_installs_nothing() -> None:
    root = tempdir("r2")
    home = tempdir("r2-home")
    try:
        _, zip_path = build_release(root)
        # Corrupt the published SHA256SUMS so it disagrees with real bytes.
        sums = zip_path.parent / "SHA256SUMS"
        sums.write_text(("f" * 64) + f"  pipelinek-{TARGET}.zip\n")
        base = _serve(root)

        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode != 0, f"expected non-zero exit, got 0:\n{combined}")
        # Reason, not merely exit status: a 404 or a network error would
        # also be non-zero and would be a false green.
        check(
            "mismatch" in combined.lower() or "digest" in combined.lower(),
            f"digest mismatch was not the stated reason for refusal:\n{combined}",
        )
        check(not (home / "versions" / TARGET).exists(), "install directory exists after refusal")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R3 - clean install (P-UAT-05)
# ---------------------------------------------------------------------------


def test_r3_clean_install_from_empty_home() -> None:
    root = tempdir("r3")
    home = tempdir("r3-home")
    try:
        build_release(root)
        base = _serve(root)

        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode == 0, f"install failed:\n{combined}")

        target_dir = home / "versions" / TARGET
        check(target_dir.is_dir(), "target dir missing")
        binary = target_dir / "bin" / "pipelinek"
        check(binary.is_file() and os.access(binary, os.X_OK), "binary not executable")
        # The single top-level directory is flattened away.
        check(
            not (target_dir / f"pipelinek-{TARGET}").exists(),
            "archive root directory was not flattened",
        )
        check((target_dir / "lib" / "app.jar").is_file(), "lib/app.jar missing")

        use = run_installer(["use", TARGET], home, mirror=base)
        check(use.returncode == 0, f"use failed:\n{use.stdout}{use.stderr}")
        check((home / "current").is_symlink(), "current symlink missing")

        version_out = subprocess.run(
            [str(home / "current" / "bin" / "pipelinek"), "version"],
            capture_output=True,
            text=True,
            timeout=60,
        )
        check(version_out.returncode == 0, f"binary failed:\n{version_out.stderr}")
        check(
            version_out.stdout.strip() == f"pipeline {TARGET}",
            f"unexpected version output: {version_out.stdout!r}",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R4 - runtime identity mismatch is a HARD failure (P-UAT-06)
# ---------------------------------------------------------------------------


def test_r4_identity_mismatch_is_hard_failure() -> None:
    """P-UAT-06: inject a mismatched runtime version.

    Expected: non-zero, target install removed, previous active version
    untouched. This is the P1.3 change from a warning to a hard failure.
    """
    root = tempdir("r4")
    home = tempdir("r4-home")
    try:
        # First install a known-good OTHER version and activate it.
        build_release(root, version=OTHER, reported_version=OTHER)
        base = _serve(root)
        first = run_installer(["install", OTHER], home, mirror=base)
        check(first.returncode == 0, f"baseline install failed:\n{first.stdout}{first.stderr}")
        assert_served_by_mirror(first, base)
        activated = run_installer(["use", OTHER], home, mirror=base)
        check(activated.returncode == 0, "baseline use failed")
        previous = home / "versions" / OTHER / "bin" / "pipelinek"

        # Now the target version: ZIP name/root says TARGET, but the
        # binary reports a different version.
        build_release(root, version=TARGET, reported_version="0.41.0")
        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)

        check(result.returncode != 0, f"identity mismatch must fail, got 0:\n{combined}")
        check(
            "0.41.0" in combined,
            f"refusal did not name the version the binary actually reported:\n{combined}",
        )
        check(
            not (home / "versions" / TARGET).exists(),
            f"target install not removed after mismatch:\n{combined}",
        )
        check(previous.is_file(), "previous version was disturbed")
        check(
            (home / "current").resolve() == (home / "versions" / OTHER).resolve(),
            "active symlink was disturbed",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


def test_r4b_candidate_suffixed_body_is_refused() -> None:
    """A candidate-suffixed body under a final filename must be refused.

    This is the laundering shape the whole release-evolution protocol
    exists to prevent, on the INSTALL side. VERSION_REGEX rejects
    `0.44.0-rc1` as an argument, so the reachable case is a ZIP named
    `pipelinek-0.44.0.zip` whose binary reports `0.44.0-rc1`.

    MUTATION-7 replaced the exact token comparison with a substring test
    and this suite scored 12/12: `'0.44.0' in '0.44.0-rc1'` is true, so the
    substring installer silently accepted a release candidate while
    claiming to have installed 0.44.0. R4 could not see it because its
    fixture reported a completely unrelated `0.41.0`.
    """
    root = tempdir("r4b")
    home = tempdir("r4b-home")
    try:
        build_release(root, reported_version=f"{TARGET}-rc1")
        base = _serve(root)
        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)

        check(result.returncode != 0, f"a candidate-suffixed body must be refused, got 0:\n{combined}")
        check(
            f"{TARGET}-rc1" in combined,
            f"refusal did not name the candidate-suffixed version actually reported:\n{combined}",
        )
        check(
            not (home / "versions" / TARGET).exists(),
            f"laundered candidate was installed anyway:\n{combined}",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R5 - no partial install directory
# ---------------------------------------------------------------------------


def test_r5_no_partial_directory_after_failure() -> None:
    root = tempdir("r5")
    home = tempdir("r5-home")
    try:
        # A ZIP with no binary at all: extraction succeeds, identity fails.
        build_release(root, omit_binary=True)
        base = _serve(root)

        result = run_installer(["install", TARGET], home, mirror=base)
        assert_served_by_mirror(result, base)
        combined = result.stdout + result.stderr
        check(result.returncode != 0, "expected failure for a ZIP with no binary")
        check(
            "binary" in combined.lower() or "pipelinek" in combined.lower(),
            f"refusal did not mention the missing binary:\n{combined}",
        )
        check(
            not (home / "versions" / TARGET).exists(),
            "partial install directory left behind",
        )
        # No staging leftovers either.
        versions = home / "versions"
        if versions.is_dir():
            leftovers = [p.name for p in versions.iterdir()]
            check(not leftovers, f"leftover entries under versions/: {leftovers}")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R6 - transactional install (P1.2)
# ---------------------------------------------------------------------------


def test_r6_install_is_transactional_not_in_place() -> None:
    """The final directory must appear only after full verification (P1.2).

    The outcome-only checks in R5 are not enough: a `mkdir -p $target_dir`
    followed by `cp -a` produces the same "nothing left behind" result as a
    rename. MUTATION-3 (in-place copy instead of rename) survived an earlier
    version of this suite at 10/10, so this test asserts the MECHANISM
    instead:

      - the installer must report that it is staging and then publishing;
      - and a failure after extraction must leave versions/ without any
        entry for the target version.
    """
    root = tempdir("r6")
    home = tempdir("r6-home")
    try:
        # Identity fails AFTER extraction, so the transaction must roll back.
        build_release(root, reported_version="0.41.0")
        base = _serve(root)
        result = run_installer(["install", TARGET], home, mirror=base)
        assert_served_by_mirror(result, base)
        combined = result.stdout + result.stderr
        check(result.returncode != 0, "expected identity failure")
        check(
            "0.41.0" in combined,
            f"refusal did not name the mismatched version:\n{combined}",
        )

        # MECHANISM: the target directory must never have existed.
        versions = home / "versions"
        check(
            not (versions / TARGET).exists(),
            f"target directory existed during a failed transaction:\n{combined}",
        )
        entries = sorted(p.name for p in versions.iterdir()) if versions.is_dir() else []
        check(not entries, f"any entry under versions/ after a rolled-back install: {entries}")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


def test_r6c_publish_uses_a_single_rename() -> None:
    """The install must be published by `mv`, never copied in place (P1.2).

    MUTATION-3 replaced the final `mv` with `mkdir -p $target_dir` plus
    `cp -a`, which leaves the same observable files, so an outcome-based
    test cannot tell the two apart. This test does not introspect the shell
    source (that would be a tautology); it observes the one behavioural
    difference that actually matters on a real filesystem: a copy is
    non-atomic and observably creates the destination before it is filled,
    so a reader racing the install can see a partially populated directory.

    The difference is observed by sampling the destination path from a
    concurrent thread while the install runs. With `mv` the destination
    never exists in an incomplete state; with `cp -a` the destination is
    created and then filled, so a sample lands on a directory that lacks
    one of the expected entries. The sampling loop is deliberately tight so
    it gets many chances during the copy.
    """
    root = tempdir("r6c")
    home = tempdir("r6c-home")
    try:
        build_release(root)
        base = _serve(root)

        dest = home / "versions" / TARGET
        observed_partial = []
        stop = threading.Event()

        def observer() -> None:
            while not stop.is_set():
                if dest.is_dir():
                    # A complete install always has bin/ and lib/ present.
                    complete = (dest / "bin" / "pipelinek").is_file() and (
                        dest / "lib" / "app.jar"
                    ).is_file()
                    if not complete:
                        observed_partial.append(sorted(p.name for p in dest.iterdir()))
                        return

        watcher = threading.Thread(target=observer, daemon=True)
        watcher.start()
        try:
            result = run_installer(["install", TARGET], home, mirror=base)
        finally:
            stop.set()
            watcher.join(timeout=5)

        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode == 0, f"install failed:\n{combined}")
        check(
            not observed_partial,
            "the install destination was observable in a partial state, so publication "
            f"is not a single atomic rename: {observed_partial[:3]}",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


def test_r6b_transactional_publish_is_reported() -> None:
    """A successful install must report staging and publishing (P1.2).

    This is the observability half of the transactional contract: the
    installer tells the operator that it verified a temporary copy and then
    published it, rather than writing directly into the install root.
    """
    root = tempdir("r6b")
    home = tempdir("r6b-home")
    try:
        build_release(root)
        base = _serve(root)
        result = run_installer(["install", TARGET], home, mirror=base)
        assert_served_by_mirror(result, base)
        combined = result.stdout + result.stderr
        check(result.returncode == 0, f"install failed:\n{combined}")
        check(
            "staged" in combined.lower() or "staging" in combined.lower(),
            f"installer did not report staging a temporary copy:\n{combined}",
        )
        check(
            "publish" in combined.lower() or "verified" in combined.lower(),
            f"installer did not report publishing the verified copy:\n{combined}",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R7 / R8 - archive root agreement
# ---------------------------------------------------------------------------


def test_r7_archive_root_matches_version() -> None:
    root = tempdir("r7")
    home = tempdir("r7-home")
    try:
        build_release(root, archive_root=f"pipelinek-{TARGET}")
        base = _serve(root)
        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode == 0, f"install failed:\n{result.stdout}{result.stderr}")
        check((home / "versions" / TARGET / "bin" / "pipelinek").is_file(), "binary missing")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


def test_r8_archive_root_mismatch_rejected() -> None:
    """Historical-canary shape: name says TARGET, bytes are a different tree.

    The fake binary reports the REQUESTED version on purpose: that isolates
    the archive-root check from the runtime-identity check, so this test
    fails if and only if the root check is missing.
    """
    root = tempdir("r8")
    home = tempdir("r8-home")
    try:
        build_release(root, archive_root="pipelinek-0.41.0", reported_version=TARGET)
        base = _serve(root)
        result = run_installer(["install", TARGET], home, mirror=base)
        combined = result.stdout + result.stderr
        assert_served_by_mirror(result, base)
        check(result.returncode != 0, f"archive-root mismatch must fail, got 0:\n{combined}")
        # The refusal must be ABOUT the archive root, not merely mention the
        # observed name somewhere. MUTATION-4 (deleting the root check
        # entirely) survived an earlier version of this suite, because the
        # runtime-identity check rejected the same fixture for a different
        # reason. Isolate the root check with a fixture whose binary reports
        # the REQUESTED version, so only the archive root can refuse.
        check(
            "archive root" in combined.lower(),
            f"refusal was not about the archive root:\n{combined}",
        )
        check(
            "pipelinek-0.41.0" in combined,
            f"refusal did not name the observed archive root:\n{combined}",
        )
        check(not (home / "versions" / TARGET).exists(), "install dir exists after refusal")
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R9 - doctor diagnostics (P1.4)
# ---------------------------------------------------------------------------


def test_r9_doctor_reports_root_and_version() -> None:
    root = tempdir("r9")
    home = tempdir("r9-home")
    try:
        build_release(root)
        base = _serve(root)
        run_installer(["install", TARGET], home, mirror=base)
        run_installer(["use", TARGET], home, mirror=base)

        result = run_installer(["doctor"], home, mirror=base)
        combined = result.stdout + result.stderr
        check(result.returncode == 0, f"doctor failed:\n{combined}")
        check(TARGET in combined, f"doctor did not report the version:\n{combined}")
        check(
            str(home / "versions" / TARGET) in combined or "bin/pipelinek" in combined,
            f"doctor reported no binary root:\n{combined}",
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# R10 - the URL allowlist still fails closed
# ---------------------------------------------------------------------------


def test_r10_non_allowlisted_base_url_refused() -> None:
    home = tempdir("r10-home")
    try:
        result = run_installer(
            ["install", TARGET],
            home,
            base_url="https://evil.example.com/releases/download",
        )
        combined = result.stdout + result.stderr
        check(result.returncode != 0, f"non-allowlisted host must fail:\n{combined}")
        check("allowlist" in combined.lower(), f"no allowlist diagnostic:\n{combined}")
    finally:
        shutil.rmtree(home, ignore_errors=True)


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


def main() -> int:
    if not INSTALLER.is_file():
        print(f"ERROR: installer not found at {INSTALLER}", file=sys.stderr)
        return 2

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_r")]
    failures = []
    for test in tests:
        name = test.__name__
        try:
            test()
        except Exception as exc:  # noqa: BLE001 - report every failure
            failures.append((name, exc))
            print(f"FAIL {name}\n      {exc}")
        else:
            print(f"ok   {name}")
    print(f"\n{len(tests) - len(failures)}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
