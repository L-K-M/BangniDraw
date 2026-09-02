#!/usr/bin/env bash
# Fetch and stage the pinned Electron ANGLE runtime for macOS packages.
#
# Usage: scripts/fetch-angle-macos.sh <version> <macos-arm64|macos-x64> <output-root>
set -euo pipefail

usage() {
  sed -n 's/^# Usage: //p' "$0" >&2
  exit 2
}

[ "$#" -eq 3 ] || usage

VERSION="$1"
TARGET="$2"
OUTPUT_ROOT="$3"

case "$TARGET" in
  macos-arm64)
    ARCHIVE_ARCH="darwin-arm64"
    ARCHIVE_SHA256="8961cdb57c95c073ff4770bc9309953832f447575f1a91127010f7b4870884b3"
    EGL_SHA256="3b35f5ef9c8023508d0942748cfd77550de0987724a492165360b7013d8866fd"
    GLES_SHA256="1191c94e59446e17c7f0442a9a9c342ce0bd6312954136c6d87037d6c6060491"
    ;;
  macos-x64)
    ARCHIVE_ARCH="darwin-x64"
    ARCHIVE_SHA256="7218af14b48457ed128f33392bf0497725300db97d474b6cca7237f0c44d847d"
    EGL_SHA256="f0ab5a4be5f17ee5bfa54f7ff387c348a7d4f1e400eea3ac1b7148039d04f9fb"
    GLES_SHA256="24b43874538969a52dde35b62af9c717ba5d324e764e91d7087913a3c3174806"
    ;;
  *) usage ;;
esac

LICENSE_SHA256="5154e165bd6c2cc0cfbcd8916498c7abab0497923bafcd5cb07673fe8480087d"
NOTICES_SHA256="7f7b7dbc738503773824915bc87f754fd9f4c5f8f1d9ac22ed971f55f36e8f29"

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"
CACHE_DIR="$REPO_ROOT/desktop/build/angle-cache/$VERSION"
ARCHIVE_NAME="electron-v${VERSION}-${ARCHIVE_ARCH}.zip"
ARCHIVE="$CACHE_DIR/$ARCHIVE_NAME"
ARCHIVE_URL="https://github.com/electron/electron/releases/download/v${VERSION}/${ARCHIVE_NAME}"

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual

  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  [ "$actual" = "$expected" ] || {
    echo "!! SHA-256 mismatch: $file" >&2
    return 1
  }
}

mkdir -p "$CACHE_DIR"
if ! [ -f "$ARCHIVE" ] || ! verify_sha256 "$ARCHIVE" "$ARCHIVE_SHA256"; then
  PARTIAL_ARCHIVE="${ARCHIVE}.part"
  rm -f "$PARTIAL_ARCHIVE"
  curl --proto '=https' --tlsv1.2 --fail --location --retry 3 --retry-all-errors \
    --output "$PARTIAL_ARCHIVE" "$ARCHIVE_URL"
  verify_sha256 "$PARTIAL_ARCHIVE" "$ARCHIVE_SHA256"
  mv "$PARTIAL_ARCHIVE" "$ARCHIVE"
fi

TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/bangnidraw-angle.XXXXXX")"
cleanup() {
  rm -rf "$TEMP_ROOT"
  if [ -n "${STAGE_DIR:-}" ]; then
    rm -rf "$STAGE_DIR"
  fi
}
trap cleanup EXIT

FRAMEWORK_ROOT="Electron.app/Contents/Frameworks/Electron Framework.framework/Versions/A"
EGL_ENTRY="$FRAMEWORK_ROOT/Libraries/libEGL.dylib"
GLES_ENTRY="$FRAMEWORK_ROOT/Libraries/libGLESv2.dylib"

# Extract only the runtime libraries and their required notices.
JAR_TOOL="${JAVA_HOME:+$JAVA_HOME/bin/jar}"
if ! [ -x "$JAR_TOOL" ]; then
  JAR_TOOL="$(command -v jar || true)"
fi
[ -n "$JAR_TOOL" ] || { echo "!! JDK jar tool not found" >&2; exit 1; }
(
  cd "$TEMP_ROOT"
  "$JAR_TOOL" xf "$ARCHIVE" "$EGL_ENTRY" "$GLES_ENTRY" \
    LICENSE LICENSES.chromium.html
)

EGL_SOURCE="$TEMP_ROOT/$EGL_ENTRY"
GLES_SOURCE="$TEMP_ROOT/$GLES_ENTRY"
LICENSE_SOURCE="$TEMP_ROOT/LICENSE"
NOTICES_SOURCE="$TEMP_ROOT/LICENSES.chromium.html"

verify_sha256 "$EGL_SOURCE" "$EGL_SHA256"
verify_sha256 "$GLES_SOURCE" "$GLES_SHA256"
verify_sha256 "$LICENSE_SOURCE" "$LICENSE_SHA256"
verify_sha256 "$NOTICES_SOURCE" "$NOTICES_SHA256"

mkdir -p "$OUTPUT_ROOT"
STAGE_DIR="$(mktemp -d "$OUTPUT_ROOT/.${TARGET}.XXXXXX")"
cp "$EGL_SOURCE" "$STAGE_DIR/libEGL.dylib"
cp "$GLES_SOURCE" "$STAGE_DIR/libGLESv2.dylib"
cp "$LICENSE_SOURCE" "$STAGE_DIR/LICENSE"
cp "$NOTICES_SOURCE" "$STAGE_DIR/LICENSES.chromium.html"

TARGET_DIR="$OUTPUT_ROOT/$TARGET"
rm -rf "$TARGET_DIR"
mv "$STAGE_DIR" "$TARGET_DIR"

echo "==> staged Electron ANGLE $VERSION for $TARGET"
