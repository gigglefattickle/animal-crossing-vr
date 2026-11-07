# Quest 3 application architecture

This document defines the boundaries for the standalone Quest rewrite. The game library remains the Animal Crossing native port; Android and OpenXR concerns must not leak into gameplay code.

## Runtime flow
```text
Android Activity
  ├─ first-run / resume state machine
  ├─ Storage Access Framework document picker
  └─ AppDataService
       ├─ atomic import + format validation
       ├─ content metadata
       └─ saves / settings
              |
              v
       Native Game Host
          ├─ DiscReader C ABI
          ├─ Animal Crossing game + GX compatibility
          └─ VR presentation hooks
                 |
                 v
             OpenXR
          ├─ session + swapchains
          ├─ predicted frame timing
          ├─ head + controller actions
          └─ pause / focus / tracking recovery
```

## Module contracts
### Android host
Owns Android Activity lifecycle, SAF result handling, app permissions, and user-facing status. It must not implement disc parsing, rendering, or gameplay.

### App data service
Imports into a temporary file in app-private storage, verifies the selected format and expected disc identity, flushes data, then atomically promotes the completed import. Cancellation or failure must leave the previous valid game and saves untouched.

### DiscReader
A C-compatible API exposes open, close, size, and bounded read operations. Reads must support unaligned offsets and must not expose Java objects to native game code. Format-specific parsers remain separate implementation units.

### Game host
Starts only after a validated game is available. Existing game logic and save semantics remain authoritative. The host receives a disc-reader handle and app-owned save/settings paths through a narrow startup structure.

### OpenXR platform
Owns instance, system, session, reference spaces, swapchains, frame loop, action sets, and controller poses. It reports lifecycle events to the host; it never owns Android UI or ROM import state.

### VR presentation
Consumes head/eye/controller poses and game camera/player state. It owns per-eye composition, tracked hands, optional body presentation, comfort controls, and sky rendering. Gameplay actions still flow through the existing game input layer.

## First-run state machine
- NEEDS_GAME: no valid imported game exists.
- PICKING_GAME: system document picker is open.
- IMPORTING: selected document is copied and verified.
- READY: validated game and writable app storage are present.
- STARTING: native runtime and OpenXR are initializing.
- PLAYING: game is running.
- RECOVERABLE_ERROR: recoverable picker/import/runtime failure with retry.
- FATAL_ERROR: incompatible runtime or corrupted required data with a clear diagnostic.

A cancelled picker returns to NEEDS_GAME; it is not an error. An interrupted import is never treated as a valid game.

## Test seams
- Fake document provider for URI, permission, cancellation, and partial-read tests.
- In-memory DiscReader for native boundary and game startup tests.
- Disc fixtures containing headers and metadata only; never commit full game images.
- Fake OpenXR runtime for session transitions and action-state tests.
- Shader compile and offscreen render tests for sky and body passes.
- Quest 3 hardware tests for runtime-specific behavior and performance.