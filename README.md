# Carozerra

Pioneer car-stereo OEL display animations, decoded and brought back to life —
a web player, a Linux desktop visualizer, and the reverse-engineered `.lkd`
format they're both built on.

Old Pioneer head units (e.g. the DEH-P7600MP) let you upload custom
animations to their blue OEL display via CD or PC link, stored as `.lkd`
files. This repo decodes them and recreates the look on both the web and the
desktop.

*(This repository is private.)*

## Repo layout

```
carozerra/
├── web/                # GitHub Pages player — decodes .lkd fully client-side
│   └── index.html
├── carozerra.py         # Linux desktop visualizer (PyQt6)
├── decode.py             # .lkd decoder — CLI + importable library
├── assets/
│   ├── pioneer.png        # faceplate cutout, shared by web + desktop
│   └── clips/              # the 8 preloaded .lkd animations
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

## Web player

Live at **https://youxufkhan.github.io/carozerra/** (deploys automatically
from `web/` via `pages.yml`).

Open it and the Pioneer DEH-P7600MP faceplate renders with the animation
playing right on its OEL screen. All 8 built-in clips are preloaded and
selectable via chips; drag-and-drop (or click) still lets you load any other
`.lkd` file — nothing is uploaded anywhere, everything decodes in your
browser (`DecompressionStream` for gzip, an inline tar reader, a BMP parser
to canvas). Controls: play/pause, speed, retro-glow toggle, frame scrubber.

> Requires a browser with `DecompressionStream` (current Chrome/Firefox/Edge).

To preview locally: `python3 -m http.server -d web 8000`, then open
`http://localhost:8000`. (`assets/pioneer.png` and `assets/clips/*.lkd` need
to be copied into `web/pioneer.png` / `web/clips/` first — the CI workflow
does this automatically at deploy time; see `pages.yml`.)

## Desktop app (Linux / Debian)

A frameless, transparent, stereo-shaped window: the faceplate floats on your
desktop and animations play on its OEL screen, with the stereo's own
controls wired to real actions.

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

## The `.lkd` format (reverse-engineered)

An `.lkd` file is a thin wrapper around **entirely standard formats**:

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

So decoding is just: strip the 20-byte header → `gunzip` → `tar` → read the
BMP → slice it into 60 frames of **256 × 64**. No proprietary codec is
involved.

- Native frame: **256 × 64**, 60 frames, ~**60 ms/frame** (matches
  `reference/1.gif`, the low-res preview used to verify frame order/timing
  during reverse-engineering).
- Palette: cyan-blue `rgb(7,158,175)` + white on black — the classic blue-OEL
  glow. Artwork is dithered (mountains, waterfalls, leaping dolphins, etc.).

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
