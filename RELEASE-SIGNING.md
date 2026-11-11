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

This repository signs two things, and they belong to two trust domains:

| What it signs | Where it is declared | What a consumer verifies |
|---|---|---|
| the release tags | `scripts/release/signing-key.conf` | the signature of the tag |
| the Maven artifacts | `scripts/release/maven-signing-key.conf` | the signature of the JAR, the POM and the sources |

Both declarations are read through the same reader,
`scripts/release/read-signing-trust.sh`. One reader per format is the rule;
two facts that are not the same fact do not need two readers.

Each declaration is a **set**, and a set is a set for a reason. The current key
signs what is cut from now on. The keys of the second group signed what is
already published, and those signatures keep verifying: a published tag is a
fact, and a key that stops signing does not un-sign what it already signed. A
rotation that dropped the second group would turn every release ever published
into a tag nobody can verify, which is the opposite of what a rotation is for.

The shape of a set is checked by `scripts/check-signing-key-trust.py`, which
reads the tree and nothing else. Seven rows per declaration, and one more
about the pair:

| Row | What it asks |
|---|---|
| 1 | exactly one current key is declared |
| 2 | every fingerprint is forty hexadecimal characters |
| 3 | no fingerprint is declared twice |
| 4 | the current key is not among the keys of the second group |
| 5 | every key of the second group declares the identity it claims |
| 6 | the current key claims no domain reserved for documentation |
| 7 | the fingerprint that signs is published in `RELEASE-SIGNING.md` |
| 8 | the two declarations do not name the same key |

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

Row 8 is the separation between the two domains. They are only separate while
they are signed by different keys: a fingerprint both declarations name binds
them into one, and then the revocation of one reaches the other. The row is
red while they share a fingerprint, and it names the fingerprint it found in
both.

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

## The key that signs the Maven artifacts

This is a **different key** from the one that signs the tags, and it is
declared in `scripts/release/maven-signing-key.conf`.

The two are different on purpose. The tag that names a release and the JAR
that a third party downloads are two trust domains, and their revocations are
two: compromising the key that signs the tags would leave every published
artifact unsigned, and compromising the key that signs the artifacts would
leave every release tag unattributable. One key pair would bind both domains
into one, and then one revocation would reach the other. Row 8 of the guard
is what keeps them apart, and the signer of the bundle refuses a bundle signed
with the key of the tags.

- **Fingerprint:** `965464FFF6496FAFCC62F49644E527FF58783108`
- **Identity it claims:** `PipelineK Maven Artifacts <rubentxu74@gmail.com>`
- **Algorithm:** RSA 4096, on the primary key, with no signing subkey. The
  artifacts are signed by the primary key, and a consumer's verifier needs no
  special case for it.

