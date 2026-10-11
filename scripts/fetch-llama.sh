#!/usr/bin/env bash
# Fetches the pinned llama.cpp the native bridge builds against.
#
# The source is not committed: it is tens of megabytes across a thousand-odd files, and
# almost none of it is ever read by anyone reviewing this project. Pinning the tag and the
# checksum gives the same reproducibility at none of the cost.
#
# Pin rationale: b4600 predates PR #29818 (merged 2026-10-02), which added the
# `modern-bert` architecture and the typed-decision API Laya needs. v0.6.0 (2026-10-05) is
# the first release containing both.
set -euo pipefail

TAG="v0.6.0"
# sha256 of the GitHub source tarball for $TAG, pinned below.
SHA256="09b36dba235fcac180efff18514da7038e65d8e5b9e4aefa59238468abfdec12"

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

# GitHub tags unpack with the name as it appears in the archive, which differs between
# `bNNNN` and `vN.N.N` forms. Find it rather than assuming a prefix.
EXTRACTED="$(find "$TMP/out" -maxdepth 1 -mindepth 1 -type d | head -1)"
if [ -z "$EXTRACTED" ]; then
    echo "Extraction produced no directory — the tarball layout may have changed" >&2
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
mv "$EXTRACTED" "$DEST"

echo "llama.cpp $TAG installed at $DEST"