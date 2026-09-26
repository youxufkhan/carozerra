# Android App for Head Units — Design

**Date:** 2026-09-21
**Branch:** `feat/android-head-unit` (worktree at `../carozerra-android`)
**Roadmap item:** "Android App for Head Units" (🔴 Long-Term / New Ecosystems)
**Status:** awaiting review, then implementation planning

## Context

Carozerra decodes Pioneer `.lkd` OEL animations and replays them on a rendered
DEH-P7600MP faceplate — today as a web player and a PyQt6 desktop app
(Linux `.deb`, Windows `.exe` beta). Modern aftermarket car stereos run
Android, which puts a screen in the dashboard capable of showing the faceplate
the clips were made for. This spec covers a native Android app for those units.

## Goals

1. Render the Pioneer faceplate full-screen, scaled to the head unit's display
2. Play all 83 bundled clips, browsable by category
3. Make the faceplate's own buttons interactive — 17 controls live, versus the
   desktop app's 5, each mapped to the function the unit's own manual prints
   against it
4. Drive real system volume from the left knob
5. Render a live level-meter overlay driven by real playing audio, composited
   over the animation the way the hardware does it
6. Command playback — play/pause, previous/next track — of whatever app owns
   the media session

## Non-goals

- Reading track metadata via `MediaController` / `MediaSessionManager`, which
  needs a notification-listener grant the user must award by hand. Transport is
  *commanded* with media key events (no permission) but nothing is *read back*,
  so no track title, artist or playback state is ever displayed
- Android Live Wallpaper and the homescreen widget — the next roadmap item.
  The decoder is kept UI-free so that item can reuse it
- Google Play distribution — these units ship without Play Services
- Custom `.lkd` file loading (drag-and-drop) — a web-player-only feature

## Constraints

- **No physical head unit available.** Verification is emulator-only, so the
  app ships labelled beta with an "untested on real hardware" note — the same
  honesty as the Windows `.exe`.
- **No Play Services, no Play Store.** Distribution is a sideloaded APK
  attached to a GitHub Release.
- **Aftermarket units are slow and small.** Typical panels are 1024×600 or
  1280×720 landscape on low-end MTK/Allwinner SoCs.
- **No native code.** Everything is JVM/Kotlin, so one APK covers every ABI.

## Architecture

Package `io.github.youxufkhan.carozerra`, under `android/` in this repo
alongside `web/` and `packaging/`.

| File | Responsibility | Depends on |
|---|---|---|
| `LkdDecoder.kt` | `ByteArray → LkdClip(width, height, frames: List<IntArray>)`. Pure Kotlin, **no Android imports** | stdlib only |
| `Geometry.kt` | the `G` fraction table + `hit(bx, by): Action`, ported from `carozerra.py` | nothing |
| `ClipRepository.kt` | clip catalog by category, lazy decode from assets, LRU cache, thumbnails | `LkdDecoder`, `AssetManager` |
| `FaceplateView.kt` | custom `View`: contain-fit faceplate, OEL screen, glow, touch dispatch | `Geometry`, `ClipRepository` |
| `AudioBridge.kt` | volume get/set/mute, transport via media key events, audio level source + fallback chain | `AudioManager`, `Visualizer` |
| `GalleryOverlay.kt` | categorized clip picker (`RecyclerView`) | `ClipRepository` |
| `Overlays.kt` | control-map / about card, clock, scrolling text line, info overlay | `Geometry` |
| `MainActivity.kt` | orientation lock, immersive mode, keep-screen-on, permission, wiring | all |

`LkdDecoder` emits `IntArray` of packed ARGB rather than `Bitmap`. That keeps it
a plain-JVM file testable without Robolectric, and it is the exact surface the
Live Wallpaper item will reuse. A one-line `toBitmap()` wrapper lives in
`ClipRepository`.

Rendering uses a plain `View` with invalidation on a `Handler` tick, not a
`SurfaceView`. At 4–30 fps over a 256×64 source there is nothing for a render
thread to do.

