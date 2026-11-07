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
