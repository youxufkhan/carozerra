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

### Changed
- Desktop app: window now crops tightly to the faceplate's visible bounds instead of including the surrounding transparent margin, for a cleaner floating widget.

## [1.1.0] - Initial Public Release
- Released Web Player with `.lkd` decoding capabilities via `DecompressionStream` and Canvas.
- Released Linux Desktop visualizer (PyQt6) with transparent floating faceplate and `wpctl` volume integration.
- Released `decode.py` batch converter with GIF/MP4 export and retro phosphor glow effects.
- Added 8 pre-loaded classic animations to the assets.
