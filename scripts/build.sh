#!/usr/bin/env bash
# Build the 帮你Draw APK from the command line and stage it into dist/.
#
# Release build by default; --debug builds the debug-variant APK instead.
# Both variants are signed with the checked-in debug keystore (deliberate,
# see docs/decisions/0005), so the staged APK is installable as-is. It is
# named after the committed versionName.
#
# The lkm-build engine (https://github.com/L-K-M/release-tool) has no Gradle
# kind, so this is a self-contained orchestrator in the family house style.
#
#   scripts/build.sh                  # incremental release build -> dist/
#   scripts/build.sh --debug          # debug build -> dist/
#   scripts/build.sh --clean          # wipe Gradle build output first
#   scripts/build.sh --check          # print resolved config; build nothing
#   scripts/build.sh --install        # also install onto the connected device
#
# Usage: scripts/build.sh [--debug] [--clean] [--check] [--install]
# Requirements: JDK 17+; the Android SDK (local.properties or ANDROID_HOME).
set -euo pipefail

# Absolute self-path first: usage() re-opens the script, which a relative $0
# would no longer find after the cd below.
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELF")/.."

usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

VARIANT="release"
CLEAN=0
CHECK=0
INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --debug) VARIANT="debug" ;;
    --clean) CLEAN=1 ;;
    --check) CHECK=1 ;;
    --install) INSTALL=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "!! unknown argument: $arg" >&2; usage >&2; exit 2 ;;
  esac
done

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*$/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$VERSION" ] || { echo "!! could not read versionName from app/build.gradle.kts" >&2; exit 1; }

if [ "$VARIANT" = "release" ]; then
  TASK="assembleRelease"
  APK="app/build/outputs/apk/release/app-release.apk"
  OUT="dist/bangnidraw-v${VERSION}-release.apk"
else
  TASK="assembleDebug"
  APK="app/build/outputs/apk/debug/app-debug.apk"
  OUT="dist/bangnidraw-v${VERSION}-debug.apk"
fi

if [ "$CHECK" -eq 1 ]; then
  echo "==> config"
  echo "-- variant:  $VARIANT"
  echo "-- version:  $VERSION"
  echo "-- task:     ./gradlew $TASK"
  echo "-- staged:   $OUT"
  echo "-- install:  $INSTALL"
  exit 0
fi

if [ "$CLEAN" -eq 1 ]; then
  echo "==> ./gradlew clean"
  ./gradlew clean
fi

echo "==> ./gradlew $TASK"
./gradlew "$TASK"

[ -f "$APK" ] || { echo "!! expected APK not found: $APK" >&2; exit 1; }

mkdir -p dist
cp "$APK" "$OUT"
echo "==> staged $OUT"

if [ "$INSTALL" -eq 1 ]; then
  # Same AGP install task scripts/install.sh uses; fails loudly with no device.
  echo "==> ./gradlew install${VARIANT^}  (installs to the connected device)"
  ./gradlew "install${VARIANT^}"
fi

# Reveal the staged APK in the desktop file browser, best-effort: select it
# in Finder on a Mac, open its directory elsewhere. Never fails the build.
if command -v open >/dev/null 2>&1; then
  open -R "$OUT" >/dev/null 2>&1 || true
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$(dirname "$OUT")" >/dev/null 2>&1 || true
fi
echo "==> Done."
