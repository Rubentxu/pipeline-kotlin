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

## The trust channel is outside the artifact

An artifact that declares its own trust proves nothing about itself: anybody
able to write the file is able to write the declaration. So the binding
between a key and a publisher has to live somewhere the publisher does not
control alone.

For this repository the channel is **the account that owns the repository**.
An account publishes the list of GPG keys it vouches for, and a key on that
list is one the publishing account stands behind. A consumer who wants to
place the publisher reads that list and compares it with the fingerprint the
release names.

The channel is checked, not merely described:

```bash
scripts/release/verify-signing-key-attribution.sh
```

The check reads the public API without credentials, and it distinguishes two
facts that a single boolean would flatten:

- the key is **not registered** on the account, and
- the registration **could not be measured** (the account could not be read).

The second one is not a pass either. A measurement that did not happen is not
a green, and the promotion to a stable release refuses in both cases.

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
reads the tree and nothing else. Six rows, and the sixth one has teeth:

| Row | What it asks |
|---|---|
| 1 | exactly one current key is declared |
| 2 | every fingerprint is forty hexadecimal characters |
| 3 | no fingerprint is declared twice |
| 4 | the current key is not among the keys of the second group |
| 5 | every key of the second group declares the identity it claims |
| 6 | the current key claims no domain reserved for documentation |

Row 6 exists because a domain reserved for documentation belongs to nobody.
An address under such a domain cannot be corroborated by any channel,
because there is no owner behind it. A key that claims one can therefore
never receive an attribution, and a repository that publishes it as the key
that signs is publishing a key that no consumer is able to place. The row is
red while the current key claims such an address and green once it claims an
address a channel can corroborate.

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
  every tag it had signed as unverified.
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
   was never registered anywhere, and no rotation reaches back to change
   them.

Nothing was rewritten. The old key is still in the keyring, no published tag
was touched, and no tag was moved.

## What is still missing

Two things are open, and both need the publisher rather than the code.

**The registration.** The new key is not registered on the account that owns
this repository yet. Registering it needs the account and a browser, and
until it happens `scripts/release/verify-signing-key-attribution.sh` reports
that the key is not registered, and `scripts/release/promote-stable.sh`
**refuses the promotion to a stable release**. That refusal is deliberate: a
stable release is the last thing a consumer looks at, and a stable release
signed by a key that no account vouches for is a release nobody can
attribute. The next candidate is cut with the new key, and the promotion
becomes possible once the account registers it.

**The backup of the private key.** The private key of the new key exists in
the keyring of one machine. Without a copy of it there is no signed release
worth anything: the tags already published keep verifying, and the next one
cannot be signed at all. The publisher keeps that copy, and this repository
deliberately does not hold it.

## How to check a published tag

```bash
# The signature verifies, and it comes from a key this repository trusts.
scripts/release/verify-tag-signature.sh --tag v0.92.0-rc1 \
    --trust-conf scripts/release/signing-key.conf

# The whole published history, against the whole trusted set.
scripts/release/verify-published-tags.sh --pattern 'v*' \
    --trust-conf scripts/release/signing-key.conf

# The publisher is attributable.
scripts/release/verify-signing-key-attribution.sh
```

The release path asks a narrower question about the tag it has just cut: that
one has to carry the key that signs NOW, and not any key of the set. That is
the `--expect-fingerprint` mode, and the fingerprint it asks for is the one
the declaration names as current — which
`scripts/release/read-signing-trust.sh` is what reads.
