# Mixbox — third-party notice

帮你Draw's natural color mixing ("blue + yellow = green") is
[Mixbox 2.0](https://github.com/scrtwpns/mixbox) by Secret Weapons
(Šárka Sochorová and Ondřej Jamriška):

> MIXBOX 2.0 (c) 2022 Secret Weapons. All rights reserved.
> License: Creative Commons Attribution-NonCommercial 4.0

The full license text is in [LICENSE](LICENSE) (copied verbatim from
upstream). **Non-commercial use only**; a commercial license is available
from mixbox@scrtwpns.com.

## What we use

- CPU: the Maven artifact `com.scrtwpns:mixbox` (pinned in
  `gradle/libs.versions.toml`) — palette mixing dish, swatch math, unit
  tests.
- GPU: `mixbox.glsl` and `mixbox_lut.png` from upstream's `shaders/`
  directory at commit `a1bdb75a668f638ba066aa74bfd32809ed7fef45`.
  They are unmodified. SHA-256:
  - `mixbox.glsl`: `1ca60762c730405f8df18ef08ea0501d43606a67a6d309a610a077c8781cfce4`
  - `mixbox_lut.png`: `b13d7532033d96d963c7e3a854ba2b4e98b8a44d324456386e9b34e0615552be`

## Why this matters for the whole app

Everything 帮你Draw itself writes is public domain (Unlicense). Mixbox is
the one component that is not, and its non-commercial term applies to the
app as distributed. The decision and its consequences are recorded in
`docs/decisions/0003-mixbox-non-commercial.md`; the attribution appears in
the app's About screen and in the README. Stripping Mixbox is a one-line
property (`bangnidraw.mixbox=false`, see `docs/plan/09-color-and-mixing.md`).
