#!/usr/bin/env python3
"""Regenerate small gallery posters from the bundled clips (requires Pillow)."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from decode import decode_lkd

destination = ROOT / 'web' / 'previews'
destination.mkdir(exist_ok=True)
for source in sorted((ROOT / 'assets' / 'clips').glob('*.lkd')):
    frames, _ = decode_lkd(source)
    frames[len(frames) // 2].save(destination / (source.stem + '.png'), optimize=True)
print(f'Wrote {len(list(destination.glob("*.png")))} gallery posters.')
