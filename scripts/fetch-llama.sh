#!/usr/bin/env bash
# Fetches the pinned llama.cpp the native bridge builds against.
#
# The source is not committed: it is ~68 MB across 1135 files, and almost none of it is
# ever read by anyone reviewing this project. Pinning the tag and the checksum gives the same
# reproducibility at none of the cost.
set -euo pipefail

TAG="b4600"
# sha256 of the GitHub source tarball for $TAG.
SHA256="317efa87cfd06fcf016eeb9b366a0e8dfda36d42449710dd5e3bde2d3d3ee996"

DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/cpp/third_party/llama.cpp"
URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${TAG}.tar.gz"

if [ -d "$DEST" ]; then
    echo "llama.cpp $TAG already present at $DEST"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading llama.cpp $TAG…"
curl -sSL -o "$TMP/llama.tar.gz" "$URL"

echo "Verifying checksum…"
echo "${SHA256}  ${TMP}/llama.tar.gz" | sha256sum --check --status || {
    echo "Checksum mismatch for ${URL}" >&2
    echo "Refusing to install. Refusing to guess why." >&2
    exit 1
}

echo "Extracting…"
mkdir -p "$TMP/out"
tar xzf "$TMP/llama.tar.gz" -C "$TMP/out"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/out/llama.cpp-${TAG}" "$DEST"

echo "llama.cpp $TAG installed at $DEST"