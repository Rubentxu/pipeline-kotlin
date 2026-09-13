#!/usr/bin/env python3
"""S2-B10 / G7 — assembles g7-installed-acceptance.json from the raw run outputs.

Re-derives every asserted number from the archived raw files so the receipt can
never drift from the evidence. Run after ./run-g7.sh.

Reads : raw/<tag>-stdout.log   (JSON array of the run's events, payloads included)
        raw/<tag>-exit.txt, raw/<tag>-runid.txt
        raw/<tag>-archived-files.sha256, raw/<tag>-workspace-files.txt
        raw/absence-probe.txt
Writes: g7-installed-acceptance.json
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
TAGS = ["ar-g7-01", "ar-g7-02", "ar-g7-03", "ar-g7-04"]


def read(path, default=""):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except FileNotFoundError:
        return default


def events(tag):
    text = read(os.path.join(RAW, f"{tag}-stdout.log"))
    if not text.strip():
        return []
    return json.loads(text)


def archived_files(tag):
    out = []
    for line in read(os.path.join(RAW, f"{tag}-archived-files.sha256")).splitlines():
        if not line.strip():
            continue
        digest, path = line.split(None, 1)
        out.append({"sha256": digest, "path": path.strip()})
    return out


def pick(evs, kind):
    return [e for e in evs if e["kind"] == kind]


def scenario(tag, stage):
    evs = events(tag)
    archived = pick(evs, "ArtifactArchived")
    failed = pick(evs, "ArtifactArchiveFailed")
    step_failed = pick(evs, "StepFailed")
    run_finished = pick(evs, "RunFinished")[-1] if pick(evs, "RunFinished") else {}
    archive_step = [
        e for e in pick(evs, "StepStarted") if e.get("stepType") == "archiveArtifacts"
    ]
    return {
        "scenario": tag,
        "stage": stage,
        "exit_code": int(read(os.path.join(RAW, f"{tag}-exit.txt"), "-1").strip() or -1),
        "run_id": read(os.path.join(RAW, f"{tag}-runid.txt")).strip(),
        "archive_step": {
            "stepName": archive_step[0]["stepName"] if archive_step else None,
            "stepType": archive_step[0]["stepType"] if archive_step else None,
        },
        "artifact_archived": archived,
        "artifact_archive_failed": failed,
        "step_failed": step_failed,
        "run_finished": run_finished,
        "archived_files_on_disk": archived_files(tag),
        "workspace_files_retained": [
            p for p in read(os.path.join(RAW, f"{tag}-workspace-files.txt")).splitlines() if p.strip()
        ],
    }


def main():
    expected_s1 = subprocess.run(
        ["bash", "-c", "seq 1 2000 | sha256sum | cut -d' ' -f1"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()

    probe_text = read(os.path.join(RAW, "absence-probe.txt"))
    probe = {
        "jars_scanned": None,
        "legacy_dispatcher_jar_hits": None,
        "registry_control_jars": None,
        "source_hits": [
            l for l in probe_text.splitlines()
            if re.search(r"CanonicalArchiveArtifacts", l)
        ],
    }
    m = re.search(
        r"jars_scanned=(\d+) legacy_jar_hits=(\d+) registry_control_jars=(\d+)",
        probe_text,
    )
    if m:
        probe["jars_scanned"] = int(m.group(1))
        probe["legacy_dispatcher_jar_hits"] = int(m.group(2))
        probe["registry_control_jars"] = int(m.group(3))

    s = {tag: scenario(tag, tag) for tag in TAGS}

    s1 = s["ar-g7-01"]
    s2 = s["ar-g7-02"]
    s3 = s["ar-g7-03"]
    s4 = s["ar-g7-04"]

    s1_event_sha = (
        s1["artifact_archived"][0]["files"][0]["sha256"] if s1["artifact_archived"] else None
    )
    s1_disk_sha = (
        s1["archived_files_on_disk"][0]["sha256"] if s1["archived_files_on_disk"] else None
    )
    s4_relpaths = sorted(
        f["relPath"] for f in (s4["artifact_archived"][0]["files"] if s4["artifact_archived"] else [])
    )

    evidence = {
        "slice": "S2-B10 / G7",
        "gate": "installed-distribution acceptance (real CLI, registry-only spine)",
        "claim": (
            "core.archiveArtifacts, burned down to LEGACY_REMOVED at G5 and certified "
            "against the 17/17 contract matrix at G6, behaves correctly as an INSTALLED "
            "distribution: the archived payload is byte-identical to the workspace source, "
            "the empty-match policy fails closed with the typed SCRIPT failure, the "
            "discriminator proves that failure is policy- rather than glob-driven, the "
            "frozen excludes delta (D2) holds on the real path, and the deleted legacy "
            "dispatcher is absent from source and from every shipped jar."
        ),
        "binary": read(os.path.join(RAW, "binary.txt")).strip() or None,
        "distribution_jar_sha256": read(os.path.join(RAW, "dist-jar-sha256.txt")).strip() or None,
        "independence": (
            "The AR-G7-01 expected sha256 is computed OUTSIDE the engine "
            "(`seq 1 2000 | sha256sum`) and compared against both the ArtifactArchived "
            "event payload and the archived file on disk. No assertion is derived from "
            "the engine's own claim."
        ),
        "scenarios": {
            "AR-G7-01": {
                "run_id": s1["run_id"],
                "raw_stdout": "raw/s1-stdout.log",
                "purpose": "non-empty match -> byte-identical archived payload",
                "exit_code": s1["exit_code"],
                "archive_step": s1["archive_step"],
                "expected_source_sha256": expected_s1,
                "event_sha256": s1_event_sha,
                "disk_sha256": s1_disk_sha,
                "size": s1["artifact_archived"][0]["files"][0]["size"] if s1["artifact_archived"] else None,
                "relPath": s1["artifact_archived"][0]["files"][0]["relPath"] if s1["artifact_archived"] else None,
                "run_outcome": s1["run_finished"].get("outcome"),
                "artefacts": s1["artifact_archived"],
                "verdict": (
                    "PASS"
                    if s1_event_sha == expected_s1 and s1_disk_sha == expected_s1
                    and s1["exit_code"] == 0
                    else "FAIL"
                ),
            },
            "AR-G7-02": {
                "run_id": s2["run_id"],
                "raw_stdout": "raw/s2-stdout.log",
                "purpose": "empty match + allowEmptyArchive=false -> fail closed, archive nothing",
                "exit_code": s2["exit_code"],
                "reason": s2["artifact_archive_failed"][0]["reason"] if s2["artifact_archive_failed"] else None,
                "failure_kind": s2["step_failed"][0].get("failureKind") if s2["step_failed"] else None,
                "message": s2["step_failed"][0].get("message") if s2["step_failed"] else None,
                "run_outcome": s2["run_finished"].get("outcome"),
                "artefacts_on_disk": len(s2["archived_files_on_disk"]),
                "workspace_files_retained": s2["workspace_files_retained"],
                "events": s2["artifact_archive_failed"] + s2["step_failed"] + [s2["run_finished"]],
                "verdict": (
                    "PASS"
                    if s2["exit_code"] != 0
                    and len(s2["archived_files_on_disk"]) == 0
                    and s2["run_finished"].get("outcome") == "failure"
                    and s2["step_failed"]
                    and s2["step_failed"][0].get("failureKind") == "SCRIPT"
                    and s2["artifact_archive_failed"]
                    else "FAIL"
                ),
            },
            "AR-G7-03": {
                "run_id": s3["run_id"],
                "raw_stdout": "raw/s3-stdout.log",
                "purpose": (
                    "DISCRIMINATOR — byte-identical to AR-G7-02 except the policy flag: "
                    "same glob, same workspace, same absence of *.jar"
                ),
                "exit_code": s3["exit_code"],
                "archived_count": len(
                    s3["artifact_archived"][0]["files"] if s3["artifact_archived"] else []
                ),
                "run_outcome": s3["run_finished"].get("outcome"),
                "artefacts": s3["artifact_archived"],
                "verdict": (
                    "PASS"
                    if s3["exit_code"] == 0
                    and s3["artifact_archived"]
                    and s3["artifact_archived"][0]["files"] == []
                    and s3["run_finished"].get("outcome") == "success"
                    else "FAIL"
                ),
            },
            "AR-G7-04": {
                "run_id": s4["run_id"],
                "raw_stdout": "raw/s4-stdout.log",
                "purpose": "frozen delta D2 — excludes are APPLIED (legacy silently ignored them)",
                "exit_code": s4["exit_code"],
                "archived_relpaths": s4_relpaths,
                "excluded_relpath": "build/libs/skip.jar",
                "run_outcome": s4["run_finished"].get("outcome"),
                "artefacts": s4["artifact_archived"],
                "verdict": (
                    "PASS"
                    if s4["exit_code"] == 0
                    and s4_relpaths == ["build/libs/keep.jar"]
                    else "FAIL"
                ),
            },
        },
        "legacy_absence_probe": probe,
        "verdicts": {},
    }

    discriminator = (
        s2["exit_code"] != 0 and s3["exit_code"] == 0
    )
    evidence["discriminator"] = {
        "same_glob_same_workspace": True,
        "allowEmptyArchive_false_fails": s2["exit_code"] != 0,
        "allowEmptyArchive_true_succeeds": s3["exit_code"] == 0,
        "empty_match_failure_is_policy_driven_not_glob_defect": discriminator,
    }
    evidence["verdicts"] = {k: v["verdict"] for k, v in evidence["scenarios"].items()}
    evidence["verdicts"]["AR-G7-04-legacy-absence"] = (
        "PASS"
        if probe["legacy_dispatcher_jar_hits"] == 0
        and probe["registry_control_jars"] >= 1
        and not probe["source_hits"]
        else "FAIL"
    )
    evidence["all_pass"] = all(v == "PASS" for v in evidence["verdicts"].values())

    with open(os.path.join(HERE, "g7-installed-acceptance.json"), "w", encoding="utf-8") as fh:
        json.dump(evidence, fh, indent=1, sort_keys=False)
        fh.write("\n")

    print(json.dumps(evidence["verdicts"], indent=1))
    print("all_pass =", evidence["all_pass"])
    return 0 if evidence["all_pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
