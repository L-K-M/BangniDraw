Placeholder for linux-x64's ANGLE shared libraries (libEGL.so.1 /
libGLESv2.so.2) — NOT committed.

Linux normally needs nothing here: Mesa and the vendor drivers ship
GLES natively, and the desktop context uses it directly. This folder
exists for a future ANGLE-on-Linux fallback.

Files in this directory are packaged verbatim into the app image —
remove this placeholder when the real libraries are staged.
Distributing binaries here requires recording their provenance in
AGENTS.md (the repo's third-party rule); none are checked in.
