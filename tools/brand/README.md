# Brand assets

`generate.py` draws NZStreamer's launcher icon and TV banner into `app/src/main/res`, and the README's logo:

- `drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`:
  the adaptive icon's layers (vectors; the monochrome one is what themed icons tint);
- `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`: the adaptive icons, which every device
  the app runs on (minSdk 26) takes;
- `mipmap-*/ic_launcher.png`, `ic_launcher_round.png`: the same icon as PNGs, 80 dp (xhdpi 160 px);
- `drawable-*/tv_banner.png`: the TV launcher's banner, 160 x 90 dp (xhdpi 320 x 180, xxxhdpi
  640 x 360), the mark beside the name in Sora Bold;
- `drawable-v31/splash_mark.xml`: the splash's icon on Android 12+, an animated vector: the mark's
  three lines run in from the left into the triangle's front, in `values/splash_mark.xml`'s
  `splash_mark_duration` (the splash theme's animation duration);
- `drawable/splash_mark.xml`: the splash's icon before Android 12, which the library draws still: the
  mark as the launcher icon has it;
- `.github/readme/logo-on-dark.png`, `logo-on-light.png`: the README's logo, the mark beside the name as
  the banner has them on a clear ground, the name light for GitHub's dark theme and dark for its light one.

The mark's outline and the colours are defined once in the script. Change them there and run it
again rather than editing its output:

```sh
python3 -I tools/brand/generate.py
```

It needs Python 3 and Pillow (10 or newer). PNGs drawn by another Pillow or FreeType version may
differ by a few anti-aliased pixels. `BrandAssetsTest` checks what the app needs of the files.

To see the splash without a device, draw it as it plays, from the same outline and timings, into a
directory outside the repository (nothing else is written):

```sh
python3 -I tools/brand/generate.py --preview /tmp/splash
```

That gives `splash-preview.webp`, Android 12+'s animation on a phone, and `splash-android11.png`,
the still splash Android 8-11 shows, on a 1080p TV.
