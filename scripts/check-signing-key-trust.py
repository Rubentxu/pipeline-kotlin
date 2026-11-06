#!/usr/bin/env python3
"""The signing keys of this repository are a SET, and the set has a shape.

The file `scripts/release/signing-key.conf` declares the key that signs the
release tags and the keys that signed the tags already published. Both halves
are needed: a rotation that dropped the second half would turn every release
ever published into a tag nobody can verify, which is the opposite of what a
rotation is for. So the shape of the declaration is a contract, and this guard
is what checks it.

The guard reads the tree and nothing else: no network, no keyring, no
subprocess. It asks six questions about the declaration:

  1. exactly ONE current key is declared;
  2. every fingerprint it names is forty hexadecimal characters;
  3. no fingerprint is named twice;
  4. the current key is not among the keys that signed the tags already
     published;
  5. every key of that second group declares the identity it claims;
  6. the current key does not claim a domain reserved for documentation.

The sixth row is the one with teeth. A domain reserved for documentation
belongs to nobody: an address under such a domain cannot be corroborated by
any channel, because there is no owner behind it. A key that claims one can
therefore never receive an attribution, and a repository that publishes it as
the key that signs is publishing a key that no consumer is able to place. The
row is red while the current key claims such an address, and green once the
current key claims an address a channel can corroborate. That is what makes
a rotation measurable instead of merely announced.

The rows are reported one per line, and the run fails when any of them fails.
"""

import re
import sys
from pathlib import Path

CONF_RELATIVE = "scripts/release/signing-key.conf"

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


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    conf = repo / CONF_RELATIVE

    if not conf.is_file():
        print(f"  {CONF_RELATIVE}: the declaration of the signing keys is missing")
        print("\nCLAVE DE FIRMA: FAIL")
        print("El fichero que declara las claves de firma no existe.")
        return 1

    declaration = parse(conf.read_text(encoding="utf-8"))
    failures: list[str] = []
    rows: list[str] = []

    def row(ok: bool, number: str, detail: str) -> None:
        rows.append(f"  row {number} verdict={'PASS' if ok else 'FAIL'} {detail}")
        if not ok:
            failures.append(f"row {number}")

    # Row 1. ONE current key. Two current keys would mean two keys sign, and
    # a signature does not say which of them did it.
    if len(declaration.current_fingerprints) == 1:
        row(True, "1", "the declaration names exactly one current key")
    else:
        found = len(declaration.current_fingerprints)
        row(False, "1", f"the declaration names {found} current keys and it must name one")

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
        row(True, "2", f"all {len(fingerprints)} fingerprints are forty hexadecimal characters")
    else:
        for role, value, number in malformed:
            rows.append(f"  {CONF_RELATIVE}:{number}  a {role} fingerprint is not forty hexadecimal characters")
        row(False, "2", f"{len(malformed)} fingerprint(s) are malformed")

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
        row(True, "3", "no fingerprint is declared twice")
    else:
        for key in sorted(set(duplicates)):
            rows.append(f"  {CONF_RELATIVE}  the fingerprint {key} is declared twice")
        row(False, "3", f"{len(set(duplicates))} fingerprint(s) are declared twice")

    # Row 4. The current key is not also one of the others. A key that signs
    # and also counts as history is a key whose history nobody can read.
    current = declaration.current_fingerprint.upper()
    others_fingerprints = {value.upper() for value, _uid, _number in declaration.others}
    if current and current in others_fingerprints:
        row(False, "4", "the current key is also declared among the keys that signed the tags already published")
    elif not current:
        row(False, "4", "there is no current key to compare")
    else:
        row(True, "4", "the current key is not among the keys that signed the tags already published")

    # Row 5. Every key of the second group says which identity it claims. A
    # key without an identity cannot be checked against the identity the
    # signature carries, so it can only ever be believed.
    without_identity = [number for _fingerprint, uid, number in declaration.others if not uid]
    if not declaration.others:
        row(True, "5", "no key of the second group is declared")
    elif not without_identity:
        row(True, "5", f"all {len(declaration.others)} keys of the second group declare an identity")
    else:
        for number in without_identity:
            rows.append(f"  {CONF_RELATIVE}:{number}  a key of the second group declares no identity")
        row(False, "5", f"{len(without_identity)} key(s) of the second group declare no identity")

    # Row 6. The current key claims no domain reserved for documentation. A
    # domain nobody owns cannot corroborate an address, so a key that claims
    # one can never be attributed to anybody, and the release path would be
    # signing with a key no consumer is able to place.
    uid = declaration.current_uid
    address = address_of(uid)
    domain = domain_of(address)
    if not uid:
        row(False, "6", "the current key declares no identity")
    elif not address:
        row(True, "6", f"the current key claims no address, so it claims no reserved domain (uid '{uid}')")
    elif reserved_domain(domain):
        row(False, "6", f"the current key claims '{address}', and '{domain}' is reserved for documentation")
    else:
        row(True, "6", f"the current key claims '{address}', and '{domain}' is a domain that can be corroborated")

    for line in rows:
        print(line)
    if failures:
        print(f"\nCLAVE DE FIRMA: FAIL ({len(failures)} row(s): {', '.join(failures)})")
        print("La declaracion de las claves de firma no tiene la forma que el camino de firma exige.")
        return 1
    print(f"\nCLAVE DE FIRMA: OK ({len(rows)} rows)")
    print("Una clave actual, un conjunto de confianza, y ninguna identidad que nadie pueda corroborar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