The public key is published here as well, so a consumer needs nothing from
the machine that published the artifact to verify a signature:

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGrxff4BEADLO3ZwNctPEP9imOGG/uZHluJJojzuWRpoveNr75+8xCoRdN8y
gMcljRq2coVPxwWFNoevmBcHyLTna69IRcW1fSZCESyTDhtn30719cBcclnbWe0J
Mj+KY1tmlEOKFDaPBPkxR4LB4pe9/Bq+aaSf9PHWbUGG6zQ/pDBMvp6ufF2bwQME
jzPlv2difO97O8XwbhbVpfow45OPhbCX3FuC8fPSzqnwLgC0cWUN9y5q5WasPtWa
MYZMcmHN5iC+KmFy7kx3WPmYxNilDjdwn4Fhvp6qTVpKD04S4T+GPSl3R63LAUXF
ZimlBsWNV2QM9ttXQLCPGD3M4ju1/+KMnBV1gqndSzuw+nhu7xT92orkprtbrjto
MM5SxxeHin5cYE1+gCVGqWBoFdhtDTo2x4HAv8CjjXqVpHy/yipuv9RV9XeKL79y
XbEWf4KJO1KX+WlZbkPTw65jWrMhJcR/1uGJpUqeMryopTih57/DkeotUxEYsZhn
JnbwO6CZsJVSQJvzOHsr9/z01CD6IJxmoIbBN4wt/f1GvKCqqsc0G7L+PjJrpo9p
nyo+szyI2zaWmxlJzmz86sVmFL4NaxSCEGWCqCAq89tGLpGgoDsSjcmAN7WH4EtR
RV7EQcMbvOFKGzda2fgqam2gOrk2ZGuGUgvNh6F4GIuBZTZKdS1oOx3uywARAQAB
tDBQaXBlbGluZUsgTWF2ZW4gQXJ0aWZhY3RzIDxydWJlbnR4dTc0QGdtYWlsLmNv
bT6JAlEEEwEKADsWIQSWVGT/9klvr8xi9JZE5Sf/WHgxCAUCavF9/gIbAwULCQgH
AgIiAgYVCgkICwIEFgIDAQIeBwIXgAAKCRBE5Sf/WHgxCKVQD/4/olW7W+QBYSiD
tqLoBQRDf4kVkBIzKva/lKiOTa9N+ogtHiMatVBBKDmNN24CLp45SqyX9W6MpOwF
PjRw4VgaZPaJu6pcGAw5VDbu27/0QGI178E0SamcIed81/N2hGoGt1Lzqz1lp/ms
4zzHBiYQcI1AwY615/Frxq/oTvdYENk2Gk3VtjMYE4X76TuORiAj1qijRtYmujfI
ZhY88SLqPSeYgKvL66MbttwRd5Srpd8bYmeFJuhkaZaJVgy0VjmwjZPbcD18OXkK
A5s82TNIEq0laXVDfQ1PliLza9PZ+bJ0Cv2g7WD/D+Sj7ciG3ptfda1tjt2OLrTR
x1Q69LUFP466n5YqmupxPK/hSSJf/53Cqoa9DJOw1W0wuLNeLTutHhF+cjF9LeI/
/h/9nk1YcIs8byNqP6qpDCFuT8D1kpnYRDQwXplB2QIEx90igq+s+41vhoft5/W6
awgtxPe4DewC8m3jxV0MR+eXf1oIOZeioJZzimDXraHnQuXOZVu73FMZEfQED8XP
Rg4HLEmWkKYvR7huEA2QOXItewBJNVaU9TX/nR5DLQlFkzKFsVQFk/AkVdIZlRk5
LzbumlF4VqUxA5V0igvGqNJuI4UgqdT0JikN+wec6Ura8fmOkOeD5hlQZYSagaz2
BAuOFkSRYGCYAe/T6xxfQaBlo6+5hw==
=hhpD
-----END PGP PUBLIC KEY BLOCK-----
```

A consumer that verifies a published artifact against this key compares the
fingerprint the artifact's signature carries with the fingerprint above. The
two are the same when the artifact was published with this key.

**What is done, and what is not.** This key is declared here and its public key
is published here. Its public key has been published on the key servers
`keys.openpgp.org` and `keyserver.ubuntu.com`, and a consumer who cannot reach
either of them can still place the key with the armored block above. Maven
Central found the key by that fingerprint when it validated the signature of the
published bundle, which is the fact the publication needed.

What is **not** done is registering the key on the account of the Maven
Central Portal: that account is somebody else's database, and nothing here
depends on it. The declaration above and the guard that reads it are the
channel, and the guard never asks an account whether it holds a key.

## How a consumer verifies a published artifact

Every artifact of the bundle carries a detached signature beside it:
`pipelinek-domain-0.96.0.jar` and `pipelinek-domain-0.96.0.jar.asc`. The
signature is made with the key declared for the Maven artifacts, and the check
is the same one this repository runs over its own bundle before it travels.

```bash
# 1. the public key of the signer: the one published above, and the one the
#    declaration names.
gpg --import pipelinek-maven-artifacts.asc

