#!/usr/bin/env bash
set -euo pipefail
# Build dist/bangnidraw-linux-amd64.flatpak by repacking the release .deb
# under flatpak's /app prefix — the bundle ships byte-for-byte what the .deb
# installs. Usage:
#   scripts/build-flatpak.sh [--install]     # build the .deb first, then repack
#   scripts/build-flatpak.sh path/to.deb     # repack an existing .deb (CI)
#
# The bundle names Flathub as its runtime's source, so installing it fetches
# the GNOME runtime from there. Needs flatpak and flatpak-builder.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
readonly APP_ID=ch.lkmc.bangnidraw
readonly COMMAND=bangnidraw
readonly MANIFEST="$ROOT/flatpak/$APP_ID.yml"
readonly FLATHUB_REPO=https://dl.flathub.org/repo/flathub.flatpakrepo
readonly WORK="$ROOT/dist/flatpak"

die() { echo "build-flatpak.sh: $*" >&2; exit 1; }

INSTALL=0
DEB=""
for argument in "$@"; do
  case "$argument" in
    --install) INSTALL=1 ;;
    -h|--help) awk 'NR==1&&/^#!/{next} /^set -euo pipefail/{next} /^#/{sub(/^# ?/,"");print;next} {exit}' "$0"; exit 0 ;;
    *.deb) [ -z "$DEB" ] || die "pass at most one .deb"; DEB="$argument" ;;
    *) echo "Unknown argument: $argument" >&2; exit 2 ;;
  esac
done

# A relative .deb argument names the caller's directory, not the
# repository root we cd'd into above.
if [[ -n "$DEB" && "$DEB" != /* ]]; then DEB="$OLDPWD/$DEB"; fi

command -v flatpak-builder >/dev/null 2>&1 ||
  die "flatpak-builder not found: install flatpak and flatpak-builder"
command -v dpkg-deb >/dev/null 2>&1 ||
  die "dpkg-deb not found: install dpkg"

if [[ -z "$DEB" ]]; then
  ./gradlew :desktop:packageDeb
  DEB="$(find desktop/build/compose/binaries/main/deb -maxdepth 1 -name '*.deb' -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -n1 | cut -f2- || true)"
fi
[ -f "$DEB" ] || die ".deb not found: $DEB"

# Debian /usr -> flatpak /app.
rm -rf "$WORK/debroot" "$WORK/stage" "$WORK/build" "$WORK/repo"
mkdir -p "$WORK"   # dpkg-deb creates the target dir but not its parents
dpkg-deb -x "$DEB" "$WORK/debroot"
mkdir -p "$WORK/stage"
[ -d "$WORK/debroot/usr" ] && cp -a "$WORK/debroot/usr/." "$WORK/stage/"

# jpackage debs install the app under /opt/<pkg>/ (bin/, lib/, bundled JRE) —
# promote that payload to the /app root so launchers land on PATH.
OPT="$(ls -d "$WORK"/debroot/opt/*/ 2>/dev/null | head -1 || true)"
if [[ -n "$OPT" ]]; then
  cp -a "$OPT." "$WORK/stage/"
  rm -rf "$WORK/stage/opt"
fi

# The launcher may carry the display name instead of the package name —
# normalize it to the manifest's command. (jpackage launchers resolve lib/
# relative to their own path, so this must be a link inside bin/, not a copy.)
if [[ ! -x "$WORK/stage/bin/$COMMAND" ]]; then
  LAUNCHER="$(find "$WORK/stage/bin" -maxdepth 1 -type f -executable ! -name "$COMMAND" | head -1)"
  [ -n "$LAUNCHER" ] || die "no launcher binary in $DEB's bin/ (looked for $COMMAND)"
  ln -sf "$(basename "$LAUNCHER")" "$WORK/stage/bin/$COMMAND"
fi

# Wrapper scripts hardcode /usr or /opt; inside flatpak the prefix is /app.
while IFS= read -r f; do
  sed -i '1!s|/usr/|/app/|g; 1!s|/opt/[^/]*/|/app/|g' "$f"
done < <(grep -rl '/usr/\|/opt/' "$WORK/stage/bin/" 2>/dev/null || true)

# The desktop file may live in /usr/share/applications or inside the
# jpackage lib/ tree — flatpak exports it only from share/applications,
# named after the app id.
mkdir -p "$WORK/stage/share/applications"
DESKTOP="$(find "$WORK/stage/share/applications" -type f -name '*.desktop' -print -quit 2>/dev/null || true)"
[ -n "$DESKTOP" ] || DESKTOP="$(find "$WORK/stage" -type f -name '*.desktop' | head -1)"
[ -n "$DESKTOP" ] || die "no .desktop file inside $DEB"
[ "$(dirname "$DESKTOP")" = "$WORK/stage/share/applications" ] ||
  mv "$DESKTOP" "$WORK/stage/share/applications/"
DESKTOP="$WORK/stage/share/applications/$(basename "$DESKTOP")"
sed -i 's|Exec=/usr/bin/|Exec=|; s|Exec=/opt/[^/]*/bin/|Exec=|' "$DESKTOP"
[ "$(basename "$DESKTOP")" = "$APP_ID.desktop" ] ||
  mv "$DESKTOP" "$WORK/stage/share/applications/$APP_ID.desktop"
DESKTOP="$WORK/stage/share/applications/$APP_ID.desktop"
# The launcher resolves Icon= through flatpak's exported name.
sed -i "s|^Icon=.*|Icon=$APP_ID|" "$DESKTOP"

# Icons likewise export only when named after the app id.
if ! find "$WORK/stage/share/icons" "$WORK/stage/share/pixmaps" -name "$APP_ID.*" 2>/dev/null | grep -q .; then
  ICON="$(find "$WORK/stage" -name '*.png' ! -path '*/runtime/*' -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -n1 | cut -f2- || true)"
  [ -n "$ICON" ] || die "no icon inside $DEB"
  mkdir -p "$WORK/stage/share/icons/hicolor/256x256/apps"
  cp "$ICON" "$WORK/stage/share/icons/hicolor/256x256/apps/$APP_ID.png"
fi

flatpak remote-add --user --if-not-exists flathub "$FLATHUB_REPO"
# --disable-rofiles-fuse: containers (CI) have no FUSE, and a copy-only build
# gains nothing from it.
flatpak-builder --user --install-deps-from=flathub --force-clean \
  --disable-rofiles-fuse \
  --state-dir="$WORK/state" --repo="$WORK/repo" \
  "$WORK/build" "$MANIFEST"

# Smoke check: the staged tree must leave an executable under
# /app/bin — catches a failed /usr->/app remap before the bundle
# ships.
COMMAND_NAME="$(sed -n 's/^command:[[:space:]]*//p' "$MANIFEST" | head -1)"
flatpak-builder --user --state-dir="$WORK/state" \
  --run "$WORK/build" "$MANIFEST" \
  sh -c "test -x '/app/bin/$COMMAND_NAME' || { echo "no executable /app/bin/$COMMAND_NAME" >&2; ls -l /app/bin >&2; exit 1; }"

BUNDLE="$ROOT/dist/bangnidraw-linux-amd64.flatpak"
flatpak build-bundle --runtime-repo="$FLATHUB_REPO" \
  "$WORK/repo" "$BUNDLE" "$APP_ID"
if ((INSTALL)); then
  flatpak install --user -y --noninteractive "$BUNDLE"
fi
echo "Built ${BUNDLE#"$ROOT"/}"
