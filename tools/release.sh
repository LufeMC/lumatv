#!/usr/bin/env bash
# Builds a release of LumaTV for the TVs' updater (Settings → About): the APK without the TMDB key or home location
# (./build.sh --release) and latest.json {versionCode, versionName, apk, sha256, notes}.
# By default it's a dry run: it builds and shows what it would publish. With --publish it creates the GitHub release
# v<versionName> on LufeMC/lumatv with both files (needs the gh CLI, signed in).
#
#   tools/release.sh                                  # dry run
#   tools/release.sh --notes "Subtitles stay in sync|Fewer stalls on Wi-Fi"
#   tools/release.sh --publish --notes "..."
#
# Notes are short lines separated by "|"; the TV shows up to five.
set -euo pipefail
cd "$(dirname "$0")/.."

PUBLISH=false
NOTES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) PUBLISH=true ;;
    --notes) NOTES="${2:-}"; shift ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
  shift
done

if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  echo "Warning: uncommitted changes. The version number counts commits, so commit first for a clean release."
fi

./build.sh --release

CONFIG=out/gen/io/freemytv/Config.java
CODE="$(sed -n 's/.*VERSION_CODE = \([0-9][0-9]*\);.*/\1/p' "$CONFIG")"
NAME="$(sed -n 's/.*VERSION_NAME = "\([^"]*\)";.*/\1/p' "$CONFIG")"
[ -n "$CODE" ] && [ -n "$NAME" ] || { echo "Couldn't read the version from $CONFIG."; exit 1; }
# A published APK must not carry anyone's key or address.
grep -q 'TMDB_KEY = "";' "$CONFIG" || { echo "This build has a TMDB key in it. Stopping."; exit 1; }
grep -q 'CITY = "";' "$CONFIG" || { echo "This build has a city in it. Stopping."; exit 1; }

SHA="$(shasum -a 256 FreeMyTV.apk | cut -d' ' -f1)"
python3 -c '
import json, sys
code, name, sha, notes = sys.argv[1:5]
lines = [n.strip() for n in notes.split("|") if n.strip()][:5]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": "FreeMyTV.apk", "sha256": sha, "notes": lines}, indent=2))
' "$CODE" "$NAME" "$SHA" "$NOTES" > latest.json

echo
echo "LumaTV $NAME (versionCode $CODE)"
echo "FreeMyTV.apk sha256 $SHA"
cat latest.json
echo

TAG="v$NAME"
if ! $PUBLISH; then
  echo "Dry run. To publish, run again with --publish. It would run:"
  echo "  gh release create $TAG FreeMyTV.apk latest.json --repo LufeMC/lumatv --title \"LumaTV $NAME\""
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "Install the GitHub CLI (gh) and sign in first."; exit 1; }
BODY="$(printf '%s\n' "$NOTES" | tr '|' '\n')"
gh release create "$TAG" FreeMyTV.apk latest.json --repo LufeMC/lumatv --title "LumaTV $NAME" --notes "${BODY:-LumaTV $NAME}"
echo "Published $TAG. TVs with automatic updates install it at night; others show it in Settings → About."
