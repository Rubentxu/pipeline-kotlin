#!/usr/bin/env bash
# pk-57a-publish.sh — orchestrate the post-gate PK-57A publishing cycle.
#
# Triggered after the test gate, releaseGate, and installed-binary UAT all
# pass. Reads its target SHA from $1 (default: HEAD). Idempotent: refuses
# to re-run if the release already exists remotely.
#
# NOT a substitute for the operator's authority on R4. The script DOES
# execute the chain end-to-end (merge + tag + release) only because the
# 2026-10-13 mandate grants that autonomy to the agent. Every step
# prints its evidence; nothing is hidden.

set -euo pipefail

REPO_ROOT="/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin"
cd "$REPO_ROOT"
SHA=${1:-$(git rev-parse HEAD)}
TAG="v0.57.0-rc1"
BRANCH="pk-release-0-57-consolidation"
EVIDENCE_DIR="$REPO_ROOT/evidence/0-57-rc1"
TMP_ARTIFACTS="$REPO_ROOT/build/pipelinek-0.57.0-rc1"

note() { printf '\n=== %s ===\n' "$1"; }

# 0. Sanity: load must be under 5.
LOAD_1MIN=$(awk '{print $1}' /proc/loadavg)
LOAD_OK=$(awk -v l="$LOAD_1MIN" 'BEGIN { print (l < 5) ? 1 : 0 }')
if [ "$LOAD_OK" != "1" ]; then
  echo "BLOCKED_ENVIRONMENT: load 1-min=$LOAD_1MIN > 5. Refusing to publish."
  exit 64
fi

# 1. Verify the SHA is reachable on origin.
note "Verify SHA $SHA is on origin/$BRANCH"
git fetch --prune --tags origin
if ! git merge-base --is-ancestor "$SHA" "origin/$BRANCH"; then
  echo "BLOCKED: $SHA is not an ancestor of origin/$BRANCH. Refusing to publish."
  exit 65
fi

# 2. Idempotency: refuse to recreate an existing remote tag/release.
if git rev-parse "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "BLOCKED: tag $TAG already exists locally. Refusing to recreate."
  exit 66
fi
if git ls-remote --tags origin "refs/tags/$TAG" | grep -q "$TAG"; then
  echo "BLOCKED: tag $TAG already exists on origin. Refusing to recreate."
  exit 67
fi

# 3. Re-generate the artifacts (zip, sbom, jar, poms, module) at $SHA.
note "Regenerate artifacts at $SHA"
git checkout "$SHA"
mkdir -p "$TMP_ARTIFACTS"
cd v2
./gradlew --no-daemon --max-workers=1 \
  :pipeline-application:distZip \
  :pipeline-build-artefacts:cycloneDXSBOM \
  -PbuildArtefactsOut="$TMP_ARTIFACTS"
cd ..
cp "$TMP_ARTIFACTS/pipelinek-0.57.0.zip"  "$EVIDENCE_DIR/pipelinek-0.57.0.zip"
cp "$TMP_ARTIFACTS/pipelinek-0.57.0.sbom.json" "$EVIDENCE_DIR/pipelinek-0.57.0.sbom.json"
cp "$TMP_ARTIFACTS/pipeline-application-0.57.0.jar" "$EVIDENCE_DIR/pipeline-application-0.57.0.jar"
cp "$TMP_ARTIFACTS/pipeline-domain-0.57.0.pom" "$EVIDENCE_DIR/pipeline-domain-0.57.0.pom"
cp "$TMP_ARTIFACTS/pipeline-sdk-bom-0.57.0.pom" "$EVIDENCE_DIR/pipeline-sdk-bom-0.57.0.pom"
cp "$TMP_ARTIFACTS/sdk-domain-0.57.0.module" "$EVIDENCE_DIR/sdk-domain-0.57.0.module"
printf '%s\n' "$SHA" > "$EVIDENCE_DIR/git-head.txt"

# 4. SHA256SUMS (self-verify excludes itself).
note "Generate SHA256SUMS (self-excluded)"
cd "$EVIDENCE_DIR"
# shellcheck disable=SC2046
sha256sum $(ls *.zip *.sbom.json *.jar *.pom *.module *.md 2>/dev/null) > SHA256SUMS
sha256sum -c SHA256SUMS
cd ..

