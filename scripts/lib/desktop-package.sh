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
  local contents="$1/Contents"
  local egl
  local gles

  [ -d "$contents" ] || return 1
  egl="$(find "$contents" -type f -name "$DESKTOP_EGL_DYLIB" -print -quit)"
  gles="$(find "$contents" -type f -name "$DESKTOP_GLES_DYLIB" -print -quit)"

  [ -n "$egl" ] && [ -n "$gles" ]
}
