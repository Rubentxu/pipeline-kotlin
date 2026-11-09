#!/usr/bin/env python3
"""The signing keys of this repository are a SET, and the set has a shape.

This repository declares TWO sets, because it signs two things that belong to
two trust domains:

  * `scripts/release/signing-key.conf` declares the key that signs the
    RELEASE TAGS, and the keys that signed the tags already published;
  * `scripts/release/maven-signing-key.conf` declares the key that signs the
    MAVEN ARTIFACTS, and the keys that signed the artifacts already published.

The two sets are separate because their revocations are separate. The tag that
names a release and the JAR that any third party downloads are signed by
different keys, so compromising one of them leaves the other intact: a single
key pair would bind both domains into one, and then one revocation would
reach the other.

Both declarations have the SAME shape, and both are read with the same reader.
One reader per format is the rule; two facts that are not the same fact do
not need two readers.

Each declaration is a set, and a set is a set for a reason. The current key
signs what is cut from now on. The keys of the second group signed what is
already out there, and those signatures keep verifying: a published tag is a
fact, and a key that stops signing does not un-sign what it already signed. A
rotation that dropped the second group would turn every release ever published
into a tag nobody can verify, which is the opposite of what a rotation is for.

The shape of a set is a contract, and this guard is what checks it. The guard
reads the tree and nothing else: no network, no keyring, no subprocess. It
asks seven questions about EACH declaration:

  1. exactly ONE current key is declared;
  2. every fingerprint it names is forty hexadecimal characters;
  3. no fingerprint is named twice;
  4. the current key is not among the keys that signed what was already
     published;
  5. every key of that second group declares the identity it claims;
  6. the current key does not claim a domain reserved for documentation;
  7. the fingerprint that signs is PUBLISHED to the consumers.

and one more about the PAIR:

  8. the two declarations do not name the same key.

The sixth row is the one with teeth. A domain reserved for documentation
belongs to nobody: an address under such a domain cannot be corroborated by
any channel, because there is no owner behind it. A key that claims one can
therefore never receive an attribution, and a repository that publishes it as
the key that signs is publishing a key that no consumer is able to place. The
row is red while the current key claims such an address, and green once the
current key claims an address a channel can corroborate. That is what makes
a rotation measurable instead of merely announced.

The seventh row is the CHANNEL. The declaration names the key that signs,
and a consumer cannot place a publisher with a fingerprint nobody published.
The fingerprint the declaration names as current has to appear in the
document the consumers read — `RELEASE-SIGNING.md`, in the root of the
repository — so the attribution of a release rests on a declaration that is
versioned here and on nothing that lives in an account of a third party. A
key that signs and is not published is a key no consumer is able to place,
and the row is red while the two disagree.

The eighth row is the SEPARATION. The two domains are only separate while
they are signed by different keys. A declaration that named the key of the
other domain would bind them, and the revocation of one would reach the
other. The row is red while the two declarations share a fingerprint, and it
names the fingerprint it found in both.

The rows are reported one per line, and the run fails when any of them fails.
"""

import re
import sys
from pathlib import Path

CONF_RELATIVE = "scripts/release/signing-key.conf"
MAVEN_CONF_RELATIVE = "scripts/release/maven-signing-key.conf"

# The document that publishes the fingerprint to the consumers. It is the
# channel: a consumer who reads it compares the fingerprint it names with the
# fingerprint the release names.
PUBLISHED_RELATIVE = "RELEASE-SIGNING.md"

# The declarations this repository holds, each one with the name of the thing
# it signs. The order is the order of the report.
DECLARATIONS = (
    ("the release tags", CONF_RELATIVE),
    ("the Maven artifacts", MAVEN_CONF_RELATIVE),
)

FINGERPRINT = re.compile(r"^[0-9A-Fa-f]{40}$")

# The domains that no channel can corroborate, and the suffixes that carry
# the same property. A domain under one of these belongs to nobody, so a
# mailbox there is a shape and not a place.
RESERVED_DOMAINS = frozenset(
    {
        "example.com",
        "example.net",
        "example.org",
        "localhost",
        "local",
    }
)
RESERVED_SUFFIXES = (".test", ".example", ".invalid", ".localhost", ".local")

ADDRESS_IN_UID = re.compile(r"<([^<>@\s]+@[^<>@\s]+)>")


class Declaration:
    """What the file says, as the facts a reader can check."""

    def __init__(self) -> None:
        self.current_fingerprints: list[tuple[str, int]] = []
        self.current_uids: list[tuple[str, int]] = []
        self.others: list[tuple[str, str, int]] = []

    @property
    def current_fingerprint(self) -> str:
        return self.current_fingerprints[0][0] if self.current_fingerprints else ""

    @property
    def current_uid(self) -> str:
        return self.current_uids[0][0] if self.current_uids else ""

    def every_fingerprint(self) -> list[str]:
        """Every fingerprint the declaration names, in the order it names them."""
        found = [fingerprint for fingerprint, _number in self.current_fingerprints]
        found += [fingerprint for fingerprint, _uid, _number in self.others]
        return found


