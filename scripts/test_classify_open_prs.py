#!/usr/bin/env python3
"""Unit tests for classify-open-prs.py.

Run:
    python3 scripts/test_classify_open_prs.py
"""
import importlib.util
import json
import pathlib
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "classify-open-prs.py"


def _load_mod():
    spec = importlib.util.spec_from_file_location("classify_open_prs", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _fake_pr(n, title, head="wu/test", author="Rubentxu", is_draft=False):
    return {
        "number": n,
        "title": title,
        "headRefName": head,
        "author": {"login": author},
        "isDraft": is_draft,
        "labels": [],
        "createdAt": "2026-09-26T00:00:00Z",
    }


class ClassifyOpenPrsTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_dependabot_is_dependency(self):
        pr = _fake_pr(70, "chore(deps): bump foo", head="dependabot/gradle/v2/foo-1.0")
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "DEPENDENCY")

    def test_rp053_is_supersede(self):
        pr = _fake_pr(95, "fix(workspace): route deleteDir through effective cwd (WU-RP-053)")
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "SUPERSEDE")

    def test_pr91_rebase_is_supersede(self):
        pr = _fake_pr(91, "perf(wu-rp-043): integration clean (rebased on main 0a62cb82)")
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "SUPERSEDE")

    def test_pr93_keep(self):
        pr = _fake_pr(93, "ci(actions): drop pull_request trigger on LPR-0 CI",
                       head="wu/disable-actions-pr-trigger-rp-harness")
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "KEEP")

    def test_docs_only_is_supersede(self):
        pr = _fake_pr(54, "docs: plan intelligent testing CLI", head="docs/test")
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "SUPERSEDE")

    def test_draft_is_close(self):
        pr = _fake_pr(100, "wip: something", is_draft=True)
        cat, _ = self.mod.classify(pr)
        self.assertEqual(cat, "CLOSE")

    def test_unknown_pr_is_needs_review_not_keep(self):
        """Claim: an unclassified PR is not silently declared KEEP.

        KEEP reads as "mergeable, scope aligned, no superseder", but the
        default branch runs no check at all. Mutation: restore the KEEP
        default, which is the fail-open this test kills.
        """
        pr = _fake_pr(999, "totally unrelated refactor", head="wu/random-thing")
        cat, why = self.mod.classify(pr)
        self.assertEqual(cat, "NEEDS_REVIEW")
        self.assertIn("needs human review", why.lower())

    def test_render_markdown_includes_all_prs(self):
        prs = [
            _fake_pr(70, "chore(deps): bump foo", head="dependabot/gradle/v2/foo"),
            _fake_pr(90, "fix(workspace): WU-RP-053"),
            _fake_pr(93, "ci: drop pr trigger"),
        ]
        classifications = [self.mod.classify(p) for p in prs]
        md = self.mod.render_markdown(prs, classifications)
        self.assertIn("#70", md)
        self.assertIn("#90", md)
        self.assertIn("#93", md)
        self.assertIn("KEEP", md)
        self.assertIn("SUPERSEDE", md)
        self.assertIn("DEPENDENCY", md)
        self.assertIn("Acceptance Criteria", md)

    def test_render_c1_count_is_derived_from_the_enumeration(self):
        """Claim: C1 states how many PRs were actually enumerated.

        Mutation: hardcode "All 25 OPEN PRs enumerated", which stays ticked
        at 0 PRs and at any other count.
        """
        prs = [_fake_pr(i, f"fix: change {i}") for i in (1, 2, 3)]
        classifications = [self.mod.classify(p) for p in prs]
        md = self.mod.render_markdown(prs, classifications)
        self.assertIn("3 OPEN PRs enumerated", md)
        self.assertNotIn("25 OPEN PRs", md)

    def test_render_refuses_length_mismatch(self):
        """Claim: a receipt cannot show fewer rows than it declares.

        Mutation: keep the silent zip() truncation: the summary says 4 PRs,
        the table shows 3, and the category sum is 3 while C1 stays ticked.
        """
        prs = [_fake_pr(i, f"fix: change {i}") for i in (1, 2, 3, 4)]
        classifications = [self.mod.classify(p) for p in prs[:3]]
        with self.assertRaises(ValueError):
            self.mod.render_markdown(prs, classifications)

    def test_render_c4_unticked_without_sha(self):
        """Claim: C4 cannot tick provenance the receipt does not carry.

        Mutation: keep the hardcoded "- [x] C4: SHA-stamped provenance"
        while the document contains no SHA at all.
        """
        prs = [_fake_pr(1, "fix: change")]
        classifications = [self.mod.classify(p) for p in prs]
        md = self.mod.render_markdown(prs, classifications)
        self.assertIn("[ ] C4", md)
        self.assertIn("NOT STAMPED", md)

    def test_render_c4_ticked_with_sha(self):
        """Claim: a stamped receipt names the SHA it was generated from."""
        prs = [_fake_pr(1, "fix: change")]
        classifications = [self.mod.classify(p) for p in prs]
        md = self.mod.render_markdown(prs, classifications, head_sha="deadbeef")
        self.assertIn("[x] C4", md)
        self.assertIn("deadbeef", md)

    def test_render_markdown_counts_correct(self):
        prs = [
            _fake_pr(70, "chore(deps): bump", head="dependabot/gradle/v2/foo"),
            _fake_pr(71, "chore(deps): bump", head="dependabot/gradle/v2/bar"),
            _fake_pr(90, "fix: WU-RP-053"),
            _fake_pr(93, "ci: drop pr"),
        ]
        classifications = [self.mod.classify(p) for p in prs]
        md = self.mod.render_markdown(prs, classifications)
        self.assertIn("**DEPENDENCY:** 2", md)
        self.assertIn("**SUPERSEDE:** 1", md)
        # The fourth PR matches no rule: it must surface as NEEDS_REVIEW,
        # not as the fail-open KEEP the previous contract asserted.
        self.assertIn("**NEEDS_REVIEW:** 1", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
