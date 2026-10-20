#!/usr/bin/env bash
set -euo pipefail
python3 fetch-player.py
mkdir -p out/player/jars
for lib in .player-libs/*.jar; do cp "$lib" out/player/jars/; done
PLAYER_PACKAGES=""
PLAYER_RES=()
for lib in .player-libs/*.aar; do
  name="$(basename "$lib" .aar)"
  mkdir -p "out/player/$name"
  unzip -q -o "$lib" -d "out/player/$name"
  cp "out/player/$name/classes.jar" "out/player/jars/$name.jar"
  package="$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "out/player/$name/AndroidManifest.xml" | head -1)"
  [ -z "$package" ] || PLAYER_PACKAGES="${PLAYER_PACKAGES:+$PLAYER_PACKAGES:}$package"
  if [ -d "out/player/$name/res" ]; then
    "$BT/aapt2" compile --dir "out/player/$name/res" -o "out/player/$name-res.zip"
    PLAYER_RES+=("out/player/$name-res.zip")
  fi
done
PLAYER_CP="$(find out/player/jars -name '*.jar' | paste -sd: -)"
