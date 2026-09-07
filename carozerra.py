#!/usr/bin/env python3
"""
Carozerra — a Pioneer DEH-P7600MP desktop visualizer.

A frameless, transparent, stereo-shaped window that plays the decoded `.lkd`
OEL animations on the head-unit's blue screen. The stereo's own controls work:

  presets 1-6      switch animation
  left knob        rotate / scroll  -> system volume (PipeWire wpctl / pactl)
  right nav knob   left|right switch clip, up|down change speed
  FUNC button      toggle retro glow
  ESC (top-right)  quit
  body / edges     drag to move / resize the window

...and the keyboard mirrors them:

  <- ->            prev / next clip
  up / down        animation speed
  F                toggle retro glow
  1-6              preset / animation
  Esc              quit
  F1               show the control-map overlay (also shown for a few
                   seconds at launch; see HELP_FULL / _draw_help)

Native PyQt6 — reuses decode_lkd() from decode.py for decoding only.

Run with `--selftest <out.png>` to render a single frame and exit; CI uses it
to prove a packaged build actually draws (see packaging/smoke-windows.ps1).
"""
import math
import os
import re
import shutil
import subprocess
import sys

from PyQt6.QtCore import Qt, QRectF, QTimer, QPointF
from PyQt6.QtGui import (QImage, QPixmap, QPainter, QColor, QPainterPath,
                         QGuiApplication, QFont, QFontMetricsF, QPen)
from PyQt6.QtWidgets import QApplication, QWidget

from decode import decode_lkd

# Running from source, assets/ sits next to this file. A PyInstaller build
# unpacks the bundled assets/ into a temp dir and points sys._MEIPASS at it,
# so the frozen exe has to look there instead (see packaging/carozerra.spec).
APP_DIR = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
# pioneer.png is 1600x893 but the visible faceplate (non-transparent bbox, via
# PIL Image.open("assets/pioneer.png").getbbox() -> (22, 204, 1581, 707) as
# (left, top, right, bottom)) is only 1559x503 -> crop to that so the floating
# window isn't padded with dead transparent space.
FACE_BBOX = (22, 204, 1559, 503)                # (left, top, w, h) for QPixmap.copy()
BASE_W, BASE_H = FACE_BBOX[2], FACE_BBOX[3]      # cropped faceplate size (was 1600, 893)

# clips in nav order; presets 1-6 map to the first six
CLIPS = ["movie1.lkd", "movie2.lkd", "movie3.lkd", "movie6.lkd",
         "movie7.lkd", "movie8_f.lkd", "movie9_f.lkd", "movie10_f.lkd"]

# geometry as fractions of the faceplate (so it scales with the window) —
# recomputed for the cropped FACE_BBOX (was fractions of the full 1600x893 image)
G = {
    "screen": (0.2250, 0.3117, 0.4608, 0.3213),  # left, top, w, h
    "lknob":  (0.1430, 0.4990),                   # center x,y
    "lknob_disc_x": 0.0475, "lknob_disc_y": 0.0822,  # knob art crop radii (x/y independent — see _build_knob)
    "lknob_hit": 0.0629,                          # hit-test radius (of width)
    "rknob":  (0.8743, 0.5089), "rknob_hit": 0.0657,
    "presets_y": 0.8070,
    "presets_x": [0.3194, 0.3903, 0.4606, 0.5309, 0.6017, 0.6735],
    "preset_hw": 0.0339, "preset_hh": 0.0533,     # half-width/height of hitbox
    "func": (0.7525, 0.6082), "func_hw": 0.0308, "func_hh": 0.0497,
    "esc":  (0.9480, 0.2797), "esc_hw": 0.0390, "esc_hh": 0.0675,
}

FPS_MIN, FPS_MAX = 4, 30
EDGE = 8  # px border for resize grab

