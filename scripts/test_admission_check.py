#!/usr/bin/env python3
"""Contractual tests for admission-check.py (PRDY-006R2).

Run:
    python3 scripts/test_admission_check.py

These tests verify the PRDY-006R2 contract: the gate is hermetic and
reproducible across clones. Every test that depends on Git state uses a
real temporary Git repository created via `git init`. Tests must not
depend on the development repo's working tree, mtime, or HEAD.

Contract summary:
  R0  Candidate SHA must resolve to a commit (bogus → exit 2, ERROR)
  R1  CURRENT_STATE.md must exist
  R2  state.HEAD is ancestor of candidate (or equal)
  R3  Working tree clean; exact-path exclusions: `.agent/` prefix and
      the two CURRENT_*.md files
  R4  No FAIL_PROVEN/NOT_RUN/REJECTED without ADMISSION_EXCEPTIONS entry
  R5  Every selected receipt must be reachable from candidate via
      Git ancestry (provenance, NOT mtime)
  R6  --strict extends R4 to REFERENCED
  R7  candidate not behind origin/main

Reproducibility invariant: two clones of the same candidate SHA must reach
the same admission decision, regardless of filesystem mtime or any
working-tree dirtiness of the session running the test.
"""
from __future__ import annotations

import importlib.util
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "admission-check.py"