## `.lkd` decoding on the JVM

Format, unchanged from `decode.py`:

```
0x00  "zLKD" magic
0x04  u32  version (3)
0x08  u32  (1)
0x0C  u32  (7)
0x10  u32  frame count (60)
0x14  gzip stream -> TAR -> one 24-bit BMP, 256 x 3840, bottom-up, BGR
```

Steps:

1. Verify magic, read `frameCount` at `0x10` (little-endian)
2. `GZIPInputStream` over `data[20..]`, read fully
3. TAR: the single member's size is the octal field at offset 124, length 12;
   the payload starts at offset 512
4. BMP: read pixel-data offset from the header at `0x0A`, width/height at
   `0x12`/`0x16`. Row stride is `width * 3` rounded up to 4 — at 256 px that is
   768, already aligned, so there is no padding to skip. Bytes are BGR
5. **The BMP is bottom-up**, so rows are emitted in reverse to produce a
   top-down image. `decode.py` gets this free from Pillow; a manual reader must
   do it explicitly, and getting it wrong yields vertically mirrored frames
   that still look plausible — this is the single most likely decoder bug
6. Slice into `frameCount` frames of `height / frameCount` rows each. Frame `i`
   is rows `[i * fh, i * fh + fh)` of the flipped image

Failure modes throw `LkdFormatException` with the offending file name. A clip
that fails to decode is skipped and logged; it never takes the app down.

## Rendering

Mirrors `carozerra.py:_draw_screen`, with the expensive part precomputed:

1. Faceplate bitmap drawn contain-fit — the same `min(w/BASE_W, h/BASE_H)` math
   as `fit()`, against the cropped 1559×503 faceplate (`FACE_BBOX`)
2. Crisp frame drawn into the screen rect with filtering **off**
3. Glow: at decode time each frame also yields an 85×21 downscaled copy
   (~7 KB each, ~428 KB per clip). Per frame it is drawn twice, upscaled and
   filtered, at alpha 0.85 and 0.5 with `BlendMode.SCREEN` — available
   unconditionally at minSdk 29, so there is no legacy composite path
4. Scanlines: one `drawLines` call over a prebuilt float array, rebuilt only on
   size change

### Memory

One decoded clip is `60 × 256 × 64 × 4 B ≈ 3.9 MB`, plus ~0.4 MB of glow
frames. An LRU of 3 clips holds ~13 MB. The desktop app's preload-everything
approach would be ~326 MB here and is not ported.

Gallery thumbnails are frame 0 only, decoded on `RecyclerView` bind on a
background thread and cached — 83 × 65 KB ≈ 5.4 MB.

### OEL overlay zones

Photographs of the real unit show the OEL is **composited, not exclusive**: the
animation plays full-frame while a clock sits over the top-left, a level-meter
strip occupies a black box at the right edge, and status indicators stack
beneath it. A dolphin clip, a clock reading `8:30` and live meters are all on
screen at once. The app reproduces that layering rather than treating each as a
separate display mode.

Over the 256×64 frame:

| Zone | Region | Content |
|---|---|---|
| **Clip** | full frame | the `.lkd` animation, always drawn first |
| **Clock** | top-left, ~40×11 | `H:MM` in the OEL pixel style |
| **Meters** | right edge, ~40×40, black backing box | two segmented bar columns, L/R, with peak-hold caps |
| **Indicators** | right edge, below the meters | `LOUD` and `EQ·EX`, shown only while those states are on |
| **Text line** | bottom rows, full width | the ④ TEXT scrolling line |

Every zone is independently toggleable and none of them relayouts another, so
any combination composites without collision. Zone rectangles are fractions of
the frame in the same style as `G`, and are measured against the reference
photographs.

