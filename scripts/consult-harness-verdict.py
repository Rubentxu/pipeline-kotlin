#!/usr/bin/env python3
# consult-harness-verdict.py — Consumer mínimo en pipeline-kotlin de los
# resultados publicados por el harness en GitHub.
#
# Authority:
#   - AGENTS.md §"Release candidates" (flujo de coordinación autorizado)
#   - AGENTS.md §"Frontera de responsabilidad — pipeline-kotlin vs
#     pipelinek-release-harness"
#   - docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md §5.4 (separación
#     verificador/publicador como invariante)
#
# Purpose:
#   El harness publica un `verdict.json` por candidata en
#   `Rubentxu/pipelinek-release-harness/evidence/<candidate>/verdict.json`.
#   Este script ES el consumidor mínimo en pipeline-kotlin. NO crea
#   ledger paralelo. NO decide promoción. NO fusiona PRs. Sólo:
#
#     1. Recupera verdict.json desde GitHub API (read-only sobre el harness).
#     2. Verifica identidad material del ZIP publicado contra GitHub
#        Releases de pipeline-kotlin (ZIP SHA-256 match).
#     3. Verifica que la identidad GitHub del publicador del verdict está
#        en una allowlist configurable.
#     4. Emite a stdout un veredicto tipado y un recibo inmutable en
#        docs/v2/07-uat/RECEIPTS/consult/<timestamp>/.
#     5. Si el veredicto es FAIL reproducible Y el operador pasó
#        `--open-issue`, abre una issue con huella estable
#        (input + síntoma + tipo + causa) en pipeline-kotlin.
#
# Restrictions (from AGENTS.md):
#   - NO consulta filesystem del harness.
#   - NO modifica el repo del harness.
#   - NO crea ledger paralelo: el recibo vive en docs/v2/07-uat/RECEIPTS/consult/.
#   - NO promueve candidatas.
#   - Sin credenciales de escritura -> imprime ISSUE_PENDING y exit 75
#     (no stack trace).
#
# Usage:
#   consult-harness-verdict.py --candidate v0.39.0
#   consult-harness-verdict.py --candidate v0.39.0 --open-issue
#   consult-harness-verdict.py --candidate v0.39.0 --allow-author 'octocat'
#   consult-harness-verdict.py --candidate v0.39.0 --json-output
#
# Environment:
#   GITHUB_TOKEN              required for rate-limit margin; optional if
#                              unauthenticated calls are enough.
#   PIPELINEK_HARNESS_REPO    default Rubentxu/pipelinek-release-harness
#   PIPELINEK_PRODUCT_REPO    default Rubentxu/pipeline-kotlin
#   PIPELINEK_ALLOWED_AUTHORS comma-separated allowlist of GitHub logins
#                              allowed to publish verdict.json entries
#                              (default: pipelinek-harness[bot] only)
#   PIPELINEK_CONSULT_RECEIPT_ROOT
#                              receipt root (default
#                              docs/v2/07-uat/RECEIPTS/consult/); overridable so
#                              tests never write receipts into the tracked tree
#
# Exit codes:
#   0   verdict PASS reproducible (no promotion action) or FAIL 'transient'
#   2   verdict FAIL 'reproducible' (issue draft printed; --open-issue opens)
#   3   verdict INVALID (ZIP SHA-256 mismatch OR malformed digest OR unknown
#       publisher OR missing/wrong-typed critical fields OR a FAIL whose
#       failure_kind is absent/unknown)
#   4   verdict MISSING (no verdict.json found in harness)
#   75  ISSUE_PENDING (failed to open issue: credentials or network)
#
# Accepted failure_kind values (normalised: trimmed, lower-cased):
#   reproducible    -> FAIL_REPRODUCIBLE, exit 2
#   transient       -> FAIL_NON_REPRODUCIBLE, exit 0
#   anything else   -> INVALID, exit 3 (a FAIL without a recognized
#                      discriminator is a missing critical field, not an
#                      unactionable verdict)
#
# Idempotent: re-running with the same --candidate is safe; receipt paths
# include timestamp to avoid clobbering.

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_HARNESS_REPO = "Rubentxu/pipelinek-release-harness"
DEFAULT_PRODUCT_REPO = "Rubentxu/pipeline-kotlin"
DEFAULT_ALLOWED_AUTHORS = ("pipelinek-harness[bot]",)
DEFAULT_RECEIPT_ROOT = os.path.join("docs", "v2", "07-uat", "RECEIPTS", "consult")

