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
reader, a BMP parser to canvas. All 8 built-in clips are preloaded and
selectable via chips; drag-and-drop (or click) still lets you load any other
`.lkd` file — nothing is uploaded anywhere.

> Requires a browser with `DecompressionStream` (current Chrome/Firefox/Edge).

To preview locally: `python3 -m http.server -d web 8000`, then open
`http://localhost:8000`. (`assets/pioneer.png` and `assets/clips/*.lkd` need
to be copied into `web/pioneer.png` / `web/clips/` first — the CI workflow
does this automatically at deploy time; see `.github/workflows/pages.yml`.)

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

Keyboard: `←→` clip, `↑↓` speed, `F` glow, `1–6`, `Esc`.

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

Geometry (screen rect, knob centers, button hitboxes) lives in the `G` dict
at the top of `carozerra.py` as fractions of the faceplate, so it scales with
the window and is easy to re-tune against a different photo.

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

```bash
git tag v1.1.0
git push --tags
```

`release.yml` builds the `.deb` and attaches it to a new GitHub Release
automatically. To build locally without releasing:

```bash
packaging/build-deb.sh 1.1.0
# -> packaging/dist/carozerra_1.1.0_all.deb
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
│   └── index.html
├── carozerra.py         # Linux desktop visualizer (PyQt6)
├── decode.py             # .lkd decoder — CLI + importable library
├── assets/
│   ├── pioneer.png        # faceplate cutout, shared by web + desktop
│   ├── clips/               # the 8 preloaded .lkd animations
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
