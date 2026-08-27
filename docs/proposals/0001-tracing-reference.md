# 0001 — Tracing reference image

- **Status:** proposed
- **Date:** 2026-08-27

## Problem

A painter should be able to place a photo beneath their paint and trace it
without importing the photo into the artwork. The post-v1 backlog reserves an
image-reference feature, but its current notes disagree: one puts an overlay
above every layer, while another describes a separate floating panel. Neither
is a tracing surface.

This is not animation onion skinning. Frames, playback, and animation export
remain out of scope.

## User behavior

- **Reference image** in the canvas overflow opens Android's Photo Picker.
  The app requests no permission and copies the chosen image into the project.
- The image starts fitted inside the canvas. While reference editing is active,
  two fingers move, scale, and rotate it independently of the canvas view.
- The reference is drawn above the paper and below every paint layer:

  ```text
  paint layers
  tracing reference   <- never exported
  paper
  canvas void
  ```

- Controls set opacity, hide/show, replace, reset the transform, or remove the
  image. Leaving reference editing restores normal canvas navigation.
- The image, transform, opacity, and visibility survive reopen. Thumbnails,
  gallery sync, sharing, and exported files omit it.
- Import failure leaves the current reference untouched and reports a localized
  error.

## Design

`Document` gains optional `TracingReference` metadata: an app-private asset
name, image dimensions, an affine canvas-space transform, opacity, and
visibility. `ProjectFile` persists it; `ProjectStore` owns atomic asset copy,
replacement, and cleanup. A picked URI is never retained as the source of
truth.

`CanvasViewModel` owns reference-edit mode and exposes domain actions to
`CanvasScreen`. UI code does not call the picker, store, or renderer directly.
`EngineSession` forwards accepted reference state to `CanvasRenderer`, which
uploads decoded tiles through a dedicated `ReferenceTextures` abstraction.
`CompositePass` draws those tiles after paper and before the first layer.

The decoder bounds the uploaded image by the canvas dimensions and the
transient allowance from `MemoryBudget`. A full-canvas reference costs at most
`width × height × 4` GPU bytes plus bounded decode scratch; the compressed
project asset is the persistent copy. If the transient allowance cannot hold a
useful decode, import is refused before replacing the current reference.

Reference edits do not enter the painting undo journal: they cannot affect
paint pixels or exports, and adding image payloads would consume the journal's
document-loss budget. Removal therefore requires confirmation. Every accepted
change checkpoints with the document metadata.

## Performance

The visible reference adds one clipped textured-tile pass beneath the layer
loop. Front-buffer damage uses the same reference tiles only inside its dirty
rectangle. Transform and opacity changes request a normal multi-buffer redraw;
decoding and project writes stay off the main and GL threads.

The device gate is a canvas-sized reference beneath eight painted layers at
120 Hz. It must stay within the existing composite budget in
`docs/plan/10-performance.md`; otherwise the implementation must cache the
paper/reference base before acceptance.

## Tests

- JVM: transform composition, reset, bounds, and opacity policy.
- JVM: project round-trip, atomic replacement, missing/corrupt asset recovery,
  and cleanup after removal.
- Contract: render order is paper → reference → layers; flatten/export paths
  cannot see `TracingReference`; the manifest remains permission-free.
- Device: pick, transform, hide, reopen, replace, and remove on phone/tablet;
  exported pixels match with the reference visible and hidden.
- Device: the performance gate above, including a live front-buffered stroke.

## Price

Size **M**. It depends on the document/persistence and layer compositor work
already shipped. Importing an image as a paint layer remains a separate
proposal because it changes pixels, history, and export.
