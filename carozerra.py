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

Native PyQt6 — reuses decode_lkd() from decode.py for decoding only.
"""
import math
import os
import re
import shutil
import subprocess
import sys

from PyQt6.QtCore import Qt, QRectF, QTimer, QPointF
from PyQt6.QtGui import (QImage, QPixmap, QPainter, QColor, QPainterPath,
                         QGuiApplication)
from PyQt6.QtWidgets import QApplication, QWidget

from decode import decode_lkd

APP_DIR = os.path.dirname(os.path.abspath(__file__))
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
        self._set_vol(self.vol + (3 if e.angleDelta().y() > 0 else -3))

    def keyPressEvent(self, e):
        k = e.key()
        if k == Qt.Key.Key_Escape:      self.close()
        elif k == Qt.Key.Key_Right:     self._set_clip(self.clip + 1)
        elif k == Qt.Key.Key_Left:      self._set_clip(self.clip - 1)
        elif k == Qt.Key.Key_Up:        self._set_fps(self.fps + 2)
        elif k == Qt.Key.Key_Down:      self._set_fps(self.fps - 2)
        elif k == Qt.Key.Key_F:         self.glow = not self.glow; self.update()
        elif Qt.Key.Key_1 <= k <= Qt.Key.Key_6: self._set_clip(k - Qt.Key.Key_1)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Carozerra")
    w = Stereo(); w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
