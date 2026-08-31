Placeholder for linux-x64's ANGLE dylibs (libEGL / libGLESv2) — NOT committed.

macOS has no native GLES; the desktop context needs ANGLE's Metal
backend (see the README's desktop section). Place the dylibs for this
target here before packaging; jpackage picks them up via
appResourcesRootDir. linux-* folders exist for a future ANGLE-on-Linux
fallback — the native Mesa/NVIDIA GLES path needs nothing.

Distributing binaries here requires recording their provenance in
AGENTS.md (the repo's third-party rule); none are checked in.
