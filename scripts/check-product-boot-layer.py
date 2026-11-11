#!/usr/bin/env python3
"""The layer that boots a product lives in a harness, not in this tree.

THE GUARANTEE
  This repository certifies a tree with three legs: unit tests, integration
  tests with mocks and mutants. It holds no layer that boots the product: no
  scenario suite, no acceptance harness, no launcher of the product under
  test, no receipt of a booted run and no executable end-to-end program.

  A layer like that belongs to a harness repository, which certifies the
  PUBLISHED artefact against the contract of the product and keeps the record
  of that certification in its own board. The tree that certifies the tree
  does not carry it: a booting layer that lives next to the code it certifies
  certifies nothing, because it cannot fail for a reason the code does not
  have.

WHY A SCRIPT AND NOT A LINTER RULE
  The guarantee is about the SHAPE of what the tree holds, not about the shape
  of a piece of Kotlin: a scenario is a file by extension, a launcher is a
  file by permission, a family map is a file by extension, and an init script
  is a file by name. No analysis of the sources can see any of them.

  The same guarantee is also about the tree as a WHOLE: a single file that
  boots the product is enough to break it, and that file is not in any
  module, so no module-scoped rule would ever look at it.

THE FOUR SHAPES, MEASURED
  The vocabulary here is closed on purpose. It was not guessed: the layer that
  was taken out of this tree was measured, and it was made of these shapes.

    a scenario      a pipeline definition: the file the product executes.
    a launcher      an executable program that starts the product under test.
    a family map    the table that says which module runs which family of
                    booted scenarios.
    an init script  the Gradle init script that rewires the build for that
                    layer.

  Each shape is matched by what it IS, so the layer cannot come back under
  another name: a scenario with another name is still a `.pipeline.kts`.

WHAT IS DECLARED, AND WHY IT IS DECLARED BY NAME
  A program is not forbidden by its kind: the repository's own gate, its own
  guards, the installer and the release tooling are programs, and they are
  product content or the machinery of publication. They are DECLARED, one by
  one, with the reason each one is here, and a program that is not declared
  is a failure.

  A file is a program by any of three signals, and each signal sees what the
  others do not: the extension (a script run by naming its interpreter, with
  no mark of its own), the mode the tree records (a program the tree holds
  executable) and the mark itself (a program the shell runs). A file that is
  a program by none of them is a text file, whatever its name suggests.

  That is the point of the declaration: adding a program to this tree is an
  act a reader can see. The list is closed, and a declared program that is
  gone is a failure too, so the declaration cannot rot into a list of names
  that would quietly allow whatever arrives next.

  The programs that put a PUBLISHED artefact in front of a consumer are the
  external compatibility check of the release and the staging of the Maven
  bundle. Both are declared with their reason: they compare the artefact that
  leaves the tree against the consumption contract, so what is under test is
  the publication contract and not this tree's behaviour. Neither is part of
  this gate.

WHAT IS SCANNED
  The working tree, not the Git index: the guarantee is about the tree that
  is about to be certified, and an index-only scan would not see a file that
  has been written and not added yet -- a green that arrives before the file
  does. Build output and ignored files are skipped: they are generated, they
  are not the product, and a rule that fired on generated code would be noise.

THE DECLARED ROOTS
  The layer that boots a product is a SET: a scenario, a launcher, a family
  map and an init script. This tree holds none of the four, and the rule
  that says so counts by SHAPE, so it sees a scenario wherever it is.

  A ROOT can be DECLARED, and then the scenarios under it are not a layer
  that boots a product: they are documentation. The catalogue of examples
  is the case this tree has: a pipeline definition that a reader runs to see
  what a capability does. Nobody in this tree runs them; the release
  harness runs them, against the published candidate.

  The declaration opens the SCENARIO and nothing else. A launcher under the
  declared root is still a launcher, a family map is still a family map and
  an init script is still an init script: the exception is about what a
  pipeline definition is for, not about where a file sits.

  A declared root that is no longer there is a failure, for the same reason
  a declared program that is no longer there is: a declaration that can
  disappear without saying anything is a declaration that governs nothing.

Exit status is 0 when the tree holds no layer that boots a product, and 1
with the list of the offenders when it does.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

# The extension of a pipeline definition: the file the product executes.
SCENARIO_SUFFIX = ".pipeline.kts"

# The extension of the table that maps a module to a family of booted
# scenarios.
FAMILY_MAP_SUFFIX = ".tsv"

# The names of the Gradle init scripts that rewire the build for that layer.
INIT_SCRIPT_SUFFIXES = (".init.gradle", ".init.gradle.kts")

# The extensions that mark a file as a program. A script that has no mark of
# its own is still a program when it is run by naming its interpreter, so the
# extension is a signal of its own and not a synonym of the mark.
PROGRAM_SUFFIXES = (
    ".sh", ".bash", ".zsh", ".ksh", ".py", ".pl", ".rb", ".bat", ".ps1",
)

# Every executable program this tree is allowed to hold, with the reason it
# is here. A program that is not in this table is a failure, and a program in
# this table that is gone is a failure too.
DECLARED_EXECUTABLES = {
    "pipelinek/gradlew": (
        "the wrapper of the build: it builds the product and runs no scenario"
    ),
    "pipelinek/gradlew.bat": (
        "the wrapper of the build for Windows: it builds the product and runs "
        "no scenario"
    ),
    "pipelinek/pipeline-application/launcher/pipelinek": (
        "the launcher of the distribution: product content shipped in the "
        "archive, not a layer that certifies the tree"
    ),
    "scripts/verify-all.sh": (
        "the gate of this repository: it reads the tree, builds it and runs "
        "its three legs"
    ),
    "scripts/check-budgets.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-catalogue-shape.py": (
        "a guard of the gate: it reads the catalogue of examples and starts "
        "nothing"
    ),
    "scripts/check-comment-provenance.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-compiler-wire-authority.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-compat-comparison-selection.py": (
        "a guard of the gate: it runs the compatibility gate in its "
        "selection-only mode, which reads the published releases and starts "
        "nothing of the product"
    ),
    "scripts/check-durable-shell-cost.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-kdoc-coverage.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-kdoc-throws.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-process-exits.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-signing-key-trust.py": (
        "a guard of the gate: it reads the declaration of the signing keys and "
        "starts nothing"
    ),
    "scripts/check-product-boot-layer.py": (
        "this guard: it reads the tree and starts nothing"
    ),
    "scripts/check-published-reference.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-serializer-visibility.py": (
        "a guard of the gate: it reads the tree and starts nothing"
    ),
    "scripts/check-sdk-bundle.py": (
        "a guard of the gate: it reads the staged bundle, recomputes its "
        "digests and verifies its signatures, and it starts nothing of the "
        "product"
    ),
    "scripts/bench-service-cost.py": (
        "a measurement of what the local service costs: it times an installed "
        "distribution against a table of figures with no threshold, asserts "
        "nothing and certifies nothing about the tree"
    ),
    "scripts/build-oci-image.sh": (
        "the builder of the OCI profile: it packages the distribution that "
        "leaves this tree into an image and starts nothing of the product"
    ),
    "scripts/install-pipelinek.sh": (
        "the installer of a published release: it installs the archive for a "
        "user and asks the installed command for its version"
    ),
    "scripts/read-junit-reports.py": (
        "the reader of the gate's census: it parses the JUnit reports of a "
        "lane and starts nothing"
    ),
    "scripts/release-pipelinek.sh": (
        "the gate of the publication: it publishes the artifact and certifies "
        "nothing about the behaviour of the tree"
    ),
    "scripts/release/build-release-manifest.sh": (
        "publication: it describes the artifact that leaves"
    ),
    "scripts/release/build-maven-publication-manifest.sh": (
        "publication: it measures the staged Maven bundle and writes the manifest that "
        "describes it, and it starts nothing of the product"
    ),
    "scripts/release/check-sdk-bundle-control.sh": (
        "the negative control of the bundle checker: it proves that the checker refuses a "
        "bundle whose Gradle module metadata resolves a file the bundle does not carry, "
        "and it starts nothing of the product"
    ),
    "scripts/release/emit-certification-requested.sh": (
        "the emitter of the certification request: it composes the event that crosses the "
        "boundary between the repositories and leaves it beside the publication, so what "
        "leaves here is a request about the PUBLISHED artifact and not a certification of "
        "this tree's behaviour"
    ),
    "scripts/release/promote-stable.sh": (
        "publication: it moves the stable slot to a published release"
    ),
    "scripts/release/publish-github-release.sh": (
        "publication: it publishes the release on GitHub"
    ),
    "scripts/release/publish-tag.sh": (
        "publication: it cuts the tag that names the release"
    ),
    "scripts/release/read-signing-trust.sh": (
        "the reader of the declaration of the signing keys: the release scripts "
        "source it and it starts nothing"
    ),
    "scripts/release/resolve-release-digest.sh": (
        "publication: it resolves the digest of a published release"
    ),
    "scripts/release/sdkman-publish.sh": (
        "publication: it publishes the release to the SDKMAN channel"
    ),
    "scripts/release/stage-sdk-bundle.sh": (
        "publication: it stages and signs the Maven bundle and then compiles an "
        "independent consumer against the STAGED artefact, so what is under "
        "test is the publication contract and not the behaviour of this tree; it "
        "is not part of this gate"
    ),
    "scripts/release/verify-promotion-certification.sh": (
        "publication: it verifies that the certification of a candidate "
        "admits it to a stable release"
    ),
    "scripts/release/upload-sdk-bundle.sh": (
        "publication: it uploads the signed Maven bundle to the remote "
        "destination, and it re-verifies the bundle before it travels, so what "
        "is under test is the publication contract and not the behaviour of "
        "this tree; it is not part of this gate"
    ),
    "scripts/release/verify-external-compatibility.sh": (
        "the compatibility gate of the publication: it compares the PUBLISHED "
        "artifact against the PREVIOUSLY published one, so what is under test "
        "is the publication contract and not the behaviour of this tree; it is "
        "not part of this gate"
    ),
    "scripts/release/verify-maven-publication.sh": (
        "the publication gate of the Maven channel: it decides whether a request "
        "may publish a bundle, so what is under test is the publication contract "
        "and not the behaviour of this tree; it is not part of this gate"
    ),
    "scripts/release/verify-published-maven.sh": (
        "the consumer-side gate of the Maven channel: it asks a repository "
        "whether it serves the bytes the publication manifest declares, so what "
        "is under test is the publication contract and not the behaviour of this "
        "tree; it is not part of this gate"
    ),
    "scripts/release/verify-published-tags.sh": (
        "publication: it verifies the tags that were published"
    ),
    "scripts/release/verify-tag-signature.sh": (
        "publication: it verifies the signature of a published tag"
    ),
}

# The roots whose scenarios are DOCUMENTATION and not a layer that boots a
# product, with the reason each one is here.
#
# The declaration opens the scenario and nothing else: a launcher under one
# of these roots is still a launcher, and a family map is still a family
# map. A root that is declared and no longer there is a failure.
DECLARED_SCENARIO_ROOTS = {
    "examples/catalogue": (
        "the catalogue of examples: the pipeline definitions a reader runs to "
        "see what a capability does. The release harness runs them, against "
        "the published candidate, and this tree runs none of them"
    ),
}


def tree_files(repo: Path) -> list[tuple[str, str]]:
    """Every file of the working tree, with the mode Git records for it.

    The tracked files come with their mode, so a file that is executable in
    the tree is known by the tree and not by the machine that reads it. The
    untracked ones come with an empty mode: they are not in the tree yet, and
    the guard reads the working tree so that a file written and not added is
    seen anyway.

    The ignored files are skipped: they are generated output, not content.
    """
    tracked = subprocess.run(
        ["git", "ls-files", "--cached", "-s", "-z"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if tracked.returncode != 0:
        print("could not read the tree: git ls-files failed", file=sys.stderr)
        return []
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    )
    if untracked.returncode != 0:
        print("could not read the tree: git ls-files failed", file=sys.stderr)
        return []

    found: list[tuple[str, str]] = []
    for entry in tracked.stdout.split("\0"):
        if not entry:
            continue
        # "100644 <sha> 0\t<path>"
        meta, _, name = entry.partition("\t")
        found.append((name, meta.split(" ", 1)[0]))
    for name in untracked.stdout.split("\0"):
        if name:
            found.append((name, ""))
    return sorted(found)


def starts_a_program(path: Path) -> bool:
    """Whether the file opens with the mark of an executable program."""
    try:
        with path.open("rb") as handle:
            return handle.read(2) == b"#!"
    except OSError:
        return False


def is_a_program(name: str, mode: str, path: Path) -> bool:
    """Whether the file is a program, by any of the three signals it has.

    A program can be marked three ways, and each of them sees something the
    others do not: the extension (a script run by naming its interpreter, with
    no mark of its own), the mode (a program the tree holds executable) and
    the mark itself (a program run by the shell). A file that is a program by
    none of them is a text file, whatever its name suggests.
    """
    if name.endswith(PROGRAM_SUFFIXES):
        return True
    if mode == "100755":
        return True
    return starts_a_program(path)


def declared_root_of(name: str) -> str | None:
    """The declared root a path sits under, or None when it sits under none."""
    for root in DECLARED_SCENARIO_ROOTS:
        if name.startswith(root + "/"):
            return root
    return None


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    files = tree_files(repo)

    scenarios: list[str] = []
    documented: list[str] = []
    family_maps: list[str] = []
    init_scripts: list[str] = []
    programs: list[str] = []
    undeclared: list[tuple[str, str]] = []

    for name, mode in files:
        if name.endswith(SCENARIO_SUFFIX):
            if declared_root_of(name):
                documented.append(name)
            else:
                scenarios.append(name)
        if name.endswith(FAMILY_MAP_SUFFIX):
            family_maps.append(name)
        if name.endswith(INIT_SCRIPT_SUFFIXES):
            init_scripts.append(name)
        path = repo / name
        if not path.is_file():
            continue
        if is_a_program(name, mode, path):
            programs.append(name)
            if name not in DECLARED_EXECUTABLES:
                undeclared.append(
                    (name, "a program that this tree does not declare")
                )

    # A declared program that is no longer on disk is a failure: the
    # declaration is the list of what this tree holds, and a name that no
    # longer matches a file would quietly allow whatever arrives next.
    gone = [
        name for name in sorted(DECLARED_EXECUTABLES)
        if not (repo / name).is_file()
    ]

    # A declared root that is no longer there is a failure for the same
    # reason: the declaration has to govern something, or it is a name
    # that could be deleted without anyone noticing.
    missing_roots = [
        root for root in sorted(DECLARED_SCENARIO_ROOTS)
        if not (repo / root).is_dir()
    ]

    print("the layer that boots a product lives in a harness repository")
    print(f"  files read:             {len(files)}")
    print(f"  programs:               {len(programs)} found, {len(DECLARED_EXECUTABLES)} declared")
    print(f"  scenarios:              {len(scenarios)}")
    print(f"  declared as docs:       {len(documented)}")
    print(f"  family maps:            {len(family_maps)}")
    print(f"  init scripts:           {len(init_scripts)}")

    if scenarios or family_maps or init_scripts or undeclared or gone or missing_roots:
        for name in scenarios:
            print(f"  {name}: a scenario of the product under test")
        for name in family_maps:
            print(f"  {name}: the map of the families that boot the product")
        for name in init_scripts:
            print(f"  {name}: an init script that rewires the build for that layer")
        for name, reason in undeclared:
            print(f"  {name}: {reason}")
        for name in gone:
            print(f"  {name}: declared as an executable program and no longer there")
        for root in missing_roots:
            print(f"  {root}: declared as a root of documented scenarios and no longer there")
        print()
        print(
            "LAYER THAT BOOTS A PRODUCT: "
            f"{len(scenarios) + len(family_maps) + len(init_scripts) + len(undeclared) + len(gone) + len(missing_roots)}"
        )
        print(
            "  this tree certifies itself with unit tests, integration tests with "
            "mocks and mutants."
        )
        print(
            "  a layer that boots the product belongs to a harness repository, "
            "which keeps"
        )
        print(
            "  the record of what it certified in its own board, and certifies the "
            "published"
        )
        print("  artifact instead of the tree that produced it.")
        return 1

    print(
        "OK: no scenario, no launcher, no family map and no init script; "
        f"{len(programs)} programs, all of them declared"
    )
    if documented:
        print(
            f"  {len(documented)} pipelines are declared as documentation under "
            f"{', '.join(sorted(DECLARED_SCENARIO_ROOTS))}"
        )
    print(
        "  this tree holds no layer that boots a product, and this gate calls none"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
