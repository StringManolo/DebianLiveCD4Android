#!/usr/bin/env bash
# Builds assets/rootfs/debian-arm64.tar.xz from the OFFICIAL Debian Docker image (arm64):
# pull the image, create (never run) a container from it and export its filesystem.
set -euo pipefail
SUITE="${DEBIAN_SUITE:-trixie}"
VARIANT="${DEBIAN_VARIANT:-slim}"      # "slim" or "" for the full image
IMAGE="debian:${SUITE}${VARIANT:+-$VARIANT}"
DEST="app/src/main/assets/rootfs"
FILE="$DEST/debian-arm64.tar.xz"
mkdir -p "$DEST" build-info

docker pull --platform linux/arm64 "$IMAGE"
digest="$(docker image inspect --format '{{join .RepoDigests ", "}}' "$IMAGE" || true)"
cid="$(docker create --platform linux/arm64 "$IMAGE" /bin/true)"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

# -6 keeps the xz dictionary at 8 MiB so decompression fits comfortably in the app's heap
docker export "$cid" | xz -T0 -6 > "$FILE"

tar -tJf "$FILE" | grep -qE '(^|/)usr/bin/env$' || { echo "rootfs looks wrong (no usr/bin/env)"; exit 1; }
{
  echo "image: $IMAGE (linux/arm64)"
  echo "digest: $digest"
  sha256sum "$FILE"
} > build-info/rootfs-provenance.txt
cat build-info/rootfs-provenance.txt
ls -lh "$FILE"
