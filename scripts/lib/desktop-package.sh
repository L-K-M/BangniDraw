# Private helpers shared by the desktop installer and its JVM contract test.

DESKTOP_EGL_DYLIB="libEGL.dylib"
DESKTOP_GLES_DYLIB="libGLESv2.dylib"

desktop_display_name() {
  local strings_file="$1"

  sed -nE "s@.*<string[[:space:]]+name=['\"]app_name['\"][^>]*>([^<]+)</string>.*@\1@p" \
    "$strings_file" | head -n 1
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
