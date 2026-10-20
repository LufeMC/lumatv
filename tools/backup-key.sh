#!/usr/bin/env bash
# Makes an encrypted copy of the signing key to keep somewhere other than this Mac (cloud drive, USB stick).
# TVs only accept updates signed with this key, so losing it means reinstalling LumaTV on every TV.
# Restore with: openssl enc -d -aes-256-cbc -pbkdf2 -in lumatv-key-backup.enc | tar -x
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f freemytv.keystore ] || { echo "No freemytv.keystore here."; exit 1; }
out="${1:-lumatv-key-backup.enc}"
echo "Choose a password for the backup (you'll need it to restore):"
tar -c freemytv.keystore signing.sha256 | openssl enc -aes-256-cbc -pbkdf2 -salt -out "$out"
echo "Saved $out. Copy it off this Mac, then delete the local copy."