# 2. the signature verifies, and it carries the published fingerprint.
gpg --verify pipelinek-domain-0.96.0.jar.asc pipelinek-domain-0.96.0.jar
```

A good signature prints `Good signature` and the fingerprint of the key that
made it. That fingerprint has to be the one published in this document,
`965464FFF6496FAFCC62F49644E527FF58783108`. A signature that verifies against
a key nobody published proves possession and nothing else, and a consumer who
reads the fingerprint out of this repository and compares it with the one the
signature carries is placing the publisher with the repository and not with an
account of a third party.

In a Gradle build, the same comparison is made by the dependency verification of
Gradle: the `gradle/verification-metadata.xml` of the consumer carries the key
and the checksums of the artifacts it accepts, and the build fails when an
artifact arrives with bytes nobody pinned. In a Maven build there is no built-in
equivalent: the `.asc` that sits beside the artifact is what a consumer can
check, with `gpg --verify`, before it trusts the bytes.

**The Maven channel carries 0.96.0 today.** The release this tree declares is
0.96.0 (`pipelinek/build.gradle.kts`), and it is published in Maven Central
under the group `dev.rubentxu.pipelinek`, together with 0.95.0, which shares
those coordinates. The release before it, 0.94.2, was published under the
group `dev.rubentxu.pipeline.v2` and under the artifact names `pipeline-domain`
and the rest of that set, and it is STILL SERVED: a published version is never
replaced, and the coordinates of 0.94.2 answer today exactly as they answered
when they were published. The measurement that says so, with the commands that
produce it, is in "What the channel carries today" below.

The bytes a consumer resolves in Central are the bytes this repository signed,
and the key that signed them is the one named above. The paragraph below is how
a consumer verifies what arrives.

## How the artifacts travel to the remote destination

The destination is **Maven Central**, through its portal. The portal is not a
Maven-layout repository that accepts a push per file: the service that did is
retired, and what the portal accepts is a **bundle** — one ZIP with the layout
of a Maven repository — sent to one endpoint with a token. The seven coordinates
are published to one endpoint, and the bundle is the whole publication.

The bytes that travel are the bytes that were signed. The bundle is not
rebuilt on the way out: `scripts/release/stage-sdk-bundle.sh` publishes the
seven coordinates into `build/sdk-repo-staging`, signs every artifact there and
compiles an independent consumer against the staged bytes, and
`scripts/release/upload-sdk-bundle.sh` packages **that** directory. The script
re-verifies the bundle against the key declared for the Maven artifacts before
it packages it, so what travels is what was signed and not what the build
happened to leave on disk.

The upload is a task of the build, and the order is the guarantee:

```bash
cd pipelinek && ./gradlew :sdkUpload \
  -Pmaven.request=<file> -Pmaven.manifest=<file> -Pmaven.certification=<file> \
  -Pmaven.bundle=<dir> -Pmaven.sdkZip=<file> -Pmaven.published=<dir> \
  -Pmaven.verdict=<file>
```

| Step | What it is | What it refuses |
|---|---|---|
| `sdkUploadPreconditions` | the documents of the admission and the credentials of the destination | a missing document or a missing credential, before anything expensive runs |
| `sdkSigningTrustCheck` | `scripts/check-signing-key-trust.py` | a tree whose keys cannot be attributed, or two declarations that name the same key |
| `:pipeline-release:mavenPublication` | the decision of the product's own model | a request that may not publish the bundle |
| `sdkUpload` | `scripts/release/upload-sdk-bundle.sh` | a verdict that does not admit the publication, and a bundle that fails its own checker |

The credentials are read from the environment (`SONATYPE_USERNAME` and
`SONATYPE_PASSWORD`) and never from a file of this repository, and they reach
the transport through its configuration on standard input, so they do not
appear in the process list. The endpoint and the name the destination shows
are properties (`sdkRemoteUploadUrl` and `sdkRemoteDeploymentName`), because
this tree does not pretend to own a server.

The publication is **fail-closed in both directions**: the upload task does not
run while the admission refuses, and the upload script refuses a verdict that
does not admit the publication even when it is handed one. The bundle is
packaged before the credentials are read, so a missing secret costs a ZIP and
not a half-published deployment.

**The 0.94.2 deployment of the portal is still in Central.** The account, its
namespace, its token and the registration of the key on it are facts about
somebody else's database, and nothing here depends on them: what depends on
them is the upload, and the upload is the one thing this tree cannot do on its
own. The deployment is named `pipelinek-0.94.2-r6` and the bytes it carried are
the ones the signed bundle holds. It was NOT withdrawn: measured on 2026-11-11,
the coordinates of 0.94.2 under `dev.rubentxu.pipeline.v2` answer 200 in
Central. The API of the publisher cannot drop a deployment that is already
published, so a withdrawal there needs a person, and it has not happened.

What that means for a consumer is exact. The coordinates of 0.94.2 still
resolve, with the bytes they were published with, and the coordinates of 0.96.0
resolve beside them under the new group. A consumer that stays on the old
coordinates keeps resolving a version that was certified; a consumer that moves
to the new ones resolves the current release. Neither group is withdrawn, and
the two groups are two names for contracts that were published under both.

## The channels the artifacts travel in

The SDK is published under one group, `dev.rubentxu.pipelinek`, and it travels
in three places. **The group is the same in all three**: a consumer's build does
not change between them, and a coordinate that resolves from one resolves from
the others with the same name.

The release before this one, 0.94.2, was published under the group
`dev.rubentxu.pipeline.v2` and under the artifact names `pipeline-domain`,
`pipeline-events`, `pipeline-output`, `pipeline-runtime-api`,
`pipeline-scripting-api` and `pipeline-sdk-bom`. A consumer that depended on
those coordinates has to move to the new ones: one release has one name for the
same contracts, and this one carries the name of the solution.

| Channel | What it serves | What a consumer needs |
|---|---|---|
| the SDK ZIP of the release | the seven coordinates with their JARs and POMs | unzip it and point the build at the directory |
| Maven Central, through its portal | the same coordinates with `sources.jar`, `javadoc.jar`, the Gradle module metadata and a detached signature for every artifact | nothing but the coordinates |
| the Maven registry of GitHub Packages | the same coordinates and the same bytes | a token with `read:packages` |

The two remote channels exist because they fail differently. Central needs a
namespace and a token that are facts about somebody else's database; the GitHub
registry needs only a token. Publishing to one does not depend on the other being
ready.

**What the signature proves, per channel.** The detached signature travels with
the artifacts of the Central channel, and the GitHub registry serves the same
bytes. A signature is over the bytes of one artifact, not over the place they came
from: the `.asc` that verifies a JAR downloaded from the registry is the same
`.asc` that verifies the same JAR downloaded from Central. A consumer who wants
the signature of a registry artifact takes it from the Central channel, where it
travels, and verifies it against the fingerprint published above.

**How a channel is checked.** The bytes that reach a registry are the bytes that
were signed, and the only way to know that the registry serves them is to ask
the registry. `scripts/release/verify-published-maven.sh` does that: it reads the
publication manifest of the candidate and asks a repository whether it serves
every artifact the manifest declares, at its path, with its digest.

```bash
scripts/release/verify-published-maven.sh \
  --manifest <maven-publication-manifest.json> \
  --repository https://repo1.maven.org/maven2
