# New Leaf / AzaharXR Integration

Status: integration preparation; the emulator engine is not yet linked into the Animal Crossing VR APK.

## Pinned upstream source

- Repository: https://github.com/gigglefattickle/Animal-Crossing-New-Leaf-VR-
- Branch inspected: `master`
- Commit inspected: `311689448fbac31076328abff8866a79e94e3256`
- License: GPLv3-or-later, per upstream README. Preserve upstream license and notices when integrating.

Do not fetch an unpinned moving branch in release builds. The exact source revision must be recorded in the build receipt.

## Current architecture findings

### Animal Crossing VR (host app)

- Package: `com.liquidazir.animalcrossingquest`
- The Quest APK is currently assembled by custom Python/Android SDK packaging in `quest/tools/package_apk.py`, not by Gradle.
- Java sources are compiled directly with `javac --release 8`.
- The native CMake project currently builds the Wild World game, SDL2 and OpenXR loader.
- The current CI workflow builds only `armeabi-v7a` (32-bit), although the packager accepts either ARM ABI.
- The launcher already has a New Leaf tile, but it intentionally displays an integration-in-progress message.

### AzaharXR (New Leaf engine)

- The Android app uses package/namespace `org.citra.citra_emu`.
- It builds for `arm64-v8a` only and uses NDK `27.3.13750724`, Android API 35 compile SDK and CMake 3.25+.
- The native Android JNI target is `citra-android`; it links Azahar's `audio_core`, `citra_common`, `citra_core`, `input_common`, and `network`, plus Android/OpenXR graphics and media libraries.
- The VR activity is Kotlin and extends the emulator's `EmulationActivity`; it is not a standalone Activity that can be copied into the current Java-only APK.
- The Android app also depends on AndroidX, Material, Kotlin serialization, navigation, view binding, generated resources, and additional downloaded native libraries.
- The upstream app manifest requests camera/audio/storage permissions and declares VR/OpenXR features and activities. Only the permissions and components actually needed by the merged app should be retained.

## Integration decision

Use one Android application package with the existing Animal Crossing package ID and launcher as the entry point. Embed Azahar's native emulator library and Android UI/resources as internal components; do not launch a second installed application and do not change the app ID to `org.citra.citra_emu`.

The safest path is to migrate the custom APK assembly to a Gradle Android application build, then add Azahar's Android source/resources/dependencies and native target to that build. Keep Wild World's existing native target and launcher behavior intact. The New Leaf tile should launch the embedded emulator's game picker/VR flow.

## Required implementation phases

1. **Build-system foundation**
   - Pin and fetch the upstream AzaharXR commit above in CI.
   - Move the host APK packaging to Gradle (or create a reproducible Gradle packaging stage) so Kotlin, AndroidX, generated resources and manifest merging are supported.
   - Preserve the existing launcher application ID, signing, diagnostics, APK artifacts and Wild World native library.
2. **Native engine**
   - Configure Azahar for Android/Quest and build `citra-android` plus all linked native dependencies.
   - Resolve the ABI mismatch. Azahar currently requires ARM64; validate Wild World on ARM64 before changing the default, or ship a tested multi-ABI APK.
   - Avoid duplicate OpenXR loader copies and reconcile OpenXR runtime loading between both engines.
3. **Android/VR lifecycle**
   - Merge the required Kotlin/Java classes, resources, manifest declarations and dependencies.
   - Ensure only one active immersive OpenXR session at a time. Define clean transitions between launcher, Wild World and New Leaf, including pause/resume, back/quit, activity recreation and crash recovery.
   - Keep emulator state and game selection isolated from Wild World save/import paths.
4. **Launcher handoff**
   - Replace the New Leaf informational dialog with an internal activity/navigation handoff.
   - Return cleanly to the Animal Crossing launcher when the emulator exits.
5. **Verification**
   - CI builds and packages the unified APK and publishes matching native symbols.
   - Test on Quest hardware: app launch, New Leaf game picker, user-imported 3DS backup, OpenXR session start/stop, controller input, suspend/resume, returning to launcher, and Wild World regression tests.

## Guardrails

- Do not bundle copyrighted game ROMs, firmware, encryption keys or user saves. Users provide their own legally obtained backups and required files.
- Preserve GPLv3-or-later notices and provide the corresponding source for distributed Azahar-derived binaries.
- Do not mark New Leaf as playable until the embedded engine launches and runs a user-provided game on Quest hardware.
- Do not remove or regress the working Wild World path while integrating the second engine.
