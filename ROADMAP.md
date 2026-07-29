# Carozerra Roadmap

This document outlines the planned features, requested enhancements, and future direction for the Carozerra project, ordered roughly by priority and effort.

## 🟢 High Priority / Quick Wins

*   **Windows Build**
    *   *Details:* Package and release the desktop visualizer for Windows users, allowing the floating widget to run natively on Windows desktops.

## 🟡 Medium Priority / Platform Expansion

*   **Multiple Head-Unit Skins**
    *   *Details:* Support switching the faceplate/display style — e.g. a Kenwood/Alpine green-VFD skin, an amber hi-fi VFD skin, a modern-LCD skin — alongside the existing Pioneer OEL faceplate. Motivated by a similar community gallery project ([jonaudi.xyz/units](https://www.jonaudi.xyz/units/)) and by the newly-added "Color" clip category (genuinely full-color content currently shown through the cyan-OEL cosmetic overlay it wasn't designed for) — a natural first real use case for a second skin.
*   **Keyboard Arrow-Key Gallery Navigation**
    *   *Details:* Add ←→ (prev/next animation) and ↑↓ (prev/next unit skin, once multiple skins exist) keyboard navigation to the web player, matching the pattern used by jonaudi.xyz/units. Cheap to add once multiple skins exist.
*   **Additional Linux Distributions**
    *   *Details:* Expand Linux packaging beyond just the current Debian (`.deb`) releases. Look into supporting Arch, RPM-based distros, or universal formats like Flatpak/AppImage.
*   **Reverse-Engineer the `.lka` Container Format**
    *   *Details:* Community-contributed discs also included `ent_disp.lka` and `Default_all.LKA` — neither matches the `zLKD` magic bytes our decoder expects, and their format is currently unknown. Needs its own investigation before any content inside them can be extracted.

## 🔴 Long-Term / New Ecosystems

*   **Android App for Head Units**
    *   *Details:* Build a native Android application designed specifically to run on modern Android-based aftermarket head units, bringing the retro Pioneer aesthetic back to the dashboard.
*   **Android Live Wallpaper & Widget**
    *   *Details:* Develop an Android animated background (Live Wallpaper) that plays the `.lkd` clips, and a homescreen media control widget styled like the classic head unit.
