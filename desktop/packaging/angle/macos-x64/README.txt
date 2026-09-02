Placeholder for macos-x64's ANGLE dylibs (libEGL / libGLESv2) — NOT committed.

macOS has no native GLES; the desktop context needs ANGLE's Metal
backend (see the README's desktop section). Place the dylibs for this
target here before packaging; jpackage picks them up via
appResourcesRootDir. Linux uses the system GLES natively (Mesa/NVIDIA);
its fallback folders are recreated only if an ANGLE-on-Linux path ever lands.

CI's `macos-latest` label currently resolves to arm64 runners; x64
packaging is therefore manual and untested by the pipeline unless the
workflow pins an Intel runner label.

Distributing binaries here requires recording their provenance in
AGENTS.md (the repo's third-party rule); none are checked in.

Files in this directory are packaged verbatim into the app image —
remove this placeholder when the real dylibs are staged.
