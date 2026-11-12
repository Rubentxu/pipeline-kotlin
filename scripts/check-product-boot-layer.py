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

THE FIFTH SHAPE, AND WHY IT IS A SHAPE
  A BOOT CHECK: a row that STARTS the product and asks it something.

  It was missing here, and the hole was measured: the tree held two files
  that unpack the published distribution, start its launcher and ask the
  service it starts over a socket, and this guard said the tree held no layer
  that boots a product. The four shapes above could not see them because a
  test file is none of them: the launcher it starts lives in a temporary
  directory, and the file that starts it is a `.kt` of a test source set.

  A boot check is matched by what it IS, in two signals that only mean
  something together: the file NAMES the product --the ZIP the build
  publishes, the installation the build writes, the launcher of the
  distribution or the entry point of the product-- and it STARTS A PROCESS.
  A file that only names the product asserts about it, and a file that only
  starts a process starts something else.

  A boot check is not a failure BY ITSELF: the tree may hold declared ones.
  What is a failure is a boot check the declaration does not name, a
  declaration whose file is gone, a declared file that does not carry the tag
  the build excludes, and a tag that only one of the three knows.

THE SIXTH SHAPE, AND THE SPLIT BETWEEN THE TWO HALVES
  A boot check of the TREE: a row that boots the product of THIS tree.

  The fifth shape starts the PUBLISHED product: it unpacks the distribution,
  starts its launcher and asks the service it starts. The sixth shape starts
  the tree's own product: it composes the modules of this tree and starts a
  real child of them. Both are rows that boot a product, and they are NOT the
  same thing, because the two halves of the certification are about two
  different subjects:

    - the PUBLISHED product is certified by the harness, against the bytes it
      published and by their digest. The gate calls none of those rows.
    - the TREE is certified by the gate, at every change. The rows that boot
      the tree's own product are the tree's third leg, so the gate calls them.

  A row of the tree's own product is matched by what it IS too: a row of a
  test source set that opens one of the doors of the production code that
  boot it --the composition of the service that the product builds, or the
  port that launches a real child process.

  The declaration of these rows has the OPPOSITE rule of the other one: they
  carry NO tag, because the gate calls them, and a row that carries it would
  be a row the gate stops calling. What is a failure is a row that boots the
  tree's product and the declaration does not name it, a declaration whose
  file is gone, a declared file that does not boot the tree's product any
  more, and a declared file that carries the tag of the published boot
  checks.

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

Exit status is 0 when the tree holds no layer that boots a product and no
undeclared boot check, and 1 with the list of the offenders when it does.
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
    "pipelinek/pipeline-application/oci/entrypoint.sh": (
        "the entrypoint of the OCI profile: product content that ships in the "
        "image and prepares the substrate before the launcher serves it. The "
        "gate does not build the image and does not run it"
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

# The tag that marks a row that STARTS the product.
#
# The word is written here, in the rows that carry it and in the build that
# excludes them, and this guard is what says that those three are the same
# word: a tag that only one of the three knows excludes nothing.
BOOT_CHECK_TAG = "boot-check"

# The property the gate asks with, so that the build leaves the rows that
# carry the tag out of the lanes the gate runs.
BOOT_CHECK_PROPERTY = "pipelinek.excludeBootChecks"

# The signals that a file NAMES the product.
#
# They are the ways a test can reach the product: the ZIP the build
# publishes, the installation the build writes, the launcher of the
# distribution and the entry point of the product itself.
PRODUCT_SIGNALS = (
    "pipelinek.distribution.zip",
    "build/distributions",
    "build/install",
    "bin/pipelinek",
    "MainKt",
)

# The signals that a file STARTS A PROCESS.
PROCESS_SIGNALS = (
    "ProcessBuilder",
    "Runtime.getRuntime().exec",
)

# The source sets whose rows this guard reads. The product starts processes
# all day long --that is what it does-- and those files are not boot checks.
TEST_SOURCE_MARKERS = (
    "/src/test/",
    "/src/integrationTest/",
)

# The rows that START the product, with the reason each one is here.
#
# A boot check can go red for a reason the code does not have: the machine is
# busy, the port is taken, the clock of the machine is off. The gate certifies
# the TREE, so it calls none of these rows; they stay in the tree, they are
# declared here, and they carry the tag the build excludes.
#
# The certification of the PUBLISHED artefact is the harness's, and it is
# there that the bytes are certified by their digest. These rows are the
# tree's own, and the tree's own gate does not call them.
DECLARED_BOOT_CHECKS = {
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/InstalledProductBootIntegrationTest.kt": (
        "the rows that start the INSTALLED product: the launcher of the "
        "distribution and the service it starts, asked over a real socket. "
        "They are the tree's own check of the packaging it publishes, and the "
        "gate does not call them"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/OciEntrypointIntegrationTest.kt": (
        "the rows that start the INSTALLED product through the entrypoint of "
        "the OCI profile: the profile is a packaging of the published "
        "distribution, and the cold start they measure is a check of that "
        "packaging. The gate does not call them"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceTelemetryCorrelationBootIntegrationTest.kt": (
        "the rows that start the INSTALLED service with the live telemetry "
        "the operator gives it in the environment, and read what a run it "
        "starts exports to a local collector. The environment of a process "
        "is only given when it starts, so the guarantee can only be measured "
        "on a service started as its own process. The gate does not call them"
    ),
}

