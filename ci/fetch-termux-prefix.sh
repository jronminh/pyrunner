#!/usr/bin/env bash
# Reconstruct a minimal Termux aarch64 prefix (python + the shared libraries
# its stdlib and C extensions need) from the Termux apt repo, so pyrunner can
# be built on a host that isn't Termux. Result is usable as PREFIX=... for
# build.sh. Needs: curl, gunzip, dpkg-deb, python3.
set -euo pipefail

DEST="${1:?usage: fetch-termux-prefix.sh DEST}"
ARCH="${ARCH:-aarch64}"
REPO="https://packages.termux.dev/apt/termux-main"
PKGS="python libandroid-support libandroid-posix-semaphore openssl zlib libbz2
      liblzma libffi libsqlite libexpat ncurses readline gdbm zstd"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$DEST"

echo "Fetching package index..."
curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages.gz" -o "$WORK/Packages.gz"
gunzip -f "$WORK/Packages.gz"

echo "Resolving packages..."
python3 - "$WORK/Packages" $PKGS > "$WORK/filenames.txt" <<'PY'
import sys

index, wanted = sys.argv[1], sys.argv[2:]
stanzas = open(index, encoding="utf-8", errors="replace").read().split("\n\n")
by_name = {}
for stanza in stanzas:
    fields = {}
    for line in stanza.splitlines():
        if ": " in line:
            key, value = line.split(": ", 1)
            fields[key] = value.strip()
    name = fields.get("Package")
    if name and name not in by_name:
        by_name[name] = fields.get("Filename")

missing = [p for p in wanted if not by_name.get(p)]
if missing:
    sys.exit("not in index: " + ", ".join(missing))
for name in wanted:
    print(by_name[name])
PY

while read -r filename; do
    echo "  $filename"
    curl -fsSL "$REPO/$filename" -o "$WORK/pkg.deb"
    rm -rf "$WORK/x"
    dpkg-deb -x "$WORK/pkg.deb" "$WORK/x"
    cp -a "$WORK/x/data/data/com.termux/files/usr/." "$DEST/"
done < "$WORK/filenames.txt"

echo "Prefix ready: $DEST"
