# Quest 3 rewrite

## Goal
Replace the current Quest-specific application layer with a maintainable standalone Quest 3 application while preserving the existing Animal Crossing GameCube game and its proven emulation/runtime code.

This is a clean rewrite of the Quest host, lifecycle, import/storage, VR integration boundaries, and build/release pipeline. It is not a rewrite of the Animal Crossing decompilation or a new GameCube emulator.

## Product requirements
- One installable Android APK; no PC streaming, SideQuest installer, or end-user ADB workflow.
- First launch opens an Android Storage Access Framework picker.
- The user selects their own compatible USA GameCube disc image. The app imports it into private app storage and validates it before enabling Play.
- Support ISO/GCM/CISO first; add RVZ through the dedicated decoder and test against the user's own RVZ sample before advertising support.
- Preserve save data and settings across app updates. Never bundle game images, saves, keys, or copyrighted game assets.
- Native OpenXR stereo rendering, Quest Touch input, recentering, pause/resume, and robust headset lifecycle handling.
- Full player body and tracked hands, reusing original character animation/state where technically possible.
- A Wild World-inspired procedural sky that preserves weather and time-of-day transitions.
- Reproducible ARM64 Quest 3 build, APK signing/verification, release receipt, and automated tests.

## Architecture
1. Android host — minimal Activity, lifecycle forwarding, SAF picker, permissions, and user-facing errors. Java/Kotlin owns Android APIs only.
2. Game library — existing Animal Crossing native game and GX-to-GLES compatibility layer. No Android UI or storage APIs in game code.
3. Disc library — a narrow C API for validating/opening/reading supported disc formats. ISO/GCM/CISO and RVZ implementations are isolated behind the same interface.
4. App data service — private app storage, atomic import, content identity, save/settings migration, and crash-safe metadata.
5. OpenXR platform — session, swapchains, frame timing, input actions, controller poses, tracking loss, and Android pause/resume.
6. VR presentation — per-eye game rendering, UI panel, player body/hands, comfort settings, sky, and performance instrumentation.
7. Build/release — pinned SDK/NDK/CMake dependencies, ARM64-first configuration, tests, APK manifest/signature checks, and artifact provenance.

## Rewrite rules
- Keep the current working branch intact; all rewrite work happens on chatgpt/quest-standalone-rewrite.
- Do not remove the existing PCVR implementation or regress the PC build.
- No game ROM or save data is committed, packaged, logged, or uploaded as a build artifact.
- Prefer small interfaces and testable modules over cross-layer JNI calls.
- No feature is marked complete based only on compilation. Import, rendering, lifecycle, and input need targeted tests; headset-only behavior needs Quest 3 verification.
- Maintain an explicit compatibility/migration path for existing app data.

## Milestones
### R0 — Baseline and architecture
- Preserve the last successful APK build as a reference.
- Map native entry points, Android host, storage paths, OpenXR lifecycle, and current VR/game contracts.
- Add architecture and acceptance-test documentation.

### R1 — Android app shell
- Replace the monolithic SDLActivity subclass with a small lifecycle-aware host.
- Implement first-run state machine: Welcome → Select game → Import/validate → Ready → Play.
- Use SAF document picker and persist only the selected app-owned copy.
- Unit-test URI handling, cancellation, duplicate import, low storage, interrupted copy, and invalid images.

### R2 — Disc and storage services
- Define a format-neutral disc reader interface.
- Integrate ISO/GCM/CISO and RVZ behind it.
- Test random, aligned, unaligned, and boundary reads; verify disc identity and reject unsupported layouts.
- Add atomic install and save-preserving migration.

### R3 — OpenXR runtime boundary
- Isolate Android lifecycle from OpenXR session state.
- Cover focus changes, pause/resume, recenter, controller loss, swapchain recreation, and shutdown.
- Add deterministic contract tests and Quest runtime smoke tests.

### R4 — VR presentation
- Integrate full-body player presentation and tracked hands without replacing original gameplay animation logic unnecessarily.
- Add Wild World-inspired sky shader and weather/time transitions.
- Validate comfort, stereo correctness, and sustained Quest 3 performance.

### R5 — Release quality
- Build ARM64 APK, run unit/packaging tests, verify manifest and signature, generate checksums and receipt.
- Test clean install, update install, import, save persistence, suspend/resume, and gameplay on Quest 3.
- Publish APK only after all automated gates pass; distinguish build success from headset verification.

## Current known baseline
The previous branch chatgpt/quest-standalone-upgrade-v2 produced a signed ARM32 APK artifact in GitHub Actions. That proves the existing native build and packaging path works; it does not prove the planned SAF importer, RVZ flow, full-body rendering, sky changes, ARM64 support, or on-device operation. The rewrite branch is intentionally separate.