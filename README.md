<p align="center">
  <img src="./assets/readme/hero-title.svg" width="100%" alt="Carozerra — decode forgotten Pioneer car-stereo .lkd animations and watch them glow again, live in a browser or as a Linux desktop visualizer.">
</p>

<p align="center">
  <a href="https://github.com/youxufkhan/carozerra/releases/latest"><img src="https://img.shields.io/github/v/release/youxufkhan/carozerra?label=release&color=12e0ff" alt="Latest release"></a>
  <a href="https://youxufkhan.github.io/carozerra/"><img src="https://img.shields.io/badge/demo-live-12e0ff" alt="Live demo"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/github/license/youxufkhan/carozerra?color=12e0ff" alt="License"></a>
</p>

<p align="center">
  <a href="./CHANGELOG.md">Changelog</a> •
  <a href="./ROADMAP.md">Roadmap</a>
</p>
<p align="center">
  <a href="https://youxufkhan.github.io/carozerra/">
    <img src="./assets/readme/proof-web.png" width="100%" alt="The live web player: a Pioneer DEH-P7600MP faceplate with a decoded .lkd animation glowing cyan on its OEL screen">
  </a>
</p>

<p align="center"><sub>Live and playing right now at <a href="https://youxufkhan.github.io/carozerra/">youxufkhan.github.io/carozerra</a> — click the image, no install.</sub></p>

## What it is

Old Pioneer head units (e.g. the DEH-P7600MP) let you upload custom animations
to their blue OEL display via CD or PC link, stored as `.lkd` files no modern
software could open. Carozerra decodes them and brings the look back — on the
web or on a Linux desktop, with the stereo's own controls doing real things.

## How the format works

`.lkd` turned out to be a thin wrapper around **entirely standard formats** —
no proprietary codec, no guesswork left in the pipeline:

```
offset  bytes
0x00    "zLKD"   magic (7A 4C 4B 44)
0x04    uint32   version           = 3
0x08    uint32   (unknown)         = 1
0x0C    uint32   (unknown)         = 7
0x10    uint32   frame count       = 60
0x14    gzip stream ──▶ gunzip ──▶ TAR archive
                         └─ one member = a 24-bit Windows BMP
                            BMP = 256 × 3840, bottom-up, BGR
                                = 60 stacked frames of 256 × 64
```

Strip the 20-byte header → `gunzip` → `tar` → read the BMP → slice it into 60
frames of **256 × 64**. Native palette is cyan-blue `rgb(7,158,175)` + white on
black — the classic blue-OEL glow — and artwork is dithered (mountains,
waterfalls, leaping dolphins, etc.).

## Web player

Decodes fully client-side — `DecompressionStream` for gzip, an inline tar
reader, a BMP parser to canvas. 83 built-in clips (movies, backgrounds, stills,
level meters, and a full-color set — see `assets/clips/`) are preloaded and
picked from a categorized gallery of preview thumbnails that animate on hover;
drag-and-drop (or click) still lets you load any other `.lkd` file — nothing is
uploaded anywhere. GIF and WebM export (with the retro phosphor glow baked into
the output) are available via the Export buttons.

> Requires a browser with `DecompressionStream` (current Chrome/Firefox/Edge).

`←→` browse the gallery, and the footer's **Changelog** link opens
`CHANGELOG.md` in a modal — fetched at click time, so it never drifts from the
file in the repo.

To preview locally: `python3 -m http.server -d web 8000`, then open
`http://localhost:8000`. (`assets/pioneer.png`, `assets/clips/*.lkd` and
`CHANGELOG.md` need to be copied into `web/pioneer.png` / `web/clips/` /
`web/CHANGELOG.md` first — the CI workflow does this automatically at deploy
time; see `.github/workflows/pages.yml`.)

## Desktop app (Linux / Debian)

A frameless, transparent, stereo-shaped window: the faceplate floats on your
desktop and animations play on its OEL screen, with the stereo's own
controls wired to real actions.

<p align="center">
  <img src="./assets/readme/proof-desktop.png" width="100%" alt="The Linux desktop app: the same Pioneer faceplate floating as a transparent window, playing a dolphin animation">
</p>

| Control | Action |
|---|---|
| **Presets 1–6** | switch animation (first six clips) |
| **Left knob** | drag to rotate / scroll wheel → **system volume** |
| **Right nav knob** | left/right = prev/next clip · up/down = animation speed |
| **FUNC** (by the display) | toggle retro glow |
| **ESC** (top-right) | quit |
| drag body / edges | move / resize the window (aspect-locked) |

Keyboard: `←→` clip, `↑↓` speed, `F` glow, `1–6`, `F1` control map, `Esc`.

