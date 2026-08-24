# 0005 — Zero-secret signing with a checked-in debug keystore

- **Status:** accepted
- **Date:** 2026-08-24

> Covers PLAN.md decision 6 and §8. The family model, adopted from
> Meltorama's ADR 0002 (itself from Kararead).

## Context

Release APKs must be signed. The family offers two proven models: a real
keystore held in four CI secrets (sibling Blipbird — store-capable,
fail-closed release gates) or a checked-in debug keystore signing both
build types (siblings Kararead and Meltorama — zero secrets, anyone can
build upgrade-compatible APKs). 帮你Draw is a hobby drawing app distributed
by sideload via GitHub Releases, not app stores; its CI and releases are
operated by agents that cannot mint or hold secrets; and its licensing
(ADR 0003) rules out a paid store listing anyway. The choice is
upgrade-compatibility-critical: Android refuses to upgrade an install
whose signature changes, and paintings live in the app's private
`filesDir`, so a forced uninstall/reinstall would destroy every user's
project folders (the gallery copies survive, the layered originals and
undo history do not). The model must therefore be picked before the first
release, not after.

## Decision

The checked-in `app/debug.keystore` (standard android/androiddebugkey
passwords, generated fresh for this repo — never copied from a sibling)
signs BOTH debug and release build types. No signing secrets exist
anywhere. `.gitignore` whitelists exactly this file; the debug variant uses
the `.debug` applicationIdSuffix so both builds coexist on a device.
`release.yml` runs `assembleRelease` with no secret inputs and publishes
`bangnidraw-vX.Y.Z.apk` plus a sha256 sidecar to a GitHub Release.

## Consequences

- CI publishes installable releases with zero configuration; every clone
  builds APKs that can upgrade an existing install.
- The signature proves nothing about origin (the key is public). Sideload
  trust rests on the GitHub Release provenance and the sha256 sidecar,
  stated in SECURITY.md.
- App-store distribution is off the table until a real key is introduced —
  and doing so breaks upgrades for every installed user
  (uninstall/reinstall, which for this app means losing project folders
  unless an export/import of projects ships first). That switch is a
  product decision requiring its own ADR, a project export path, and a
  major-version release.
- Debug and release installs are separate apps with separate `filesDir`,
  so a painting made in the `.debug` build is not visible in the release
  build; the gallery copies are, since MediaStore items are per package
  but publicly visible.
- Do not "rotate" the keystore, do not add signing secrets, do not
  "harden" CI by removing the file — AGENTS.md lists this under
  "don't fix these".
- Revisit if: store distribution is ever wanted (which also requires
  resolving ADR 0003), or Android policy changes around debug-signed
  installs.