```

The verdict names what the repository served: a version it does not serve, an
artifact it does not serve and an artifact it serves with other bytes are three
different facts, and each one has its own fix. The instrument measures the bytes
that arrive and compares digests, so it does not believe what the registry says
about itself. A registry that answers with something that is neither a file nor
its absence has NOT been measured, and the verdict says so instead of claiming
that the version is absent.

What the manifest declares is what the check measures, and the manifest
declares the files a consumer resolves: the POM, the JAR, the sources, the
javadoc and the Gradle module metadata. The last one is not a companion of the
publication: a consumer of Gradle resolves the `.module` in preference to the
POM, and the POM says so itself, so a manifest that left it out would certify
the coordinate of a consumer of Maven and say nothing about the one of a
consumer of Gradle. The manifest carries a shape, and the instrument refuses a
manifest of another shape: a check that cannot declare the files it certifies
says nothing about them. And the kind of an artifact is not a free label: it is
the kind its path says it is, so the manifest refuses an artifact that calls a
file something else — a manifest that describes bytes nobody can compare with
the measurement of the bundle describes nothing.

MEASURED on 2026-11-11 against `https://repo1.maven.org/maven2`: the manifest
of the 0.96.0 candidate, measured again by the producer of this repository
from the same staged bundle the publication used — its digest over the whole
bundle is the one the published manifest carries — declares 32 artifacts, and
the channel serves the 32 with their declared digests: the 6 JARs, the 6
sources, the 6 javadocs, the 7 POMs and the 7 Gradle module metadata files.
The same check with one of those digests altered is refused, and the refusal
names the file.

What it does NOT measure: the detached signatures, which travel with the
artifacts and are verified when the bundle is staged
(`scripts/check-sdk-bundle.py`), and the files the registry generates beside the
artifacts, which are its own checksums and its own metadata. It does not read
`maven-metadata.xml` either: the verdict says that the files of the version are
served with their digests, not that a resolver of any shape finds them. The
authenticity of the manifest itself is the caller's assumption: the instrument
reads the document it is given and measures the repository against it, so the
manifest that reaches it must come from the publication that produced it.

