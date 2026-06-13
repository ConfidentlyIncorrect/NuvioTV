#!/usr/bin/env bash
#
# Sync upstream tapframe/NuvioTV into our `custom` branch — the manual dance in one command.
#
# It fetches upstream, spins up a throwaway `sync/upstream-<date>` branch off `custom`, attempts the
# merge, and:
#   * clean merge  -> compile-checks it and prints the fast-forward command to land it on `custom`.
#   * conflicts    -> lists the conflicted files and leaves the half-merged branch for you to resolve.
#
# It NEVER touches `custom` or pushes anything — you review, build, and fast-forward yourself. Our
# changes overlap upstream's churn (HeroSection, MetaDetailsViewModel, EpisodesSection, the DTOs/
# mappers, …), so a sync almost always needs a few manual conflict resolutions; this just removes the
# boilerplate around them.
#
# Usage:   scripts/sync-upstream.sh [upstream-branch]   (default: dev)
# Requires: JAVA_HOME pointing at a JDK 17–21 for the compile check (skip with SKIP_BUILD=1).
set -euo pipefail

UPSTREAM_URL="https://github.com/tapframe/NuvioTV.git"
UPSTREAM_BRANCH="${1:-dev}"
BASE_BRANCH="custom"
SYNC_BRANCH="sync/upstream-$(date +%Y%m%d-%H%M%S)"

cd "$(git rev-parse --show-toplevel)"

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
echo "› Fetching upstream/$UPSTREAM_BRANCH …"
git fetch --quiet upstream "$UPSTREAM_BRANCH"

NEW="$(git rev-list --count "${BASE_BRANCH}..upstream/${UPSTREAM_BRANCH}")"
echo "› $NEW new commit(s) on upstream/$UPSTREAM_BRANCH."
if [ "$NEW" -eq 0 ]; then echo "✅ Already up to date."; exit 0; fi

echo "› Conflict surface (files changed both upstream and on $BASE_BRANCH):"
comm -12 \
  <(git diff --name-only "upstream/${UPSTREAM_BRANCH}...${BASE_BRANCH}" | sort -u) \
  <(git diff --name-only "${BASE_BRANCH}...upstream/${UPSTREAM_BRANCH}" | sort -u) \
  | sed 's/^/    /' || true

git switch "$BASE_BRANCH"
git switch -C "$SYNC_BRANCH"
echo "› Merging upstream/$UPSTREAM_BRANCH into $SYNC_BRANCH …"

if git merge --no-edit "upstream/${UPSTREAM_BRANCH}"; then
  echo "✅ Clean merge."
  if [ "${SKIP_BUILD:-0}" = "1" ]; then
    echo "   (SKIP_BUILD=1 — skipping compile check)"
  else
    echo "› Compile-checking …"
    if ./gradlew :app:compileFullDebugKotlin --no-watch-fs -q; then
      echo "✅ Compiles."
    else
      echo "⚠️  Clean merge but compile FAILED — fix on $SYNC_BRANCH before landing."
      exit 1
    fi
  fi
  echo
  echo "Land it:  git switch $BASE_BRANCH && git merge --ff-only $SYNC_BRANCH && git push origin $BASE_BRANCH"
else
  echo "⚠️  Merge conflicts in:"
  git diff --name-only --diff-filter=U | sed 's/^/    /'
  echo
  echo "Resolve on $SYNC_BRANCH, then: ./gradlew :app:assembleFullDebug --no-watch-fs"
  echo "Land it:  git switch $BASE_BRANCH && git merge --ff-only $SYNC_BRANCH && git push origin $BASE_BRANCH"
  echo "Bail out: git merge --abort && git switch $BASE_BRANCH && git branch -D $SYNC_BRANCH"
  exit 1
fi