**Glyphs.** The clock, the indicators and the text line all need text rendered
at OEL scale, which nothing in this codebase does today — `carozerra.py`'s help
overlay uses Qt's default face, nothing like the dot-matrix in the photographs.
The approach is a system monospace typeface drawn with antialiasing **off** and
tinted OEL cyan, scaled so glyphs land on whole source pixels. Hand-authoring a
5×7 bitmap font for ~40 glyphs is the obvious-looking answer and is not worth
it: switching off antialiasing at this scale already produces hard pixel edges,
and the glow and scanline passes run over the result either way.

③ DISPLAY cycles the overlay set — the manual's "select different displays":

1. **Clean** — animation only (default)
2. **Clock**
3. **Clock + clip metadata** — name and category

⑥ AUDIO toggles the meter zone. ⑬ EQ and ⑪ EQ-EX light the `EQ·EX` and `LOUD`
indicators when their states are on, which is how the hardware reports them —
so the picture-quality family gets real on-screen feedback rather than a silent
state change.

④ TEXT toggles the scrolling line, carrying clip name, category and frame
count. The manual's "radio text" is literally text scrolling on this display,
so the mapping is the factory one.

The line carries no track title. Transport is write-only (see **Audio →
Transport**), so the app has no source for one, and scrolling a blank or
guessed title would be the display-layer version of the knob that lies.

## Controls

Touch position maps back to faceplate space through the inverse of the
contain-fit transform, then into `Geometry.hit()` — the same pure function as
`carozerra.py:_hit`, which keeps it unit-testable exactly as it is today.

### Live controls

Every mapping below is derived from the DEH-P7600MP owner's manual, *What's
What*, §02. The manual's own numbering is kept so the table can be checked
against it. The governing rule: **a button does the app's nearest equivalent of
what the manual prints against it, or it does nothing.** Nothing is invented to
fill a gap, because a control that lies is the exact bug the Windows build is
still carrying.

| # | Button | Manual function | Carozerra action | Geometry |
|---|---|---|---|---|
| ① | TA | traffic announcements on/off | mute toggle — TA is the audio-interrupt control | **new** `ta` |
| ② | VOLUME rotate | increase / decrease volume | system volume | exists (`lknob`, `lknob_hit`) |
| ② | VOLUME press | extends outward / retracts | blackout ↔ wake | gesture on existing hitbox |
| ③ | DISPLAY | select different displays | cycle OEL mode: clip → clock → clip + metadata | **new** `display` |
| ④ | TEXT | radio text on/off | scrolling text line on the OEL (clip name · category · frames) | **new** `text` |
| ⑤ | FUNCTION | select functions | glow on/off | exists (`func`) |
| ⑥ | AUDIO | various sound quality controls | level-meter overlay on/off | **new** `audio` |
| ⑦ | ◄ ► | track search, fast forward, reverse | previous / next **track** | exists (`rknob`, `rknob_hit`) |
| ⑦ | ▲ ▼ | manual seek tuning | animation speed | exists |
| ⑦ | centre press | *undocumented* | play / pause | **new** `rknob_center_hit` |
| ⑧ | OPEN | open the front panel | control map + about/credits card — what is "behind the panel" | **new** `open` |
| ⑨ | BAND | select band, **cancel the control mode** | close overlay · long-press exits the app | exists (`esc`) |
| ⑩ | ENTERTAINMENT | change to the entertainment display | open the clip gallery | **new** `ent` |
| ⑪ | EQ-EX | switch between EQ-EX and SFEQ | scanlines on/off | **new** `eqex` |
| ⑫ | 1–6 | preset tuning, disc number search | clips 1–6 of the current category | exists (`presets_x`, `presets_y`) |
| ⑬ | EQ | select various equalizer curves | glow intensity cycle: off → soft → full | **new** `eq` |
| ⑭ | SOURCE | cycle through available sources | cycle category: Movies → Backgrounds → Stills → Meters → Colour | **new** `source` |

Also live, on unlabelled surfaces so no printed label is contradicted:
horizontal swipe = previous / next clip, and a tap on the OEL screen = play /
pause.

Three mappings deserve their reasoning recorded, because they look like
inventions and are not:

