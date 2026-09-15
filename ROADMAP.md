# Carozerra Roadmap

This document outlines the planned features, requested enhancements, and future direction for the Carozerra project, ordered roughly by priority and effort.

## 🟢 High Priority / Quick Wins

*   **Windows Build** — *build plumbing done, not released*
    *   *Details:* Package and release the desktop visualizer for Windows users, allowing the floating widget to run natively on Windows desktops.
    *   *Done:* `packaging/carozerra.spec` builds a single 46.7 MB `carozerra.exe` (PyInstaller, onefile, no console), with the icon generated from the faceplate art at build time. `.github/workflows/windows-check.yml` builds it on every relevant change and smoke-tests it on `windows-latest`: a `carozerra.exe --selftest` render, then a real launch that has to survive, own a top-level window, and be screenshotted. The green run's screenshot confirms DWM composites the frameless translucent window correctly and a clip plays from inside the frozen exe.
    *   *Open — decide before a release:* the **volume knob does nothing on Windows.** It drives `wpctl`/`pactl`, neither of which exists there, so `get_volume()` returns a fixed 50 and the knob turns while lying. Either wire it to `pycaw` (~0.5–1 day, real volume control) or disable the knob's volume role on Windows (~1 hour, honest but a dead control on the faceplate).
    *   *Open — manual pass:* CI only ever runs it on Windows Server 2025 at 1024x768. Edge-drag resize and HiDPI scaling (125% / 150%) still need testing on a real Windows 10/11 desktop.
    *   *Open — release wiring:* `release.yml` builds and attaches the `.deb` only. Attaching the `.exe` also means deciding how to handle an **unsigned** binary — SmartScreen will warn, and PyInstaller output draws antivirus false positives (UPX is deliberately off in the spec for that reason). Likely ships as a labelled beta prerelease first, with the warning documented.

## 🟡 Medium Priority / Platform Expansion

*   **Multiple Head-Unit Skins**
    *   *Details:* Support switching the faceplate/display style — e.g. a Kenwood/Alpine green-VFD skin, an amber hi-fi VFD skin, a modern-LCD skin — alongside the existing Pioneer OEL faceplate. Motivated by a similar community gallery project ([jonaudi.xyz/units](https://www.jonaudi.xyz/units/)) and by the "Color" clip category (genuinely full-color content currently shown through the cyan-OEL cosmetic overlay it wasn't designed for) — a natural first real use case for a second skin. The web player's ↑↓ keys are reserved for switching between skins; ←→ already browse animations.
*   **Additional Linux Distributions**
    *   *Details:* Expand Linux packaging beyond just the current Debian (`.deb`) releases. Look into supporting Arch, RPM-based distros, or universal formats like Flatpak/AppImage.
*   **Reverse-Engineer the `.lka` Container Format**
    *   *Details:* Community-contributed discs also included `ent_disp.lka` and `Default_all.LKA` — neither matches the `zLKD` magic bytes our decoder expects, and their format is currently unknown. Needs its own investigation before any content inside them can be extracted.

## 🔴 Long-Term / New Ecosystems

*   **Android App for Head Units**
    *   *Details:* Build a native Android application designed specifically to run on modern Android-based aftermarket head units, bringing the retro Pioneer aesthetic back to the dashboard.
*   **Android Live Wallpaper & Widget**
    *   *Details:* Develop an Android animated background (Live Wallpaper) that plays the `.lkd` clips, and a homescreen media control widget styled like the classic head unit.