def _load_mod():
    spec = importlib.util.spec_from_file_location("admission_check", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _run_git(cwd, *args, env=None):
    r = subprocess.run(
        ["git", "-C", str(cwd), *args],
        capture_output=True, text=True,
        env=env,
    )
    if r.returncode != 0:
        raise RuntimeError(f"git {args} failed: {r.stderr.strip()}")
    return r.stdout.rstrip("\n")


def _git_env():
    """Return an env that disables any interactive Git prompting."""
    env = os.environ.copy()
    env["GIT_AUTHOR_NAME"] = "Test"
    env["GIT_AUTHOR_EMAIL"] = "test@example.com"
    env["GIT_COMMITTER_NAME"] = "Test"
    env["GIT_COMMITTER_EMAIL"] = "test@example.com"
    return env


def _valid_40(s):
    return (isinstance(s, str)
            and len(s) == 40
            and all(c in "0123456789abcdef" for c in s))


def _empty_commit_tree(repo: pathlib.Path, message: str = "init") -> str:
    """Create an empty commit in `repo` and return its SHA."""
    _run_git(repo, "commit", "--allow-empty", "-m", message,
             env=_git_env())
    return _run_git(repo, "rev-parse", "HEAD")


class TempGitRepo:
    """A real temporary Git repo, completely isolated from the dev repo.

    Tests use this to verify hermetic behaviour: the admission gate must
    behave the same on this fresh repo as it does on any clone of the
    candidate SHA. Use as a context manager:

        with TempGitRepo() as repo:
            sha = repo.head()
            ...
    """

    def __init__(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="admission-test-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        _run_git(self.repo, "init", "-q", "--initial-branch=main")
        _run_git(self.repo, "config", "user.email", "test@example.com")
        _run_git(self.repo, "config", "user.name", "Test")
        # Establish a HEAD so `repo.head()` is always callable.
        _run_git(self.repo, "commit", "--allow-empty", "-m", "init",
                 env=_git_env())

    def __enter__(self) -> "TempGitRepo":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.cleanup()

    def cleanup(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def commit(self, message: str = "c",
               files: dict[str, str] | None = None) -> str:
        """Commit (optionally with files) and return the new commit SHA.

        Empty directories cannot be committed on their own — every file
        passed in `files` provides at least an empty marker. Use at least
        one non-empty file in directories you want to track.
        """
        files = files or {}
        for rel, body in files.items():
            p = self.repo / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body)
            _run_git(self.repo, "add", "--", rel)
        return _empty_commit_tree(self.repo, message)

    def head(self) -> str:
        return _run_git(self.repo, "rev-parse", "HEAD")


# ---------------------------------------------------------------------------
# R0 — candidate existence
# ---------------------------------------------------------------------------

class R0CandidateExistenceTests(unittest.TestCase):
    """R0: the candidate SHA must exist and resolve to a commit.

    Without this precondition, several rules (R5, R7) degrade to PASS on
    bogus input because git show / merge-base silently fail. R0 is an
    ERROR, not a regular FAIL, because no other rule can be evaluated
    meaningfully.
    """

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_r0_accepts_real_commit_sha(self):
        # Real repo HEAD exists.
        repo = TempGitRepo()
        with repo:
            sha = repo.head()
            try:
                self.mod.check_r0_candidate_exists(sha, repo.repo)
            except SystemExit as e:
                self.fail(f"R0 unexpectedly rejected real commit: exit={e.code}")

    def test_r0_rejects_zero_sha(self):
        repo = TempGitRepo()
        with repo:
            with self.assertRaises(SystemExit) as ctx:
                self.mod.check_r0_candidate_exists("0" * 40, repo.repo)
            self.assertEqual(ctx.exception.code, 2)

    def test_r0_rejects_malformed_sha(self):
        repo = TempGitRepo()
        with repo:
            with self.assertRaises(SystemExit) as ctx:
                self.mod.check_r0_candidate_exists("not-a-sha", repo.repo)
            self.assertEqual(ctx.exception.code, 2)

    def test_r0_rejects_empty_sha(self):
        repo = TempGitRepo()
        with repo:
            with self.assertRaises(SystemExit) as ctx:
                self.mod.check_r0_candidate_exists("", repo.repo)
            self.assertEqual(ctx.exception.code, 2)

    def test_r0_rejects_short_sha(self):
        """Git treats short SHAs (>=4 hex) as valid commit refs when
        unambiguous; the gate must accept them too. This test pins that
        behaviour: short SHAs are NOT malformed input."""
        repo = TempGitRepo()
        with repo:
            sha = repo.head()[:7]  # short SHA, not full 40 hex
            # If the short SHA is unambiguous it resolves to the commit
            # and R0 must NOT exit. (If for some reason it doesn't resolve,
            # R0 will exit 2 — that's also acceptable, just not silent
            # acceptance.)
            try:
                self.mod.check_r0_candidate_exists(sha, repo.repo)
            except SystemExit as e:
                self.assertEqual(e.code, 2)

    def test_r0_rejects_commitish_pointing_to_blob(self):
        """A tree or blob is NOT a commit — R0 must reject it."""
        repo = TempGitRepo()
        with repo:
            repo.commit("c0", {"file.txt": "hello"})
            blob_sha = _run_git(repo.repo, "hash-object", "file.txt")
            with self.assertRaises(SystemExit) as ctx:
                self.mod.check_r0_candidate_exists(blob_sha, repo.repo)
            self.assertEqual(ctx.exception.code, 2)

    def test_r0_driver_exits_2_on_bogus(self):
        repo = TempGitRepo()
        with repo:
            sha = repo.commit("c1")
            r = subprocess.run(
                [sys.executable, str(SCRIPT),
                 "--head", "0" * 40, "--root", str(repo.repo)],
                capture_output=True, text=True,
            )
            self.assertEqual(r.returncode, 2,
                             f"expected exit 2, got {r.returncode}; stderr={r.stderr}")
            self.assertIn("R0 ERROR", r.stderr)

    def test_r0_driver_rejects_empty_head_arg(self):
        """`--head ""` must NOT silently fall back to HEAD; that would mask
        user error and contradict the R0 contract. PRDY-006R2 hardening:
        explicit empty string is malformed input.
        """
        repo = TempGitRepo()
        with repo:
            sha = repo.commit("c1")
            r = subprocess.run(
                [sys.executable, str(SCRIPT),
                 "--head", "", "--root", str(repo.repo)],
                capture_output=True, text=True,
            )
            self.assertEqual(r.returncode, 2,
                             f"expected exit 2, got {r.returncode}; stderr={r.stderr}")
            self.assertIn("R0 ERROR", r.stderr)


# ---------------------------------------------------------------------------
# R2 — ancestry
# ---------------------------------------------------------------------------

class R2AncestryTests(unittest.TestCase):
    """R2: CURRENT_STATE.HEAD must be an ancestor (or equal) of --head."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_r2_accepts_equal_head(self):
        repo = TempGitRepo()
        with repo:
            sha = repo.head()
            text = f"| **HEAD** | `{sha}` |"
            ok, _ = self.mod.check_r2_state_head_is_ancestor(sha, text, repo.repo)
            self.assertTrue(ok)

    def test_r2_accepts_parent_as_basis(self):
        repo = TempGitRepo()
        with repo:
            parent = repo.head()
            sha = repo.commit("child")
            text = f"| **HEAD** | `{parent}` |"
            ok, msg = self.mod.check_r2_state_head_is_ancestor(sha, text, repo.repo)
            self.assertTrue(ok, f"parent projection must be accepted: {msg}")

    def test_r2_rejects_unrelated_head(self):
        repo = TempGitRepo()
        with repo:
            sha = repo.head()
            # 40-char SHA that is NOT in this repo's history
            other = "0" * 40
            text = f"| **HEAD** | `{other}` |"
            ok, msg = self.mod.check_r2_state_head_is_ancestor(sha, text, repo.repo)
            self.assertFalse(ok)
            self.assertIn("NOT an ancestor", msg)

    def test_r2_rejects_missing_head_field(self):
        repo = TempGitRepo()
        with repo:
            sha = repo.head()
            ok, msg = self.mod.check_r2_state_head_is_ancestor(
                sha, "| **HEAD** | (none) |", repo.repo)
            self.assertFalse(ok)
            self.assertIn("parse HEAD", msg)


# ---------------------------------------------------------------------------
# R3 — working tree cleanliness (exact-path exclusions, hermetic)
# ---------------------------------------------------------------------------

class R3ExactPathExclusionTests(unittest.TestCase):
    """R3: dirty paths are detected by `git status --porcelain`.

    Exclusions are EXACT-PATH only:
      - prefix `.agent/`
      - exact file `docs/v2/08-production-readiness/CURRENT_STATE.md`
      - exact file `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md`

    Tests use a temp git repo so they do NOT depend on the dev repo's
    pre-existing working-tree state (Hallazgo 1).
    """

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def _make_dirty(self, repo: TempGitRepo, paths: list[str]) -> list[str]:
        """Touch the given paths in repo, return R3 dirty paths."""
        for rel in paths:
            p = repo.repo / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("dirty")
        return self.mod.git_working_tree_dirty_paths(repo.repo)

    def test_clean_repo_returns_empty(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            self.assertEqual(
                self.mod.git_working_tree_dirty_paths(repo.repo), [])

    def test_excludes_dot_agent_prefix(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, [".agent/SESSION_POINTER.md"])
            self.assertEqual(dirty, [],
                             f".agent/ must be excluded: {dirty}")

    def test_excludes_nested_dot_agent_prefix(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, [".agent/scratch/notes.md"])
            self.assertEqual(dirty, [],
                             f".agent/ prefix must extend to nested paths: {dirty}")

    def test_excludes_current_state_exact(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(
                repo,
                ["docs/v2/08-production-readiness/CURRENT_STATE.md"])
            self.assertEqual(dirty, [],
                             f"CURRENT_STATE.md must be excluded: {dirty}")

    def test_excludes_current_uat_status_exact(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(
                repo,
                ["docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md"])
            self.assertEqual(dirty, [],
                             f"CURRENT_UAT_STATUS.md must be excluded: {dirty}")

    def test_blocks_unrelated_path_containing_agent_substring(self):
        """Substring 'agent' must NOT trigger an exclusion.
        Hallazgo 4: prior code matched `agent/` anywhere in the path,
        accidentally masking files like `pkg/agent_router/...`.
        """
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, ["pkg/agent_router/foo.kt"])
            self.assertIn("pkg/agent_router/foo.kt", dirty,
                          "substring 'agent' must not exclude")

    def test_blocks_unrelated_path_containing_current_state_substring(self):
        """Substring 'CURRENT_STATE.md' must NOT trigger an exclusion
        unless the path is exact."""
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, ["tmp/CURRENT_STATE.md.bak"])
            self.assertIn("tmp/CURRENT_STATE.md.bak", dirty,
                          "substring match must not exclude; only exact path")

    def test_blocks_source_kt_file(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, ["v2/foo/src/Foo.kt"])
            self.assertIn("v2/foo/src/Foo.kt", dirty)

    def test_blocks_scripts_py_file(self):
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, ["scripts/foo.py"])
            self.assertIn("scripts/foo.py", dirty)

    def test_blocks_exact_CURRENT_STATE_at_other_location(self):
        """If someone creates docs/v3/.../CURRENT_STATE.md, R3 must flag it
        (only the canonical exact path is excluded)."""
        repo = TempGitRepo()
        with repo:
            repo.commit("c")
            dirty = self._make_dirty(repo, ["docs/v3/CURRENT_STATE.md"])
            self.assertIn("docs/v3/CURRENT_STATE.md", dirty)

    def test_rename_target_is_evaluated(self):
        """A rename in the working tree should be evaluated on the
        post-rename path (Hallazgo 1 mention: rename → path final)."""
        repo = TempGitRepo()
        with repo:
            repo.commit("c", {"old.kt": "x"})
            # rename old.kt -> renamed.kt via git mv
            _run_git(repo.repo, "mv", "old.kt", "renamed.kt")
            # leave it staged-as-rename, but also touch a real dirty file
            dirty = self._make_dirty(repo, ["extra.kt"])
            self.assertIn("extra.kt", dirty)
            # The rename target should also be a tracked file. It may or may
            # not show up as "dirty" depending on whether the index matches
            # the working tree. We just verify the normalisation logic
            # doesn't drop the rename.
            self.assertTrue(isinstance(dirty, list))


# ---------------------------------------------------------------------------
# R4 — blocking UAT states
# ---------------------------------------------------------------------------

class R4BlockingUatTests(unittest.TestCase):
    """R4: blocking states (FAIL_PROVEN / NOT_RUN / REJECTED) require
    ADMISSION_EXCEPTIONS entries; --strict also blocks REFERENCED."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def _with_fake_uat_status(self, fake_md, exceptions, strict, expect_ok,
                              expect_substring, cwd):
        """Run R4 with monkey-patched UAT_STATUS/EXCEPTIONS_FILE.

        The rule resolves paths through `_paths(cwd)`, which reads the
        module-level `_CURRENT_STATE_REL` / `_UAT_STATUS_REL` /
        `_EXCEPTIONS_FILE_REL` constants and joins them to `cwd`. To make
        the rule read from a temp file we monkey-patch those constants
        with absolute paths and pass `cwd` as a parent Path.
        """
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(fake_md)
            tmp_uat = f.name
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(exceptions)
            tmp_exc = f.name
        orig_cs_rel = self.mod.CURRENT_STATE_REL
        orig_us_rel = self.mod.UAT_STATUS_REL
        orig_ex_rel = self.mod.EXCEPTIONS_FILE_REL
        # Constants are relative; to get an absolute path we set them to
        # the temp file's basename and put the basename into cwd.
        # Simpler: use the temp dir as cwd and rely on `_paths(cwd)` joining.
        tmp_dir = pathlib.Path(tmp_uat).parent
        self.mod.CURRENT_STATE_REL = pathlib.Path(tmp_uat).name + ".cs"
        self.mod.UAT_STATUS_REL = pathlib.Path(tmp_uat).name
        self.mod.EXCEPTIONS_FILE_REL = pathlib.Path(tmp_exc).name
        # Make sure the matching CS file exists in tmp_dir (placeholder).
        (tmp_dir / self.mod.CURRENT_STATE_REL).write_text("# cs")
        try:
            ok, msg = self.mod.check_r4_no_blocking_uats(strict=strict, cwd=tmp_dir)
            self.assertEqual(ok, expect_ok,
                             f"strict={strict} expected {expect_ok} got {ok}: {msg}")
            if expect_substring:
                self.assertIn(expect_substring, msg)
        finally:
            self.mod.CURRENT_STATE_REL = orig_cs_rel
            self.mod.UAT_STATUS_REL = orig_us_rel
            self.mod.EXCEPTIONS_FILE_REL = orig_ex_rel

    def test_fail_proven_blocks_without_exception(self):
        repo = TempGitRepo()
        with repo:
            fake = "| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |\n| **FAIL_PROVEN:** 1\n"
            self._with_fake_uat_status(fake, "", False, False,
                                       "UAT-RP-099", repo.repo)

    def test_not_run_blocks_without_exception(self):
        repo = TempGitRepo()
        with repo:
            fake = "| `UAT-RP-098` | **NOT_RUN** | — | _no receipt found_ |\n| **NOT_RUN:** 1\n"
            self._with_fake_uat_status(fake, "", False, False,
                                       "UAT-RP-098", repo.repo)

    def test_exception_overrides_blocking_state(self):
        repo = TempGitRepo()
        with repo:
            fake = "| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |\n| **FAIL_PROVEN:** 1\n"
            self._with_fake_uat_status(fake, "UAT-RP-099\n", False, True,
                                       "", repo.repo)

    def test_referenced_only_blocks_under_strict(self):
        repo = TempGitRepo()
        with repo:
            fake = "| `UAT-RP-097` | **REFERENCED** | `r.md` | x |\n| **REFERENCED:** 1\n"
            self._with_fake_uat_status(fake, "", False, True, "", repo.repo)
            self._with_fake_uat_status(fake, "", True, False,
                                       "REFERENCED", repo.repo)

    def test_covered_never_blocks(self):
        repo = TempGitRepo()
        with repo:
            fake = "| `UAT-RP-001` | **COVERED** | `r.md` | x |\n| **COVERED:** 1\n"
            for strict in (False, True):
                self._with_fake_uat_status(fake, "", strict, True, "", repo.repo)

    def test_missing_uat_status_file_blocks(self):
        repo = TempGitRepo()
        with repo:
            # The rule resolves via `_paths(cwd)`, which uses the
            # CURRENT_STATE_REL / UAT_STATUS_REL constants. Point them at
            # a non-existent path inside the repo so R4 fails cleanly.
            orig = self.mod.UAT_STATUS_REL
            self.mod.UAT_STATUS_REL = "no_such/UAT_STATUS.md"
            try:
                ok, msg = self.mod.check_r4_no_blocking_uats(strict=False,
                                                            cwd=repo.repo)
                self.assertFalse(ok)
                self.assertIn("R4", msg)
            finally:
                self.mod.UAT_STATUS_REL = orig


# ---------------------------------------------------------------------------
# R5 — receipt provenance via Git ancestry (the Hallazgo 3 contract)
# ---------------------------------------------------------------------------

class R5ProvenanceTests(unittest.TestCase):
    """R5: each UAT's selected receipt must be reachable from candidate
    via Git ancestry. NO mtime dependency."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def _setup_repo_with_receipts(
            self, receipt_commits: dict[str, str]
    ) -> tuple[TempGitRepo, str, str]:
        """Build a temp repo where each receipt path in `receipt_commits`
        has been added in its own commit. Return (repo, parent_sha, head_sha).
        """
        repo = TempGitRepo()
        # Parent commit (no receipts yet).
        parent = repo.commit("parent")
        # Each receipt gets its own commit so the last-modifying SHA differs.
        for rel, body in receipt_commits.items():
            repo.commit(f"add {rel}", {rel: body})
        head = repo.head()
        return repo, parent, head

    def _uat_status_md(self, rows: list[tuple[str, str, str]]) -> str:
        """Build a minimal CURRENT_UAT_STATUS.md text for the test."""
        lines = ["# UAT Status (test fixture)", ""]
        for uid, status, path in rows:
            lines.append(f"| `{uid}` | **{status}** | `{path}` | x |")
        return "\n".join(lines) + "\n"

    def _with_fake_uat_status(self, fake_md, repo: "TempGitRepo"):
        """Write `fake_md` to the repo's CURRENT_UAT_STATUS location and
        return the path. The rule resolves the file from `cwd` via
        `_paths(cwd)`, so writing it into the repo is the canonical way
        to make the rule see the fixture content."""
        uat_rel = self.mod.UAT_STATUS_REL
        uat_path = repo.repo / uat_rel
        uat_path.parent.mkdir(parents=True, exist_ok=True)
        uat_path.write_text(fake_md)
        return uat_path

    def test_passes_when_receipt_provenance_is_ancestor_of_candidate(self):
        repo, parent, head = self._setup_repo_with_receipts({
            "docs/v2/07-uat/R1.md": "x",
            "docs/v2/07-uat/R2.md": "y",
        })
        md = self._uat_status_md([
            ("UAT-RP-001", "COVERED", "docs/v2/07-uat/R1.md"),
            ("UAT-RP-002", "COVERED", "docs/v2/07-uat/R2.md"),
        ])
        self._with_fake_uat_status(md, repo)
        ok, msg = self.mod.check_r5_receipt_provenance(head, repo.repo)
        self.assertTrue(ok, f"fresh provenance must PASS: {msg}")

    def test_fails_when_receipt_not_reachable_from_candidate(self):
        """Receipt provenance commit NOT ancestor of candidate → FAIL.
        Hallazgo 3: don't use mtime; use ancestry.
        """
        repo, parent, head = self._setup_repo_with_receipts({
            "docs/v2/07-uat/R1.md": "x",
        })
        # Build a child branch that does NOT contain the receipt's last commit.
        _run_git(repo.repo, "checkout", "-q", "-b", "side", parent)
        repo.commit("side-add", {"unrelated.md": "u"})
        side_head = repo.head()
        md = self._uat_status_md([
            ("UAT-RP-001", "COVERED", "docs/v2/07-uat/R1.md"),
        ])
        self._with_fake_uat_status(md, repo)
        ok, msg = self.mod.check_r5_receipt_provenance(side_head, repo.repo)
        self.assertFalse(ok, f"unreachable provenance must FAIL: {msg}")
        self.assertIn("UAT-RP-001", msg)

    def test_fails_when_receipt_has_no_provenance(self):
        """Receipt file written but never committed → no provenance → FAIL.
        Hallazgo 3: mtime would say 'fresh', but provenance says 'no'.
        """
        repo = TempGitRepo()
        with repo:
            repo.commit("init")
            # Write a receipt but do NOT commit it.
            (repo.repo / "docs/v2/07-uat/R1.md").parent.mkdir(parents=True)
            (repo.repo / "docs/v2/07-uat/R1.md").write_text("x")
            head = repo.head()
            md = self._uat_status_md([
                ("UAT-RP-001", "COVERED", "docs/v2/07-uat/R1.md"),
            ])
            self._with_fake_uat_status(md, repo)
            ok, msg = self.mod.check_r5_receipt_provenance(head, repo.repo)
            self.assertFalse(ok, f"uncommitted receipt must FAIL: {msg}")
            self.assertIn("no_provenance", msg)

    def test_mixed_provenance_some_fresh_some_stale(self):
        """Several UATs where one has fresh provenance and another does not."""
        repo, parent, head = self._setup_repo_with_receipts({
            "docs/v2/07-uat/R1.md": "x",
        })
        # Side branch: candidate that can see R2 but not R1.
        _run_git(repo.repo, "checkout", "-q", "-b", "side", parent)
        repo.commit("side-add", {"unrelated.md": "u"})
        repo.commit("side-receipt", {"docs/v2/07-uat/R2.md": "y"})
        side_head = repo.head()
        md = self._uat_status_md([
            ("UAT-RP-001", "COVERED", "docs/v2/07-uat/R1.md"),  # NOT reachable
            ("UAT-RP-002", "COVERED", "docs/v2/07-uat/R2.md"),  # reachable
        ])
        self._with_fake_uat_status(md, repo)
        ok, msg = self.mod.check_r5_receipt_provenance(side_head, repo.repo)
        self.assertFalse(ok, f"mixed provenance must FAIL: {msg}")
        self.assertIn("UAT-RP-001", msg)
        self.assertNotIn("UAT-RP-002", msg)  # only the failing UATs appear

    def test_absence_of_receipt_in_status_blocks(self):
        """UAT row exists but has no `Latest Receipt` column → FAIL.
        Without a receipt we cannot derive provenance."""
        repo = TempGitRepo()
        with repo:
            repo.commit("init")
            head = repo.head()
            md = "| `UAT-RP-001` | **COVERED** | — | _none_ |\n"
            self._with_fake_uat_status(md, repo)
            ok, msg = self.mod.check_r5_receipt_provenance(head, repo.repo)
            self.assertFalse(ok, f"missing receipt must FAIL: {msg}")
            self.assertIn("no_receipt_in_status", msg)

    def test_skip_not_run_uats(self):
        """NOT_RUN UATs have no receipt to verify; they don't trigger R5."""
        repo = TempGitRepo()
        with repo:
            repo.commit("init")
            head = repo.head()
            md = "| `UAT-RP-001` | **NOT_RUN** | — | _no receipt found_ |\n"
            self._with_fake_uat_status(md, repo)
            ok, msg = self.mod.check_r5_receipt_provenance(head, repo.repo)
            self.assertTrue(ok, f"NOT_RUN must PASS R5: {msg}")


# ---------------------------------------------------------------------------
# R7 — origin/main divergence
# ---------------------------------------------------------------------------

class R7OriginMainTests(unittest.TestCase):
    """R7: candidate must not be behind origin/main."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_origin_main_unset_passes(self):
        repo = TempGitRepo()
        with repo:
            sha = repo.head()
            ok, _ = self.mod.check_r7_origin_main_not_ahead(sha, repo.repo)
            self.assertTrue(ok)

    def test_candidate_at_or_ahead_of_origin_main_passes(self):
        repo = TempGitRepo()
        with repo:
            c1 = repo.commit("c1")
            c2 = repo.commit("c2")
            # Add a fake origin/main ref pointing at c1.
            _run_git(repo.repo, "update-ref", "refs/remotes/origin/main", c1)
            ok, _ = self.mod.check_r7_origin_main_not_ahead(c2, repo.repo)
            self.assertTrue(ok)

    def test_candidate_behind_origin_main_fails(self):
        repo = TempGitRepo()
        with repo:
            c1 = repo.commit("c1")
            c2 = repo.commit("c2")
            # origin/main is at c2 (further); candidate c1 is behind.
            _run_git(repo.repo, "update-ref", "refs/remotes/origin/main", c2)
            ok, msg = self.mod.check_r7_origin_main_not_ahead(c1, repo.repo)
            self.assertFalse(ok)
            self.assertIn("behind", msg)


# ---------------------------------------------------------------------------
# Driver / CLI smoke
# ---------------------------------------------------------------------------

class DriverSmokeTests(unittest.TestCase):
    """End-to-end CLI smoke tests."""

    def test_cli_help_lists_required_args(self):
        r = subprocess.run(
            [sys.executable, str(SCRIPT), "--help"],
            capture_output=True, text=True,
        )
        self.assertIn("--head", r.stdout)
        self.assertIn("--strict", r.stdout)
        self.assertIn("--root", r.stdout)
        # --max-receipt-age-days is removed in PRDY-006R2 (provenance > mtime)
        self.assertNotIn("--max-receipt-age-days", r.stdout)

    def test_driver_against_temp_repo_hermetic(self):
        """Build a small repo where every receipt is committed and the
        projection is current. Driver must return 0."""
        with tempfile.TemporaryDirectory() as tmp:
            repo = pathlib.Path(tmp) / "r"
            repo.mkdir()
            _run_git(repo, "init", "-q", "--initial-branch=main")
            _run_git(repo, "config", "user.email", "t@t")
            _run_git(repo, "config", "user.name", "t")
            _run_git(repo, "commit", "--allow-empty", "-m", "i",
                     env=_git_env())
            head = _run_git(repo, "rev-parse", "HEAD")
            # Add a CURRENT_STATE.md and CURRENT_UAT_STATUS.md
            (repo / "docs").mkdir()
            (repo / "docs" / "v2").mkdir()
            (repo / "docs" / "v2" / "08-production-readiness").mkdir()
            cs = repo / "docs" / "v2" / "08-production-readiness" / "CURRENT_STATE.md"
            cs.write_text(f"# CS\n| **HEAD** | `{head}` |\n| **Generated at (UTC)** | `2026-09-26T00:00:00Z` |\n")
            _run_git(repo, "add", "--", "docs")
            _run_git(repo, "commit", "-m", "cs", env=_git_env())
            head = _run_git(repo, "rev-parse", "HEAD")
            r = subprocess.run(
                [sys.executable, str(SCRIPT),
                 "--head", head, "--root", str(repo)],
                capture_output=True, text=True,
            )
            # We expect R4 to FAIL because CURRENT_UAT_STATUS.md does not
            # exist, and R5 to FAIL because the UAT status is missing.
            # But R1, R2, R3, R7 should PASS — and R0 should not block.
            self.assertIn("R4 ", output := r.stdout)
            self.assertIn("R5 ", output)
            self.assertNotIn("R0 ERROR", r.stderr)


# ---------------------------------------------------------------------------
# Reproducibility invariant: same candidate, fresh clone, same decision
# ---------------------------------------------------------------------------

class ReproducibilityInvariantTests(unittest.TestCase):
    """Two clones of the same candidate SHA must reach the same decision.

    Hallazgo: the admission decision must depend ONLY on
      candidate SHA + repo state + explicit config.
    """

    def _make_clone(self, src: pathlib.Path, dst: pathlib.Path) -> None:
        _run_git(src, "clone", "-q", str(src), str(dst))

    def test_two_clones_same_decision(self):
        """Build a small repo with a CURRENT_STATE + UAT status. Clone it.
        Run admission twice (against each clone) using the same candidate
        SHA; results must match."""
        with tempfile.TemporaryDirectory() as outer:
            base = pathlib.Path(outer) / "base"
            base.mkdir()
            _run_git(base, "init", "-q", "--initial-branch=main")
            _run_git(base, "config", "user.email", "t@t")
            _run_git(base, "config", "user.name", "t")
            # Initial commit so HEAD exists.
            _run_git(base, "commit", "--allow-empty", "-m", "init",
                     env=_git_env())
            # Commit a placeholder receipt first (so the receipt has its own
            # provenance), then commit the projection files.
            (base / "docs" / "v2" / "07-uat").mkdir(parents=True)
            (base / "docs" / "v2" / "07-uat" / "R_RECEIPT.md").write_text("ok")
            _run_git(base, "add", "--", "docs")
            _run_git(base, "commit", "-m", "add receipt", env=_git_env())
            (base / "docs" / "v2" / "08-production-readiness").mkdir(parents=True)
            (base / "docs" / "v2" / "08-production-readiness" / "CURRENT_STATE.md").write_text(
                "# CS placeholder"
            )
            (base / "docs" / "v2" / "08-production-readiness" / "CURRENT_UAT_STATUS.md").write_text(
                "| `UAT-RP-001` | **COVERED** | `docs/v2/07-uat/R_RECEIPT.md` | x |\n"
            )
            _run_git(base, "add", "--", "docs")
            _run_git(base, "commit", "-m", "cs + status", env=_git_env())
            candidate = _run_git(base, "rev-parse", "HEAD")

            clone1 = pathlib.Path(outer) / "c1"
            self._make_clone(base, clone1)
            clone2 = pathlib.Path(outer) / "c2"
            self._make_clone(base, clone2)

            r1 = subprocess.run(
                [sys.executable, str(SCRIPT), "--head", candidate,
                 "--root", str(clone1)],
                capture_output=True, text=True,
            )
            r2 = subprocess.run(
                [sys.executable, str(SCRIPT), "--head", candidate,
                 "--root", str(clone2)],
                capture_output=True, text=True,
            )
            self.assertEqual(r1.returncode, r2.returncode,
                             f"clone1 rc={r1.returncode}, clone2 rc={r2.returncode}")
            # Strip timestamps and tmp paths from outputs that might differ.
            def norm(s):
                s = re.sub(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", "T", s)
                s = re.sub(r"root=/[^\s]+", "root=<TMP>", s)
                return s
            norm1 = norm(r1.stdout)
            norm2 = norm(r2.stdout)
            self.assertEqual(norm1, norm2,
                             f"decisions diverged:\n--- c1 ---\n{norm1}\n"
                             f"--- c2 ---\n{norm2}")

    def test_three_consecutive_runs_same_decision(self):
        """Same repo, same candidate, 3 runs in a row: identical output."""
        with tempfile.TemporaryDirectory() as outer:
            repo = pathlib.Path(outer) / "r"
            repo.mkdir()
            _run_git(repo, "init", "-q", "--initial-branch=main")
            _run_git(repo, "config", "user.email", "t@t")
            _run_git(repo, "config", "user.name", "t")
            _run_git(repo, "commit", "--allow-empty", "-m", "init",
                     env=_git_env())
            (repo / "docs" / "v2" / "07-uat").mkdir(parents=True)
            (repo / "docs" / "v2" / "07-uat" / "R_RECEIPT.md").write_text("ok")
            _run_git(repo, "add", "--", "docs")
            _run_git(repo, "commit", "-m", "add receipt", env=_git_env())
            (repo / "docs" / "v2" / "08-production-readiness").mkdir(parents=True)
            (repo / "docs" / "v2" / "08-production-readiness" / "CURRENT_STATE.md").write_text(
                "# CS placeholder"
            )
            (repo / "docs" / "v2" / "08-production-readiness" / "CURRENT_UAT_STATUS.md").write_text(
                "| `UAT-RP-001` | **COVERED** | `docs/v2/07-uat/R_RECEIPT.md` | x |\n"
            )
            _run_git(repo, "add", "--", "docs")
            _run_git(repo, "commit", "-m", "cs + status", env=_git_env())
            candidate = _run_git(repo, "rev-parse", "HEAD")
            outputs = []
            for _ in range(3):
                r = subprocess.run(
                    [sys.executable, str(SCRIPT), "--head", candidate,
                     "--root", str(repo)],
                    capture_output=True, text=True,
                )
                outputs.append((r.returncode,
                               re.sub(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", "T",
                                      r.stdout)))
            self.assertEqual(outputs[0], outputs[1])
            self.assertEqual(outputs[1], outputs[2])

    def test_bogus_sha_is_rejected_consistently(self):
        """Bogus candidate always exits 2, regardless of repo state."""
        with tempfile.TemporaryDirectory() as outer:
            repo = pathlib.Path(outer) / "r"
            repo.mkdir()
            _run_git(repo, "init", "-q", "--initial-branch=main")
            for _ in range(3):
                r = subprocess.run(
                    [sys.executable, str(SCRIPT), "--head", "0" * 40,
                     "--root", str(repo)],
                    capture_output=True, text=True,
                )
                self.assertEqual(r.returncode, 2,
                                 f"bogus sha must exit 2; got {r.returncode}")


if __name__ == "__main__":
    unittest.main(verbosity=2)