- **⑩ ENTERTAINMENT opens the gallery.** The manual defines it as "change to
  the entertainment display", and the `.lkd` clips *are* this unit's
  entertainment display. This is the factory function, not an analogy.
- **⑬ EQ and ⑪ EQ-EX drive picture quality.** There is no audio DSP to
  equalise, so they become the visual counterpart of what they do for sound —
  ⑤ FUNCTION toggles glow, ⑬ steps its intensity, ⑪ toggles scanlines. Three
  controls, one coherent family, and ⑬ and ⑪ light the `EQ·EX` and `LOUD`
  indicators in the display's right zone, which is where the hardware reports
  those same states.
- **⑦ centre press is play/pause.** The manual documents ⑦ as ▲▼◄► only, so a
  centre press has no printed function to contradict. It sits in the cluster
  that already owns track skip.

Browsing clips therefore lives on swipe, the preset row, ⑭ SOURCE and the
gallery rather than on the nav knob — which suits 83 clips better than stepping
through them one click at a time, and frees ◄► for the track search the manual
assigns it.

### New geometry

Ten new entries must be measured against the cropped faceplate, as fractions,
in the same convention as the existing `G` table: `ta`, `display`, `text`,
`audio`, `rknob_center_hit`, `open`, `ent`, `eqex`, `eq`, `source`.

Note that ⑭ SOURCE is a button in its own right at the bottom-left of the
faceplate, not the volume knob's press — the manual lists them separately (②
vs ⑭), which is what frees the knob press for blackout.

Verification is a `BuildConfig.DEBUG`-only overlay that strokes every hitbox
rectangle over the faceplate; the measurement is correct when a screenshot
shows each box centred on its button. This overlay is the acceptance check for
the geometry task, not a shipped feature.

### Hit-test precedence

`carozerra.py:_hit` is a linear if-chain returning on first match, which is
safe at 5 regions and is not at 15. Several new boxes land next to existing
ones — ⑩ ENT sits close to `rknob_hit`, ⑬ EQ and ⑭ SOURCE share the bottom-left
corner, ⑧ OPEN and ⑨ BAND share the top-right. A screenshot of centred boxes
passes even when two of them overlap and the wrong one wins.

So the order is fixed and explicit: **innermost and smallest first**.
`rknob_center_hit` is tested before `rknob`; the small edge buttons are tested
before either knob; the preset row last. And `GeometryTest` asserts not only
that every control's centre resolves to its own action but that **no two hitbox
rectangles intersect** — a pure function over the `G` table, so it catches the
entire class of packing errors for the cost of one test.

### Gesture discrimination

② VOLUME serves both rotate and press from a single hitbox, so the two need
separating or every volume adjustment also triggers blackout. A **tap** is a
release within 300 ms having travelled under ~2% of the faceplate width;
anything else is a rotate. The desktop app never needed this rule because its
knob had no press action (`carozerra.py:420-424` sets `_drag_knob` immediately).

Back-porting these ten entries into `carozerra.py`'s `G`, so Linux, Windows and
Android share one geometry table rather than two that can drift, is agreed but
deliberately deferred — it does not block the Android work, and porting numbers
that the debug overlay has already verified is cheaper than measuring them
twice. Tracked in `ROADMAP.md`.

## Audio

### Volume

`AudioManager.getStreamVolume(STREAM_MUSIC)` over `getStreamMaxVolume` gives a
percentage; `setStreamVolume` writes it back. Head units commonly expose 15 or
30 steps, so the knob angle must render the **quantized** value that came back
from the system, not the continuous drag position — otherwise the knob lies
about a volume the unit is not actually at.

The stream volume is re-read once a second so the car's own volume buttons
cannot desync the knob. Hardware volume keys are not intercepted.

① TA maps to `adjustStreamVolume(STREAM_MUSIC, ADJUST_TOGGLE_MUTE, 0)`.

### Transport

