# Manual Quest setup and save backups

The normal setup is entirely on the headset: open Animal Crossing, choose **Choose Game File**, and select your own **USA Rev 0 / GAFE01** disc image. The in-app picker imports **.rvz, .iso, .gcm, and .ciso** files into the app's private storage.

The optional ADB helper is for users who prefer transferring files over USB or backing up saves. It supports importing **.rvz, .ciso, .iso, and .gcm** images and **.gci** saves. It requires ADB and Python 3.

Enable Developer Mode and authorize USB debugging first. Run `adb devices`, replace `YOUR_QUEST_SERIAL` below, and close Animal Crossing on the headset:

```sh
adb -s YOUR_QUEST_SERIAL install -r AnimalCrossing-Quest.apk
python3 device_data.py --adb /path/to/adb --serial YOUR_QUEST_SERIAL --rom /path/to/AnimalCrossing.rvz
```

Use the actual extension of your file. The helper copies the selected image into the app's private `files/rom` directory and verifies the transferred byte count and SHA-256 hash. It does not convert formats; the native game loader reads RVZ directly. Then open **Animal Crossing** from the headset's app library.

## Back up or copy a save (optional)

Close the game first. Export to a **new directory** each time:

```sh
python3 device_data.py --adb /path/to/adb --serial YOUR_QUEST_SERIAL --export-save /path/to/new-quest-backup
```

To bring a copy of a PC `.gci` save into an empty Quest save slot:

```sh
python3 device_data.py --adb /path/to/adb --serial YOUR_QUEST_SERIAL --save /path/to/DobutsunomoriP_MURA.gci
```

The helper refuses to overwrite different existing files. Keep PC and Quest saves separate; they do not sync. Do not import PC settings into Quest. Updating with the same signed APK preserves app data; uninstalling or clearing storage deletes it.
