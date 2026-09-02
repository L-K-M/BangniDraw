# ANGLE

macOS packages bundle ANGLE from
[Electron v41.10.3](https://github.com/electron/electron/releases/tag/v41.10.3).
The build downloads the official architecture-specific Electron archive,
checks pinned SHA-256 hashes, and extracts only `libEGL.dylib`,
`libGLESv2.dylib`, `LICENSE`, and `LICENSES.chromium.html`.

ANGLE and Chromium retain their upstream licenses. The packaged `LICENSE`
and `LICENSES.chromium.html` files provide the applicable notices.

Electron 41 is an EOL binary source. It unblocks the unsigned desktop preview;
a production release should replace it with a reproducible build from a pinned,
current ANGLE revision.
