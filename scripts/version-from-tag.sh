#!/usr/bin/env bash
# Derives the app version from a release tag, so the tag is the only place the version lives.
#
#   scripts/version-from-tag.sh v1.4.2       -> versionName=1.4.2       versionCode=100400299
#   scripts/version-from-tag.sh v1.4.2-rc.3  -> versionName=1.4.2-rc.3  versionCode=100400203
#
# Accepted tags: vMAJOR.MINOR.PATCH, optionally followed by -rc.N (N = 1..98), with
# MAJOR <= 20 and MINOR, PATCH <= 999. The version code is
#   (MAJOR * 1_000_000 + MINOR * 1_000 + PATCH) * 100 + (N for a release candidate, 99 for a release)
# so it grows with the version, a release candidate sorts below its release, and it stays below
# Android's limit of 2_100_000_000.
#
# Output is KEY=VALUE lines, ready to append to $GITHUB_OUTPUT. Exits non-zero for any other tag.
set -euo pipefail

tag="${1:?usage: version-from-tag.sh <tag>}"

pattern='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.([1-9][0-9]?))?$'
if [[ ! "$tag" =~ $pattern ]]; then
  echo "Tag '$tag' is not a release tag: use vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N (N = 1..98)." >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
candidate="${BASH_REMATCH[5]:-}"

if ((major > 20 || minor > 999 || patch > 999)); then
  echo "Tag '$tag' is out of range: MAJOR <= 20, MINOR and PATCH <= 999." >&2
  exit 1
fi
if [[ -n "$candidate" ]] && ((candidate > 98)); then
  echo "Tag '$tag' is out of range: the release candidate number must be 1..98." >&2
  exit 1
fi

# Force base 10: the numbers are decimal even if they start with 0 in some other notation.
base=$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))
suffix=${candidate:-99}
code=$((base * 100 + 10#$suffix))

echo "versionName=${tag#v}"
echo "versionCode=$code"
if [[ -n "$candidate" ]]; then echo "prerelease=true"; else echo "prerelease=false"; fi
