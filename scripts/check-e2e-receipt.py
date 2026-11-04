#!/usr/bin/env python3
"""The gate's reader of the E2E receipt (schema `pipelinek.e2e-receipt/2`).

The receipt is the launcher's own account of a cycle: which scenarios ran,
what each of them concluded, which runs it made, and -- for a scenario that
failed -- the trace that locates the cause. The gate CONSUMES that account,
and this script is the only reader of it, so there is exactly one thing that
decides what the receipt means.

Two subcommands, and the split is the point:

  check   read the receipt, decide whether it is a well formed account, and
          print the bounded summary an operator needs -- including the FIRST
          failure and the exact command that reproduces it.
  seal   write the gate's own verdict into the receipt's `gate` section, so
          the document that survives the run says what the run was.

THE TRIALS OF A RUN

A level that publishes a series of trials writes the series into the same
`gate` section: one entry per trial, with its clock, its verdict and the
receipt of that trial. The document that outlives the run then carries the
series, and not only the last trial of it: a receipt that named one trial of
three would let a series be quoted as a single run.

The worst clock of the series is derived here, from the entries themselves,
and not read from the caller: a worst that the caller states is a worst
nobody checked.

WHAT THE RECEIPT NAMES

The receipt names WHAT was verified. `sha` is the identifier of the content the
run verified, `head` is the commit that was HEAD, and `identity` says which of
the two the `sha` is: `commit` or `tree`. A commit and a tree have the same
shape -- forty hexadecimal characters -- so the reader needs the third field to
tell them apart, and it needs the two to agree with each other: an account that
calls its identifier a commit while naming something else would claim that a
commit passed when it was never built.

WHY THE RECEIPT FAILS THE GATE WHEN IT IS NOT WELL FORMED

The receipt is the traceability of the run. A receipt that is absent says
"nothing is known about what ran", and a receipt that is malformed says the
same thing in a louder voice. Neither is evidence, and a gate that published a
PASS next to either of them would be publishing a claim it never observed. So
the answer to an invalid receipt is failure, and the failure names the
receipt: a defect of the HARNESS is not reported as a defect of the product.

WHAT THE RECEIPT IS NOT

It is not a second census. The gate keeps its own reading of the tree, taken
before the stages ran, and compares it with the launcher's name by name; this
script only ADDS a third reading (the receipt's) to that contrast. A receipt
that agrees with the gate is a second witness, not a replacement.

The receipt carries no secret: the launcher refuses to publish one that does,
and this script does not invent a redaction of its own.

Usage:
  check-e2e-receipt.py check --receipt PATH [--census PATH] [--product PATH]
  check-e2e-receipt.py seal --receipt PATH --verdict V --scope S --wall-ms N
                            --budget-seconds N --tests-rewritten N --tests-read N
                            [--level LEVEL] [--trials JSON]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile

SCHEMA = "pipelinek.e2e-receipt/2"

# The verdicts a scenario can carry. `none` is the verdict of a scenario that
# was selected and produced no readable verdict: a declared third state, and
# not a pass.
VERDICTS = ("ok", "fail", "blocked", "none")

# The shape of a Git object identifier. A commit and a tree have the same
# shape, so the shape proves that the receipt carries SOMETHING Git wrote and
# not that it names the content the gate verified.
SHA_SHAPE = re.compile(r"[0-9a-f]{40}")


def fail(message: str) -> int:
    print(f"check-e2e-receipt: {message}", file=sys.stderr)
    return 1


def load(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def as_int(value: object) -> int:
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return 0
    return 0


def budget_seconds(value: str) -> int | None:
    """The budget of a run, or None when the run has no budget of its own.

    The wall-clock budget of this repository belongs to the certification: it
    is written for a build from nothing, and only that run measures one. The
    other levels publish their clock as a datum and are not compared with it.

    So the receipt records the budget only where there is one. A number in a
    document that outlives the run reads as a check that happened, and a
    reader who finds 900 there would read a change run as a budget check that
    it never received.
    """
    text = value.strip().lower()
    if text in ("", "none"):
        return None
    return int(text)


def parse_trials(value: str) -> list[dict]:
    """The series of trials, as the seal received it.

    A trial names its place in the series, its wall clock and its verdict.
    The three are the facts a reader needs to know whether the series is a
    series: a trial without a place is not part of one, and a trial without a
    clock is not a measurement.
    """
    text = (value or "").strip()
    if not text:
        return []
    try:
        parsed = json.loads(text)
    except ValueError as error:
        raise ValueError(f"the trials are not JSON: {error}") from error
    if not isinstance(parsed, list):
        raise ValueError("the trials are not a list")
    for row in parsed:
        if not isinstance(row, dict):
            raise ValueError("a trial is not an object")
        for key in ("index", "wallMs", "verdict"):
            if key not in row:
                raise ValueError(f"a trial does not carry {key!r}")
        for key in ("index", "wallMs"):
            if not isinstance(row[key], int) or isinstance(row[key], bool):
                raise ValueError(f"the {key} of a trial is not a number")
    return parsed


def trials_problems(document: dict) -> list[str]:
    """The problems the series of trials of the run has, if it has one.

    The series is OPTIONAL: a run that publishes one trial carries no series,
    and a reader that insisted on it would refuse an account that is otherwise
    sound.

    What it does NOT accept is a series whose worst clock is not the worst of
    its trials: the worst is the number the budget is compared with, and a
    worst that the document states and its own entries contradict is a number
    nobody can trust.
    """
    gate = document.get("gate")
    if not isinstance(gate, dict):
        return []
    trials = gate.get("trials")
    if trials is None:
        return []
    try:
        parse_trials(json.dumps(trials))
    except ValueError as error:
        return [f"the trials of the run are not a sound series: {error}"]
    worst = max(row["wallMs"] for row in trials)
    if gate.get("trialsWorstMs") != worst:
        return [
            f"the run says its worst trial was {gate.get('trialsWorstMs')} ms "
            f"and its own trials say {worst} ms"
        ]
    return []


def launch_problems(document: dict) -> list[str]:
    """The problems the census of the product's launches has.

    The census is OPTIONAL: a receipt written before the launcher counted its
    launches carries none, and a reader that insisted on the section would
    refuse an account that is otherwise sound.

    What it does NOT accept is a census that does not add up. The total and
    the breakdown are the same fact told twice, and a receipt whose two
    tellings disagree is a receipt nobody can read the cost of the suite from.
    """
    launches = document.get("productLaunches")
    if launches is None:
        return []
    if not isinstance(launches, dict):
        return ["the product launches are not an object"]
    total = launches.get("total")
    by_verb = launches.get("byVerb")
    if not isinstance(total, int) or isinstance(total, bool) or total < 0:
        return ["the total of the product launches is not a count"]
    if not isinstance(by_verb, dict):
        return ["the launches per verb are not an object"]
    counted = 0
    for name, value in by_verb.items():
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return [f"the launches of {name!r} are not a count"]
        counted += value
    if counted != total:
        return [f"the launches add up to {counted} and the receipt says {total}"]
    return []


def validate(document: object) -> list[str]:
    """The problems a receipt has, as a list of sentences. Empty means sound."""
    problems: list[str] = []

    if not isinstance(document, dict):
        return ["the document is not a JSON object"]

    schema = document.get("schema")
    if schema != SCHEMA:
        problems.append(f"the schema is {schema!r} and this reader only knows {SCHEMA!r}")

    for key in ("sha", "head", "identity", "generatedAt", "host", "jdk", "scope", "totals", "cache", "scenarios"):
        if key not in document:
            problems.append(f"the document carries no {key!r}")

    # The identity of the content the run verified.
    #
    # A commit and a tree have the SAME shape -- forty hexadecimal
    # characters -- so the receipt has to say which one it carries, and
    # the two facts have to agree with each other: a receipt that calls
    # its identifier a commit while naming something else would claim
    # that a commit passed when it was never built.
    sha = document.get("sha")
    head = document.get("head")
    identity = document.get("identity")
    if isinstance(sha, str) and sha != "unknown" and not SHA_SHAPE.fullmatch(sha):
        problems.append(f"the sha {sha!r} is neither a forty-character hexadecimal identifier nor 'unknown'")
    if isinstance(head, str) and head != "unknown" and not SHA_SHAPE.fullmatch(head):
        problems.append(f"the head {head!r} is neither a forty-character hexadecimal identifier nor 'unknown'")
    if identity not in ("commit", "tree", "unknown"):
        problems.append(f"the identity {identity!r} is not one of 'commit', 'tree' or 'unknown'")
    elif identity == "commit" and sha != head:
        problems.append(f"the identity is a commit and the sha {sha!r} is not the head {head!r}")
    elif identity == "tree" and sha == head:
        problems.append("the identity is a tree and the sha is the head, so the content is not named")
    elif identity == "unknown" and (sha != "unknown" or head != "unknown"):
        problems.append("the identity is unknown and the receipt names a sha or a head anyway")

    scope = document.get("scope")
    if not isinstance(scope, dict):
        problems.append("the scope is not an object")
    else:
        for key in ("filter", "gate", "selected"):
            if key not in scope:
                problems.append(f"the scope carries no {key!r}")

    problems.extend(launch_problems(document))

    scenarios = document.get("scenarios")
    if not isinstance(scenarios, list):
        problems.append("the scenarios are not a list")
        return problems

    totals = document.get("totals")
    if not isinstance(totals, dict):
        problems.append("the totals are not an object")
        return problems

    # The totals are not trusted: they are RECONCILED against the rows they
    # summarise. A total that does not add up is a receipt that counts
    # something the reader cannot see, and a number nobody can see is a
    # number nobody can trust.
    #
    # TWO populations, reconciled separately, because they answer different
    # questions:
    #
    #   executions  how many times the product was INVOKED. A scenario that
    #               replays its run durably invoked the product twice.
    #   runs        how many execution IDENTITIES the ledger recorded. The
    #               replay RESUMED the same run, so it added no identity.
    #
    # Reconciling one against the other would demand the two be equal, which
    # is false for every durable scenario, and a check that demands a false
    # thing fails for a reason the reader cannot see.
    checks = executions = runs_count = steps = 0
    verdicts = {"ok": 0, "fail": 0, "blocked": 0, "none": 0}
    # The census of the WORLDS, counted from the rows.
    #
    # It is counted here, from the per-scenario facts, and compared with the
    # totals the launcher published: the launcher counted the census while the
    # scenarios ran, and the receipt carries its answer. Two counts of one
    # fact, compared, is a check; two counts that are never compared are a
    # number nobody can trust.
    worlds_census = {"both": 0, "one": 0, "none": 0}
    names: list[str] = []
    for row in scenarios:
        if not isinstance(row, dict):
            problems.append("a scenario row is not an object")
            continue
        name = row.get("scenario")
        if not isinstance(name, str) or not name:
            problems.append("a scenario row carries no name")
        else:
            names.append(name)
        verdict = row.get("verdict")
        if verdict not in VERDICTS:
            problems.append(f"the scenario {name!r} carries the verdict {verdict!r}")
        else:
            verdicts[verdict] += 1
        checks += as_int(row.get("checks"))
        steps += as_int(row.get("steps"))
        declared = as_int(row.get("executions"))
        executions += declared
        # A WORLD is a place the scenario was checked in: with a store, or
        # without one. It is not an execution -- the durable replay is a second
        # execution in the SAME world -- so the two are counted apart, and a
        # scenario cannot be checked in more worlds than it made executions.
        worlds = as_int(row.get("worlds"))
        if worlds < 0:
            problems.append(f"the scenario {name!r} declares {worlds} world(s)")
        elif worlds > declared:
            problems.append(
                f"the scenario {name!r} declares {worlds} world(s) and {declared} execution(s):"
                " a world needs an execution"
            )
        if worlds >= 2:
            worlds_census["both"] += 1
        elif worlds == 1:
            worlds_census["one"] += 1
        else:
            worlds_census["none"] += 1
        runs = row.get("runs")
        if not isinstance(runs, list):
            problems.append(f"the scenario {name!r} carries no runs list")
            continue
        runs_count += len(runs)
        # A scenario cannot hold more identities than invocations. An identity
        # that appears without an invocation would be a run nobody started.
        if declared < len(runs):
            problems.append(
                f"the scenario {name!r} declares {declared} execution(s) and carries {len(runs)} run(s)"
            )
        for run in runs:
            if not isinstance(run, dict):
                problems.append(f"a run of the scenario {name!r} is not an object")
                continue
            if "runId" not in run:
                problems.append(f"a run of the scenario {name!r} carries no run id")

    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        problems.append(f"the scenario {duplicates[0]!r} appears more than once")

    if as_int(totals.get("scenarios")) != len(scenarios):
        problems.append(
            f"the totals say {as_int(totals.get('scenarios'))} scenario(s) and the document carries {len(scenarios)}"
        )
    for key, counted in (
        ("passed", verdicts["ok"]),
        ("failed", verdicts["fail"]),
        ("blocked", verdicts["blocked"]),
    ):
        if as_int(totals.get(key)) != counted:
            problems.append(
                f"the totals say {as_int(totals.get(key))} {key} scenario(s) and the rows carry {counted}"
            )
    if as_int(totals.get("checks")) != checks:
        problems.append(
            f"the totals say {as_int(totals.get('checks'))} check(s) and the rows carry {checks}"
        )
    if as_int(totals.get("executions")) != executions:
        problems.append(
            f"the totals say {as_int(totals.get('executions'))} execution(s) and the scenarios declare {executions}"
        )
    for key, counted in (
        ("worldsBoth", worlds_census["both"]),
        ("worldsOne", worlds_census["one"]),
        ("worldsNone", worlds_census["none"]),
    ):
        if as_int(totals.get(key)) != counted:
            problems.append(
                f"the totals say {as_int(totals.get(key))} {key} and the scenarios declare {counted}"
            )
    if as_int(totals.get("runs")) != runs_count:
        problems.append(
            f"the totals say {as_int(totals.get('runs'))} run(s) and the rows carry {runs_count}"
        )
    if as_int(totals.get("executions")) < as_int(totals.get("runs")):
        problems.append(
            f"the totals say {as_int(totals.get('executions'))} execution(s) and {as_int(totals.get('runs'))} run(s): "
            "a run without an invocation is impossible"
        )
    if as_int(totals.get("steps")) != steps:
        problems.append(f"the totals say {as_int(totals.get('steps'))} step(s) and the rows carry {steps}")

    # The scope says how many scenarios were selected, and the receipt says
    # how many it observed. A receipt that observed fewer than it selected is
    # a receipt with a gap in it, and a gap that is not named is a gap nobody
    # can act on.
    if isinstance(scope, dict):
        selected = as_int(scope.get("selected"))
        if selected and len(scenarios) != selected:
            problems.append(
                f"the scope selected {selected} scenario(s) and the receipt carries {len(scenarios)}"
            )

    return problems


def contrast(names: list[str], census: list[str]) -> list[str]:
    """The scenarios the receipt names and the census does not, and the other way."""
    # The order is the byte order, the same one `LC_ALL=C sort` gives, because
    # the census the gate keeps was made with it.
    receipt = sorted(set(names), key=lambda value: value.encode())
    planned = sorted(set(census), key=lambda value: value.encode())
    missing = [name for name in planned if name not in set(receipt)]
    unexpected = [name for name in receipt if name not in set(planned)]
    problems = []
    if missing:
        problems.append(f"the census plans {missing[0]!r} and the receipt does not carry it")
    if unexpected:
        problems.append(f"the receipt carries {unexpected[0]!r} and the census does not plan it")
    return problems


def read_census(path: str) -> list[str]:
    names = []
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            name = raw.strip()
            if name:
                names.append(name)
    return names


def first_failure(scenarios: list) -> dict | None:
    """The first scenario that did not pass, in the order the receipt carries."""
    for row in scenarios:
        if not isinstance(row, dict):
            continue
        if row.get("verdict") in ("ok",):
            continue
        return row
    return None


def print_first_failure(row: dict, product: str) -> None:
    """The bounded account of the first failure: what, why, and how to see it."""
    print("    the FIRST failure, bounded:")
    print(f"      scenario: {row.get('scenario', '')}")
    reason = str(row.get("reason", "")).strip()
    if reason:
        print(f"      reason:   {reason}")
    runs = row.get("runs") if isinstance(row.get("runs"), list) else []
    traces = row.get("traces") if isinstance(row.get("traces"), list) else []
    run_id = ""
    db = ""
    for run in runs:
        if isinstance(run, dict) and run.get("runId") not in (None, "", "none"):
            run_id = str(run.get("runId"))
            db = str(run.get("db", ""))
            break
    trace_id = ""
    for trace in traces:
        if isinstance(trace, dict) and trace.get("traceId"):
            trace_id = str(trace.get("traceId"))
            break
    print(f"      runId:    {run_id or '(no run id)'}")
    if trace_id:
        print(f"      traceId:  {trace_id}")
    if db and db != "-":
        print(f"      db:       {db}")
        print(f"      inspect:  {product} inspect {run_id} --db {db} --telemetry")


def command_check(args: argparse.Namespace) -> int:
    if not os.path.isfile(args.receipt):
        return fail(f"there is no receipt at {args.receipt}")
    try:
        document = load(args.receipt)
    except (OSError, ValueError) as error:
        return fail(f"the receipt at {args.receipt} could not be read: {error}")

    problems = validate(document)
    problems.extend(trials_problems(document))
    # The receipt has to name the content the GATE verified. The launcher
    # wrote the document from the environment the gate gave it, and this is
    # where that copy is checked against the gate's own answer: a receipt
    # that names another content would be an account of a run that did not
    # happen.
    for flag, expected, key in (
        ("--expected-sha", args.expected_sha, "sha"),
        ("--expected-head", args.expected_head, "head"),
        ("--expected-identity", args.expected_identity, "identity"),
    ):
        if expected and document.get(key) != expected:
            problems.append(f"the receipt names {key} {document.get(key)!r} and the gate verified {expected!r}")
    # The launch census the launcher PUBLISHED and the one the receipt CARRIES
    # are the same fact told twice. The gate read the published one to build
    # its verdict line, so the receipt has to agree with it: a receipt that
    # counts the launches differently is a receipt whose cost nobody can read.
    if args.launches is not None:
        launches = document.get("productLaunches")
        if not isinstance(launches, dict):
            problems.append("the receipt carries no product launches and the gate counted some")
        elif launches.get("total") != args.launches:
            problems.append(
                f"the launcher published {args.launches} launch(es) and the receipt carries "
                f"{launches.get('total')}"
            )
    if problems:
        for problem in problems:
            print(f"check-e2e-receipt: the receipt is not a sound account: {problem}", file=sys.stderr)
        return 1

    scenarios = document["scenarios"]
    totals = document["totals"]
    scope = document["scope"]

    print(
        "    receipt: ok  schema={}  sha={}  identity={}  head={}  scope={}  filter={}".format(
            document["schema"],
            document.get("sha", "unknown"),
            document.get("identity", "unknown"),
            document.get("head", "unknown"),
            scope.get("gate", "unknown"),
            scope.get("filter", ""),
        )
    )
    print(
        "    receipt: scenarios={} passed={} failed={} blocked={} checks={} executions={} runs={} steps={}".format(
            totals.get("scenarios"),
            totals.get("passed"),
            totals.get("failed"),
            totals.get("blocked"),
            totals.get("checks"),
            totals.get("executions"),
            totals.get("runs"),
            totals.get("steps"),
        )
    )

    if args.census:
        if not os.path.isfile(args.census):
            return fail(f"there is no census at {args.census}")
        census = read_census(args.census)
        names = [row.get("scenario", "") for row in scenarios if isinstance(row, dict)]
        difference = contrast(names, census)
        if difference:
            for problem in difference:
                print(f"check-e2e-receipt: the receipt and the census differ: {problem}", file=sys.stderr)
            return 1
        print(f"    receipt: census=ok planned={len(census)} observed={len(names)}")

    row = first_failure(scenarios)
    if row is not None:
        print_first_failure(row, args.product)

    return 0


def command_seal(args: argparse.Namespace) -> int:
    if not os.path.isfile(args.receipt):
        return fail(f"there is no receipt at {args.receipt}")
    try:
        document = load(args.receipt)
    except (OSError, ValueError) as error:
        return fail(f"the receipt at {args.receipt} could not be read: {error}")

    try:
        trials = parse_trials(args.trials)
    except ValueError as error:
        return fail(f"the trials of the run could not be read: {error}")

    document["gate"] = {
        "verdict": args.verdict,
        "scope": args.scope,
        "level": args.level,
        "wallMs": args.wall_ms,
        "budgetSeconds": args.budget_seconds,
        "testsRewritten": args.tests_rewritten,
        "testsRead": args.tests_read,
        "sealedAt": args.sealed_at,
    }

    # The series of trials, when the run published one. The worst clock is
    # DERIVED from the entries, so the document cannot claim a worst that its
    # own trials contradict.
    if trials:
        document["gate"]["trials"] = trials
        document["gate"]["trialsWorstMs"] = max(row["wallMs"] for row in trials)
        if args.trials_evidence_index:
            document["gate"]["trialsEvidenceIndex"] = args.trials_evidence_index

    # The seal is written to a name of its own and then MOVED into place, so a
    # reader never sees a receipt with half a gate section in it.
    directory = os.path.dirname(os.path.abspath(args.receipt)) or "."
    handle, tmp_path = tempfile.mkstemp(dir=directory, prefix=".receipt-", suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(document, out, indent=2, ensure_ascii=False, sort_keys=False)
            out.write("\n")
        os.replace(tmp_path, args.receipt)
    except OSError as error:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        return fail(f"the receipt could not be sealed: {error}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="read and seal the E2E receipt of the gate")
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser("check", help="validate the receipt and print the bounded summary")
    check.add_argument("--receipt", required=True)
    check.add_argument("--census", default="")
    check.add_argument("--product", default="pipelinek")
    check.add_argument("--expected-sha", default="")
    check.add_argument("--expected-head", default="")
    check.add_argument("--expected-identity", default="")
    check.add_argument("--launches", type=int, default=None)
    check.set_defaults(handler=command_check)

    seal = subparsers.add_parser("seal", help="write the gate's verdict into the receipt")
    seal.add_argument("--receipt", required=True)
    seal.add_argument("--verdict", required=True)
    seal.add_argument("--scope", required=True)
    seal.add_argument("--level", default="certificacion")
    seal.add_argument("--wall-ms", dest="wall_ms", type=int, required=True)
    seal.add_argument("--budget-seconds", dest="budget_seconds", type=budget_seconds, default=None)
    seal.add_argument("--tests-rewritten", dest="tests_rewritten", type=int, default=0)
    seal.add_argument("--tests-read", dest="tests_read", type=int, default=0)
    seal.add_argument("--sealed-at", dest="sealed_at", default="")
    seal.add_argument("--trials", dest="trials", default="")
    seal.add_argument("--trials-evidence-index", dest="trials_evidence_index", type=int, default=0)
    seal.set_defaults(handler=command_seal)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
