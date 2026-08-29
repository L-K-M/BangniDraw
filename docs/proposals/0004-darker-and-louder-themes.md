# 0004 — Darker and louder application themes

- **Status:** Accepted — roadmap #14
- **Date:** 2026-08-28

## Problem

Proposal 0003 fixed every palette to a light tone. Users drawing at night want
dark chrome, and the first set leans tasteful over fun. The tone was hardwired
in several places: system-bar appearance, the neutral canvas void, and the
error roles all assumed light surfaces.

## Decision

`AppTheme` gains a `tone` (`LIGHT`/`DARK`) and four values: `NINETIES`
(light, Memphis magenta/yellow/teal), `SYNTHWAVE` (dark, neon pink/cyan on
deep violet), `MIDNIGHT` (dark, quiet blue/sage), and `FOREST` (dark, moss
green/amber). Settings lists all eight in enum order; a future value still
needs a palette, a tone, a localized name, and contrast evidence.

The tone drives the two things that must flip with it:

- **System bars**: light themes keep dark icons; dark themes get light icons
  via `SystemBarStyle.dark`, re-applied once storage emits the theme
  (recreation seeds it from the retained ViewModel). The
  launch window stays the fixed light Saffron background — it still cannot
  read DataStore — so dark-theme users see it briefly at cold start, the same
  accepted tradeoff proposal 0003 made for non-default palettes.
- **Canvas void**: one neutral gray per tone (`#B8B2AA` light, `#171717`
  dark), never palette-tinted, so chrome changes cannot skew artwork
  perception. The transparent-paper checkerboard follows `surface`/
  `surfaceVariant` and adapts on its own.

Error roles also split by tone (Material's light/dark baselines) because the
light `#B3261E` fails 4.5:1 as text on dark surfaces. Android's dark mode
remains deliberately ignored: dark chrome is a chosen theme, not a system
reaction; `forceDarkAllowed` stays `false` and no night-qualified resources
exist.

## Consequences

Every contrast contract becomes tone-aware instead of light-only: bar-icon
contrast is checked against the tone's icon colour, and error text is checked
on the palette's surfaces. `ThemeTone` is part of the persisted choice's
meaning; like enum names, changing a theme's tone changes what returning users
see without a migration path.