# ---- control-map overlay ----------------------------------------------------
# Shown automatically on launch and re-openable with F1. Two side-by-side
# groups: what the head-unit's own buttons do, and the keyboard equivalents.
HELP_FULL = (
    ("HEAD UNIT", (("PRESET 1-6",  "switch animation"),
                   ("LEFT KNOB",   "drag / scroll = volume"),
                   ("NAV ← →", "prev / next clip"),
                   ("NAV ↑ ↓", "animation speed"),
                   ("FUNC",        "toggle retro glow"),
                   ("ESC",         "quit"),
                   ("BODY / EDGE", "drag to move / resize"))),
    ("KEYBOARD",  ((" ← →", "prev / next clip"),
                   (" ↑ ↓", "animation speed"),
                   ("F",       "toggle retro glow"),
                   ("1 - 6",   "preset / animation"),
                   ("F1",      "show this help"),
                   ("Esc",     "quit"))),
)
# Degraded layout: near the ~519x167 minimum window size the full table can't
# be drawn legibly, so we drop to a keys-only shortlist (no group header) and
# point the user at the faceplate. _draw_help picks whichever one fits.
HELP_COMPACT = (
    ("", (("1 - 6", "preset"), (" ← →", "clip"),
          (" ↑ ↓", "speed"), ("F", "glow"),
          ("F1", "help"), ("Esc", "quit"))),
)
HELP_LAYOUTS = ((HELP_FULL, "CAROZERRA — CONTROLS",
                 "click anywhere or wait — F1 reopens"),
                (HELP_COMPACT, "CONTROLS",
                 "faceplate buttons work too · F1 reopens"))
HELP_FS = 20.0    # row font size in faceplate units, scaled by fit()'s factor
HELP_MS = 5000    # ~5s: two unhurried passes over a 13-row table, then gone
HELP_OEL = QColor(18, 224, 255)   # the OEL cyan the web player uses (#12e0ff)
HELP_EDGE = QColor(18, 224, 255, 150)     # panel border
HELP_HDR = QColor(18, 224, 255, 165)      # group headers, dimmed
HELP_BG = QColor(8, 12, 16, 234)          # backdrop, opaque enough to read over
HELP_TXT = QColor(214, 226, 234)          # action descriptions
HELP_DIM = QColor(140, 162, 176)          # footer hint

# ---- system volume (PipeWire wpctl, fallback PulseAudio pactl) --------------
_WPCTL = shutil.which("wpctl")
_PACTL = shutil.which("pactl")

def get_volume():
    try:
        if _WPCTL:
            out = subprocess.check_output([_WPCTL, "get-volume",
                                           "@DEFAULT_AUDIO_SINK@"], text=True)
            return int(round(float(re.search(r"([0-9.]+)", out).group(1)) * 100))
        if _PACTL:
            out = subprocess.check_output([_PACTL, "get-sink-volume",
                                           "@DEFAULT_SINK@"], text=True)
            return int(re.search(r"(\d+)%", out).group(1))
    except Exception:
        pass
    return 50

def set_volume(pct):
    pct = max(0, min(100, int(pct)))
    try:
        if _WPCTL:
            subprocess.run([_WPCTL, "set-volume", "@DEFAULT_AUDIO_SINK@",
                            f"{pct/100:.2f}"], check=False)
        elif _PACTL:
            subprocess.run([_PACTL, "set-sink-volume", "@DEFAULT_SINK@",
                            f"{pct}%"], check=False)
    except Exception:
        pass


def pil_to_qimage(pil):
    pil = pil.convert("RGB")
    data = pil.tobytes("raw", "RGB")
    return QImage(data, pil.width, pil.height, pil.width * 3,
                  QImage.Format.Format_RGB888).copy()


