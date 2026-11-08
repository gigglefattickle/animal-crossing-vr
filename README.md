# Animal Crossing VR — Quest 3

A standalone VR port of **Animal Crossing (GameCube)** for Meta Quest. The goal is to bring the original game into a comfortable first-person VR experience while keeping its original gameplay, town, characters, items, and save data.

> **Project status:** Work in progress. Quest APKs are built by GitHub Actions, but a successful build does not mean every feature has been verified on a headset. Some gameplay and VR systems are still being developed and tested.

## Highlights

- Native Android APK for Meta Quest — the game runs on the headset and does not require PC VR streaming.
- OpenXR-based VR rendering and tracked Quest controllers.
- First-person gameplay with player-body/arm presentation work in progress.
- VR inventory interaction, including controller-pointer selection for the Fish and Bug collection pages.
- VR-themed launcher with game launch, Save Manager, and diagnostics export.
- Save-management and recovery helpers intended to help protect memory-card data.
- Wild World-inspired flat cloud and sky rendering work.
- RVZ disc-image reading, alongside ISO, GCM, and CISO support.

Features may vary between builds. Check the current source and release notes before assuming a feature is complete.

## Requirements

- A Meta Quest headset. Quest 3 is the primary target and test device.
- Your own legally obtained **Animal Crossing USA Rev 0 (GAFE01)** GameCube disc image.
- A supported image file: `.rvz`, `.iso`, `.gcm`, or `.ciso`.
- For installing a sideloaded APK, a USB-connected computer with Android platform tools/ADB or a compatible sideloading app.

**No game ROM, Nintendo game assets, or personal save files are included.** The user must provide their own game image.

## Install on Quest

1. Download the latest successful **Animal Crossing Quest APK** from the repository's [Actions artifacts](https://github.com/gigglefattickle/animal-crossing-vr/actions) or a published [GitHub Release](https://github.com/gigglefattickle/animal-crossing-vr/releases), if one is available.
2. Enable Developer Mode on your Quest and authorize USB debugging.
3. Install the APK using SideQuest, Meta Quest Developer Hub, or ADB.
4. Put your own supported disc image somewhere accessible to the headset's file picker.
5. Launch **Animal Crossing**, choose the game-file import option, and select your disc image.
6. Wait for import and validation to finish, then start the game.

The APK is standalone after installation; the computer is only needed for sideloading if you are not using another supported installation method.

### Updating

Install a newer APK over the existing app using the same signing key. Do not uninstall the app or clear its storage if you want to preserve the imported game and saves. Export a backup before major updates whenever possible.

## Build from source

The Quest APK is built by the workflow:

- [Quest standalone APK workflow](.github/workflows/quest-standalone-apk.yml)
- [Build history and downloadable artifacts](https://github.com/gigglefattickle/animal-crossing-vr/actions)

The workflow checks out the source, prepares the Android toolchain, fetches pinned SDL and OpenXR dependencies, runs package/data/RVZ tests, builds the APK, and uploads the APK with a build receipt and SHA-256 checksum.

To build locally, use the instructions in **[quest/BUILDING.md](quest/BUILDING.md)**. The project currently packages the game for **ARM32 / armeabi-v7a**; do not assume ARM64 game support.

## Documentation

- [Quest installation guide](quest/INSTALL.md)
- [Manual install, ROM transfer, and save backups](quest/MANUAL.md)
- [Quest build instructions](quest/BUILDING.md)
- [Quest architecture](quest/ARCHITECTURE.md)
- [Quest rewrite plan and milestones](docs/QUEST3_REWRITE.md)
- [Frequently asked questions](FAQ.md)

## Current development areas

Development is ongoing across:

- Player body, arms, and controller-driven movement.
- VR inventory and collection-page interaction.
- Sky and cloud rendering.
- Environment and scenery fixes.
- Launcher usability, save management, and diagnostics.
- Stability, crash reporting, and Quest lifecycle handling.

The Fish and Bug collection pointer work is intended to use the collection's own 8×5 slot layout while leaving normal pocket-grid dragging unchanged. Treat this as newly implemented until the corresponding APK has built and the behavior has been tested on-device.

## Reporting a problem

When reporting a crash or other issue, include:

1. Quest model and OS version, if known.
2. The APK build or GitHub Actions run you installed.
3. What you were doing immediately before the issue.
4. Whether it happens every time or only sometimes.
5. The diagnostics export from the launcher, if the app can still be opened.

Please do not upload or share your game image or personal save files in an issue. Diagnostics may contain device/app information and runtime logs; review them before sharing publicly.

## Credits and legal

This project builds on the Animal Crossing decompilation and the existing PC port work. See the repository history, source headers, and [LICENSE](LICENSE) for attribution and licensing details.

Animal Crossing and related trademarks and game content are owned by Nintendo. This is an unofficial fan project and is not affiliated with, endorsed by, or sponsored by Nintendo. The project does not distribute Nintendo game files; users must supply their own legally obtained game image.