**The limit, named: what the manifest declares is what is measured.** The
instrument does not enumerate what the repository holds. A file the manifest
does not declare is not measured, not compared and not reported, so the verdict
says "32 artifact(s) served with the declared digests" and never "the
repository is complete". The manifest is the whole contract of this check: a
manifest that declares less certifies less, and a manifest of another shape is
refused rather than measured with the vocabulary of this one.

A repository that asks for a credential takes it from the environment
(`PIPELINEK_REPOSITORY_USER` and `PIPELINEK_REPOSITORY_TOKEN`), never from an
argument and never from a file of this repository, and it takes it only after
the repository has asked for one: a registry that never demands a credential
never sees it. The credential lives in the environment of the build that runs
the check, and in the environment of the processes that build starts, which is
the price of not writing it to a file. The repository is reached over TLS,
and the only address the instrument accepts without it is the loopback, where
there is no wire to read.

The exit code is the decision, and the three codes are three different facts:
`0` the repository serves what the manifest declares, `1` it does not, `2` the
state could not be measured. The code that the door reads is the one the
model wrote, not the exit code of the process that ran it, because a JVM that
cannot start its own main class also exits with `1` and that `1` is not a
statement about the repository. A check that could not conclude is a check
that has to be run again, and it is not a channel that is broken.

This door asks a third party, so its answer depends on that third party: a
registry that is down, that rate-limits or whose token has expired reaches
the operator as a `2`, and not as a `1`. It belongs beside the publication,
never inside it: the decision to publish is taken from local documents, and
this one only says what a consumer would find afterwards.

**0.94.2 is published in Maven Central**, under the coordinates that release was
published with, and it is still served there: its six artifacts carry the same
bytes the signed bundle carries, an independent consumer compiles against them,
and every published file matches the digest of the signed artifact. The ZIP of
the release is published as well. It was published in the GitHub Packages
registry too, and whether its six packages are still there is NOT MEASURED
today: that registry answers 401 to everything without a credential, including
to coordinates that do not exist, so only a request WITH a credential tells an
absent package from a private one.

### What the channel carries today

Measured on 2026-11-11, with the commands that produce it:

```bash
$ curl -s -o /dev/null -w '%{http_code}\n' \
    https://repo1.maven.org/maven2/dev/rubentxu/pipeline/v2/pipeline-domain/0.94.2/pipeline-domain-0.94.2.jar
200
$ curl -s -o /dev/null -w '%{http_code}\n' \
    https://repo1.maven.org/maven2/dev/rubentxu/pipelinek/pipelinek-domain/0.95.0/pipelinek-domain-0.95.0.jar
200
$ curl -s -o /dev/null -w '%{http_code}\n' \
    https://repo1.maven.org/maven2/dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar
200
$ curl -s -o /dev/null -w '%{http_code}\n' \
    https://maven.pkg.github.com/Rubentxu/pipeline-kotlin/dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar
401
$ curl -s -o /dev/null -w '%{http_code}\n' \
    https://maven.pkg.github.com/Rubentxu/pipeline-kotlin/dev/rubentxu/pipelinek/pipelinek-domain/9.99.99/pipelinek-domain-9.99.99.jar
401
```

The versions each group carries are the directories the registry serves:
`dev/rubentxu/pipeline/v2/pipeline-domain/` holds `0.94.2` and nothing else,
and `dev/rubentxu/pipelinek/pipelinek-domain/` holds `0.95.0` and `0.96.0`.

The `401` of the mirror is neither an absence nor a presence: it is what that
registry answers to EVERY path without a credential, including to a coordinate
that does not exist. Only a request WITH a credential tells an absent package
from a private one, and the measurement that does that is the one this
repository runs with `scripts/release/verify-published-maven.sh` and a token in
the environment.

What this measurement does NOT say: that the bytes the mirror serves are the
certified ones (that is the instrument's verdict, and it is a separate
measurement), that the detached signatures of the artifacts served by Central
verify (they travel with the artifacts and are verified when the bundle is
staged), and that the metadata the registries generate beside the artifacts is
present.

## The review of the published fingerprint

The fingerprint that signed the releases published so far was
`9600F7D49B9C966E3BB04E6A17E44CA7C5288281`. It was reviewed on 2026-11-06,
and this is what the review measured. This is the **tag** key, and it is not
the key that signs the Maven artifacts: the Maven artifacts are signed by
`965464FFF6496FAFCC62F49644E527FF58783108`, which is published on both key
servers and was found by Maven Central when it validated the published bundle.
The measurements below are about the tag key, and they are still true of it:

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

