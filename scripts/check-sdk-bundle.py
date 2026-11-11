#!/usr/bin/env python3
"""The SDK bundle must be publishable to a REMOTE Maven repository, and this
checker is what says it is.

The bundle is a second Maven repository, staged under the build directory with
the layout a repository on the other side of the network has: a POM, the JAR,
the sources, the javadoc, a detached signature and a checksum per file. A
repository that a third party resolves from is not a directory of JARs: it is a
set of documents that say what each artifact is, where it came from, and that
nobody touched it on the way.

This checker asks twelve questions about the bundle that is on disk:

  1. the repository holds exactly the six coordinates and ONE version;
  2. every library carries its JAR, its sources and its javadoc, and the BOM
     carries only the document it is;
  3. every POM declares a name, a description, a url, a license, an SCM and a
     developer, and it is byte for byte the POM the RC carries;
  4. the Gradle module metadata exists and names the same coordinates;
  5. the external dependencies a consumer needs transitively are declared;
  6. no artifact carries a path of the machine that built it;
  7. no class is published by two of the six JARs;
  8. every file has its four checksums and they match the bytes;
  9. every artifact carries a detached signature that verifies against the
     key this repository declares for the MAVEN ARTIFACTS, which is not the
     key that signs the release tags;
 10. every published contract declares its maturity, and nothing in the bundle
     claims a stable ABI;
 11. the bundle is not one of the artifacts the product publishes;
 12. every directory of the bundle that carries files carries a POM.

The list of the six is NOT written here. It is derived from the bundle itself:
the BOM names the five contracts it pins, and the BOM is the sixth. A checker
that carried its own copy of the list would be a second authority about what
the product publishes, and the day the two disagreed the checker would certify
a bundle nobody intended.

The MATURITY row joins two name spaces, and it joins them the same way: the
published name is the directory the bundle carries the artifact under, and the
module is what the artifact itself declares (see `kotlin_module_of`). The
maturity contract is written in modules, and the published name is not a
module: the two were the same string until the coordinates were renamed, and
a row that looked one up by the other then stopped finding what it was
looking for.

The signature row reads the local keyring with `gpg`, and the checksums are
RECOMPUTED here rather than believed: a checksum file that says "ok" about
bytes that changed is not a checksum. Everything else is read from the files.

Usage:
  check-sdk-bundle.py --repo <dir> [--version <v>] [--landing-repo <dir>]
                      [--maturity <file>] [--assets <file>]

Exit codes: 0 = every row passed; 1 = at least one row failed; 2 = usage error.
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

MAVEN_NS = "{http://maven.apache.org/POM/4.0.0}"

# The group the SDK publishes under, and the one it published under before the
# rename. A bundle carries ONE of them: the releases before the rename carry the
# old group and the releases after it carry this one, and both are readable here
# because a checker that only knew the current one would refuse a bundle of a
# previous release as if it were malformed.
GROUP_PATH = "dev/rubentxu/pipelinek"
LEGACY_GROUP_PATH = "dev/rubentxu/pipeline/v2"


def group_path_in(repo: Path) -> str:
    """The group path this repository carries: the current one, or the one before it."""
    for candidate in (GROUP_PATH, LEGACY_GROUP_PATH):
        if (repo / candidate).is_dir():
            return candidate
    raise Failure(
        f"the repository has no {GROUP_PATH} directory (nor the {LEGACY_GROUP_PATH} "
        f"one the releases before the rename carried): {repo}"
    )


def group_id_in(repo: Path) -> str:
    """The group ID (dotted) this repository carries.

    It is DERIVED from the layout the bundle has, not written here: the group
    the artifacts are published under is the group the repository carries them
    under, and a checker that compares the metadata against a group written in
    this file compares it against a second authority -- one that says "the
    artifacts are published under X" while the bundle says they are published
    under Y. That comparison failed the other way round once already: the group
    written here was the one of the releases BEFORE the rename, so the checker
    would have refused every bundle of the releases after it.
    """
    return group_path_in(repo).replace("/", ".")

# The four digests a Maven repository carries beside every file. Central asks
# for the first two; the other two are what this repository publishes anyway,
# and a file with no digest at all is a file nobody can tell apart from a
# damaged one.
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")

# The claim this bundle must not make. The product has published stable
# releases; the SDK contracts have not earned a stable ABI, and a bundle that
# says "stable" about them would be claiming something the maturity contract
# does not say.
STABILITY_CLAIM = re.compile(r"\bstable\b", re.IGNORECASE)


class Failure(Exception):
    """A row that cannot be answered because the bundle is not there."""


class Report:
    """The rows, in order, and the ones that failed."""

    def __init__(self) -> None:
        self.rows: list[str] = []
        self.failed: list[str] = []

    def row(self, ok: bool, number: str, message: str, detail: list[str] | None = None) -> None:
        for line in detail or []:
            self.rows.append(f"  {line}")
        self.rows.append(f"{'PASS' if ok else 'FAIL'} {number} {message}")
        if not ok:
            self.failed.append(number)

    def note(self, message: str) -> None:
        self.rows.append(f"  {message}")


def digest_of(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pom_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def pom_value(root: ET.Element, path: str) -> str:
    node = root
    for segment in path.split("/"):
        node = node.find(f"{MAVEN_NS}{segment}")
        if node is None:
            return ""
    if node.text is None:
        return ""
    return node.text.strip()


def module_coordinates(repo: Path) -> dict[str, Path]:
    """The artifact directories of the repository: artifact -> its version dir."""
    base = repo / group_path_in(repo)
    found: dict[str, Path] = {}
    for artifact_dir in sorted(p for p in base.iterdir() if p.is_dir()):
        versions = sorted(p for p in artifact_dir.iterdir() if p.is_dir())
        if len(versions) != 1:
            raise Failure(
                f"the repository holds {len(versions)} versions of {artifact_dir.name}; "
                "a bundle carries one version and the checker refuses to guess which"
            )
        found[artifact_dir.name] = versions[0]
    return found


def read_pom(version_dir: Path, artifact: str) -> ET.Element:
    pom = version_dir / f"{artifact}-{version_dir.name}.pom"
    if not pom.is_file():
        raise Failure(f"{artifact} has no POM in {version_dir}")
    return ET.fromstring(pom.read_text(encoding="utf-8"))


def bom_pinned_coordinates(bom_root: ET.Element) -> list[str]:
    managed = bom_root.find(f"{MAVEN_NS}dependencyManagement/{MAVEN_NS}dependencies")
    if managed is None:
        raise Failure("the BOM declares no dependencyManagement, so it pins nothing")
    coordinates = []
    for dependency in managed.findall(f"{MAVEN_NS}dependency"):
        group = dependency.findtext(f"{MAVEN_NS}groupId", default="").strip()
        artifact = dependency.findtext(f"{MAVEN_NS}artifactId", default="").strip()
        version = dependency.findtext(f"{MAVEN_NS}version", default="").strip()
        coordinates.append(f"{group}:{artifact}:{version}")
    return coordinates


def jar_entries(version_dir: Path, artifact: str) -> list[str]:
    jar = version_dir / f"{artifact}-{version_dir.name}.jar"
    if not jar.is_file():
        raise Failure(f"{artifact} has no JAR in {version_dir}")
    with zipfile.ZipFile(jar) as archive:
        return archive.namelist()


def kotlin_module_of(version_dir: Path, artifact: str) -> str | None:
    """The module an artifact was built from, as the artifact itself declares it.

    The JAR carries the Kotlin module declaration, whose name is
    `<group>_<module>.kotlin_module`, and the module is the Gradle project the
    artifact was built from: the name a consumer never resolves and the name
    the maturity contract is written in.

    It is read from the artifact's OWN bytes and not from a table written
    here, because the table would be a second authority about what the
    product publishes. The two names are different facts and they meet HERE:
    the directory the bundle publishes the artifact under is the coordinate,
    and the declaration inside the artifact is the module. The rename broke
    the coincidence that made the two the same string, and the checker that
    looked up the maturity by the published name stopped finding it: the
    contract declares `pipeline-domain` and the bundle publishes
    `pipelinek-domain`, and the row said "the maturity contract declares
    nothing about it" about five contracts that ARE declared.

    `None` when the artifact declares no module, or declares more than one:
    a maturity that cannot be read is not a maturity that is satisfied.
    """
    jar = version_dir / f"{artifact}-{version_dir.name}.jar"
    if not jar.is_file():
        return None
    try:
        with zipfile.ZipFile(jar) as archive:
            declarations = [
                name for name in archive.namelist() if name.endswith(".kotlin_module")
            ]
    except (zipfile.BadZipFile, OSError):
        return None
    if len(declarations) != 1:
        return None
    stem = declarations[0].removeprefix("META-INF/").removesuffix(".kotlin_module")
    # The group carries no underscore, so everything after the FIRST one is
    # the module: a module whose own name carries one still comes out whole.
    if "_" not in stem:
        return None
    return stem.split("_", 1)[1]


def text_entries(version_dir: Path, artifact: str) -> list[tuple[str, str]]:
    """The entries of the JAR that are text, and small enough to be worth reading."""
    jar = version_dir / f"{artifact}-{version_dir.name}.jar"
    if not jar.is_file():
        return []
    found: list[tuple[str, str]] = []
    with zipfile.ZipFile(jar) as archive:
        for info in archive.infolist():
            if info.is_dir() or info.file_size > 262144:
                continue
            if not info.filename.endswith((".properties", ".xml", ".json", ".txt", ".MF", ".md")):
                continue
            try:
                found.append((info.filename, archive.read(info).decode("utf-8", errors="replace")))
            except (KeyError, zipfile.BadZipFile):
                continue
    return found


def check_coordinates(repo: Path, report: Report) -> tuple[dict[str, Path], str]:
    coordinates = module_coordinates(repo)
    if not coordinates:
        raise Failure("the repository holds no artifact at all")
    artifacts = sorted(coordinates)
    bom = next((a for a in artifacts if a.endswith("-bom")), None)
    if bom is None:
        raise Failure("the repository holds no BOM, so the contracts it pins cannot be derived")
    pinned = bom_pinned_coordinates(read_pom(coordinates[bom], bom))
    pinned_artifacts = sorted(coordinate.split(":")[1] for coordinate in pinned)
    expected = sorted(set(pinned_artifacts) | {bom})
    if artifacts == expected:
        report.row(
            True,
            "1",
            f"the repository holds exactly {len(expected)} coordinates and one version: {', '.join(artifacts)}",
        )
    else:
        report.row(
            False,
            "1",
            f"the repository holds {artifacts} and the BOM pins {pinned_artifacts}",
            [f"  expected exactly: {expected}"],
        )
    versions = {path.name for path in coordinates.values()}
    if len(versions) != 1:
        report.row(False, "1", f"the coordinates live in {len(versions)} versions: {sorted(versions)}")
    else:
        report.row(True, "1", f"every coordinate lives in version {versions.pop()}")
    return coordinates, bom


def check_artifacts(coordinates: dict[str, Path], bom: str, report: Report) -> None:
    missing: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        version = version_dir.name
        wanted = [f"{artifact}-{version}.pom", f"{artifact}-{version}.module"]
        if artifact != bom:
            # The BOM is a document of versions: it has no classes, so it has no JAR, and
            # asking for one would be asking for an artifact that cannot exist.
            wanted += [
                f"{artifact}-{version}.jar",
                f"{artifact}-{version}-sources.jar",
                f"{artifact}-{version}-javadoc.jar",
            ]
        for name in wanted:
            if not (version_dir / name).is_file():
                missing.append(f"{artifact}: {name}")
    if missing:
        report.row(False, "2", "the bundle does not carry every artifact it owes", missing)
    else:
        report.row(
            True,
            "2",
            "every library carries its JAR, its sources and its javadoc, and the BOM carries its POM",
        )


def check_pom_metadata(
    coordinates: dict[str, Path],
    bom: str,
    landing: Path | None,
    report: Report,
) -> None:
    missing: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        root = read_pom(version_dir, artifact)
        for field, label in (
            ("name", "name"),
            ("description", "description"),
            ("url", "url"),
        ):
            if not pom_value(root, field):
                missing.append(f"{artifact}: no <{label}>")
        licenses = root.findall(f"{MAVEN_NS}licenses/{MAVEN_NS}license")
        if not licenses:
            missing.append(f"{artifact}: no <licenses>")
        else:
            if not pom_value(licenses[0], "name"):
                missing.append(f"{artifact}: the license names no <name>")
            if not pom_value(licenses[0], "url"):
                missing.append(f"{artifact}: the license names no <url>")
        scm = root.find(f"{MAVEN_NS}scm")
        if scm is None:
            missing.append(f"{artifact}: no <scm>")
        else:
            for field in ("connection", "url"):
                if not pom_value(scm, field):
                    missing.append(f"{artifact}: the <scm> names no <{field}>")
        developers = root.findall(f"{MAVEN_NS}developers/{MAVEN_NS}developer")
        if not developers:
            missing.append(f"{artifact}: no <developers>")
        else:
            if not pom_value(developers[0], "id"):
                missing.append(f"{artifact}: the developer names no <id>")
            if not pom_value(developers[0], "name"):
                missing.append(f"{artifact}: the developer names no <name>")
    if missing:
        report.row(False, "3", "a POM does not declare what a repository needs it to declare", missing)
    else:
        report.row(True, "3", "every POM declares a name, a description, a url, a license, an SCM and a developer")

    if landing is None:
        report.row(True, "3", "the landing repository was not given, so the POM comparison was skipped")
        return
    diverged: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        staged = version_dir / f"{artifact}-{version_dir.name}.pom"
        landed = landing / group_path_in(landing) / artifact / version_dir.name / staged.name
        if not landed.is_file():
            diverged.append(f"{artifact}: the RC carries no POM with that name")
        elif landed.read_bytes() != staged.read_bytes():
            diverged.append(f"{artifact}: the staged POM differs from the POM the RC carries")
    if diverged:
        report.row(False, "3", "the staged POM is not the POM the RC carries", diverged)
    else:
        report.row(True, "3", "the staged POM is byte for byte the POM the RC carries")


def missing_variant_files(artifact: str, version_dir: Path, document: dict) -> list[str]:
    """The files the variants of the metadata resolve, and the ones the bundle lacks.

    The Gradle module metadata is the document a consumer of Gradle resolves IN
    PREFERENCE to the POM: it says which variant resolves and which file the
    bytes of that variant come from. A variant whose file is not in the bundle
    is a variant no consumer can resolve, and a bundle that carries the
    metadata anyway certifies a coordinate that cannot be used.

    The url is read as a path RELATIVE to the version directory, the way the
    resolver reads it. A url that leaves that directory is refused by name: a
    metadata that points outside the bundle is not naming a file of it.
    """
    problems: list[str] = []
    root = version_dir.resolve()
    for variant in document.get("variants", []):
        name = str(variant.get("name", "a variant without a name"))
        for entry in variant.get("files", []):
            url = str(entry.get("url", ""))
            if not url:
                problems.append(f"{artifact}: the variant '{name}' names a file with no url")
                continue
            resolved = (version_dir / url).resolve()
            if not resolved.is_relative_to(root):
                problems.append(
                    f"{artifact}: the variant '{name}' names the file '{url}', "
                    "which is not a file of the bundle"
                )
                continue
            if not resolved.is_file():
                problems.append(
                    f"{artifact}: the variant '{name}' names the file '{url}', "
                    "which the bundle does not carry"
                )
    return problems


def check_gradle_metadata(coordinates: dict[str, Path], group: str, report: Report) -> None:
    problems: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        module = version_dir / f"{artifact}-{version_dir.name}.module"
        if not module.is_file():
            problems.append(f"{artifact}: no Gradle module metadata")
            continue
        try:
            document = json.loads(module.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            problems.append(f"{artifact}: the Gradle module metadata is not JSON ({error})")
            continue
        component = document.get("component", {})
        if component.get("group") != group:
            problems.append(f"{artifact}: the metadata names the group '{component.get('group')}'")
        if component.get("module") != artifact:
            problems.append(f"{artifact}: the metadata names the module '{component.get('module')}'")
        if str(component.get("version")) != version_dir.name:
            problems.append(f"{artifact}: the metadata names the version '{component.get('version')}'")
        problems.extend(missing_variant_files(artifact, version_dir, document))
    if problems:
        report.row(
            False,
            "4",
            "the Gradle module metadata does not describe the artifact it accompanies",
            problems,
        )
    else:
        report.row(
            True,
            "4",
            "every artifact carries Gradle module metadata that names it and the files it resolves",
        )


def check_transitive_dependencies(
    coordinates: dict[str, Path], bom: str, group: str, report: Report
) -> None:
    problems: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        if artifact == bom:
            continue
        root = read_pom(version_dir, artifact)
        dependencies = root.findall(f"{MAVEN_NS}dependencies/{MAVEN_NS}dependency")
        if not dependencies:
            problems.append(f"{artifact}: the POM declares no dependency at all")
            continue
        external = [
            dependency.findtext(f"{MAVEN_NS}artifactId", default="").strip()
            for dependency in dependencies
            if dependency.findtext(f"{MAVEN_NS}groupId", default="").strip() != group
        ]
        if not any(name.startswith("kotlin-stdlib") for name in external):
            problems.append(f"{artifact}: the POM declares no kotlin-stdlib, so a consumer compiles without it")
    bom_root = read_pom(coordinates[bom], bom)
    pinned = bom_pinned_coordinates(bom_root)
    versions = {coordinate.split(":")[2] for coordinate in pinned}
    if len(versions) != 1:
        problems.append(f"{bom}: the BOM pins {len(versions)} versions: {sorted(versions)}")
    if problems:
        report.row(False, "5", "the external dependencies a consumer needs are not declared", problems)
    else:
        report.row(
            True,
            "5",
            f"every library declares its external dependencies and the BOM pins {len(pinned)} coordinates at one version",
        )


def check_no_build_machine_paths(
    coordinates: dict[str, Path],
    bom: str,
    workspace: Path,
    report: Report,
) -> None:
    """No artifact may name the directory it was built in.

    A POM or a JAR that carries `/home/somebody/...` travels to every consumer with a
    stranger's directory layout on it, and the consumer's own build then resolves
    against a path that does not exist there. The paths that matter are the ones a
    build agent produces: the checkout, the home directory and the temporary tree. A
    URL is not a path, and neither is the namespace of an XML document, so both are
    taken out of the text before it is read.
    """
    offenders: list[str] = []
    workspace_text = str(workspace)
    forbidden = [workspace_text, str(Path.home()), "/tmp/", "/var/tmp/", "/private/var/"]
    for artifact, version_dir in sorted(coordinates.items()):
        if artifact == bom:
            # The BOM carries no JAR: it is a document of versions.
            continue
        documents = [
            version_dir / f"{artifact}-{version_dir.name}.pom",
            version_dir / f"{artifact}-{version_dir.name}.module",
        ]
        for document in documents:
            if not document.is_file():
                continue
            text = document.read_text(encoding="utf-8", errors="replace")
            for path in forbidden:
                if path and path in text:
                    offenders.append(f"{document.name}: names the path {path}")
        for entry in jar_entries(version_dir, artifact):
            if entry.startswith("/") or ".." in entry.split("/"):
                offenders.append(f"{artifact}: the JAR carries the entry {entry}")
        for entry, content in text_entries(version_dir, artifact):
            for path in forbidden:
                if path and path in content:
                    offenders.append(f"{artifact}: the entry {entry} names the path {path}")
    if offenders:
        report.row(False, "6", "an artifact carries a path of the machine that built it", offenders)
    else:
        report.row(True, "6", "no artifact carries a path of the machine that built it")


def check_duplicate_classes(coordinates: dict[str, Path], bom: str, report: Report) -> None:
    owners: dict[str, list[str]] = {}
    for artifact, version_dir in sorted(coordinates.items()):
        if artifact == bom:
            continue
        for entry in jar_entries(version_dir, artifact):
            if not entry.endswith(".class"):
                continue
            owners.setdefault(entry, []).append(artifact)
    duplicated = {entry: artifacts for entry, artifacts in owners.items() if len(artifacts) > 1}
    if duplicated:
        report.row(
            False,
            "7",
            f"{len(duplicated)} class(es) are published by more than one artifact",
            [f"  {entry}: {', '.join(artifacts)}" for entry, artifacts in sorted(duplicated.items())],
        )
    else:
        report.row(True, "7", f"the {len(owners)} classes of the bundle are published by exactly one artifact")


def read_sidecar(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip().split()[0] if path.is_file() else ""


def check_checksums(coordinates: dict[str, Path], report: Report) -> None:
    problems: list[str] = []
    for artifact, version_dir in sorted(coordinates.items()):
        for file in sorted(p for p in version_dir.iterdir() if p.is_file()):
            if file.suffix in (".md5", ".sha1", ".sha256", ".sha512"):
                continue
            for algorithm in CHECKSUMS:
                sidecar = file.with_name(file.name + f".{algorithm}")
                if not sidecar.is_file():
                    problems.append(f"{file.name}: no .{algorithm} beside it")
                    continue
                recorded = read_sidecar(sidecar)
                computed = digest_of(file, algorithm)
                if recorded.lower() != computed:
                    problems.append(f"{file.name}: the .{algorithm} does not match the bytes")
    if problems:
        report.row(False, "8", "a checksum is missing or does not match the bytes it describes", problems)
    else:
        report.row(True, "8", "every file carries its four checksums and they match the bytes")


def check_signatures(
    coordinates: dict[str, Path],
    fingerprint: str,
    report: Report,
) -> None:
    if not fingerprint:
        report.row(False, "9", "no signing key was given, so no signature can be checked")
        return
    problems: list[str] = []
    checked = 0
    for artifact, version_dir in sorted(coordinates.items()):
        for file in sorted(p for p in version_dir.iterdir() if p.is_file()):
            if file.suffix in (".md5", ".sha1", ".sha256", ".sha512", ".asc"):
                continue
            signature = file.with_name(file.name + ".asc")
            if not signature.is_file():
                problems.append(f"{file.name}: no detached signature")
                continue
            result = subprocess.run(
                ["gpg", "--batch", "--status-fd", "1", "--verify", str(signature), str(file)],
                capture_output=True,
                text=True,
                check=False,
            )
            status = result.stdout
            if "VALIDSIG" not in status:
                problems.append(f"{file.name}: the signature does not verify ({result.stderr.strip()[:120]})")
                continue
            signer = ""
            for line in status.splitlines():
                if line.startswith("[GNUPG:] VALIDSIG "):
                    signer = line.split()[2]
            if signer.upper() != fingerprint.upper():
                problems.append(f"{file.name}: the signature names the key {signer}, not the key declared for the Maven artifacts")
                continue
            checked += 1
    if problems:
        report.row(False, "9", "an artifact is not signed by the key declared for the Maven artifacts", problems)
    else:
        report.row(True, "9", f"the {checked} artifacts carry a detached signature that verifies against the key declared for the Maven artifacts")


def check_maturity(
    coordinates: dict[str, Path],
    bom: str,
    maturity_path: Path | None,
    report: Report,
) -> None:
    if maturity_path is None or not maturity_path.is_file():
        report.row(False, "10", f"the maturity contract was not found: {maturity_path}")
        return
    document = json.loads(maturity_path.read_text(encoding="utf-8"))
    contracts = document.get("contracts", {})
    problems: list[str] = []
    declared: list[str] = []
    for artifact in sorted(coordinates):
        if artifact == bom:
            continue
        # The maturity contract is written in MODULES: the artifact's own
        # declaration says which module it is, and that is the name the
        # contract is keyed by. The published name is a different fact, and
        # the two meet in the artifact.
        module = kotlin_module_of(coordinates[artifact], artifact)
        if module is None:
            problems.append(
                f"{artifact}: the artifact declares no module, so its maturity cannot be read"
            )
            continue
        classification = contracts.get(module)
        if not classification:
            problems.append(
                f"{artifact} (module {module}): the maturity contract declares nothing about it"
            )
            continue
        declared.append(f"{artifact}={classification} (module {module})")
    for artifact, version_dir in sorted(coordinates.items()):
        for document_name in (f"{artifact}-{version_dir.name}.pom", f"{artifact}-{version_dir.name}.module"):
            document_path = version_dir / document_name
            if not document_path.is_file():
                continue
            text = document_path.read_text(encoding="utf-8", errors="replace")
            for match in STABILITY_CLAIM.finditer(text):
                snippet = text[max(0, match.start() - 40) : match.end() + 40].replace("\n", " ")
                problems.append(f"{document_name}: claims a stable ABI ({snippet.strip()})")
    if problems:
        report.row(False, "10", "the bundle does not carry the maturity of what it publishes", problems)
    else:
        report.row(True, "10", "every published contract declares its maturity, and none claims a stable ABI")
        for entry in declared:
            report.note(f"maturity {entry}")


def check_not_published(assets_path: Path | None, report: Report) -> None:
    if assets_path is None or not assets_path.is_file():
        report.row(True, "11", "the published asset list was not given, so the row was skipped")
        return
    document = json.loads(assets_path.read_text(encoding="utf-8"))
    names = []
    if isinstance(document, list):
        names = [str(entry) for entry in document]
    elif isinstance(document, dict):
        for value in document.values():
            if isinstance(value, list):
                names.extend(str(entry) for entry in value)
            elif isinstance(value, str):
                names.append(value)
    offenders = [name for name in names if "sdk-repo-staging" in name or "sdk-bundle" in name]
    if offenders:
        report.row(False, "11", "the bundle is among the artifacts the product publishes", offenders)
    else:
        report.row(True, "11", "the bundle is not among the artifacts the product publishes")


def check_every_directory_carries_a_pom(repo: Path, report: Report) -> None:
    """Every directory that carries files must carry a POM.

    The destination refuses a bundle whose directory carries content and no POM, and
    it names the directory it refuses. The rule is measured here rather than assumed:
    a bundle is a set of artifacts, and a directory that holds files without a POM
    holds something a consumer cannot resolve by its coordinate.

    The shape that trips this is the index of a REPOSITORY: a `maven-metadata.xml`
    in the directory of the module, whose POM is one level deeper. It travels to the
    destination as content of a directory with no POM, and the destination refuses
    the whole bundle for it.
    """
    offenders = []
    for directory in sorted({path.parent for path in repo.rglob("*") if path.is_file()}):
        files = sorted(path.name for path in directory.iterdir() if path.is_file())
        if files and not any(name.endswith(".pom") for name in files):
            offenders.append(f"{directory.relative_to(repo)}: {', '.join(files)}")
    if offenders:
        report.row(False, "12", "a directory of the bundle carries content and no POM", offenders)
    else:
        report.row(True, "12", "every directory of the bundle that carries files carries a POM")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(add_help=True, description="Check the staged SDK bundle.")
    parser.add_argument("--repo", required=True, help="the staged Maven repository")
    parser.add_argument("--version", default="", help="the version the bundle must carry")
    parser.add_argument("--landing-repo", default="", help="the repository the RC's SDK ZIP is built from")
    parser.add_argument("--maturity", default="", help="the published-contract maturity contract")
    parser.add_argument("--assets", default="", help="the published asset list of the candidate")
    parser.add_argument(
        "--fingerprint",
        default="",
        help="the key declared for the Maven artifacts, whose signatures the bundle must carry",
    )
    arguments = parser.parse_args(argv)

    repo = Path(arguments.repo).resolve()
    if not repo.is_dir():
        print(f"BUNDLE SDK: FAIL row 1 the repository does not exist: {repo}")
        print("\nBUNDLE SDK: FAIL (1 row(s): 1)")
        return 1

    report = Report()
    try:
        coordinates, bom = check_coordinates(repo, report)
        group = group_id_in(repo)
        check_artifacts(coordinates, bom, report)
        landing = Path(arguments.landing_repo).resolve() if arguments.landing_repo else None
        check_pom_metadata(coordinates, bom, landing, report)
        check_gradle_metadata(coordinates, group, report)
        check_transitive_dependencies(coordinates, bom, group, report)
        workspace = Path(__file__).resolve().parent.parent
        check_no_build_machine_paths(coordinates, bom, workspace, report)
        check_duplicate_classes(coordinates, bom, report)
        check_checksums(coordinates, report)
        check_signatures(coordinates, arguments.fingerprint, report)
        maturity = Path(arguments.maturity) if arguments.maturity else None
        check_maturity(coordinates, bom, maturity, report)
        assets = Path(arguments.assets) if arguments.assets else None
        check_not_published(assets, report)
        check_every_directory_carries_a_pom(repo, report)
        version = arguments.version or next(iter(coordinates.values())).name
        report.note(f"bundle version {version}, {len(coordinates)} coordinates")
    except Failure as failure:
        report.row(False, "1", str(failure))

    for line in report.rows:
        print(line)
    if report.failed:
        print(
            f"\nBUNDLE SDK: FAIL ({len(report.failed)} row(s): {', '.join(sorted(set(report.failed)))})"
        )
        print("El bundle no esta en grado de publicacion remota.")
        return 1
    print("\nBUNDLE SDK: OK")
    print("Seis coordenadas, una version, y cada artefacto con lo que un repositorio remoto exige.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