Play/pause and track skip go out as media key events:
`AudioManager.dispatchMediaKeyEvent(KeyEvent(ACTION_DOWN, KEYCODE_MEDIA_*))`,
paired with the matching `ACTION_UP`. This routes to whichever app currently
owns the media session and **requires no permission at all** — unlike
`MediaController`, which would need a notification-listener grant the user has
to award by hand in system settings. For three buttons that only ever send
commands, the permissioned API buys nothing.

The consequence is that transport is write-only: the app can command playback
but cannot read back whether anything is playing, or what. `isMusicActive()`
covers the only state actually needed — whether to animate the meters. Nothing
displays a track title or a play/pause indicator, because there is no source
for either. A play/pause button that reports no state is honest; one that draws
a fake "playing" indicator is the knob that lies, in a new place.

### Level meters

The meter zone is **drawn live**, not selected from clip frames: two segmented
bar columns fed from the audio level, with fast attack, slow decay and a
peak-hold cap that falls more slowly still. It composites over whatever clip is
playing, exactly as the reference photographs show.

This is a correction to an earlier design that would have driven the 15
`meter_level*` / `alt_meter_li*` clips by picking their frame from loudness.
Those clips are ordinary animation content and keep playing on the timer like
every other clip; the meter is its own overlay, which is both what the hardware
does and simpler — it needs no special-casing per clip category.

Level source, resolved at startup:

1. **Capture** — `Visualizer(0)` with `RECORD_AUDIO`, 128-sample waveform at
   ~20 Hz, RMS about the 128 midpoint. Left and right columns are driven from
   the two channels where the capture provides them, and mirrored where it does
   not. Whether `MODIFY_AUDIO_SETTINGS` is also required alongside
   `RECORD_AUDIO` is unconfirmed — a one-line manifest addition, settled in
   Phase 5 against a running build rather than guessed now
2. **Unavailable** — if the constructor throws, the permission is denied, or
   samples stay flat (max deviation < 2) for 3 consecutive seconds while
   `isMusicActive()` reports true, the `Visualizer` is released and **the meter
   zone is hidden**. Pressing ⑥ AUDIO then reports "NO SIGNAL" on the OEL once
   and leaves it off

There is deliberately no fallback that animates the bars without a real signal.
A meter is a measurement; bars that bounce to a timer are the knob that lies,
scaled up to the most eye-catching element on the display. Hiding the zone is
the honest failure.

Output-mix capture is the one behaviour that cannot be verified without
hardware, and the emulator is expected to land on step 2 — so the hidden-zone
path is the one that gets exercised in development, and capture is an
enhancement that either lights up on a real unit or visibly does not.

## Head-unit specifics

- `android:screenOrientation="sensorLandscape"`
- Immersive: `WindowCompat.setDecorFitsSystemWindows(false)` plus
  `WindowInsetsControllerCompat.hide(systemBars())` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
- `FLAG_KEEP_SCREEN_ON` — without it the dashboard blanks mid-clip
- Blackout mode additionally sets `screenBrightness = 0.01f`
- `onPause` stops the tick and releases the `Visualizer`; `onResume` restarts
  and re-reads volume
- No dependency anywhere on Google Play Services

## Build and toolchain

- Kotlin, plain Android Views. No Compose: the app is one canvas plus a
  `RecyclerView`, which does not justify a compiler plugin
- `minSdk 29` (Android 10, the realistic aftermarket floor), `compileSdk 36` —
  `android-36.1` is the only platform installed locally
- AGP and Gradle versions are pinned in Phase 1 and proven by a real build.
  They must support `compileSdk 36` and run on JDK 21
- **The system JDK is 25, ahead of AGP support.** Local builds use Android
  Studio's bundled JBR: `JAVA_HOME=~/android-studio/jbr ./gradlew assembleDebug`.
  CI uses `actions/setup-java` with Temurin 21. This is documented in the
  README rather than pinned via `org.gradle.java.home`, which would hardcode a
  machine-specific path