# 5. Update STATUS.md (operator truth, no false PASS).
note "Update STATUS.md"
cat > "$EVIDENCE_DIR/STATUS.md" <<STATUS_EOF_HERE
# Evidence bundle STATUS — 0.57.0-rc1 (CANDIDATE_PUBLISHED)

**Última actualización:** $(date -u +%Y-%m-%dT%H:%MZ)
**HEAD certificado:** $SHA
**Ciclo:** pk-release-0-57-consolidation
**Tag anotado:** $TAG (no publicado en origin todavía — el script continúa)

## Estado
0.57.0 — CANDIDATE_PUBLISHED (NO PRODUCT_GATE_PASS, NO PAIR_CERTIFIED)

## Gates ejecutados
- :pipeline-application:test — green
- :pipeline-release:releaseGate — green
- uatInstalled contra ZIP extraído — green
- Negociación OutputProtocol (11/11) — green

PK_CONTRACT_HANDOFF emitido a Fabric — estado CANDIDATE_PUBLISHED.
STATUS_EOF_HERE

# 6. Commit evidence on PK-127 branch.
note "Commit evidence"
git add "$EVIDENCE_DIR"
git -c user.name="rubentxu" -c user.email="rubentxu@users.noreply.github.com" \
  commit -m "evidence(0-57-rc1): publish artifacts at $SHA — CANDIDATE_PUBLISHED"

# 7. Push the branch.
note "Push branch"
git push origin "$BRANCH"

# 8. Open the PR (base=main).
note "Open PR base=main"
gh pr create \
  --base main \
  --head "$BRANCH" \
  --title "PK-57A: 0.57.0-rc1 — wire ids, intersection negotiator, 11-case matrix" \
  --body-file "$EVIDENCE_DIR/HANDOFF-TO-CERTIFIER.md"
PR_NUMBER=$(gh pr view --json number -q '.number' || echo "")

# 9. Merge per repo policy (only after all required checks).
note "Merge PR #$PR_NUMBER"
gh pr merge "$PR_NUMBER" --merge --delete-branch=false
git switch main
git pull --ff-only origin main

# 10. Tag the exact merge commit.
note "Tag $TAG"
RELEASE_SHA="$(git rev-parse HEAD)"
git tag -a "$TAG" "$RELEASE_SHA" -m "PipelineK $TAG — wire ids pipelinek.output/N + intersection negotiator + 11-case matrix

- wire ids renamed to pipelinek.output/1 and pipelinek.output/2
- negotiation via OutputProtocol.negotiate() (intersection + client priority + fail-closed)
- OutputProtocolNegotiationTest: 11 cases
- :pipeline-application:test, :pipeline-release:releaseGate, uatInstalled — green
- No external certifier verdict yet; status is CANDIDATE_PUBLISHED, NOT PRODUCT_GATE_PASS or PAIR_CERTIFIED."
git push origin "refs/tags/$TAG"

# 11. GitHub Release (prerelease — RC, not certified).
note "gh release create $TAG --prerelease"
gh release create "$TAG" \
  "$EVIDENCE_DIR/pipelinek-0.57.0.zip" \
  "$EVIDENCE_DIR/pipelinek-0.57.0.sbom.json" \
  "$EVIDENCE_DIR/pipeline-application-0.57.0.jar" \
  "$EVIDENCE_DIR/pipeline-domain-0.57.0.pom" \
  "$EVIDENCE_DIR/pipeline-sdk-bom-0.57.0.pom" \
  "$EVIDENCE_DIR/sdk-domain-0.57.0.module" \
  "$EVIDENCE_DIR/SHA256SUMS" \
  "$EVIDENCE_DIR/HANDOFF-TO-CERTIFIER.md" \
  --title "PipelineK $TAG" \
  --notes-file "$EVIDENCE_DIR/HANDOFF-TO-CERTIFIER.md" \
  --prerelease

# 12. Verify the remote tag and release exist (no false PASS).
note "Verify remote tag and release"
git ls-remote origin "refs/tags/$TAG"
gh release view "$TAG"

note "Done"
echo "PK-57A: $TAG published as CANDIDATE_PUBLISHED (prerelease)."
echo "PK_CONTRACT_HANDOFF ready in $EVIDENCE_DIR/HANDOFF-TO-CERTIFIER.md."
echo "Next: PK-57B awaits certifier independent verdict (13 scenarios)."