This placeholder records Compose's macos-x64 resource-directory name.

CI's `macos-latest` label currently resolves to arm64 runners; x64
packaging remains manual unless the workflow pins an Intel runner.

Do not put binaries here. `stageMacAngle` fetches and verifies the pinned
runtime, then writes generated resources under `desktop/build/`. See
`third-party/angle/README.md` for provenance.