- `cmdline-tools` is not installed, so there is no `sdkmanager`; Gradle
  resolves platforms itself against the already-accepted licenses

### Assets

A Gradle `Sync` task copies `../../assets/clips/` and `../../assets/pioneer.png`
into `build/generated/assets/`, registered as an additional asset source dir.
Copying a filtered set rather than pointing at `../../assets` wholesale keeps
`assets/readme/` art out of the APK. Expected APK size ≈ 14 MB.

## Testing

| Test | Kind | Asserts |
|---|---|---|
| `LkdDecoderTest` | JVM unit | a real `.lkd` yields 60 frames of 256×64; a known pixel matches `decode.py`'s output; bad magic throws |
| `GeometryTest` | JVM unit | every control's centre resolves to its action; **no two hitbox rectangles intersect**; nav sectors resolve by angle; a point on bare faceplate resolves to nothing |
| `SelftestTest` | instrumented | launch, wait for first frame, screenshot, assert > 32 distinct sampled colours |

`SelftestTest` is the direct parallel of `carozerra.py --selftest` and exists
for the same reason: a frozen or packaged build fails quietly — a missing
asset, an unpacked asset tree, a paint path that draws nothing — and "the
process stayed alive" catches none of it.

The known-pixel assertion in `LkdDecoderTest` is what catches the bottom-up BMP
flip, which is otherwise invisible in a test that only checks dimensions.

Manual verification uses an emulator AVD at 1024×600 landscape, API 29,
documented in the README.

## CI and release

- `.github/workflows/android-check.yml` — on pushes and PRs touching
  `android/**` or `assets/**`: `assembleDebug` + unit tests, then
  `connectedDebugAndroidTest` on an API 29 emulator. Uploads the APK and the
  selftest screenshot as artifacts
- `release.yml` gains a `build-android` job attaching
  `carozerra_<version>_android.apk` to tagged releases

**Open decision — signing.** A debug keystore is generated per machine, so
successive builds are signed differently and cannot upgrade over each other.
Beta ships debug-signed with "uninstall before upgrading" documented; a release
keystore held as an Actions secret is the fix when the app leaves beta. No
keystore is committed to the repo.

## Risks

| Risk | Mitigation |
|---|---|
| Output-mix capture blocked by playback-capture policy | Meter zone hides itself rather than faking levels; every other feature is unaffected |
| No hardware to verify against | Ships beta, emulator screenshot in CI, documented like the Windows `.exe` |
| Bottom-up BMP flip silently mirrors frames | Known-pixel assertion cross-checked against `decode.py` |
| AGP / JDK 21 / compileSdk 36 pinning | Resolved by a real build in Phase 1 before anything is written on top |
| Unknown head-unit panel sizes | Contain-fit handles any aspect; no fixed layout |
| Glow composite too slow on a weak SoC | Blur frames precomputed at decode; FUNC already disables glow entirely |

## Phasing

| Phase | Work | Estimate |
|---|---|---|
| 1 | Gradle skeleton, toolchain pinned by a real build, faceplate renders contain-fit, one hardcoded clip plays, emulator screenshot green | 0.5 day |
| 2 | `LkdDecoder` + `ClipRepository` + LRU + all 83 clips + gallery | 1 day |
| 3 | Ten new hitboxes measured via the debug overlay; all 17 controls wired | 1.25 days |
| 4 | Volume, mute, transport, blackout; the overlay zones — clock, text line, indicators, and the control-map / about card | 1 day |
| 5 | Live level-meter overlay, the capture source, and the hidden-zone path | 0.75 day |
| 6 | CI workflow, release wiring, README / ROADMAP / CHANGELOG | 0.5 day |

**Total ≈ 5 days.**

The control map is shown automatically for about five seconds at launch, as the
desktop app does, and ⑧ OPEN reopens it. With 17 live controls it cannot be a
hidden feature, and no button's printed label suggests "help" — "open the front
panel" revealing what is behind it is the closest the faceplate offers.
