# 0003 — Selectable application themes

- **Status:** Accepted — roadmap #13
- **Date:** 2026-08-28

## Problem

Following Android's dark-mode setting gives the app no character the user can
choose. The fixed saffron-on-neutral scheme is also too restrained for a
drawing app. The replacement must feel more expressive without tinting the
canvas or allowing combinations whose contrast has not been checked.

## Decision

`AppTheme` has four curated values: `SAFFRON`, `CORAL`, `VIOLET`, and
`TEAL`; Settings labels them Saffron, Coral, Violet, and Teal. `SAFFRON` is
the default for new installs and upgrades. Every choice uses a fixed light
tone, its own accent roles, and subtly tinted chrome surfaces. The canvas void
stays neutral so changing application chrome does not change how artwork is
perceived.

Settings exposes **Appearance → Theme color** as named radio choices with a
swatch and selection mark. The choice applies immediately across Studio,
Canvas, sheets, dialogs, and system bars. Names and radio state keep the
control meaningful without colour perception.

The app ignores system dark mode. There is no System choice, wallpaper-derived
dynamic colour, or arbitrary colour picker. Curated palettes keep every text,
icon, and control pair testable.

`Prefs` stores the enum name. A missing or unknown value resolves to Saffron;
renaming or removing a value therefore requires a preference migration rather
than an ordinary refactor. `ThemeColorPolicy` stays in `engine/core` so opaque
ARGB decisions remain JVM-testable and independent of DataStore and Compose;
the data layer persists only the selection. An activity-scoped
`AppThemeViewModel` observes that preference and wraps the whole navigation host
in `BangniTheme`; a screen-local ViewModel cannot own an application-wide
choice. The Compose adapter maps every Material colour role explicitly;
tertiary, fixed, inverse, and surface-container roles derive from the selected
palette, while shared error and scrim roles stay app-owned. The selection also
supplies light system-bar appearance. `LocalAppTheme` carries it, while the
renderer receives the shared neutral canvas-void colour through its existing
Compose-to-GL appearance boundary. `CanvasSurface` includes that appearance in
the session's initial configuration before GL can draw its bootstrap frame;
later theme or density changes update the live session. During a live stroke,
the renderer defers that mutation to its commit or cancel scene so front-frame
damage cannot mix old and new checkerboard cells.

Android resource themes cannot read DataStore before Compose starts. The
launch window therefore uses the fixed light Saffron background, with no
night-qualified override, instead of briefly following an unrelated system
mode. That window remains visible while Compose withholds navigation until the
asynchronous preference read emits. The first `IOException` is logged; before
any value it also emits Saffron once. I/O failures retry with capped
exponential backoff — five attempts, then the flow ends on its last value —
and never replace an already-visible selection. A corrupt preference file
resets to defaults through the DataStore corruption handler instead of
retrying forever. Cancellation and non-I/O failures propagate.

## Tests

- JVM: every enum name round-trips; missing and unknown values select Saffron.
- JVM: `PreferenceFlowRecoveryTest` pins I/O-only retry, the single pre-load
  fallback, post-load stability, and exception propagation.
- JVM: required text and non-text contrast holds for every palette, including
  the primary active-tool ring against its container.
- JVM: `BangniColorSchemeTest` proves every Material role is mapped explicitly.
- Contract: Settings exposes one labelled radio group; the selected palette
  reaches `BangniTheme`, light system bars, `android:forceDarkAllowed = false`,
  and the GL canvas appearance before bootstrap and across a live-stroke
  boundary; the root keeps navigation behind the fixed launch window until a
  theme arrives.
- Device: each choice applies immediately, survives restart, and remains
  unchanged when Android dark mode is toggled.

## Price

Size **S**. No document format, painting pixels, dependency, permission, or
third-party asset changes.
