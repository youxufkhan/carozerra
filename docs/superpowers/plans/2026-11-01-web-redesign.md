# Carozerra web redesign implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement these tasks inline, followed by a fresh whole-change review.

**Goal:** Deliver the approved Listening room design as a responsive static player.

**Architecture:** Retain the current decoder, lazy clip cache, canvas playback, export routines, and changelog renderer. Extract page styling into `web/styles.css` and module logic into `web/player.js`, preserving element IDs where existing code uses them. Add categorized browsing and switch the existing canvas container between full-stereo and standalone display through CSS, using one playback loop.

**Tech stack:** HTML, CSS, native JavaScript, locally hosted IBM Plex Sans WOFF2; Python Playwright for browser verification only.

**Spec:** `docs/superpowers/specs/2026-11-01-web-redesign-design.md` (approved in chat).

## Global constraints

- No runtime framework, backend, animation library, or generated artwork.
- Self-host regular and semibold fonts with license; combined transfer below 100 KB.
- Responsive at 320px, 390px, 768px, and wide desktop; minimum 44px touch targets.
- Preserve decoding, glow, speed/scrub ranges, GIF/WebM exports, custom files, changelog, links, and analytics.
- Never eagerly fetch hidden clip categories; retain race protection and one playback loop.
- Plain copy, visible focus, reduced-motion previews, keyboard-safe controls.

## Review focus

- Fast clip switches and malformed custom files must preserve the last successful playback state.
- Export must block all ways to swap the backing strip, including custom files and keyboard.
- Category browsing must not change playback until a tile is selected.
- Responsive mode changes must preserve the current frame and playback state.
- Changelog/menu/settings keyboard interactions must not navigate clips behind the control.

## Files

- Modify `web/index.html`: semantic page structure, native disclosure panels/dialog, metadata, local assets.
- Create `web/styles.css`: tokens, type, player geometry, responsive layout, focus/motion styles.
- Create `web/player.js`: existing player module plus category navigation, safe controls and status.
- Create `web/fonts/`: two compressed fonts and their license.
- Create `tests/web_player.py`: browser integration checks, screenshot output and baseline request measurement; no production dependency.
- Modify `.gitignore`: ignore local companion/test artifacts.
- Modify `README.md`: updated page usage and verification command if needed.

## Task 1: Responsive presentation and category navigation

Interfaces: preserve `#screen`, `#glow`, `#oel`, `#play`, `#fps`, `#scrub`, `#fx`, export IDs, and existing loaded/pending clip cache. `selectClip(name)` remains the shared selection path. Add `showCategory(category)` to redraw only the browsed category and `navigateClip(delta)` to navigate global order and reveal the destination category.

- [x] Write browser checks asserting category filters exist, changing categories does not switch the player, choosing a tile does, and mobile display switching preserves playback/frame.
- [x] Run against the original page and observe failures for missing category filters/mobile toggle. Record baseline initial clip transfer at the same desktop viewport.
- [x] Implement semantic structure, token CSS, locally hosted fonts, mobile display toggle, gallery, controls, and accessible project information. Preserve initial clip loading and lazy decoding.
- [x] Run browser checks; capture desktop/mobile screenshots and inspect them. Correct layout against the approved board.

## Task 2: Interaction safeguards and full verification

Interfaces: use Task 1's IDs and shared selection path; keep original decoder/export interfaces.

- [x] Add browser cases for slider arrows, dialogs, malformed/multiple custom files, export locking, and unsupported WebM.
- [x] Observe any missing safeguards fail, then implement focused fixes with statuses and focus handling.
- [x] Verify play/pause, speed, scrub, glow, global previous/next, valid uploads, GIF/WebM downloads, and changelog. Verify no initial loads from hidden categories, no overflow at four widths, reduced-motion behavior, and font transfer size.
- [x] Run the full browser checks, module syntax check, and `git diff --check`. Review screenshots at all target widths.
- [x] Request fresh code review, fix material findings, rerun affected checks, and provide a local preview. Do not publish.

## Execution notes

The user approved implementation. Execute inline without another approval pause. Work on `codex/web-redesign` in the existing checkout so the shared workspace and companion stay together; no extra worktree is needed for this single-page change. The tests exercise behavior, not pixel-specific DOM structure. Leave implementation reviewable and unpushed.
