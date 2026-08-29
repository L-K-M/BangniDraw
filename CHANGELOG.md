# Changelog

## Unreleased

- Keep a second gallery copy that includes a visible tracing image, and
  remove it when the image is hidden or removed.
- Fix red and blue channels swapping in thumbnails and gallery exports on
  BGRA devices.
- Add a Settings option to pick one of eight theme colors: light Saffron,
  Coral, Violet, Teal, and Nineties, or dark Synthwave, Midnight, and Forest.
  The app no longer follows system dark mode; dark chrome is a chosen theme.

## 1.1.3 - 2026-08-28

- Show wet paper, retain heavier water longer, and make fresh Watercolor
  react to stored water.

## 1.1.1 - 2026-08-28

- Keep the canvas visible after fold, rotation, and aspect-ratio changes.
- Remember each paint-slot assignment across tools, paintings, and restarts.

## 1.1.0 - 2026-08-28

- Add Watercolor with wet mixing, spreading, granulation, and dark rims.
- Add a colourless Water tool that re-wets and carries existing paint.
- Add Spray can, Charcoal, Soft pastel, Technical pen, Chinese ink,
  Dry brush, Oil paint, and Pigment wash presets.
- Add private tracing references and composition guides.
- Keep the full tool rail usable on short screens.
- Preserve hue through greyscale edits and make the color panel smoother.
- Draw pressure curves and preserve typed custom canvas sizes.
- Name palettes, reject duplicate or reserved names, and cache thumbnails.
- Report Studio and share failures accurately.
- Preserve canvas placement through rotation, harden layer reordering, tint
  the hover ring, and clarify tool icons.

## 1.0.8 - 2026-08-27

- Remember the last custom canvas size.
- Let users choose which eraser the S Pen eraser end activates.
- Expose HSV controls to accessibility services.
- Wait for a live stroke to commit before leaving the canvas.
- Fix quarter-turn canvas presentation.
- Improve quick-palette focus and touch feedback.
- Hide unavailable layer-reorder commands.
- Enlarge and harden the eraser toggle.

## 1.0.7 - 2026-08-27

- Long-press the eraser slot to switch between the hard and soft erasers.
- Add a right-angle rotation snapping setting.
- Title the share chooser with the painting's name.
- Keep the fill progress card clear of the compact dock.
- Add move up/down/top/bottom commands to the layer menu.
- Keep hex and RGB field edits from resetting the cursor while typing.
- Center the eyedropper hover cursor on the sampled pixel.
- Inset the top strip's layer count badge off the icon.
- Reset the New Canvas orientation override with the preset.
- Complete accessibility semantics across the canvas chrome.

## 1.0.6 - 2026-08-27

- Neutralize canonical half-turn buffer transforms on both sides of the
  presentation handoff.
- Show the canvas boundary.
- Keep live stroke frames responsive.
- Improve Studio painting cards.
- Make layer reordering accessible.

## 1.0.5 - 2026-08-27

- Restore live stroke presentation after each canvas surface attaches.

## 1.0.4 - 2026-08-27

- Reissue the canvas rendering fixes with a new package version.

## 1.0.3 - 2026-08-27

- Restore the canvas after startup and surface recreation.
- Ignore eyedropper reads that finish after pen-up.
- Give every tool a distinct rail icon (the pencil and smudge, and the
  airbrush and blur, shared glyphs).
- Show a live zoom/angle readout while a pinch or pan gesture runs.
- Throttle eyedropper reads to one per frame (a drag stalled the GL pipeline
  once per input sample).
- Allocate nothing in the sandwich's per-frame viewport query.
- Add settings sheets for smudge, blur and eyedropper.
- Hide pigment controls when the pigment mixer is unavailable, and canvas
  actions that cannot run.
- Show the Studio empty state on every width; stack RGB fields under font
  scaling; use plural resources for counts.
- Verify release builds without Mixbox.

## 1.0.2 - 2026-08-27

- Remove moving live-stroke cutoffs and tile-edge seams.

## 1.0.1 - 2026-08-27

- Keep the complete stroke visible while drawing.
- Raise dark tool-rail contrast and separate its sliders.
- Show the source artwork on adaptive launcher surfaces.

## 1.0.0 - 2026-08-27

- Add the tiled GLES canvas, seven brush presets, smudge, blur, fill, layers,
  persistent undo, autosave, gallery mirroring, sharing, and adaptive UI.
- Add Settings, About/licenses, accessibility semantics, and Simplified
  Chinese.
