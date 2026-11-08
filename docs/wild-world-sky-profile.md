# Wild World-inspired sky profile

The shared procedural sky renderer now uses a softer, flatter cloud silhouette and a brighter, more stylized color palette inspired by the compact presentation of Animal Crossing: Wild World.

## Visual changes

- Brighter daytime blue with a pale blue horizon.
- Longer, flatter, soft-edged cloud banks.
- Peach horizon and lavender zenith during sunrise and sunset.
- Procedural stars and a soft moon at night.
- Cloud highlights and shadows respond to the game clock and overcast blend.

## Preserved behavior

- Uses the existing game clock and weather inputs.
- Keeps clouds direction-locked and consistent across both stereo eyes.
- Preserves the existing outdoor-scene guards, pause behavior, and skybox setting.
- Does not import or redistribute Wild World textures, models, or ROM content.
- Keeps the sky behind world geometry and restores the renderer's GL state.

## Verification

The existing native sky harnesses are the relevant checks:

```sh
python pc/tests/run_sky_tests.py
python pc/tests/run_sky_row_tests.py
```

A successful Android APK build confirms compilation and packaging only. The new sky still needs a Quest 3 visual check for shader compatibility, visual quality, and frame-time impact.

## Importing original Wild World sky assets

The Quest launcher includes **Import Wild World Sky ZIP**. Import a ZIP made
from the `sky/` resource directory of a legally obtained Wild World ROM.
The importer accepts only the flat `sky/*.bin` files, checks the core
background/cloud/star resources and expected sizes, rejects unsafe ZIP paths,
and installs the set atomically in app-private storage at `acww_sky/sky/`.
The ROM itself and extracted game assets are not bundled in the public source
repository or APK.

When the imported set is present, the outdoor renderer uses the original
tiled sky strip, weather cloud layers, star map, and full-moon sprite. Cloud
palettes follow the game clock; clear, cloudy, and heavy-overcast blends select
the corresponding cloud layouts. The procedural sky remains the fallback when
no valid asset pack has been imported. The imported object atlases also retain
the original moon phases, rainbow, and flying-object artwork; animated bird
flocks and the other sky-object sequences remain a follow-up integration task.

Restart the game after importing. A successful APK build confirms the decoder
and shader compile; the final appearance, cloud alignment, palette timing, and
Quest frame-time still require an in-headset playtest.
