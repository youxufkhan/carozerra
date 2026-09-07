#!/usr/bin/env python3
"""Generate packaging/carozerra.ico from assets/pioneer.png.

Kept as a build step rather than a committed binary so the icon can't drift
from the faceplate art. The faceplate is a ~3:1 strip, so it gets letterboxed
onto a transparent square instead of squashed -- at 16px that reads as a thin
silver bar, which is still the stereo and not a stretched smear.
"""
import os
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir))
SIZES = [16, 24, 32, 48, 64, 128, 256]


def main(out=None):
    out = out or os.path.join(os.path.dirname(os.path.abspath(__file__)), "carozerra.ico")
    face = Image.open(os.path.join(ROOT, "assets", "pioneer.png")).convert("RGBA")
    face = face.crop(face.getbbox())          # same dead-margin crop the app does

    side = max(face.width, face.height)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(face, ((side - face.width) // 2, (side - face.height) // 2))

    square.save(out, format="ICO", sizes=[(s, s) for s in SIZES])
    print(f"wrote {out} ({square.width}x{square.height} source, "
          f"{len(SIZES)} sizes)")


if __name__ == "__main__":
    main(*sys.argv[1:])
