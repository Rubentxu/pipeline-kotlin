# Release signing

This document is the review of the provenance of the fingerprint that signs
PipelineK release tags, the trust channel a consumer has to use, and the
rotation that followed the review.

Everything stated here was measured, not assumed. Where a fact could not be
measured, the document says so instead of rounding it into a claim.

## What a signature proves, and what it does not

A signed tag proves **possession** of the private key: the tag carries a
signature that verifies against a key, and the tag cannot be altered without
breaking that signature. It says nothing about *who* holds the key.

The identity a key carries — the user id in its own keyring — is a claim the
key makes about itself. It is compared with the identity the signature
carries, so that a key whose identity changed is a key somebody else should
look at. It is never an attribution: nobody is attributed by the string their
key carries.

## The trust channel is the declaration of this repository

The bytes that leave this repository carry their own signature, and a signature
that travels with the artifact proves only possession. An artifact that
declares its own trust proves nothing about itself, so the binding between a
key and a publisher has to be something a consumer can read and compare.

For this repository the channel is **the declaration this repository
versions**. `scripts/release/signing-key.conf` names the fingerprint and the
uid of the key that signs, `RELEASE-SIGNING.md` publishes that same
fingerprint to the consumers of the release, and the public key travels with
the release notes. The declaration is reviewed in the same history as the code
that signs, so a consumer who reads the repository reads the declaration and
compares it with the fingerprint the release names.

No account of a third party takes part in it. The platform that hosts the
releases is transport, and what its database says about a tag — whether it
marks it as verified — is a fact about that database and not about the
publisher. The attribution of a release in this repository rests on the
declaration and on nothing else.

The channel is checked, not merely described:

```bash
scripts/check-signing-key-trust.py
```

The check reads the tree and nothing else. The row that carries the channel
asks whether the fingerprint the declaration names as current is published in
`RELEASE-SIGNING.md`, the file a consumer reads: a key that signs and is
published nowhere is a key no consumer is able to place, and a consumer that
compares the fingerprint the release names with the published one finds two
different keys. The promotion to a stable release refuses while the two
disagree.

## The keys this repository trusts

The declaration lives in `scripts/release/signing-key.conf`, and it is the one
authority: the release path, the tag-signature check and the published-tag
audit all read it through `scripts/release/read-signing-trust.sh`.

The declaration is a **set**, and it is a set for a reason. The current key
signs the tags cut from now on. The keys of the second group signed the tags
that are already published, and those signatures keep verifying: a published
tag is a fact, and a key that stops signing does not un-sign what it already
signed. A rotation that dropped the second group would turn every release
ever published into a tag nobody can verify, which is the opposite of what a
rotation is for.

The shape of the set is checked by `scripts/check-signing-key-trust.py`, which
reads the tree and nothing else. Seven rows, and two of them have teeth:

| Row | What it asks |
|---|---|
| 1 | exactly one current key is declared |
| 2 | every fingerprint is forty hexadecimal characters |
| 3 | no fingerprint is declared twice |
| 4 | the current key is not among the keys of the second group |
| 5 | every key of the second group declares the identity it claims |
| 6 | the current key claims no domain reserved for documentation |
| 7 | the fingerprint that signs is published in `RELEASE-SIGNING.md` |

Row 6 exists because a domain reserved for documentation belongs to nobody.
An address under such a domain cannot be corroborated by any channel,
because there is no owner behind it. A key that claims one can therefore
never receive an attribution, and a repository that publishes it as the key
that signs is publishing a key that no consumer is able to place. The row is
red while the current key claims such an address and green once it claims an
address a channel can corroborate.

Row 7 is the channel itself. The declaration names the key that signs, and a
consumer cannot place a publisher with a fingerprint nobody published. The
fingerprint the declaration names as current has to appear in the document the
consumers read, so the attribution of a release rests on a declaration that is
versioned in this repository and on nothing that lives in an account of a third
party. The row is red while the two disagree, and the promotion to a stable
release refuses while it is red.