# The signals that a row BOOTS THE TREE'S OWN PRODUCT.
#
# The tree's product is started in two ways, and both are doors of the
# production code: the composition of the service that the product itself
# builds, and the supervisor of the runs with the port that launches a real
# child. A row that opens one of those doors boots the product of THIS tree.
#
# The second door is TWO signals and not one, because the port that launches
# a real child is also used by the tests of that port itself, and a row that
# opens the port is not the same as a row that boots the product: the first
# tests a port, the second starts a run of the service.
#
# These are names of the tree's own production API, and the guard says what
# happens when they change: a rename empties the census AND makes every
# declared row fail its own check, so the guard goes red and the change is
# noticed. What the guard does NOT do is follow a name: a row that boots the
# product through a door that does not exist yet would not be seen, and the
# declaration below is what says what the tree holds.
TREE_BOOT_SIGNALS = (
    ("composeServiceToServe",),
    ("ServiceRunSupervisor", "ProcessDurableTaskRuntime"),
)

# The rows that boot the TREE'S OWN product, with the reason each one is here.
#
# These rows certify the TREE: they compose the modules of this tree and
# check that the composition works with a REAL child, a REAL socket and a
# REAL store. The gate certifies this tree, so the gate calls them: they are
# the tree's own third leg, and the split says so.
#
# The other half of the split is the harness's: it certifies the PUBLISHED
# bytes, identified by their digest. The tree's rows never touch the
# published artefact and the harness's rows never run against the tree's
# source, so neither half certifies what the other half certifies.
DECLARED_TREE_BOOT_CHECKS = {
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceStartIntegrationTest.kt": (
        "the rows that start a run through the service and check that the run "
        "EXISTS: the child took its lease, minted its own id and printed the "
        "receipt that says so"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceCancelIntegrationTest.kt": (
        "the rows that cancel a run over the socket and check that the "
        "cancellation CLOSES the run: the child is ended and the terminal is "
        "durable for every reader afterwards"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceInputIntegrationTest.kt": (
        "the rows where a run asks a question and a client answers it over the "
        "socket, and the body runs only after the answer"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceCredentialBrokersIntegrationTest.kt": (
        "the rows that hand a credential to a run through a broker and check "
        "that the broker is gone when the run is"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ConfigurationProviderIntegrationTest.kt": (
        "the rows where a controller supplies the configuration of a run over "
        "the socket and the steps of that run see the values it answered"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/CredentialProviderIntegrationTest.kt": (
        "the rows where a controller keeps the credentials of a run over the "
        "socket and the step of that run receives the secret it answered"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/RunConfigurationIntegrationTest.kt": (
        "the rows that deliver a configuration to a run and check that each run "
        "sees its own and not the one next to it"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceParameterDescriptionIntegrationTest.kt": (
        "the rows where the service describes a script from the manifest its "
        "operator approved, and a run started with that description is "
        "accepted by the child that checks the values against its own "
        "declaration"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/RunConfigurationRestartIntegrationTest.kt": (
        "the rows that stop the service and start another over the same "
        "substrate, and check that the configuration of a run is still "
        "described from the run's own facts"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/RunParametersIntegrationTest.kt": (
        "the rows that deliver parameters to a run and check that the step "
        "reads the effective value"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/SupervisedRunEndIntegrationTest.kt": (
        "the row where the watchdog kills the child of a run and the run has "
        "to END with a terminal that says why: a run whose writer is gone "
        "cannot write its own bookend"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceSubstrateSchemaIntegrationTest.kt": (
        "the rows that serve a substrate and check that a substrate whose table "
        "of promises is missing is refused by name"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/ServiceTelemetryIntegrationTest.kt": (
        "the rows that serve and stop the service and check that the telemetry "
        "closes once, after everything else it opened"
    ),
    "pipelinek/pipeline-application/src/integrationTest/kotlin/dev/rubentxu/pipeline/v2/application/SubstrateInitIntegrationTest.kt": (
        "the rows that prepare a substrate and serve it"
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


def is_a_test_source(name: str) -> bool:
    """Whether the file is a row of a test source set.

    The product starts processes all day long --that is what it does-- and
    those files are not boot checks: a boot check is a row that starts the
    product to ask it something.
    """
    return any(marker in name for marker in TEST_SOURCE_MARKERS)


def reads(name: str, path: Path) -> str:
    """The text of the file, or the empty text when it cannot be read."""
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def boots_the_product(name: str, path: Path) -> bool:
    """Whether the file starts the product.

    Two signals, and only together: the file NAMES the product --the ZIP the
    build publishes, the installation the build writes, the launcher of the
    distribution or the entry point of the product-- and it STARTS A PROCESS.

    A file that only names the product asserts about it, and a file that only
    starts a process starts something else --a shell, a tool-- and neither of
    the two boots anything.

    The tag does not hide a boot check: the form is what decides, and a row
    that starts the product is a boot check whether it carries the tag or not.
    """
    if not is_a_test_source(name):
        return False
    text = reads(name, path)
    if not text:
        return False
    names_the_product = any(signal in text for signal in PRODUCT_SIGNALS)
    starts_a_process = any(signal in text for signal in PROCESS_SIGNALS)
    return names_the_product and starts_a_process


def carries_the_tag(path: Path) -> bool:
    """Whether the file carries the tag of a boot check."""
    return f'@Tag("{BOOT_CHECK_TAG}")' in reads("", path)


def boots_the_tree_product(name: str, path: Path) -> bool:
    """Whether the file boots the product of THIS tree.

    The tree's product is started in two ways, and both are doors of the
    production code: the composition of the service that the product itself
    builds, and the supervisor of the runs with the port that launches a real
    child. A row that opens one of those doors boots the tree's product.

    These rows certify the TREE, and the gate certifies the tree, so the gate
    calls them. They are declared, and the declaration is what says that the
    tree knows what it holds.
    """
    if not is_a_test_source(name):
        return False
    text = reads(name, path)
    if not text:
        return False
    return any(
        all(signal in text for signal in door)
        for door in TREE_BOOT_SIGNALS
    )


def lane_of(name: str) -> int:
    """The stage of the gate that runs the rows of that source set.

    The rows of the unit lane are the stage 3 and the rows of the integration
    lane are the stage 4. The gate calls none of the boot checks, so this is
    the lane that would run them and not the one that does.
    """
    return 3 if "/src/test/" in name else 4


def build_exclusion_problems(repo: Path) -> list[str]:
    """Why the build does not leave the boot checks out of the lanes, if it does not.

    The build is what honours the request of the gate, and the tag it excludes
    is the one the rows carry. A build that does not exclude the tag would
    leave the rows in the lanes, and the gate would be calling the product
    without saying so.
    """
    build = repo / "pipelinek" / "build.gradle.kts"
    text = reads("", build)
    if not text:
        return ["the build of the tree is not there, so nothing excludes the boot checks"]
    problems: list[str] = []
    if f'excludeTags("{BOOT_CHECK_TAG}")' not in text:
        problems.append(
            f"the build does not exclude the rows that carry the tag '{BOOT_CHECK_TAG}'"
        )
    if BOOT_CHECK_PROPERTY not in text:
        problems.append(
            f"the build does not honour the property '{BOOT_CHECK_PROPERTY}'"
        )
    return problems


def gate_exclusion_problems(repo: Path) -> list[str]:
    """Why the gate does not ask for the exclusion, if it does not.

    The gate is the one that certifies the tree, and a boot check is a row
    that can go red for a reason the code does not have. A gate that does not
    ask for the exclusion calls the product and publishes a verdict about the
    machine under the name of a verdict about the tree.
    """
    gate = repo / "scripts" / "verify-all.sh"
    text = reads("", gate)
    if not text:
        return ["the gate of the tree is not there, so nothing asks for the exclusion"]
    if f"-P{BOOT_CHECK_PROPERTY}" not in text:
        return [f"the gate does not ask for the exclusion with '-P{BOOT_CHECK_PROPERTY}'"]
    return []


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    files = tree_files(repo)

    scenarios: list[str] = []
    documented: list[str] = []
    family_maps: list[str] = []
    init_scripts: list[str] = []
    programs: list[str] = []
    undeclared: list[tuple[str, str]] = []
    boot_checks: list[str] = []
    undeclared_boots: list[str] = []
    tree_boots: list[str] = []
    undeclared_tree_boots: list[str] = []

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
        if boots_the_product(name, path):
            boot_checks.append(name)
            if name not in DECLARED_BOOT_CHECKS:
                undeclared_boots.append(name)
        if boots_the_tree_product(name, path):
            tree_boots.append(name)
            if name not in DECLARED_TREE_BOOT_CHECKS:
                undeclared_tree_boots.append(name)

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

    # The boot checks are declared, and the declaration has to govern
    # something: a declared boot check that is gone, and a declared boot
    # check that does not carry the tag the build excludes, are both
    # failures. The second one is the important half: a declared file
    # without the tag is a file the gate CALLS, and the gate calls no boot
    # check.
    gone_boots = [
        name for name in sorted(DECLARED_BOOT_CHECKS)
        if not (repo / name).is_file()
    ]
    untagged_boots = [
        name for name in sorted(DECLARED_BOOT_CHECKS)
        if (repo / name).is_file() and not carries_the_tag(repo / name)
    ]

    # The rows that boot the TREE'S OWN product are declared too, and their
    # declaration has the OPPOSITE rule: the gate calls them, because they
    # certify the tree and the gate certifies the tree.
    #
    # Three things are failures here: a declared row that is gone, a declared
    # row that does not boot the tree's product any more (the declaration
    # governs nothing), and a declared row that carries the tag of the
    # published boot checks (the gate would stop calling it, and the split
    # says the gate calls the rows that boot the tree's product).
    gone_tree_boots = [
        name for name in sorted(DECLARED_TREE_BOOT_CHECKS)
        if not (repo / name).is_file()
    ]
    no_longer_tree_boots = [
        name for name in sorted(DECLARED_TREE_BOOT_CHECKS)
        if (repo / name).is_file() and not boots_the_tree_product(name, repo / name)
    ]
    tagged_tree_boots = [
        name for name in sorted(DECLARED_TREE_BOOT_CHECKS)
        if (repo / name).is_file() and carries_the_tag(repo / name)
    ]

    # A row that boots the published product cannot also be a row that boots
    # the tree's product: the two halves of the split say opposite things
    # about it, and a row that is in both would be in neither.
    both_boots = sorted(set(DECLARED_BOOT_CHECKS) & set(DECLARED_TREE_BOOT_CHECKS))

    # The chain that takes the boot checks out of the lanes: the gate asks,
    # the build honours the request and excludes the tag, and the rows carry
    # the tag. A link that is missing is a link that leaves the gate calling
    # the product.
    build_problems = build_exclusion_problems(repo)
    gate_problems = gate_exclusion_problems(repo)

    print("the layer that boots a product lives in a harness repository")
    print(f"  files read:             {len(files)}")
    print(f"  programs:               {len(programs)} found, {len(DECLARED_EXECUTABLES)} declared")
    print(f"  scenarios:              {len(scenarios)}")
    print(f"  declared as docs:       {len(documented)}")
    print(f"  family maps:            {len(family_maps)}")
    print(f"  init scripts:           {len(init_scripts)}")
    print(f"  boot checks:            {len(boot_checks)} found, {len(DECLARED_BOOT_CHECKS)} declared")
    print(
        "  boot checks of the tree: "
        f"{len(tree_boots)} found, {len(DECLARED_TREE_BOOT_CHECKS)} declared"
    )

    if (
        scenarios or family_maps or init_scripts or undeclared or gone or missing_roots
        or undeclared_boots or gone_boots or untagged_boots
        or undeclared_tree_boots or gone_tree_boots or no_longer_tree_boots
        or tagged_tree_boots or both_boots
        or build_problems or gate_problems
    ):
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
        for name in undeclared_boots:
            print(f"  {name}: a check that starts the product and this tree does not declare")
        for name in gone_boots:
            print(f"  {name}: declared as a check that starts the product and no longer there")
        for name in untagged_boots:
            print(
                f"  {name}: declared as a check that starts the product and does not carry "
                f"the tag '{BOOT_CHECK_TAG}', so the gate would call it"
            )
        for name in undeclared_tree_boots:
            print(
                f"  {name}: a check that boots the product of this tree and this tree "
                "does not declare"
            )
        for name in gone_tree_boots:
            print(
                f"  {name}: declared as a check that boots the product of this tree and "
                "no longer there"
            )
        for name in no_longer_tree_boots:
            print(
                f"  {name}: declared as a check that boots the product of this tree and "
                "does not boot it any more"
            )
        for name in tagged_tree_boots:
            print(
                f"  {name}: declared as a check that boots the product of this tree and "
                f"carries the tag '{BOOT_CHECK_TAG}', so the gate would not call it"
            )
        for name in both_boots:
            print(
                f"  {name}: declared as a check that starts the published product and as "
                "a check that boots the product of this tree, and the two halves of the "
                "split say opposite things about it"
            )
        for problem in build_problems:
            print(f"  the build: {problem}")
        for problem in gate_problems:
            print(f"  the gate: {problem}")
        print()
        print(
            "LAYER THAT BOOTS A PRODUCT: "
            f"{len(scenarios) + len(family_maps) + len(init_scripts) + len(undeclared) + len(gone) + len(missing_roots) + len(undeclared_boots) + len(gone_boots) + len(untagged_boots) + len(undeclared_tree_boots) + len(gone_tree_boots) + len(no_longer_tree_boots) + len(tagged_tree_boots) + len(both_boots) + len(build_problems) + len(gate_problems)}"
        )
        print(
            "  this tree certifies itself with unit tests, integration tests and "
            "mutants."
        )
        print(
            "  a layer that boots the PUBLISHED product belongs to a harness repository, "
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
    if boot_checks:
        print(
            f"  {len(boot_checks)} checks start the PUBLISHED product and all of them "
            "are declared:"
        )
        for name in sorted(boot_checks):
            print(
                f"    {name}: starts the published product; the lane is {lane_of(name)} "
                "and the gate calls none of it"
            )
    if tree_boots:
        print(
            f"  {len(tree_boots)} checks boot the product of this tree and all of them "
            "are declared:"
        )
        for name in sorted(tree_boots):
            print(
                f"    {name}: boots the product of this tree; the lane is "
                f"{lane_of(name)} and the gate calls it"
            )
    print(
        "  this tree holds no layer that boots a product that it does not declare, "
        "and this gate calls none of the checks that start the published product"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