class Stereo(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Carozerra")
        self.setWindowFlag(Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setMinimumSize(BASE_W // 3, BASE_H // 3)
        self.resize(BASE_W * 3 // 5, BASE_H * 3 // 5)
        self.setMouseTracking(True)

        self.face = QPixmap(os.path.join(APP_DIR, "assets", "pioneer.png"))
        self.face = self.face.copy(*FACE_BBOX)   # crop dead transparent margin; source file untouched
        self.clips = [[pil_to_qimage(f) for f in decode_lkd(
            os.path.join(APP_DIR, "assets", "clips", name))[0]] for name in CLIPS]
        self._build_knob()

        self.clip = 0
        self.frame = 0
        self.fps = 16
        self.glow = True
        self.vol = get_volume()
        self._drag_knob = False

        self.timer = QTimer(self)
        self.timer.timeout.connect(self._advance)
        self.timer.start(1000 // self.fps)

        # a member single-shot QTimer rather than a bare QTimer.singleShot: it
        # can be restarted/cancelled, so a stale callback can't hide an overlay
        # the user just re-opened with F1
        self.help_on = False
        self._help_timer = QTimer(self)
        self._help_timer.setSingleShot(True)
        self._help_timer.timeout.connect(self._hide_help)
        self._show_help()                       # greet with the control map

    # --- assets -------------------------------------------------------------
    def _build_knob(self):
        cx, cy = G["lknob"]
        rx, ry = G["lknob_disc_x"], G["lknob_disc_y"]
        px = self.face.copy(int((cx - rx) * BASE_W), int((cy - ry) * BASE_H),
                            int(2 * rx * BASE_W), int(2 * ry * BASE_H))
        masked = QPixmap(px.size()); masked.fill(Qt.GlobalColor.transparent)
        p = QPainter(masked)
        path = QPainterPath(); path.addEllipse(0, 0, px.width(), px.height())
        p.setClipPath(path); p.drawPixmap(0, 0, px); p.end()
        self.knob = masked

    # --- coordinate mapping (contain-fit, keeps aspect, centered) -----------
    def fit(self):
        w, h = self.width(), self.height()
        s = min(w / BASE_W, h / BASE_H)
        fw, fh = BASE_W * s, BASE_H * s
        return QRectF((w - fw) / 2, (h - fh) / 2, fw, fh), s

    def to_base(self, pos):
        fr, s = self.fit()
        return (pos.x() - fr.x()) / s, (pos.y() - fr.y()) / s

    # --- painting -----------------------------------------------------------
    def paintEvent(self, _):
        fr, s = self.fit()
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        p.drawPixmap(fr, self.face, QRectF(self.face.rect()))

        sx, sy, sw, sh = G["screen"]
        scr = QRectF(fr.x() + sx * fr.width(), fr.y() + sy * fr.height(),
                     sw * fr.width(), sh * fr.height())
        self._draw_screen(p, scr)
        self._draw_knob(p, fr)
        if self.help_on:
            self._draw_help(p, fr, s)

    def _draw_screen(self, p, rect):
        img = self.clips[self.clip][self.frame]
        p.save(); p.setClipRect(rect)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, False)
        p.drawImage(rect, img)                       # crisp OEL pixels
        if self.glow:
            p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
            blur = img.scaled(img.width() // 3, img.height() // 3,
                              Qt.AspectRatioMode.IgnoreAspectRatio,
                              Qt.TransformationMode.SmoothTransformation)
            blur = blur.scaled(int(rect.width()), int(rect.height()),
                               Qt.AspectRatioMode.IgnoreAspectRatio,
                               Qt.TransformationMode.SmoothTransformation)
            p.setCompositionMode(QPainter.CompositionMode.CompositionMode_Screen)
            for op in (0.85, 0.5):
                p.setOpacity(op); p.drawImage(rect, blur)
            p.setOpacity(1.0)
            p.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)
            step = max(2.0, rect.height() / 64.0)     # ~1 line per source row
            y = rect.top()
            while y < rect.bottom():
                p.fillRect(QRectF(rect.left(), y, rect.width(), 1),
                           QColor(0, 0, 0, 70))
                y += step
        p.restore()

    def _draw_knob(self, p, fr):
        cx = fr.x() + G["lknob"][0] * fr.width()
        cy = fr.y() + G["lknob"][1] * fr.height()
        R = G["lknob_disc_x"] * fr.width()
        ang = -140 + (self.vol / 100.0) * 280
        p.save(); p.translate(cx, cy); p.rotate(ang)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        p.drawPixmap(QRectF(-R, -R, 2 * R, 2 * R), self.knob,
                     QRectF(self.knob.rect()))
        p.restore()

    # --- help overlay -------------------------------------------------------
    def _help_layout(self, groups, title, foot, s):
        """Measure the panel from the actual font metrics (not fixed fractions)
        so it can never clip its own text. Row size tracks fit()'s scale, with
        a 9px floor so it stays readable at the minimum window size."""
        fs = max(9.0, HELP_FS * s)
        f_row = QFont(self.font()); f_row.setPixelSize(int(round(fs)))
        f_key = QFont(f_row); f_key.setBold(True)
        f_ttl = QFont(f_key); f_ttl.setPixelSize(int(round(fs * 1.15)))
        f_ft = QFont(f_row); f_ft.setPixelSize(max(8, int(round(fs * 0.85))))
        mk, md = QFontMetricsF(f_key), QFontMetricsF(f_row)
        mt, mf = QFontMetricsF(f_ttl), QFontMetricsF(f_ft)

        pad, gkd, gcol, rowh = fs * 1.1, fs * 0.9, fs * 2.0, fs * 1.45
        cols, rows = [], 0
        for name, items in groups:
            kw = max(mk.horizontalAdvance(k) for k, _ in items)
            dw = max(md.horizontalAdvance(d) for _, d in items)
            cols.append((name, items, kw,
                         max(kw + gkd + dw, mk.horizontalAdvance(name))))
            rows = max(rows, len(items))
        hdr = rowh if any(c[0] for c in cols) else 0.0   # group headers?
        inner_w = max(sum(c[3] for c in cols) + gcol * (len(cols) - 1),
                      mt.horizontalAdvance(title), mf.horizontalAdvance(foot))
        inner_h = (mt.height() + fs * .6 + hdr + rows * rowh
                   + fs * .6 + mf.height())
        return dict(fs=fs, row=f_row, key=f_key, ttl=f_ttl, ft=f_ft, md=md,
                    mt=mt, mf=mf, pad=pad, gkd=gkd, gcol=gcol, rowh=rowh,
                    hdr=hdr, cols=cols, rows=rows, title=title, foot=foot,
                    w=inner_w + 2 * pad, h=inner_h + 2 * pad)

    def _draw_help(self, p, fr, s):
        """Dark rounded panel over the faceplate listing every control."""
        # take the first layout that fits with room to breathe. Height is the
        # binding constraint (the font hits its 9px floor before the panel
        # stops shrinking), so it gets the tighter budget — at .92 the full
        # table technically "fits" a 543x175 window while looking edge-to-edge.
        for groups, ttl, foot in HELP_LAYOUTS:                # full, then compact
            L = self._help_layout(groups, ttl, foot, s)
            if L["w"] <= .92 * self.width() and L["h"] <= .78 * self.height():
                break                          # else fall through to compact

        p.save()
        p.setOpacity(1.0)
        p.setBrush(Qt.BrushStyle.NoBrush)
        box = QRectF(fr.center().x() - L["w"] / 2, fr.center().y() - L["h"] / 2,
                     L["w"], L["h"])
        path = QPainterPath(); path.addRoundedRect(box, L["fs"], L["fs"])
        p.fillPath(path, HELP_BG)
        pen = QPen(HELP_EDGE); pen.setWidthF(max(1.0, s * 1.6))
        p.setPen(pen); p.drawPath(path)

        asc, x = L["md"].ascent(), box.x() + L["pad"]
        y = box.y() + L["pad"]
        p.setFont(L["ttl"]); p.setPen(HELP_OEL)
        p.drawText(QPointF(x, y + L["mt"].ascent()), L["title"])
        y += L["mt"].height() + L["fs"] * .6

        gx = x
        for name, items, kw, gw in L["cols"]:
            ry = y
            if L["hdr"]:
                p.setFont(L["key"]); p.setPen(HELP_HDR)
                p.drawText(QPointF(gx, ry + asc), name)
                ry += L["hdr"]
            for k, d in items:
                p.setFont(L["key"]); p.setPen(HELP_OEL)
                p.drawText(QPointF(gx, ry + asc), k)
                p.setFont(L["row"]); p.setPen(HELP_TXT)
                p.drawText(QPointF(gx + kw + L["gkd"], ry + asc), d)
                ry += L["rowh"]
            gx += gw + L["gcol"]

        y += L["hdr"] + L["rows"] * L["rowh"] + L["fs"] * .6
        p.setFont(L["ft"]); p.setPen(HELP_DIM)
        p.drawText(QPointF(x, y + L["mf"].ascent()), L["foot"])
        p.restore()

    def _show_help(self):
        self.help_on = True
        self._help_timer.start(HELP_MS)      # restarts the countdown if running
        self.update()

    def _hide_help(self):
        if self.help_on:
            self.help_on = False
            self._help_timer.stop()
            self.update()

    # --- animation ----------------------------------------------------------
    def _advance(self):
        self.frame = (self.frame + 1) % len(self.clips[self.clip])
        self.update()

    def _set_fps(self, f):
        self.fps = max(FPS_MIN, min(FPS_MAX, f))
        self.timer.setInterval(1000 // self.fps)

    def _set_clip(self, i):
        self.clip = i % len(self.clips)
        self.frame = 0; self.update()

    def _set_vol(self, v):
        v = max(0, min(100, int(round(v))))
        if v != self.vol:
            self.vol = v; set_volume(v); self.update()

    # --- input --------------------------------------------------------------
    def _edges(self, pos):
        m = 0
        if pos.x() < EDGE: m |= Qt.Edge.LeftEdge.value
        if pos.x() > self.width() - EDGE: m |= Qt.Edge.RightEdge.value
        if pos.y() < EDGE: m |= Qt.Edge.TopEdge.value
        if pos.y() > self.height() - EDGE: m |= Qt.Edge.BottomEdge.value
        return m

    def _hit(self, bx, by):
        """Map a faceplate-space point to a control action (pure, testable)."""
        lx, ly = G["lknob"][0] * BASE_W, G["lknob"][1] * BASE_H
        if math.hypot(bx - lx, by - ly) <= G["lknob_hit"] * BASE_W:
            return ("knob", None)
        rx, ry = G["rknob"][0] * BASE_W, G["rknob"][1] * BASE_H
        if math.hypot(bx - rx, by - ry) <= G["rknob_hit"] * BASE_W:
            a = math.degrees(math.atan2(by - ry, bx - rx))
            if -45 <= a < 45:          return ("clip", +1)    # right
            if 45 <= a < 135:          return ("fps", -2)     # down
            if a >= 135 or a < -135:   return ("clip", -1)    # left
            return ("fps", +2)                                 # up
        py = G["presets_y"] * BASE_H
        for i, fx in enumerate(G["presets_x"]):
            if abs(bx - fx * BASE_W) <= G["preset_hw"] * BASE_W and \
               abs(by - py) <= G["preset_hh"] * BASE_H:
                return ("preset", i)
        fx, fy = G["func"][0] * BASE_W, G["func"][1] * BASE_H
        if abs(bx - fx) <= G["func_hw"] * BASE_W and abs(by - fy) <= G["func_hh"] * BASE_H:
            return ("glow", None)
        ex, ey = G["esc"][0] * BASE_W, G["esc"][1] * BASE_H
        if abs(bx - ex) <= G["esc_hw"] * BASE_W and abs(by - ey) <= G["esc_hh"] * BASE_H:
            return ("quit", None)
        return (None, None)

    def mousePressEvent(self, e):
        # while the help panel is up ANY click only dismisses it — never also
        # fires the control underneath (else "click to dismiss" could hit ESC)
        if self.help_on:
            self._hide_help(); return
        if e.button() != Qt.MouseButton.LeftButton:
            return
        mask = self._edges(e.position())
        if mask:
            self.windowHandle().startSystemResize(Qt.Edge(mask)); return

        bx, by = self.to_base(e.position())
        kind, data = self._hit(bx, by)
        if kind == "knob":
            self._drag_knob = True
            lx, ly = G["lknob"][0] * BASE_W, G["lknob"][1] * BASE_H
            self._drag_ang = math.degrees(math.atan2(by - ly, bx - lx))
            self._drag_vol = self.vol
        elif kind == "clip":   self._set_clip(self.clip + data)
        elif kind == "fps":    self._set_fps(self.fps + data)
        elif kind == "preset": self._set_clip(data)
        elif kind == "glow":   self.glow = not self.glow; self.update()
        elif kind == "quit":   self.close()
        else:                  self.windowHandle().startSystemMove()

    def mouseMoveEvent(self, e):
        if self._drag_knob:
            lx, ly = G["lknob"][0] * BASE_W, G["lknob"][1] * BASE_H
            bx, by = self.to_base(e.position())
            a = math.degrees(math.atan2(by - ly, bx - lx))
            d = a - self._drag_ang
            if d > 180: d -= 360
            if d < -180: d += 360
            self._set_vol(self._drag_vol + d / 280.0 * 100)

    def mouseReleaseEvent(self, _):
        self._drag_knob = False

    def wheelEvent(self, e):
        self._hide_help()
        self._set_vol(self.vol + (3 if e.angleDelta().y() > 0 else -3))

    def keyPressEvent(self, e):
        k = e.key()
        if k == Qt.Key.Key_F1:          # toggle, so it never feels stuck
            self._show_help() if not self.help_on else self._hide_help()
            return
        # Esc closes the overlay before it quits: dismissing a popup with Esc is
        # the near-universal instinct, and the costs are lopsided — an extra
        # keypress if you did mean quit, versus losing the app if you didn't.
        if k == Qt.Key.Key_Escape and self.help_on:
            self._hide_help()
            return
        # otherwise a keypress is unambiguous (unlike a click), so it dismisses
        # the help AND still performs its action
        self._hide_help()
        if k == Qt.Key.Key_Escape:      self.close()
        elif k == Qt.Key.Key_Right:     self._set_clip(self.clip + 1)
        elif k == Qt.Key.Key_Left:      self._set_clip(self.clip - 1)
        elif k == Qt.Key.Key_Up:        self._set_fps(self.fps + 2)
        elif k == Qt.Key.Key_Down:      self._set_fps(self.fps - 2)
        elif k == Qt.Key.Key_F:         self.glow = not self.glow; self.update()
        elif Qt.Key.Key_1 <= k <= Qt.Key.Key_6: self._set_clip(k - Qt.Key.Key_1)


def _selftest(out_path):
    """Render one frame to `out_path`; return an exit code. Used by CI.

    A frozen build's failure modes are quiet: a missing Qt platform plugin, an
    assets/ tree that never got unpacked next to sys._MEIPASS, a paint path
    that draws nothing. "The process stayed alive" catches none of those, so
    grab the widget and check the pixels actually vary — that proves the
    faceplate pixmap and a decoded clip frame both rendered.
    """
    w = Stereo()
    w.resize(BASE_W // 2, BASE_H // 2)
    w.show()
    QApplication.processEvents()
    img = w.grab().toImage()
    if img.isNull():
        print("selftest: grab() returned a null image", file=sys.stderr)
        return 1
    # every 7th pixel on both axes — enough to tell a drawn faceplate from a
    # flat fill without walking ~350k pixels
    seen = {img.pixel(x, y)
            for y in range(0, img.height(), 7)
            for x in range(0, img.width(), 7)}
    img.save(out_path)
    print(f"selftest: {img.width()}x{img.height()}, {len(seen)} distinct "
          f"sampled colours -> {out_path}")
    if len(seen) < 32:
        print("selftest: looks blank (too few distinct colours)", file=sys.stderr)
        return 1
    return 0


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Carozerra")
    argv = sys.argv[1:]
    if "--selftest" in argv:
        rest = [a for a in argv if a != "--selftest"]
        sys.exit(_selftest(rest[0] if rest else "selftest.png"))
    w = Stereo(); w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
