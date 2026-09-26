# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Packaging: dropped the unused `python3-numpy` dependency from the `.deb` and the run-from-source instructions. Nothing imports it.

## [1.3.0-beta.2] - 2026-09-26

**Android app, beta.** First Android release — a `carozerra_1.3.0-beta.2_android.apk`
attached alongside the `.deb` and `.exe`. Sideload only, debug-signed, and not
yet run on a real head unit, so it's marked prerelease.

### Added
- Android app for head units (`android/`): a native Kotlin app for Android-based aftermarket head units, running the faceplate fullscreen instead of floating on a desktop. 17 live faceplate controls, each mapped to the function the DEH-P7600MP owner's manual prints against it — TA, volume (+ blackout), DISPLAY, TEXT, FUNCTION, the AUDIO level meters, the nav knob (permission-free media-key transport: skip, speed, play/pause), OPEN, BAND, ENTERTAINMENT, EQ-EX, Presets 1–6, EQ, and SOURCE. DISPLAY's clock/metadata, the scrolling TEXT line, and the level meters are composited as OEL overlays drawn straight into the faceplate's 256×64 screen space, alongside the playing clip; the control-map/about card and the clip gallery (all 83 clips) are separate overlay views on top. Display text uses the Smallest Pixel-7 pixel font, sized in whole OEL pixels on black cut-outs. On first launch the app explains why it wants microphone access (the level meters read on-device playback, which Android gates behind the same permission) before Android's own prompt. Level meters report `NO SIGNAL` and hide the meter zone instead of faking a reading when output-mix capture isn't available.
- Web player: a "Download for Android" button, linking to the newest release that has an APK. Clicks are counted as a GA4 `android_download` event.
- CI: `.github/workflows/android-check.yml` builds, unit-tests, and instrumented-tests the Android app on every push/PR touching `android/**` or `assets/**`; `release.yml` now attaches `carozerra_<version>_android.apk` to tagged releases alongside the `.deb` and `.exe`.

### Known limitations
- The AUDIO level meters don't work on the one real phone tested so far (Redmi Note 14, Android 16, playing YouTube Music): `Visualizer` gets no signal, so they show `NO SIGNAL`. Moving to Android's playback-capture API is planned.
- Tested on an Android emulator and one phone, not yet on a real head unit.
- Debug-signed: uninstall the old version before installing a newer build.

## [1.3.0-beta.1] - 2026-09-15

**Windows build, beta.** First Windows release — a `carozerra.exe` attached
alongside the `.deb`. Marked prerelease because of two known gaps (below):
grab it and try it, but the desktop app's daily driver is still the `.deb`.

### Added
- Windows build: `packaging/carozerra.spec` builds a single `carozerra.exe` via PyInstaller, bundling only the clips `carozerra.py`'s `CLIPS` actually opens — the same payload rule the `.deb` follows. 46.7 MB, unsigned (see Known limitations in the README).
- `packaging/make-ico.py` generates the Windows icon from `assets/pioneer.png` at build time (letterboxed onto a transparent square) rather than committing a binary that could drift from the faceplate art.
- Desktop app: `carozerra.py --selftest <out.png>` renders one frame and exits, so a build can be verified without a human at a screen. Used by CI and by `release.yml`.
- CI: `windows-check.yml` builds the `.exe` on `windows-latest` on every relevant push/PR, then runs `packaging/smoke-windows.ps1` — a `--selftest` render followed by a real interactive launch that confirms the process survives, owns a top-level window, and screenshots the desktop. `release.yml` runs the same build+smoke before attaching the exe to a release.
- Manually verified on a real Windows machine (CI only covers `windows-latest`'s Windows Server image).

### Fixed
- Desktop app: assets are now located via `sys._MEIPASS` when running as a frozen build, so a packaged `.exe` finds `assets/` instead of looking beside a path that doesn't exist at run time. No effect when running from source or from the `.deb`.

### Known limitations
- The volume knob does nothing on Windows — it drives `wpctl`/`pactl`, neither of which exists there, so it turns without changing system volume.
- The `.exe` is unsigned. Expect a SmartScreen warning on first launch, and possible antivirus false positives (common for unsigned PyInstaller output).

## [1.2.0] - 2026-07-30

### Added
- The project is now MIT-licensed: added LICENSE, plus a license badge and section in the README.
- Web player: page-view analytics via Google Analytics (gtag.js).
- Web player: footer with a link to the GitHub repo and author credit.
- Created ROADMAP.md to track future features and community requests.
- Added links to Roadmap and Changelog in the README.
- Web player: GIF and WebM export, baking the "retro glow" phosphor look (blur + screen-blend + scanlines, ported from `decode.py`'s `apply_glow`) into the exported pixels/video.
- Web player: 75 new community-contributed `.lkd` animations (movies, backgrounds, stills, level meters, and a full-color category) integrated from community disc dumps, bringing the built-in library to 83 clips, organized into categorized, labeled sections in the clip picker.
- Web player: the clip picker now shows animated preview thumbnails instead of filenames — each tile renders a poster frame decoded client-side, and plays the animation in place on hover.
- Web player: clip canvas now preserves native aspect ratio (`object-fit: contain`) instead of stretching to fill the OEL screen — needed for the new content's varied resolutions, and fixes a slight stretch present on the original 8 clips too.
- Web player: `←`/`→` browse the gallery, wrapping at both ends and scrolling the selection into view. The Speed and Frame sliders keep their own native arrow-key behaviour when focused.
- Web player: a Changelog link in the footer opens this file in a modal, fetched at click time so it can't drift from the repo.
- Web player: vendored `web/gifenc.esm.js` (mattdesl/gifenc, MIT) to encode GIFs client-side without a build step.
- Desktop app: the control map is shown on the faceplate for a few seconds at launch and reopens with `F1`, covering both the head-unit's buttons and the keyboard shortcuts.

### Changed
- Desktop app: window now crops tightly to the faceplate's visible bounds instead of including the surrounding transparent margin, for a cleaner floating widget.
- Web player: clips are now fetched as their thumbnails scroll into view rather than all at once on page load — first load drops from ~12.8 MB across 89 requests to ~2.7 MB, and the gallery is interactive immediately.
- Packaging: the `.deb` now ships only the clips the desktop app actually opens instead of the whole `assets/` tree, taking it from 14.4 MB back to 2.7 MB. The list is read out of `carozerra.py` at build time so it can't drift.
- Desktop app: `Esc` now closes the control map when it's open instead of quitting — an extra keypress if you did mean to quit, versus losing the app if you didn't.

## [1.1.0] - Initial Public Release
- Released Web Player with `.lkd` decoding capabilities via `DecompressionStream` and Canvas.
- Released Linux Desktop visualizer (PyQt6) with transparent floating faceplate and `wpctl` volume integration.
- Released `decode.py` batch converter with GIF/MP4 export and retro phosphor glow effects.
- Added 8 pre-loaded classic animations to the assets.
