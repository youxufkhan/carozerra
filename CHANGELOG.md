# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Created ROADMAP.md to track future features and community requests.
- Added links to Roadmap and Changelog in the README.
- Web player: GIF and WebM export, baking the "retro glow" phosphor look (blur + screen-blend + scanlines, ported from `decode.py`'s `apply_glow`) into the exported pixels/video.
- Web player: 75 new community-contributed `.lkd` animations (movies, backgrounds, stills, level meters, and a full-color category) integrated from community disc dumps, bringing the built-in library to 83 clips, organized into categorized, labeled sections in the clip picker.
- Web player: the clip picker now shows animated preview thumbnails instead of filenames — each tile renders a poster frame decoded client-side, and plays the animation in place on hover.
- Web player: clip canvas now preserves native aspect ratio (`object-fit: contain`) instead of stretching to fill the OEL screen — needed for the new content's varied resolutions, and fixes a slight stretch present on the original 8 clips too.
- Web player: `←`/`→` browse the gallery, wrapping at both ends and scrolling the selection into view. The Speed and Frame sliders keep their own native arrow-key behaviour when focused.
- Web player: a Changelog link in the footer opens this file in a modal, fetched at click time so it can't drift from the repo.
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
