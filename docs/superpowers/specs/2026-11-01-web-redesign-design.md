# Carozerra web redesign

Status: approved by the user in chat; implemented on `codex/web-redesign`.

## Intent

Make the web player visually appealing, lightweight, and comfortable on phones. Its identity comes from the actual Pioneer stereo and its OEL animations. The user wants deliberate design and plain writing, and asked to settle the design system together through visual comparisons.

## Agreed direction

The user selected these three companion options:

- Listening room: graphite, brushed silver, and restrained cyan, with the stereo as the focal point.
- IBM Plex Sans: a single family for headings, copy, and controls.
- Player with a clip shelf: full stereo above playback controls, then category filters and a gallery.

The boards are in `.superpowers/brainstorm/780894-1793479187/content/`: `design-directions.html`, `listening-room-type.html`, and `gallery-layout.html`. They are sketches, not production code. Approving a layout does not imply that every illustrative label is final.

## Goals and boundaries

Visitors can immediately watch and browse animations. Brief introductory copy explains the project without pushing playback below a marketing section. Preserve local file opening, playback, speed, frame scrubbing, glow, GIF/WebM exports, keyboard navigation, the changelog, download links, GitHub, and support links.

Scope is the web page and the assets needed to present it. Desktop and Android apps, decoder semantics, release machinery, and the clip collection are outside this redesign. Keep existing analytics behavior. Do not add accounts, a backend, a framework, decorative animation libraries, or generated artwork. The existing stereo and decoded clips provide the imagery.

Avoid identical card containers for unrelated content, decorative gradients, fabricated claims, synthetic clip titles, unnecessary uppercase labels, and small low-contrast interface copy.

## Approaches considered

For visual direction, Listening room was selected over a pale silver product catalogue and a midnight-blue archive with serif headlines. It keeps attention on the actual player.

For typography, IBM Plex Sans was selected over Nimbus Sans. Its technical letterforms suit the subject without requiring a separate display font.

For layout, the user chose a player above a clip shelf over a desktop split view with a smaller stereo beside the collection. The selected approach preserves a large faceplate and a single reading order; browsing farther down can move the player offscreen. Do not add a sticky player by default.

Keep the existing static HTML/CSS/JavaScript platform and GitHub Pages deployment. Source organization within `web/` is left to the builder; splitting styles or interface logic into local files is fine when it makes the work clearer.

## Design system

### Color

| Token | Value | Use |
| --- | --- | --- |
| Graphite | `#15191c` | Page background |
| Control surface | `#242c32` | Controls and expanded settings |
| Silver | `#c4cdd2` | Primary playback button |
| Soft white | `#edf0f2` | Primary text |
| Display cyan | `#23cee0` | Active clip/category and keyboard focus |
| Secondary text | `#b0bbc2` | Supporting copy and metadata |

Use black behind animation pixels. Keep cyan restrained outside the artwork. Check final foreground/background pairs against WCAG AA; adjust supporting borders or disabled styling if needed.

### Type and spacing

Self-host compressed IBM Plex Sans Latin fonts with their license. Prefer regular and semibold only, with a system sans-serif fallback and `font-display: swap`. Aim for a combined font transfer below 100 KB, using a Latin subset if necessary.

Proposed scale: 14px metadata, 16px body and controls, 20px section headings, 32px mobile heading, 40px desktop heading. Use relative units, about 1.5 body line height, and left alignment. Introductory copy should stay below 60 characters per line. Final optical sizing is the builder's call.

Use a 4px spacing base, with common gaps of 8, 12, 16, 24, 32, and 48px. Content width is approximately 1040px, with a 780–900px player zone and responsive gutters. Controls use 4–6px corner radii; their shape should not turn the whole page into a collection of cards. Minimum interactive target is 44 by 44 CSS pixels.

### Page structure

```text
Brand                           About / Downloads / GitHub
Short heading and one-sentence explanation
Full Pioneer stereo with live animation
Previous / Play or Pause / Next      Settings / Export
Current clip and useful playback metadata
Animations                              Open file
Category filters
Clip previews in a responsive grid
File privacy note and project/footer links
```

Keep the copy brief. Proposed headline: "Your old dashboard, playing again." Supporting text: "Explore the animations from Pioneer’s OEL displays." The builder may refine copy for accuracy and clarity using the humanizer guidance.

