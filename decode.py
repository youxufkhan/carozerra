#!/usr/bin/env python3
"""
Decode Pioneer `.lkd` OEL car-stereo animations to modern formats.

Format (reverse-engineered):
    0x00  "zLKD" magic
    0x04  uint32  version (=3)
    0x08  uint32  (=1)
    0x0C  uint32  (=7)
    0x10  uint32  frame count (=60)
    0x14  gzip stream -> TAR -> single 24-bit BMP (256 x 3840, bottom-up)
          = `frame_count` vertically stacked frames of 256 x 64

Everything downstream of the 20-byte header is standard (gzip + tar + BMP),
so decoding needs only the Python stdlib plus Pillow.
"""
import argparse
import glob
import gzip
import io
import os
import struct
import subprocess
import sys
import tarfile

from PIL import Image, ImageChops, ImageFilter

MAGIC = b"zLKD"
NATIVE_W = 256
DEFAULT_DELAY_MS = 60  # matches reference 1.gif (60 frames @ 60ms ~ 16.7fps)


def decode_lkd(path):
    """Return (frames, meta). frames = list of RGB PIL.Image (256x64)."""
    data = open(path, "rb").read()
    if data[:4] != MAGIC:
        raise ValueError(f"{path}: bad magic {data[:4]!r} (expected {MAGIC!r})")
    version, f1, f2, frame_count = struct.unpack("<4I", data[4:20])

    payload = gzip.decompress(data[20:])
    with tarfile.open(fileobj=io.BytesIO(payload)) as tf:
        members = tf.getmembers()
        if not members:
            raise ValueError(f"{path}: empty tar payload")
        member = members[0]
        bmp_bytes = tf.extractfile(member).read()

    strip = Image.open(io.BytesIO(bmp_bytes)).convert("RGB")
    W, H = strip.size
    if frame_count <= 0:
        frame_count = H // NATIVE_W  # defensive fallback
    fh = H // frame_count
    frames = [strip.crop((0, i * fh, W, i * fh + fh)) for i in range(frame_count)]

    meta = {
        "path": path,
        "version": version,
        "fields": (f1, f2),
        "frame_count": frame_count,
        "strip_size": (W, H),
        "frame_size": (W, fh),
        "bmp_member": member.name,
    }
    return frames, meta


def _upscale(img, scale):
    if scale == 1:
        return img.copy()
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST)


def apply_glow(frame, scale=4, bloom_radius=None, scanlines=True):
    """Recreate the OEL phosphor look: crisp pixels + bloom + scanlines on black."""
    up = _upscale(frame, scale)
    if bloom_radius is None:
        bloom_radius = max(1.5, scale * 0.9)

    # Bloom: blurred, brightened copy screen-blended over the crisp frame.
    blur = up.filter(ImageFilter.GaussianBlur(bloom_radius))
    blur = blur.point(lambda v: min(255, int(v * 1.35)))
    glow = ImageChops.screen(up, blur)

    if scanlines and scale >= 2:
        px = glow.load()
        w, h = glow.size
        for y in range(0, h, scale):  # darken the top row of each source pixel band
            for x in range(w):
                r, g, b = px[x, y]
                px[x, y] = (int(r * 0.55), int(g * 0.55), int(b * 0.55))
    return glow


def _save_gif(frames, out, delay_ms):
    frames[0].save(
        out, save_all=True, append_images=frames[1:],
        duration=delay_ms, loop=0, disposal=2, optimize=False,
    )


def _save_mp4(frames, out, delay_ms):
    fps = 1000.0 / delay_ms
    # feed raw RGB frames to ffmpeg via stdin
    w, h = frames[0].size
    cmd = [
        "ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
        "-video_size", f"{w}x{h}", "-framerate", f"{fps:.4f}",
        "-i", "-", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out,
    ]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for fr in frames:
        p.stdin.write(fr.tobytes())
    p.stdin.close()
    if p.wait() != 0:
        raise RuntimeError("ffmpeg failed")


def process(path, outdir, scale, delay_ms, clean, glow, mp4, dump_frames):
    frames, meta = decode_lkd(path)
    stem = os.path.splitext(os.path.basename(path))[0]
    os.makedirs(outdir, exist_ok=True)
    made = []

    clean_frames = [_upscale(f, scale) for f in frames]
    glow_frames = [apply_glow(f, scale) for f in frames] if (glow or mp4) else None

    if clean:
        out = os.path.join(outdir, f"{stem}_clean.gif")
        _save_gif(clean_frames, out, delay_ms); made.append(out)
    if glow:
        out = os.path.join(outdir, f"{stem}_glow.gif")
        _save_gif(glow_frames, out, delay_ms); made.append(out)
    if mp4:
        out = os.path.join(outdir, f"{stem}.mp4")
        try:
            _save_mp4(glow_frames, out, delay_ms); made.append(out)
        except (FileNotFoundError, RuntimeError) as e:
            print(f"  ! mp4 skipped ({e})")
    if dump_frames:
        fdir = os.path.join(outdir, f"{stem}_frames")
        os.makedirs(fdir, exist_ok=True)
        for i, f in enumerate(frames):
            f.save(os.path.join(fdir, f"f{i:03d}.png"))
        made.append(fdir + "/")

    print(f"{path}: {meta['strip_size'][0]}x{meta['strip_size'][1]} -> "
          f"{meta['frame_count']} frames {meta['frame_size']} "
          f"(member {meta['bmp_member']})")
    for m in made:
        print(f"  -> {m}")
    return meta


def main(argv=None):
    ap = argparse.ArgumentParser(description="Decode Pioneer .lkd animations.")
    ap.add_argument("files", nargs="*", help="`.lkd` files (default: all *.lkd in cwd)")
    ap.add_argument("--outdir", default="out")
    ap.add_argument("--scale", type=int, default=4)
    ap.add_argument("--fps", type=float, default=None,
                    help="override frame rate (default derives 60ms/frame)")
    ap.add_argument("--clean", action="store_true", help="write clean GIF only")
    ap.add_argument("--glow", action="store_true", help="write glow GIF only")
    ap.add_argument("--mp4", action="store_true", help="also write MP4 (needs ffmpeg)")
    ap.add_argument("--frames", action="store_true", help="dump raw PNG frames")
    args = ap.parse_args(argv)

    files = args.files or sorted(glob.glob("*.lkd"))
    if not files:
        ap.error("no .lkd files given or found in cwd")

    # default: both clean+glow unless one is explicitly selected
    clean = args.clean or not (args.clean or args.glow)
    glow = args.glow or not (args.clean or args.glow)
    delay_ms = round(1000.0 / args.fps) if args.fps else DEFAULT_DELAY_MS

    for f in files:
        try:
            process(f, args.outdir, args.scale, delay_ms,
                    clean, glow, args.mp4, args.frames)
        except Exception as e:
            print(f"{f}: ERROR {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
