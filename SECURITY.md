# Security

## Reporting

Please report vulnerabilities privately via GitHub's "Report a
vulnerability" (Security → Advisories) on this repository. No bounty
program; reports are read and acted on.

## Scope and posture

- The app is fully offline: no permissions at all, no INTERNET, no
  accounts, no telemetry. The attack surface is essentially malformed
  project files in the app's own private storage and the usual local-app
  concerns.
- Image encoding/decoding (gallery PNGs, thumbnails) uses the platform
  `Bitmap`/`BitmapFactory` — OS hardening applies; the app's own tile and
  journal formats are read defensively (bounds-checked headers, refuse
  rather than half-load).
- The checked-in `app/debug.keystore` is public **by design** (zero-secret
  reproducible builds, sideload-only distribution — docs/decisions/0005).
  APKs signed with it prove nothing about origin; installing them is a
  personal trust decision, same as any sideload. Do not report the public
  keystore as a leak; it is documented, deliberate, and rotation is a
  known-cost product decision.
- CI runs with least-privilege tokens; the one privileged workflow
  (`pull_request_target` review) is fork-guarded and pinned to an immutable
  action commit — see [CICD.md](CICD.md).
