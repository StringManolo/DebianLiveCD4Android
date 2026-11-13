#!/usr/bin/env bash
# Downloads the official Debian rootfs (debuerreotype, same tarball as the Docker image) for arm64.
set -euo pipefail
SUITE="${DEBIAN_SUITE:-trixie}"
VARIANT="${DEBIAN_VARIANT:-slim}"      # "slim" or "" for the full one
BRANCH="dist-arm64v8"
BASE="https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/${BRANCH}/${SUITE}${VARIANT:+/$VARIANT}"
DEST="app/src/main/assets/rootfs"
mkdir -p "$DEST" build-info
FILE="$DEST/debian-arm64.tar.xz"

curl -fsSL --retry 3 -o "$FILE" "$BASE/rootfs.tar.xz"

if curl -fsSL --retry 3 -o /tmp/rootfs.sha256 "$BASE/rootfs.tar.xz.sha256"; then
  expected="$(cut -d' ' -f1 /tmp/rootfs.sha256)"
  actual="$(sha256sum "$FILE" | cut -d' ' -f1)"
  [ "$expected" = "$actual" ] || { echo "rootfs sha256 mismatch"; exit 1; }
else
  echo "::warning::upstream sha256 not found, recording local hash only"
fi

tar -tJf "$FILE" | grep -qE '(^|/)usr/bin/env$' || { echo "rootfs looks wrong (no usr/bin/env)"; exit 1; }
{ echo "source: $BASE/rootfs.tar.xz"; sha256sum "$FILE"; } > build-info/rootfs-provenance.txt
curl -fsSL -o build-info/rootfs.manifest "$BASE/rootfs.manifest" || true
ls -lh "$FILE"
