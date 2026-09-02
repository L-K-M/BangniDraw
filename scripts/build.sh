#!/usr/bin/env bash
# Build the 帮你Draw APK from the command line and stage it into dist/.
#
# Release build by default; --debug builds the debug-variant APK instead.
# Both variants are signed with the checked-in debug keystore (deliberate,
# see docs/decisions/0005), so the staged APK is installable as-is. It is
# named after the committed versionName.
#
# --install switches products: on macOS it builds the desktop .app
# (DESKTOP.md) and moves it into /Applications, then reveals it in the
# Finder. The Android device install keeps its dedicated scripts/install.sh;
# jpackage does not cross-compile, so --install exists only on macOS.
#
# The lkm-build engine (https://github.com/L-K-M/release-tool) has no Gradle
# kind, so this is a self-contained orchestrator in the family house style.
#
#   scripts/build.sh                  # incremental release build -> dist/
#   scripts/build.sh --debug          # debug build -> dist/
#   scripts/build.sh --clean          # wipe Gradle build output first
#   scripts/build.sh --check          # print resolved config; build nothing
#   scripts/build.sh --install        # desktop .app -> /Applications (macOS)
#
# Usage: scripts/build.sh [--debug] [--clean] [--check] [--install]
# Requirements: JDK 17+; the Android SDK (local.properties or ANDROID_HOME);
#   --install additionally needs macOS, and a working GL context needs
#   ANGLE's dylibs staged under desktop/packaging/angle/macos-<arch>/
#   (see that folder's README.txt and the README's desktop section).
set -euo pipefail

# Absolute self-path first: usage() re-opens the script, which a relative $0
# would no longer find after the cd below.
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELF")/.."
source scripts/lib/desktop-package.sh

usage() { awk 'NR==1 && /^#!/ {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$SELF"; }

# Open a file or folder in the desktop file browser, best-effort: select it
# in Finder on a Mac, open it elsewhere. Never fails the build.
reveal() {
  if command -v open >/dev/null 2>&1; then
    open -R "$1" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$1" >/dev/null 2>&1 || true
  fi
}

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

DESKTOP_TASK=":desktop:createDistributable"
DESKTOP_NAME="$(desktop_display_name app/src/main/res/values/strings.xml || true)"
[ -n "$DESKTOP_NAME" ] || { echo "!! could not read app_name from strings.xml" >&2; exit 1; }
DESKTOP_APP_ROOT="desktop/build/compose/binaries/main/app"
INSTALL_PATH="/Applications/${DESKTOP_NAME}.app"

if [ "$INSTALL" -eq 1 ]; then
  if [ "$(uname -s)" != "Darwin" ]; then
    echo "!! --install requires macOS: jpackage builds the .app only on its own OS" >&2
    echo "   (Linux installs via the Deb/Rpm targets; the Android device flow is" >&2
    echo "   scripts/install.sh.)" >&2
    exit 1
  fi
  if [ "$VARIANT" = "debug" ]; then
    echo "!! --debug selects an Android APK variant and does not apply to --install" >&2
    exit 2
  fi
fi

if [ "$INSTALL" -eq 1 ]; then
  if [ "$CHECK" -eq 1 ]; then
    echo "==> config"
    echo "-- product:  desktop .app (macOS)"
    echo "-- task:     ./gradlew $DESKTOP_TASK"
    echo "-- app root: $DESKTOP_APP_ROOT"
    echo "-- installs: $INSTALL_PATH"
    exit 0
  fi
else
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
    exit 0
  fi
fi

if [ "$CLEAN" -eq 1 ]; then
  echo "==> ./gradlew clean"
  ./gradlew clean
fi

if [ "$INSTALL" -eq 1 ]; then
  echo "==> ./gradlew $DESKTOP_TASK"
  ./gradlew "$DESKTOP_TASK"

  DESKTOP_APP="$(desktop_find_app "$DESKTOP_APP_ROOT" "$DESKTOP_NAME" || true)"
  [ -n "$DESKTOP_APP" ] || {
    echo "!! expected ${DESKTOP_NAME}.app under $DESKTOP_APP_ROOT" >&2
    exit 1
  }

  # Check what jpackage bundled; its JDK architecture may differ from the host.
  if ! desktop_app_has_angle "$DESKTOP_APP"; then
    echo "!! warning: ANGLE dylibs are not packaged in ${DESKTOP_NAME}.app" >&2
    echo "   Stage libEGL.dylib/libGLESv2.dylib in the matching macos-* folder" >&2
    echo "   under desktop/packaging/angle, then rebuild." >&2
  fi

  echo "==> installing $INSTALL_PATH"
  rm -rf "$INSTALL_PATH"
  ditto "$DESKTOP_APP" "$INSTALL_PATH"
  reveal "$INSTALL_PATH"
  echo "==> Done."
  exit 0
fi

echo "==> ./gradlew $TASK"
./gradlew "$TASK"

[ -f "$APK" ] || { echo "!! expected APK not found: $APK" >&2; exit 1; }

mkdir -p dist
cp "$APK" "$OUT"
echo "==> staged $OUT"
reveal "$OUT"
echo "==> Done."