class Report:
    """The rows of the report, and the numbers of the ones that failed."""

    def __init__(self) -> None:
        self.rows: list[str] = []
        self.failures: list[str] = []
        self.number = 0

    def row(self, ok: bool, detail: str) -> bool:
        self.number += 1
        self.rows.append(f"  row {self.number} verdict={'PASS' if ok else 'FAIL'} {detail}")
        if not ok:
            self.failures.append(str(self.number))
        return ok

    def note(self, text: str) -> None:
        self.rows.append(text)


def parse(text: str) -> Declaration:
    """Read the declaration. A line the file does not define is ignored."""
    declaration = Declaration()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("fingerprint="):
            declaration.current_fingerprints.append((line[len("fingerprint="):].strip(), number))
        elif line.startswith("uid="):
            declaration.current_uids.append((line[len("uid="):].strip(), number))
        elif line.startswith("retired="):
            payload = line[len("retired="):]
            fingerprint, separator, uid = payload.partition("|")
            declaration.others.append((fingerprint.strip(), uid.strip() if separator else "", number))
    return declaration


def address_of(uid: str) -> str:
    """The mailbox a uid claims, or the empty string when it claims none."""
    match = ADDRESS_IN_UID.search(uid)
    if match:
        return match.group(1)
    if "@" in uid and " " not in uid:
        return uid
    return ""


def domain_of(address: str) -> str:
    return address.rsplit("@", 1)[1].lower() if "@" in address else ""


def reserved_domain(domain: str) -> bool:
    if not domain:
        return False
    if domain in RESERVED_DOMAINS:
        return True
    return any(domain.endswith(suffix) for suffix in RESERVED_SUFFIXES)


def check_declaration(
    repo: Path,
    conf_relative: str,
    label: str,
    published_exists: bool,
    published: str,
    report: Report,
) -> list[str]:
    """Ask the seven questions about ONE declaration.

    Returns every fingerprint the declaration names, so that the question about
    the PAIR can be asked with what was measured rather than with what the
    file says it holds.
    """
    conf = repo / conf_relative
    if not conf.is_file():
        report.note(f"  {conf_relative}: the declaration of {label} is missing")
        report.row(False, f"the declaration of {label} is missing")
        return []

    declaration = parse(conf.read_text(encoding="utf-8"))

    # Row 1. ONE current key. Two current keys would mean two keys sign, and
    # a signature does not say which of them did it.
    if len(declaration.current_fingerprints) == 1:
        report.row(True, f"the declaration of {label} names exactly one current key")
    else:
        found = len(declaration.current_fingerprints)
        report.row(False, f"the declaration of {label} names {found} current keys and it must name one")

    # Row 2. Every fingerprint is a fingerprint. A shape that is not a
    # fingerprint cannot be compared with a signature, and a checker that
    # cannot compare it would refuse everything without saying why.
    fingerprints: list[tuple[str, str, int]] = []
    if declaration.current_fingerprints:
        fingerprints.append(("current", declaration.current_fingerprint, declaration.current_fingerprints[0][1]))
    for fingerprint, _uid, number in declaration.others:
        fingerprints.append(("other", fingerprint, number))
    malformed = [(role, value, number) for role, value, number in fingerprints if not FINGERPRINT.match(value)]
    if not malformed:
        report.row(True, f"all {len(fingerprints)} fingerprints of {label} are forty hexadecimal characters")
    else:
        for role, value, number in malformed:
            report.note(f"  {conf_relative}:{number}  a {role} fingerprint is not forty hexadecimal characters")
        report.row(False, f"{len(malformed)} fingerprint(s) of {label} are malformed")

    # Row 3. No fingerprint twice. The same key declared in two roles would
    # make the set's size a lie.
    seen: dict[str, int] = {}
    duplicates: list[str] = []
    for _role, value, _number in fingerprints:
        key = value.upper()
        if not key:
            continue
        if key in seen:
            duplicates.append(key)
        seen[key] = seen.get(key, 0) + 1
    if not duplicates:
        report.row(True, f"no fingerprint of {label} is declared twice")
    else:
        for key in sorted(set(duplicates)):
            report.note(f"  {conf_relative}  the fingerprint {key} is declared twice")
        report.row(False, f"{len(set(duplicates))} fingerprint(s) of {label} are declared twice")

    # Row 4. The current key is not also one of the others. A key that signs
    # and also counts as history is a key whose history nobody can read.
    current = declaration.current_fingerprint.upper()
    others_fingerprints = {value.upper() for value, _uid, _number in declaration.others}
    if current and current in others_fingerprints:
        report.row(False, f"the current key of {label} is also declared among the keys that signed what was already published")
    elif not current:
        report.row(False, f"there is no current key of {label} to compare")
    else:
        report.row(True, f"the current key of {label} is not among the keys that signed what was already published")

    # Row 5. Every key of the second group says which identity it claims. A
    # key without an identity cannot be checked against the identity the
    # signature carries, so it can only ever be believed.
    without_identity = [number for _fingerprint, uid, number in declaration.others if not uid]
    if not declaration.others:
        report.row(True, f"no key of the second group of {label} is declared")
    elif not without_identity:
        report.row(True, f"all {len(declaration.others)} keys of the second group of {label} declare an identity")
    else:
        for number in without_identity:
            report.note(f"  {conf_relative}:{number}  a key of the second group declares no identity")
        report.row(False, f"{len(without_identity)} key(s) of the second group of {label} declare no identity")

    # Row 6. The current key claims no domain reserved for documentation. A
    # domain nobody owns cannot corroborate an address, so a key that claims
    # one can never be attributed to anybody, and the release path would be
    # signing with a key no consumer is able to place.
    uid = declaration.current_uid
    address = address_of(uid)
    domain = domain_of(address)
    if not uid:
        report.row(False, f"the current key of {label} declares no identity")
    elif not address:
        report.row(True, f"the current key of {label} claims no address, so it claims no reserved domain (uid '{uid}')")
    elif reserved_domain(domain):
        report.row(False, f"the current key of {label} claims '{address}', and '{domain}' is reserved for documentation")
    else:
        report.row(True, f"the current key of {label} claims '{address}', and '{domain}' is a domain that can be corroborated")

    # Row 7. The fingerprint that signs is PUBLISHED to the consumers. The
    # declaration is the channel only if the consumers can read it: a key
    # that signs and appears nowhere is a key nobody is able to place, and a
    # consumer that compares the fingerprint the release names with the
    # fingerprint the document publishes finds two different keys. The
    # document is read from the tree, and the comparison is literal.
    if not published_exists:
        report.note(f"  {PUBLISHED_RELATIVE}: the document that publishes the fingerprint is missing")
        report.row(False, f"the document that publishes the fingerprint of {label} to the consumers is missing")
    elif not current:
        report.row(False, f"there is no current key of {label} whose fingerprint could be published")
    elif current in published:
        report.row(True, f"the fingerprint {current} that signs {label} is published in {PUBLISHED_RELATIVE}")
    else:
        report.note(f"  {PUBLISHED_RELATIVE}  the fingerprint {current} that signs {label} is not published")
        report.row(False, f"the fingerprint {current} that signs {label} is not published in {PUBLISHED_RELATIVE}")

    return declaration.every_fingerprint()


