# Install Animal Crossing on Quest 3

Download the **standalone Quest APK** from the [latest release](https://github.com/LiquidAzir/animal-crossing-vr/releases/latest). This is a native Android app for Quest 3: it does not require a PC game install, SteamVR, or PC streaming. A computer may be used to sideload the APK, but the game runs on the headset.

1. Enable Developer Mode on your Quest 3 and install the APK with SideQuest or Meta Quest Developer Hub. Follow [Meta's device setup guide](https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/) if needed.
2. Put your own **Animal Crossing USA Rev 0 (GAFE01)** disc image somewhere the Quest file picker can access, such as the headset's Downloads folder. Supported formats are **.rvz, .iso, .gcm, and .ciso**.
3. Open **Animal Crossing** from the headset's app library. On first launch, choose **Choose Game File**, select your disc image, and wait for the app to import and validate it.
4. Select **Play**. The imported game is stored in the app's private headset storage and is read directly by the native game loader.

The APK does not include a game image or save data. Use only a game dump you are legally entitled to use. RVZ files are read natively on the headset; renaming an RVZ file to ISO is not a conversion.

## Updating

Install a newer APK over the existing app using the same signing key. This preserves the imported game, saves, and settings. Do not uninstall or clear app data to update: that removes the stored game and progress.

## Playing

See **[all VR controls in one place](https://github.com/LiquidAzir/animal-crossing-vr#vr-controls)** for movement, inventory, map, tool switching, motion tools, and VR Settings. Use the Quest / Touch column.

## If setup stops

- **Game file not listed:** make sure it is stored somewhere visible in the Android file picker and has a supported extension: .rvz, .iso, .gcm, or .ciso.
- **Invalid disc image:** use the USA Rev 0 / GAFE01 image. Other regions and revisions are not supported by this build.
- **Import failed:** check that the headset has enough free storage for the imported image.
- **App is already installed:** install the update over the existing app; do not uninstall it if you want to keep saves and imported game data.

For optional ADB-based import/export and save backups, see [manual setup](MANUAL.md).
