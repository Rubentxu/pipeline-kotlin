#!/usr/bin/env python3
"""Lightweight offline package validation. Does not validate the remote product."""
from pathlib import Path
import importlib.util
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
  "README.md", "AGENT_PROMPT.md", "tools/integrate.py", "tools/validate_package.py",
  "overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md",
  "overlay/docs/v2/05-roadmap/DELIVERY_FIRST_AGENT_TEAMS.md",
  "overlay/docs/v2/03-specifications/DELIVERY_FIRST_IMPLEMENTATION_DELTA.md",
  "overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md",
  "overlay/docs/v2/07-uat/DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md",
}
actual = {str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file() and not "__pycache__" in str(p)}
missing = EXPECTED - actual
assert not missing, f"files missing: {missing}"
for rel in EXPECTED:
    if not rel.endswith(".md"):
        continue
    content = (ROOT / rel).read_text(encoding="utf-8")
    assert len(content) > 150, rel
    assert "\x00" not in content, rel
norms = ["ROADMAP.md", "QUALITY_VALUE_ADDENDUM.md", "ADR-PK-QV-001", "ADR-PK-QV-003", "delivery-first-testing"]
body = "\n".join((ROOT / rel).read_text(encoding="utf-8") for rel in EXPECTED if rel.endswith(".md"))
for norm in norms:
    assert norm in body, f"not referenced: {norm}"
for needle in ["status: proposed", "AGENTS.md", "# ADR-PK-DF-001"]:
    assert needle in (ROOT / "overlay/docs/v2/04-adrs/ADR-PK-DF-001-release-asset-policy-transition.md").read_text(), needle
print(f"PACKAGE_OK files={len(actual)} expected={len(EXPECTED)}; docs/ADRs present, no product test run")