def main() -> int:
    repo = Path(__file__).resolve().parent.parent

    published_path = repo / PUBLISHED_RELATIVE
    published_exists = published_path.is_file()
    published = published_path.read_text(encoding="utf-8") if published_exists else ""

    report = Report()
    declared: list[tuple[str, str, list[str]]] = []
    for label, conf_relative in DECLARATIONS:
        fingerprints = check_declaration(
            repo, conf_relative, label, published_exists, published, report
        )
        declared.append((label, conf_relative, fingerprints))

    # Row 8. The two domains are separate only while they are signed by
    # different keys. A fingerprint that both declarations name binds them,
    # and then the revocation of one reaches the other.
    shared: list[str] = []
    for index, (label, _relative, fingerprints) in enumerate(declared):
        others = {value.upper() for other_label, _other_relative, other_fingerprints in declared[index + 1:] for value in other_fingerprints}
        for value in fingerprints:
            if value.upper() in others:
                shared.append(value.upper())
    if shared:
        for value in sorted(set(shared)):
            report.note(f"  the declarations of {DECLARATIONS[0][0]} and {DECLARATIONS[1][0]} both name the fingerprint {value}")
        report.row(False, f"the two declarations name {len(set(shared))} fingerprint(s) in common: the two trust domains are not separated")
    else:
        report.row(True, "the two declarations name different keys, so their revocations do not reach each other")

    for line in report.rows:
        print(line)
    if report.failures:
        print(f"\nCLAVE DE FIRMA: FAIL ({len(report.failures)} row(s): {', '.join(report.failures)})")
        print("La declaracion de las claves de firma no tiene la forma que el camino de firma exige.")
        return 1
    print(f"\nCLAVE DE FIRMA: OK ({report.number} rows)")
    print("Dos conjuntos de confianza, una clave actual en cada uno, ninguna huella compartida,")
    print("ninguna identidad que nadie pueda corroborar, y una huella que los consumidores")
    print("pueden leer en este repositorio.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