## The key that signs the tags cut from now on

This is the key the declaration names as current, and the key a consumer
places the publisher with:

- **Fingerprint:** `980C8E16562A797BABDC76161135ACC699A41110`
- **Identity it claims:** `Ruben <rubentxu74@gmail.com>`

The public key is published here as well, so a consumer needs nothing from the
machine that cut the release to verify a signature:

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

mDMEau5BnRYJKwYBBAHaRw8BAQdAaRgFHlGJVb3QCXi0iXVyJg0ZXF4oTV2SQO38
GAkqrbC0HFJ1YmVuIDxydWJlbnR4dTc0QGdtYWlsLmNvbT6IkwQTFgoAOxYhBJgM
jhZWKnl7q9x2FhE1rMaZpBEQBQJq7kGdAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMB
Ah4HAheAAAoJEBE1rMaZpBEQfs0A/jpFIRBg6CJH4MQoIfO1+DKmA3Y0nvXIx5AY
vpIdzMNzAQDaFxc01+t10Ce7fpKgCBPI5D4Lbo1+t4smwXNufAHdA7g4BGruQZ0S
CisGAQQBl1UBBQEBB0BsFzk1n2ubUZJ3Lkiwd63RVQXyXZPtF0K+9Uh/ceVJTgMB
CAeIeAQYFgoAIBYhBJgMjhZWKnl7q9x2FhE1rMaZpBEQBQJq7kGdAhsMAAoJEBE1
rMaZpBEQiEUBAMAqPb6CGn71cwKLwRdplQWlP3sd9tDDIt7MraYsIBBwAQD1vVY7
UseWs/pkgrBVd0fiZV08VNhroa3WnNlI1L/yAg==
=kw8H
-----END PGP PUBLIC KEY BLOCK-----
```

A consumer that verifies a published tag against this key compares the
fingerprint the tag's signature carries with the fingerprint above. The two
are the same when the release was cut with this key.

## The review of the published fingerprint

The fingerprint that signed the releases published so far was
`9600F7D49B9C966E3BB04E6A17E44CA7C5288281`. It was reviewed on 2026-11-06,
and this is what the review measured:

- The fingerprint appeared in **exactly one place** in the tree: the
  declaration file itself. It was in no README, in no release note and in no
  other document, so the artifact itself was the whole trust channel.
- It was **not published on any keyserver**. The keyserver of
  `keys.openpgp.org` holds no such key, so no consumer could fetch it from
  there either.
- It was **not registered on the account that owns this repository**. The
  account published an empty list of GPG keys, so the platform itself showed
  every tag it had signed as unverified. The channel this repository uses now
  does not depend on that account: it is the declaration versioned here.
- Its user id claimed `test@example.com`. `example.com` is reserved for
  documentation, which means the address belongs to nobody and can never
  corroborate a key. **The key could not receive an attribution by
  construction**, not by accident.
- The key was created in the keyring of the publishing machine on 2026-10-10
  and signs without a passphrase, because the release path signs unattended.
  A replacement key with a passphrase would have broken that path.

The audit of the published tags found the consequences of the first fact:
of the 167 tags published under the `v*` pattern, **7 carry a signature that
verifies** (all seven signed by the key under review), 70 are annotated tags
with no signature at all, 12 are lightweight tags, and 78 could not be
measured because this repository holds a different tag object than the remote
publishes.

The conclusion of the review: the key was **a test key**. It was not a
forgery and not a compromise — it was a key that was never made attributable
in the first place, published as the key that signs.

## The rotation

The key was replaced on 2026-11-06. The new key declares the identity
`Ruben <rubentxu74@gmail.com>` — the name and the address that the git
configuration of this machine declares for this repository. It is an address
that a mailbox can corroborate, which is what the previous identity could
not be.

The rotation did three things, and the third is the one that matters:

1. the new key became the current key, and it is the one that signs;
2. the key that signed the published tags joined the trust set, so the seven
   tags that carry its signature still verify — the audit of the published
   tags reports the same seven passes it reported before the rotation;
3. the rotation made **future** releases attributable. It did not attribute
   the releases already published: those carry the signature of a key that
   was never published to the consumers, and no rotation reaches back to
   change them.

Nothing was rewritten. The old key is still in the keyring, no published tag
was touched, and no tag was moved.

## What is still missing

One thing is open, and it needs the publisher rather than the code.

**The backup of the private key.** The private key of the new key exists in
the keyring of one machine. Without a copy of it there is no signed release
worth anything: the tags already published keep verifying, and the next one
cannot be signed at all. The publisher keeps that copy, and this repository
deliberately does not hold it.

The attribution of the publisher is no longer open: the fingerprint that signs
is declared in `scripts/release/signing-key.conf`, published in this
document, and `scripts/check-signing-key-trust.py` refuses the tree while the
two disagree.

## How to check a published tag

```bash
# The signature verifies, and it comes from a key this repository trusts.
scripts/release/verify-tag-signature.sh --tag v0.92.0-rc1 \
    --trust-conf scripts/release/signing-key.conf