The control map above is also shown on the faceplate for a few seconds at
launch, and `F1` brings it back. `Esc` closes it rather than quitting, so
dismissing the overlay can't take the app down with it.

**Install the `.deb`** (see [Releases](../../releases) for the latest build):

```bash
sudo apt install ./carozerra_<version>_all.deb
carozerra
```

**Or run from source** — dependencies are standard Debian/Ubuntu packages,
no pip install:

```bash
sudo apt install python3-pyqt6 python3-pil python3-numpy
python3 carozerra.py
```

System volume uses `wpctl` (PipeWire) with a `pactl` fallback. All 8
animations are preloaded from `assets/` — no drag-drop or upload in the
desktop app (that's a web-player-only feature).

### Windows (beta)

**Install:** grab `carozerra_<version>_windows_x64.exe` from the latest
[prerelease](../../releases) and run it. First launch will trigger a
SmartScreen warning — the binary isn't code-signed (see below).

**Or build it yourself:**

```powershell
pip install PyQt6 pillow pyinstaller
python packaging/make-ico.py            # icon, generated from the faceplate art
pyinstaller --clean --noconfirm packaging/carozerra.spec
dist\carozerra.exe
```

Every push touching the app or its packaging builds that `.exe` on a
`windows-latest` runner and smoke-tests it via `packaging/smoke-windows.ps1`:
first `carozerra.exe --selftest out.png`, which renders one frame and exits
(so a build can be checked without a human at a screen), then a real
interactive launch that has to survive, own a top-level window, and be
screenshotted. `release.yml` runs the same sequence again before attaching
the `.exe` to a tagged release.

Why beta, not a full release:

- **The volume knob does nothing.** It drives `wpctl`/`pactl`, neither of
  which exists on Windows, so `get_volume()` returns a fixed 50 and the knob
  turns while lying. Needs either `pycaw` or an explicit disable.
- **The exe is unsigned**, so SmartScreen will warn, and antivirus false
  positives on PyInstaller output are common. (UPX compression is off in the
  spec for the same reason.) Code signing costs money; not done yet.

It is large (46.7 MB, because a onefile PyQt6 bundle carries all of Qt) but
otherwise works: CI's launch screenshot shows DWM compositing the frameless
translucent window correctly with a clip playing, and the author has run it
by hand on a real Windows machine, resize and HiDPI included.

Geometry (screen rect, knob centers, button hitboxes) lives in the `G` dict
at the top of `carozerra.py` as fractions of the faceplate, so it scales with
the window and is easy to re-tune against a different photo.

### Android (beta)

A native app for Android-based aftermarket head units — the same faceplate
and decoder, running fullscreen on the dashboard instead of floating on a
desktop.

**Sideload only** — no Play Store listing. These units ship without Play
Services, so install is a plain APK from the latest
[prerelease](../../releases):

```bash
adb install carozerra_<version>_android.apk
```

**Or build it yourself** (needs JDK 21 and the Android SDK — AGP 8.13 does
not support newer JDKs):

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`minSdk 29` (Android 10), locked to `sensorLandscape`, immersive fullscreen
(system bars hidden, swipe to reveal), and `FLAG_KEEP_SCREEN_ON` so the
display doesn't blank part-way through a clip — a dashboard has no reason to
sleep. All 17 faceplate controls are live, each mapped to the function the
DEH-P7600MP owner's manual prints against it:

| Control | Action |
|---|---|
| **TA** | mute |
| **VOLUME** turn | system volume |
| **VOLUME** press | blackout · any touch wakes |
| **DISPLAY** | clean / clock / clock + info |
| **TEXT** | scrolling text line |
| **FUNCTION** | glow on/off |
| **AUDIO** | level meters |
| **NAV** left/right | previous / next track |
| **NAV** up/down | animation speed |
| **NAV** press | play / pause |
| **OPEN** | control map / about card |
| **BAND** | close · hold to exit |
| **ENTERTAINMENT** | clip gallery (all 83 clips) |
| **EQ-EX** | scanlines |
| **1–6** | presets — clips 1-6 of the category |
| **EQ** | glow intensity |
| **SOURCE** | next category |

Track skip and play/pause go through permission-free media keys rather than
a media-session/notification-listener integration. The app does prompt for
microphone access on first launch — that's for the AUDIO level meters
(`RECORD_AUDIO`, requested via `Visualizer`), not for transport.

Why beta, not a full release:

- **Debug-signed.** There's no release keystore yet — same open item as the
  Windows build's missing code signing. Uninstall the app before sideloading
  a newer build; Android won't install a differently-signed APK over an
  existing one.
- **Audio capture is honest about what it can't verify.** AUDIO reads real
  output-mix levels via `Visualizer` where the platform allows it. On the
  development emulator, which has no audio HAL, `Visualizer`'s constructor
  itself fails (`ERROR_NO_INIT`) — the app treats that as "no signal" rather
  than faking one: AUDIO flashes **NO SIGNAL** and the meter zone stays
  hidden instead of showing frozen or invented bars. That's correct behavior
  for a meter with nothing to measure; whether real head-unit hardware
  exposes output-mix capture at all is unverified.
- **Untested on real head-unit hardware.** Built and verified only on an
  Android emulator (API 29, landscape, both a generic Pixel profile and an
  Automotive profile) — the same caveat the Windows build carries, for the
  same reason: nobody has run it on the actual target yet.

## `decode.py` — batch converter

```bash
python decode.py                             # assets/clips/*.lkd -> out/<name>_clean.gif + _glow.gif
python decode.py assets/clips/movie6.lkd --mp4 --frames   # + MP4 + raw PNG frames
python decode.py --glow --scale 6 --fps 12    # glow only, 6x, 12fps
```

Options: `--outdir` (default `out/`), `--scale` (default 4 → 1024×256),
`--fps` (default 60 ms/frame), `--clean`, `--glow`, `--mp4`, `--frames`.
Importable too: `frames, meta = decode_lkd("assets/clips/movie1.lkd")` returns
60 RGB `PIL.Image` frames.

**clean** = faithful nearest-neighbor upscale. **glow** = OEL phosphor look
(bloom + scanlines on black). Needs Pillow (and `ffmpeg` on PATH for MP4).
Output goes to `out/` (gitignored — fully regenerable, not shipped).

## Releasing

Order matters: `pages.yml` triggers on pushes to `main`, not on tags, so the
website must go out before (or with) the tag — otherwise a new Release ships
alongside a site still serving the previous player.

```bash
git push origin main          # triggers packaging-check.yml + windows-check.yml + android-check.yml + pages.yml
# wait for all green, and confirm the live site picked up the new build
git tag v1.2.0
git push origin v1.2.0        # triggers release.yml
```

Use the two-step push rather than `git push --tags`: that pushes only the tag,
leaving `main` (and the site) behind.

`release.yml` builds the `.deb`, the Windows `.exe`, and the Android `.apk`,
and attaches all three to a new GitHub Release automatically. A tag with a
`-` suffix (e.g.
`v1.3.0-beta.1`) is published as a **prerelease**; a clean `vX.Y.Z` is a
normal release — the workflow reads that off the tag name, nothing to set by
hand. (The `.deb`'s own version string swaps that `-` for dpkg's `~`, which
sorts a prerelease *before* the final version it precedes — verify with
`dpkg --compare-versions` if you're inventing a new prerelease scheme.)

Before tagging, also bump `versionName`/`versionCode` in
`android/app/build.gradle.kts` to match — unlike the `.deb` (which takes its
version as a build argument) and the `.exe` (whose filename is derived from
the tag), the Android build has no mechanism to pick up the tag
automatically yet, so its internal version has to be updated by hand or it
will silently ship stale.

To build locally without releasing:

```bash
packaging/build-deb.sh 1.2.0
# -> packaging/dist/carozerra_1.2.0_all.deb
```

## License

[MIT](./LICENSE) — covers the code (`decode.py`, `carozerra.py`, `web/`,
`packaging/`, `.github/`). The Pioneer product photography under
`assets/pioneer.png` and `reference/` is not original work of this project
and isn't covered by the license — it's included for reverse-engineering
documentation and UI purposes only.

<details>
<summary>Repo layout</summary>

```
carozerra/
├── web/                # GitHub Pages player — decodes .lkd fully client-side
│   ├── index.html
│   └── gifenc.esm.js     # vendored GIF encoder (mattdesl/gifenc, MIT) used by GIF export
├── carozerra.py         # Linux desktop visualizer (PyQt6)
├── decode.py             # .lkd decoder — CLI + importable library
├── assets/
│   ├── pioneer.png        # faceplate cutout, shared by web + desktop
│   ├── clips/               # the 83 preloaded .lkd animations (movies, bgv, bgp, level meters, color)
│   └── readme/                # this README's hero SVG + proof screenshots
├── reference/              # provenance only — not used by any code
│   ├── pioneer-original.jpeg
│   └── 1.gif
├── packaging/
│   ├── build-deb.sh         # -> packaging/dist/carozerra_<version>_all.deb
│   └── debian/                # control file template + .desktop entry
└── .github/workflows/
    ├── pages.yml               # deploys web/ whenever web/** or assets/** change
    ├── packaging-check.yml      # build-validates the .deb whenever app/packaging files change
    └── release.yml               # builds + attaches the .deb to a GitHub Release on every v* tag
```

</details>
