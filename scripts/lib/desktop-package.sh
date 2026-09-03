# Private helpers shared by the desktop installer and its JVM contract test.

DESKTOP_EGL_DYLIB="libEGL.dylib"
DESKTOP_GLES_DYLIB="libGLESv2.dylib"

desktop_display_name() {
  local strings_file="$1"
  local encoded

  [ -f "$strings_file" ] || return 1
  encoded="$(
    tr '\n' ' ' < "$strings_file" |
      sed -nE "s@.*<string[^>]*[[:space:]]name[[:space:]]*=[[:space:]]*['\"]app_name['\"][^>]*>([^<]*)</string>.*@\1@p"
  )"
  [ -n "$encoded" ] || return 1

  printf '%s\n' "$encoded" | sed -E \
    -e 's/^[[:space:]]+//' \
    -e 's/[[:space:]]+$//' \
    -e 's/&quot;/"/g' \
    -e "s/&apos;/'/g" \
    -e "s/&#39;/'/g" \
    -e 's/&lt;/</g' \
    -e 's/&gt;/>/g' \
    -e 's/&amp;/\&/g'
}

desktop_find_app() {
  local root="$1"
  local display_name="$2"

  [ -d "$root" ] || return 1
  find "$root" -type d -name "${display_name}.app" -print -quit
}

desktop_app_has_angle() {
  # The exact directory the app reads at runtime (jpackage resolves
  # compose.application.resources.dir to $APPDIR/resources). Accepting the
  # dylibs anywhere under Contents would pass a bundle whose runtime cannot
  # find them.
  local resources="$1/Contents/app/resources"

  [ -f "$resources/$DESKTOP_EGL_DYLIB" ] && [ -f "$resources/$DESKTOP_GLES_DYLIB" ]
}