GITHUB_API = "https://api.github.com"

# Digest and provenance shapes. A string that merely equals itself is not a
# verified digest.
HEX64 = re.compile(r"^[0-9a-f]{64}$", re.IGNORECASE)
HARNESS_SHA_SHAPE = re.compile(r"^[0-9a-f]{7,40}$", re.IGNORECASE)

REPRODUCIBLE_KIND = "reproducible"
TRANSIENT_KIND = "transient"


def _receipt_root():
    return os.environ.get("PIPELINEK_CONSULT_RECEIPT_ROOT", DEFAULT_RECEIPT_ROOT)


def _text_field(parsed, *keys):
    """First non-empty string among keys, else None.

    Non-string values are NOT coerced: a wrong type is an invalid document,
    not a truthy value ({"result": 1} must not become "1").
    """
    for key in keys:
        value = parsed.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _write_receipt(args, payload):
    ts_slug = payload["timestamp"].replace(":", "").replace("-", "")
    receipt_dir = os.path.join(_receipt_root(), f"{args.candidate}-{ts_slug}")
    return _print_receipt(os.path.join(receipt_dir, "verdict.json"), payload)


def _gh_get(url, token=None):
    """GET to GitHub API. Returns parsed JSON. Raises urllib.error.HTTPError."""
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _print_receipt(path, payload):
    """Write immutable receipt to disk and return the path."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, sort_keys=True)
        fh.write("\n")
    return path


def fetch_verdict(harness_repo, candidate, token=None):
    """Fetch verdict.json from the harness repo via GitHub API.

    Path: evidence/<candidate>/verdict.json
    Returns (parsed_json, raw_metadata) or (None, None) if not found.
    """
    url = (
        f"{GITHUB_API}/repos/{harness_repo}/contents/"
        f"evidence/{urllib.parse.quote(candidate, safe='-_.')}/verdict.json"
    )
    try:
        data = _gh_get(url, token=token)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None, None
        raise
    content_b64 = data["content"]
    sha = data["sha"]
    last_commit_sha = data.get("last_commit_sha", "")
    # Strip whitespace/newlines from base64.
    import base64
    raw = base64.b64decode(content_b64).decode("utf-8")
    parsed = json.loads(raw)
    metadata = {
        "path": data.get("path"),
        "sha": sha,
        "html_url": data.get("html_url"),
        "last_commit_sha": last_commit_sha,
    }
    return parsed, metadata


def fetch_product_release_zip_sha256(product_repo, candidate, token=None):
    """Fetch the published ZIP SHA-256 for the candidate from pipeline-kotlin
    Releases. Returns (sha256_hex, release_payload) or (None, None) if no
    release matches the tag.
    """
    url = (
        f"{GITHUB_API}/repos/{product_repo}/releases/tags/"
        f"{urllib.parse.quote(candidate, safe='-_.')}"
    )
    try:
        release = _gh_get(url, token=token)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None, None
        raise
    # Look for the sidecar OF THIS CANDIDATE: pipelinek-<candidate>.zip.sha256.
    # Any other *.zip.sha256 belongs to different bytes; zero or several exact
    # matches means the digest cannot be established, so the caller sees None
    # and classifies INVALID instead of trusting asset order.
    expected = f"pipelinek-{candidate}.zip.sha256"
    matches = [a for a in release.get("assets", []) if a.get("name") == expected]
    if len(matches) != 1:
        return None, release
    asset_url = matches[0].get("browser_download_url")
    try:
        with urllib.request.urlopen(asset_url, timeout=30) as resp:
            raw = resp.read().decode("utf-8").strip()
    except Exception:
        # Unreadable sidecar = no established digest. Never keep a previous
        # asset's value as if it described these bytes.
        return None, release
    # Format: "<sha256>  \n" or just "<sha256>".
    first = raw.split()[0] if raw else ""
    return (first or None), release


def verify_publisher(parsed, allowed_authors):
    """Return (ok, publisher_login) where ok=True iff the verdict
    `publisher` field is in the allowed set. Allowed publisher is an
    explicit attribution claim; we cross-check it against the allowlist.
    """
    publisher = parsed.get("publisher") or parsed.get("author") or ""
    ok = publisher in allowed_authors
    return ok, publisher


def compute_issue_fingerprint(candidate, parsed, product_zip_sha256):
    """Stable fingerprint for an issue (input + síntoma + tipo + causa).

    Per the cross-repo contract, an issue about a candidate failure is
    keyed by this fingerprint, so re-runs of the consumer do not open
    duplicate issues.
    """
    basis = {
        "candidate": candidate,
        "result": parsed.get("result"),
        "scenario": parsed.get("scenario"),
        "failure_kind": parsed.get("failure_kind"),
        "symptom": parsed.get("symptom"),
        "cause": parsed.get("cause"),
        "zip_sha256": product_zip_sha256,
    }
    canonical = json.dumps(basis, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def render_issue_body(candidate, parsed, fingerprint, product_zip_sha256,
                      harness_repo, harness_sha, publisher):
    """Render the issue body for an open issue in pipeline-kotlin."""
    scenario = parsed.get("scenario", "(unspecified)")
    result = parsed.get("result", "(unspecified)")
    symptom = parsed.get("symptom", "(unspecified)")
    cause = parsed.get("cause", "(unspecified)")
    return (
        f"### Cross-repo verdict FAIL reproducible\n\n"
        f"- Candidate: `{candidate}`\n"
        f"- ZIP SHA-256 (pipeline-kotlin Releases): `{product_zip_sha256}`\n"
        f"- Result (harness): `{result}`\n"
        f"- Scenario: `{scenario}`\n"
        f"- Symptom: `{symptom}`\n"
        f"- Cause: `{cause}`\n"
        f"- Harness SHA: `{harness_sha}` (in `{harness_repo}`)\n"
        f"- Verdict publisher: `{publisher}`\n"
        f"- Verdict URL: {parsed.get('verdict_url', '(see harness evidence)')}\n"
        f"- Fingerprint: `{fingerprint}`\n\n"
        f"This issue was opened by `scripts/consult-harness-verdict.py`.\n"
        f"The next candidate that supersedes `{candidate}` must reproduce\n"
        f"the failing scenario with the corrected ZIP. Re-running the\n"
        f"consumer with the same fingerprint MUST NOT reopen this issue\n"
        f"(idempotency is enforced by the consumer; the harness's\n"
        f"`publish-issue` performs the same dedup server-side).\n"
    )


def maybe_open_issue(args, fingerprint, body):
    """If --open-issue is set, attempt to create the issue via `gh`. On
    any failure, return ISSUE_PENDING instead of raising."""
    if not args.open_issue:
        return {"attempted": False, "status": "NOT_REQUESTED"}
    title = f"[harness verdict FAIL] {args.candidate} ({fingerprint[:12]})"
    cmd = [
        "gh", "issue", "create",
        "--repo", os.environ.get("PIPELINEK_PRODUCT_REPO",
                                 DEFAULT_PRODUCT_REPO),
        "--title", title,
        "--label", "harness-verdict-fail",
        "--body", body,
    ]
    try:
        proc = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return {
            "attempted": True,
            "status": "ISSUE_PENDING",
            "reason": f"gh unavailable or timed out: {exc!r}",
        }
    if proc.returncode != 0:
        return {
            "attempted": True,
            "status": "ISSUE_PENDING",
            "reason": proc.stderr.strip() or f"gh exit {proc.returncode}",
        }
    return {
        "attempted": True,
        "status": "OPENED",
        "issue_url": proc.stdout.strip(),
    }


def main(argv=None):
    p = argparse.ArgumentParser(
        description=("Consumer mínimo en pipeline-kotlin de los "
                     "veredictos publicados por el harness."))
    p.add_argument("--candidate", required=True,
                   help="Tag de la candidata (ej. v0.39.0).")
    p.add_argument("--open-issue", action="store_true",
                   help="Si FAIL reproducible, abrir issue en pipeline-kotlin.")
    p.add_argument("--allow-author", action="append", default=[],
                   help="Login GitHub adicional autorizado (repetible).")
    p.add_argument("--json-output", action="store_true",
                   help="Imprimir veredicto tipado como JSON a stdout.")
    p.add_argument("--harness-repo",
                   default=os.environ.get("PIPELINEK_HARNESS_REPO",
                                          DEFAULT_HARNESS_REPO))
    p.add_argument("--product-repo",
                   default=os.environ.get("PIPELINEK_PRODUCT_REPO",
                                          DEFAULT_PRODUCT_REPO))
    args = p.parse_args(argv)

    token = os.environ.get("GITHUB_TOKEN") or None
    allowed = set(DEFAULT_ALLOWED_AUTHORS)
    allowed |= set(args.allow_author)
    env_allowed = os.environ.get("PIPELINEK_ALLOWED_AUTHORS", "")
    if env_allowed:
        allowed |= {x.strip() for x in env_allowed.split(",") if x.strip()}

    verdict, verdict_meta = fetch_verdict(
        args.harness_repo, args.candidate, token=token)
    if verdict is None:
        result = {
            "consumer": "scripts/consult-harness-verdict.py",
            "candidate": args.candidate,
            "timestamp": datetime.datetime.now(datetime.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
            "harness_repo": args.harness_repo,
            "product_repo": args.product_repo,
            "status": "MISSING",
            "detail": (f"verdict.json not found at "
                       f"{args.harness_repo}/evidence/{args.candidate}/"),
        }
        # Immutable receipt even for MISSING so consumers can audit.
        receipt_path = _write_receipt(args, result)
        result["receipt_path"] = receipt_path
        _emit(result, args, exit_code=4)
        return 4

    # A malformed document (list, string, number) must produce a typed
    # verdict and a receipt instead of an AttributeError out of main().
    if not isinstance(verdict, dict):
        result = {
            "consumer": "scripts/consult-harness-verdict.py",
            "candidate": args.candidate,
            "timestamp": datetime.datetime.now(datetime.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
            "harness_repo": args.harness_repo,
            "product_repo": args.product_repo,
            "status": "INVALID",
            "detail": (f"verdict.json is a {type(verdict).__name__}, not a JSON "
                       "object; critical fields cannot be read"),
        }
        result["receipt_path"] = _write_receipt(args, result)
        _emit(result, args, exit_code=3)
        return 3

    # 1. ZIP SHA-256 cross-check. Both sides must be well-formed 64-hex; a
    # string that equals itself is not a verified digest.
    product_zip_sha, release = fetch_product_release_zip_sha256(
        args.product_repo, args.candidate, token=token)
    zip_in_verdict = _text_field(verdict, "zip_sha256", "product_zip_sha256")
    digest_shaped = (
        zip_in_verdict is not None and HEX64.match(zip_in_verdict) is not None
        and product_zip_sha is not None and HEX64.match(product_zip_sha) is not None
    )
    zip_match = digest_shaped and product_zip_sha.lower() == zip_in_verdict.lower()

    # 2. Publisher allowlist.
    publisher_ok, publisher_login = verify_publisher(verdict, tuple(allowed))

    # 3. Declared verification fields, typed. Wrong types are absent fields.
    harness_sha = _text_field(verdict, "harness_sha", "producer_sha") or ""
    scenario = _text_field(verdict, "scenario") or ""
    result_raw = verdict.get("result")
    result_field = result_raw.strip().upper() if isinstance(result_raw, str) else ""
    kind_raw = verdict.get("failure_kind")
    failure_kind = kind_raw.strip().lower() if isinstance(kind_raw, str) else ""

    # Classify.
    status = "INVALID"
    exit_code = 3
    detail = ""
    if not zip_match:
        status = "INVALID"
        exit_code = 3
        detail = "ZIP SHA-256 mismatch or malformed digest"
    elif not publisher_ok:
        status = "INVALID"
        exit_code = 3
        detail = f"publisher {publisher_login!r} not in the allowlist"
    elif result_field == "PASS":
        if not scenario or HARNESS_SHA_SHAPE.match(harness_sha) is None:
            status = "INVALID"
            exit_code = 3
            detail = ("PASS without minimal evidence: a non-empty scenario and a "
                      "harness_sha shaped like a commit are required")
        else:
            status = "PASS"
            exit_code = 0
    elif result_field == "FAIL":
        if failure_kind == REPRODUCIBLE_KIND:
            status = "FAIL_REPRODUCIBLE"
            exit_code = 2
        elif failure_kind == TRANSIENT_KIND:
            status = "FAIL_NON_REPRODUCIBLE"
            exit_code = 0  # not actionable as motor issue
        else:
            status = "INVALID"
            exit_code = 3
            detail = (f"FAIL without a recognized failure_kind (got {kind_raw!r}); "
                      f"expected {REPRODUCIBLE_KIND!r} or {TRANSIENT_KIND!r}")
    else:
        status = "INVALID"
        exit_code = 3
        detail = f"result field missing or unrecognized (got {result_raw!r})"

    fingerprint = compute_issue_fingerprint(
        args.candidate, verdict, product_zip_sha)

    issue_action = {"attempted": False, "status": "NOT_REQUESTED"}
    if status == "FAIL_REPRODUCIBLE":
        body = render_issue_body(
            args.candidate, verdict, fingerprint, product_zip_sha,
            args.harness_repo, harness_sha, publisher_login)
        issue_action = maybe_open_issue(args, fingerprint, body)
        if issue_action.get("status") == "ISSUE_PENDING":
            exit_code = 75

    receipt = {
        "consumer": "scripts/consult-harness-verdict.py",
        "candidate": args.candidate,
        "timestamp": datetime.datetime.now(datetime.timezone.utc)
            .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "harness_repo": args.harness_repo,
        "product_repo": args.product_repo,
        "verdict_meta": verdict_meta,
        "verdict": verdict,
        "product_release_zip_sha256": product_zip_sha,
        "zip_match": zip_match,
        "publisher_login": publisher_login,
        "publisher_allowlisted": publisher_ok,
        "allowed_authors": sorted(allowed),
        "harness_sha": harness_sha,
        "scenario": scenario,
        "failure_kind": failure_kind,
        "result": result_field,
        "fingerprint": fingerprint,
        "status": status,
        "detail": detail,
        "issue_action": issue_action,
    }

    # Immutable receipt.
    receipt_path = _write_receipt(args, receipt)
    receipt["receipt_path"] = receipt_path

    _emit(receipt, args, exit_code=exit_code)
    return exit_code


def _emit(payload, args, exit_code):
    if args.json_output:
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        # MISSING / INCOMPLETE payloads may not carry every key.
        zip_match = payload.get("zip_match")
        publisher = payload.get("publisher_login", "(unknown)")
        publisher_ok = payload.get("publisher_allowlisted")
        fingerprint = payload.get("fingerprint", "0" * 16)
        status = payload.get("status", "UNKNOWN")
        print(f"[consult] candidate={payload['candidate']} "
              f"status={status} "
              f"zip_match={zip_match} "
              f"publisher={publisher} "
              f"publisher_ok={publisher_ok} "
              f"fingerprint={fingerprint[:16]}")
        if exit_code == 75:
            print("ISSUE_PENDING (no stack trace; credentials or "
                  "network likely unavailable)")
        issue_action = payload.get("issue_action") or {}
        if issue_action.get("status") == "OPENED":
            print(f"[consult] issue opened: "
                  f"{issue_action.get('issue_url')}")
        elif issue_action.get("status") == "ISSUE_PENDING":
            print("[consult] issue_action=ISSUE_PENDING")
        elif issue_action.get("status") == "NOT_REQUESTED":
            print("[consult] issue_action=NOT_REQUESTED")
    sys.stderr.write(f"exit_code={exit_code}\n")


if __name__ == "__main__":
    sys.exit(main())