# The whole published history, against the whole trusted set.
scripts/release/verify-published-tags.sh --pattern 'v*' \
    --trust-conf scripts/release/signing-key.conf
```

The release path asks a narrower question about the tag it has just cut: that
one has to carry the key that signs NOW, and not any key of the set. That is
the `--expect-fingerprint` mode, and the fingerprint it asks for is the one
the declaration names as current — which
`scripts/release/read-signing-trust.sh` is what reads.

## What the audit of the published history reports

The audit over the whole history is RED, and it is supposed to be: the tags
published before the signing policy were never signed, and no tool can change
that without rewriting history, which this repository does not do.

What the audit is for is that the red says WHICH. Measured over the `v*`
scope on 2026-11-07:

```
published-tags: FAIL checked=168 annotated=156 lightweight=12
                verified=8 unsigned=148 from_another_key=0
                does_not_verify=0 uncheckable=0 not_measured=0
                failed=160
                newest_verified=v0.93.0-rc1@2026-11-06T22:38:16Z
                newest_unsigned=v0.90.0-rc2@2026-10-24T16:47:00Z
```

Three numbers in that line carry the meaning:

- **`not_measured=0`.** Nothing that was published was left unmeasured. The
  audit reads the object the REMOTE publishes — it brings it by its
  identifier when this tree does not hold it, which creates no reference —
  so a red row is a fact about the publication and never a limitation of the
  instrument. A `NOT_MEASURED` row, if there is one, is counted apart and is
  never a pass.
- **`unsigned=148` and `lightweight=12`.** The classes are counted apart
  because they are different facts: a tag that was annotated and never
  signed, and a tag that points straight at a commit with no tag object at
  all. Neither of them is a signature that failed to verify.
- **`newest_verified` and `newest_unsigned`.** The two moments say where the
  frontier lies. The newest unsigned tag is older than the newest verified
  one, so the red reaches the PAST and not the publication of today.

The audit reads the class of a signature from the REASON the checker gives,
not from which of its rows failed. "The tag carries no signature" and "the
content was altered after it was signed" are different facts, and a verdict
that calls both of them "does not verify" accuses a manipulation that did
not happen.

The audit also refuses what it did NOT find. A tag the caller names and the
remote does not publish is a red of its own (`absent=`), because the release
path asks about the tag it has just pushed: a push that had not landed used
to leave the audit with nothing to check, and nothing to check was a green.
A scope that matches no tag at all is a red too — the audit measured
nothing, and that is not a pass. A PATTERN is only a filter, so a pattern
that matches nothing says nothing about the remote; a `--tag` is a claim
about the remote, and a claim the remote contradicts is a red.