## Proposed interaction details

These are design recommendations to be included in the approval, not separately confirmed user choices.

### Mobile

Below approximately 640px, default to a larger standalone live animation display with a "View full stereo" toggle. Full stereo mode uses the same live display and playback state; its reverse action is "View animation". This preserves readable artwork while allowing access to the hardware presentation. Switching modes or resizing must not restart a clip, decode it again, or create a second playback loop.

Use two gallery columns on phones, three at intermediate sizes, and up to four on wide screens if preview legibility allows. Keep touch controls below the player. Header links may collapse into an accessible menu. There is no sideways page scrolling at 320px, and landscape remains usable.

### Playback and export

Keep play/pause and previous/next prominent. Group speed, glow, and frame scrubbing in one expandable playback settings section. Preserve current settings and ranges. Export reveals GIF and WebM choices, retains the existing encoding behavior, and exposes progress, errors, and unsupported-format information visibly and accessibly. Closing settings must not reset playback state.

### Collection

Show a single selected category at a time using category filter buttons, including a Custom category after files are opened. Start in the category containing the initial clip. Changing a category changes the browsed collection without interrupting playback; the currently playing clip remains named near the player even if it belongs to another category. Choosing a tile starts that clip using existing playback behavior.

Keep the existing global previous/next and arrow-key ordering across clips; when navigation selects a clip in another category, reveal its category and active tile. Keyboard shortcuts must not intercept text entry, range input operation, menus, or dialogs.

Preserve the existing visibility-based lazy fetch/decode strategy and selection race protection. Hidden category tiles must not trigger loads. Fetch the initial clip first; avoid loading the entire collection on entry. Cache decoded clips as the existing page does. Use static thumbnails at rest. Preview animation is allowed only on pointer hover or keyboard focus; disable automatic preview animation for reduced-motion preference and touch-only devices. The primary player keeps explicit play/pause controls.

Display readable labels derived from filenames, keeping original filenames available in accessible metadata or a details affordance. Do not invent subjects for unidentified clips. Use the cyan border and a textual current-state indication for the active tile.

Opening files works through a file picker on all devices and drag/drop where available. Keep existing supported file types and multi-file behavior. Show readable load/decode errors without changing the selected good clip. Explain that animation files are processed locally; do not claim that the site makes no network requests.

### Project information

Keep About brief and accessible through a disclosure or dialog. Explain the Pioneer display context and local processing. Preserve Android release lookup and links to other existing downloads as appropriate, using existing release destinations. Preserve GitHub, support, attribution, and the changelog's runtime loading behavior. Do not let project links compete with playback controls.

## Accessibility and motion

Use semantic buttons and navigation, meaningful control labels, visible focus, accessible selected states, and correctly associated slider values. Dialogs and menus need keyboard operation, focus handling, and Escape dismissal. Use a live region for actionable loading/export status without announcing every animation frame. Announce clip selection once, not repeatedly during playback. No decorative load entrances or continuous background motion. Respect reduced motion for optional transitions and previews.

## Verification and acceptance

Review screenshots at 320px, 390px, 768px, and a wide desktop size. Verify no horizontal overflow, readable text, usable settings/export panels, keyboard focus, and touch targets. Check both mobile presentation modes.

Exercise first load, category changes, clip selection, previous/next across categories, play/pause, speed, frame scrubbing, glow, multiple custom files, a malformed file, both exports where supported, and the changelog. Verify keyboard shortcuts do not override focused controls. Preserve network/error fallbacks.

Compare initial requests against the existing page at the same viewport with an empty cache. No eager full-library download, no duplicate playback loop, and no new runtime dependency beyond locally hosted font assets. Fonts should meet the transfer target. Do not report a performance improvement without measuring it.

The final deliverable is the implemented responsive web page, a short account of verification, and any material limitations. Publishing or committing implementation is separate from design approval.

## Builder discretion

The builder may settle exact breakpoints, icon shapes, DOM/file organization, font compression tooling, accessible menu/disclosure patterns, and minor spacing adjustments within this direction. Any material change to the chosen visual direction, mobile display proposal, preserved features, or performance constraints should return to the user.
