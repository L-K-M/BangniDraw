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

`Prefs` stores the enum name. A missing or unknown value resolves to Saffron.
An application-root `AppThemeViewModel` observes that preference and wraps the
whole navigation host in `BangniTheme`; a screen-local ViewModel cannot own an
application-wide choice. The selected theme supplies the Compose colour scheme
and light system-bar appearance. `LocalAppTheme` carries the selection, while
the renderer receives the shared neutral canvas-void colour through its existing
Compose-to-GL appearance boundary. `CanvasSurface` includes that appearance in
the session's initial configuration before GL can draw its bootstrap frame;
later theme or density changes update the live session.

Android resource themes cannot read DataStore before Compose starts. The
launch window therefore uses the fixed light Saffron background, with no
night-qualified override, instead of briefly following an unrelated system
mode. Compose withholds navigation until the asynchronous preference read
emits; a non-cancellation read failure emits Saffron and retries observation so
the launch gate cannot remain blank and later selections still apply.

## Tests

- JVM: every enum name round-trips; missing and unknown values select Saffron.
- JVM: required text and non-text contrast holds for every palette.
- Contract: Settings exposes one labelled radio group; the selected palette
  reaches `BangniTheme`, light system bars, `android:forceDarkAllowed = false`,
  and the GL canvas appearance before its bootstrap frame; the loading gate has
  a cancellation-safe, retrying fallback.
- Device: each choice applies immediately, survives restart, and remains
  unchanged when Android dark mode is toggled.

## Price

Size **S**. No document format, painting pixels, dependency, permission, or
third-party asset changes.
