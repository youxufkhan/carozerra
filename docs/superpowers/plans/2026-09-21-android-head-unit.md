# Android Head-Unit App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android app for aftermarket car head units that renders the Pioneer DEH-P7600MP faceplate full-screen, plays all 83 decoded `.lkd` animations, and makes 17 of the faceplate's own controls do real work.

**Architecture:** One `View` subclass paints a contain-fit faceplate bitmap with the animation composited into the OEL screen rect. A pure-Kotlin decoder turns `.lkd` bytes into `IntArray` frames with no Android imports, so it unit-tests on the JVM and the future Live Wallpaper can reuse it. Touches map back through the inverse contain-fit into a ported copy of the desktop app's pure `hit()` function. Audio is read and commanded through `AudioManager`; the level meters are drawn live from a `Visualizer` and hide themselves when capture is unavailable.

**Tech Stack:** Kotlin, Android Views (no Compose), AGP 8.13.2, Gradle 8.14.3, JDK 21, `androidx.core`, `androidx.recyclerview`. No native code, no Play Services, no new third-party dependencies.

**Spec:** `docs/superpowers/specs/2026-09-21-android-head-unit-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Package:** `io.github.youxufkhan.carozerra`. All Kotlin under `android/app/src/main/java/io/github/youxufkhan/carozerra/`.
- **SDK:** `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`.
- **Toolchain:** AGP `8.13.2`, Gradle `8.14.3`, Kotlin `2.1.0`, Java target 17. Gradle must run on JDK 21 — the system JDK is 25 and AGP does not support it. Every `./gradlew` invocation in this plan is prefixed `JAVA_HOME=~/android-studio/jbr`.
- **No new dependencies** beyond `androidx.core:core-ktx` and `androidx.recyclerview:recyclerview`. No Compose, no image libraries, no audio libraries.
- **No native code.** One APK serves every ABI.
- **`LkdDecoder.kt` imports nothing from `android.*`.** It is JVM-testable and is the surface the Live Wallpaper roadmap item reuses.
- **Never display a value the app cannot measure.** If audio capture is unavailable the meter zone hides; nothing renders a fake level, a fake play state, or a fake track title. This is the rule the Windows build broke with its volume knob and the reason it is still beta.
- **Geometry is fractions of the cropped faceplate** (1559 × 503, i.e. `assets/pioneer.png` cropped to `(22, 204, 1581, 707)`), matching `carozerra.py`'s `G` dict so the two can converge later.
- **Commit after every task.** Conventional commits, present tense.

## File Structure

```
android/
├── settings.gradle.kts              project + repository declarations
├── build.gradle.kts                 plugin versions
├── gradle.properties                jvmargs, AndroidX flag
├── gradlew, gradle/wrapper/         Gradle wrapper (bootstrapped in Task 1)
└── app/
    ├── build.gradle.kts             android block, deps, asset sync task
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/values/themes.xml, strings.xml
        │   └── java/io/github/youxufkhan/carozerra/
        │       ├── LkdDecoder.kt      bytes -> LkdClip. Pure Kotlin, no android.*
        │       ├── Geometry.kt        G table + hit(). Pure Kotlin, no android.*
        │       ├── ClipRepository.kt  catalog, lazy decode, LRU, thumbnails
        │       ├── FaceplateView.kt   the one custom View: paint + touch
        │       ├── Overlays.kt        clock, text line, indicators, meters, card
        │       ├── AudioBridge.kt     volume, mute, transport, level source
        │       ├── GalleryOverlay.kt  RecyclerView clip picker
        │       └── MainActivity.kt    window flags, permission, wiring
        ├── test/java/io/github/youxufkhan/carozerra/
        │   ├── LkdDecoderTest.kt
        │   └── GeometryTest.kt
        └── androidTest/java/io/github/youxufkhan/carozerra/
            └── SelftestTest.kt
```

`FaceplateView.kt` and `Overlays.kt` are split deliberately: the view owns the transform, the clip and touch dispatch; overlays own pixel content drawn *into* the OEL rect. They change for different reasons and neither needs the other's internals.

---

### Task 1: Gradle skeleton and toolchain

Pins versions by a real build before any app code exists, so a toolchain mismatch surfaces now rather than underneath five tasks of work.

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`
- Create: `android/.gitignore`

**Interfaces:**
- Consumes: nothing
- Produces: a buildable `:app` module; `MainActivity` referenced by the manifest is created in Task 5

- [ ] **Step 1: Create the project directory and Gradle files**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "carozerra"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
```

`android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`android/.gitignore`:

```gitignore
build/
.gradle/
local.properties
*.apk
```

- [ ] **Step 2: Create the app module build file**

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.youxufkhan.carozerra"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.youxufkhan.carozerra"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.3.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
```

- [ ] **Step 3: Create the manifest and resources**

`android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="false"
        android:theme="@style/Theme.Carozerra">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Carozerra</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.Carozerra" parent="android:Theme.Material.NoActionBar.Fullscreen">
        <item name="android:windowBackground">@android:color/black</item>
    </style>
</resources>
```

- [ ] **Step 4: Bootstrap the Gradle wrapper**

There is no `gradle` on PATH and no `cmdline-tools`, but a Gradle 8.14.3 distribution is already in the wrapper cache. Use it once to generate the wrapper.

Run:

```bash
cd android && \
JAVA_HOME=~/android-studio/jbr \
~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle \
  wrapper --gradle-version 8.14.3 --distribution-type bin
```

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 5: Point the build at the SDK**

`android/local.properties` (gitignored, machine-specific):

```properties
sdk.dir=/home/yousuf/Android/Sdk
```

- [ ] **Step 6: Prove the toolchain by a real build**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew assembleDebug`

Expected: `BUILD SUCCESSFUL`. `MainActivity` does not exist yet, so the manifest reference will fail to resolve — if the build errors on the missing class, create a placeholder `MainActivity.kt` containing only `class MainActivity : android.app.Activity()` and re-run. Task 5 replaces it.

If `compileSdk 36` fails because only `android-36.1` is installed locally, AGP downloads `android-36` itself (licenses are already accepted under `~/Android/Sdk/licenses`). If that download is blocked, install the platform from Android Studio's SDK Manager rather than lowering `compileSdk`.

- [ ] **Step 7: Commit**

```bash
git add android/
git commit -m "build(android): gradle skeleton pinned to AGP 8.13.2 on JDK 21"
```

---

### Task 2: `.lkd` decoder

Pure Kotlin, no Android imports. The bottom-up BMP flip is the single most likely bug in this file, so the test pins it with real pixel values taken from `decode.py`'s output.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/LkdDecoder.kt`
- Test: `android/app/src/test/java/io/github/youxufkhan/carozerra/LkdDecoderTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class LkdFormatException(message: String) : Exception(message)`
  - `data class LkdClip(val width: Int, val height: Int, val frames: List<IntArray>)` — each frame is `width * height` packed ARGB ints, row-major, top-down
  - `object LkdDecoder { fun decode(data: ByteArray, name: String = "<lkd>"): LkdClip }`

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/github/youxufkhan/carozerra/LkdDecoderTest.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LkdDecoderTest {

    // Unit tests run with the module dir (android/app) as working directory.
    private fun movie1(): ByteArray = File("../../assets/clips/movie1.lkd").readBytes()

    private fun litPixelsInRow(clip: LkdClip, frame: Int, y: Int): Int =
        (0 until clip.width).count { x ->
            clip.frames[frame][y * clip.width + x] != 0xFF000000.toInt()
        }

    @Test
    fun decodesDimensionsAndFrameCount() {
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(256, clip.width)
        assertEquals(64, clip.height)
        assertEquals(60, clip.frames.size)
        assertEquals(256 * 64, clip.frames[0].size)
    }

    @Test
    fun channelOrderIsBgrInFileAndArgbOut() {
        // decode.py reports frame0[200,10] == (7, 158, 175), the native OEL cyan.
        // A BGR/RGB swap would yield 0xFFAF9E07 here instead.
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(0xFF079EAF.toInt(), clip.frames[0][10 * 256 + 200])
    }

    @Test
    fun bmpIsFlippedFromBottomUpToTopDown() {
        // The BMP stores rows bottom-up. Frame 0 of movie1 is sparse at the top
        // (83 lit pixels in row 0) and dense at the bottom (198 in row 63); a
        // missing flip swaps those counts while every dimension check still passes.
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(83, litPixelsInRow(clip, 0, 0))
        assertEquals(198, litPixelsInRow(clip, 0, 63))
        assertEquals(0xFF05C7DC.toInt(), clip.frames[0][63 * 256 + 128])
    }

    @Test
    fun everyBundledClipDecodes() {
        val dir = File("../../assets/clips")
        val files = dir.listFiles { f -> f.name.endsWith(".lkd") }!!
        assertEquals(83, files.size)
        for (f in files) {
            val clip = LkdDecoder.decode(f.readBytes(), f.name)
            assertTrue("${f.name}: ${clip.width}x${clip.height}", clip.width == 256)
            assertTrue("${f.name}: no frames", clip.frames.isNotEmpty())
        }
    }

    @Test(expected = LkdFormatException::class)
    fun rejectsBadMagic() {
        LkdDecoder.decode("NOTALKDFILE12345678901234".toByteArray(), "bogus")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*LkdDecoderTest*'`

Expected: FAIL — `Unresolved reference: LkdDecoder`.

- [ ] **Step 3: Write the decoder**

`android/app/src/main/java/io/github/youxufkhan/carozerra/LkdDecoder.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class LkdFormatException(message: String) : Exception(message)

/** One decoded clip. Each frame is width*height packed ARGB ints, row-major, top-down. */
data class LkdClip(val width: Int, val height: Int, val frames: List<IntArray>)

/**
 * 0x00 "zLKD" | 0x04 version | 0x08 ? | 0x0C ? | 0x10 frame count
 * 0x14 gzip -> tar -> one 24-bit bottom-up BGR BMP of stacked frames
 */
object LkdDecoder {

    private const val HEADER = 20
    private val MAGIC = byteArrayOf(0x7A, 0x4C, 0x4B, 0x44) // "zLKD"

    fun decode(data: ByteArray, name: String = "<lkd>"): LkdClip {
        if (data.size < HEADER || !data.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw LkdFormatException("$name: not a zLKD file")
        }
        val declaredFrames = le32(data, 0x10)
        val tar = GZIPInputStream(
            ByteArrayInputStream(data, HEADER, data.size - HEADER)
        ).use { it.readBytes() }
        return sliceBmp(firstTarMember(tar, name), declaredFrames, name)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** The archive holds exactly one member: 512-byte header, octal size at 124. */
    private fun firstTarMember(tar: ByteArray, name: String): ByteArray {
        if (tar.size < 512) throw LkdFormatException("$name: truncated tar")
        val field = String(tar, 124, 12, Charsets.US_ASCII).trim { it <= ' ' }
        val size = field.toIntOrNull(8)
            ?: throw LkdFormatException("$name: unreadable tar size '$field'")
        if (size <= 0 || 512 + size > tar.size) {
            throw LkdFormatException("$name: tar member of $size overruns ${tar.size}")
        }
        return tar.copyOfRange(512, 512 + size)
    }

    private fun sliceBmp(bmp: ByteArray, declaredFrames: Int, name: String): LkdClip {
        if (bmp.size < 54 || bmp[0] != 'B'.code.toByte() || bmp[1] != 'M'.code.toByte()) {
            throw LkdFormatException("$name: tar member is not a BMP")
        }
        val pixOff = le32(bmp, 0x0A)
        val w = le32(bmp, 0x12)
        val h = le32(bmp, 0x16)
        val bpp = le16(bmp, 0x1C)
        if (bpp != 24) throw LkdFormatException("$name: expected 24bpp, got $bpp")
        if (w <= 0 || h <= 0) throw LkdFormatException("$name: bad size ${w}x$h")

        val frameCount = if (declaredFrames > 0) declaredFrames else h / w
        if (frameCount <= 0 || h % frameCount != 0) {
            throw LkdFormatException("$name: $h rows does not divide into $frameCount frames")
        }
        val fh = h / frameCount
        val stride = (w * 3 + 3) and 3.inv()
        if (pixOff + stride * h > bmp.size) {
            throw LkdFormatException("$name: pixel data overruns the BMP")
        }

        val frames = ArrayList<IntArray>(frameCount)
        for (f in 0 until frameCount) {
            val px = IntArray(w * fh)
            for (y in 0 until fh) {
                // The BMP is bottom-up: file row 0 is the image's LAST row.
                val fileRow = h - 1 - (f * fh + y)
                var src = pixOff + fileRow * stride
                var dst = y * w
                for (x in 0 until w) {
                    val b = bmp[src].toInt() and 0xFF
                    val g = bmp[src + 1].toInt() and 0xFF
                    val r = bmp[src + 2].toInt() and 0xFF
                    px[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    src += 3
                }
            }
            frames.add(px)
        }
        return LkdClip(w, fh, frames)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*LkdDecoderTest*'`

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/LkdDecoder.kt \
        android/app/src/test/java/io/github/youxufkhan/carozerra/LkdDecoderTest.kt
git commit -m "feat(android): decode .lkd to ARGB frames, pinned by known pixels"
```

---

### Task 3: Geometry table and hit-testing

Ports `carozerra.py`'s `G` dict and `_hit()`, then extends it with the ten controls the desktop app never wired. Values were measured off a fraction grid rendered over the cropped faceplate; the debug overlay in Task 7 confirms them visually.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/Geometry.kt`
- Test: `android/app/src/test/java/io/github/youxufkhan/carozerra/GeometryTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `enum class Control { TA, VOLUME, DISPLAY, TEXT, FUNCTION, AUDIO, NAV, NAV_CENTER, OPEN, BAND, ENT, EQEX, PRESET, EQ, SOURCE }`
  - `data class Hit(val control: Control, val data: Int = 0)` — `data` is the preset index for `PRESET`, and one of `Geometry.NAV_RIGHT/DOWN/LEFT/UP` for `NAV`
  - `data class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float)`
  - `object Geometry` with `BASE_W`, `BASE_H`, `SCREEN`, `boxes: Map<Control, Box>`, `fun hit(bx: Float, by: Float): Hit?`

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/github/youxufkhan/carozerra/GeometryTest.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    private fun hitAtFraction(fx: Float, fy: Float): Hit? =
        Geometry.hit(fx * Geometry.BASE_W, fy * Geometry.BASE_H)

    private fun hitAtCentreOf(control: Control): Hit? {
        val b = Geometry.boxes.getValue(control)
        return hitAtFraction(b.cx, b.cy)
    }

    @Test
    fun everyBoxResolvesToItsOwnControlAtItsCentre() {
        for (control in Geometry.boxes.keys) {
            assertEquals("centre of $control", control, hitAtCentreOf(control)?.control)
        }
    }

    @Test
    fun noTwoBoxesIntersect() {
        // Circular knob regions are excluded: they are resolved by precedence,
        // not by separation (see Geometry.hit). Boxes must never overlap.
        val entries = Geometry.boxes.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val (ca, a) = entries[i]
            val (cb, b) = entries[j]
            val overlaps = kotlin.math.abs(a.cx - b.cx) < (a.hw + b.hw) &&
                kotlin.math.abs(a.cy - b.cy) < (a.hh + b.hh)
            assert(!overlaps) { "$ca overlaps $cb" }
        }
    }

    @Test
    fun presetsResolveByIndex() {
        for (i in 0 until 6) {
            val h = hitAtFraction(Geometry.PRESETS_X[i], Geometry.PRESETS_Y)
            assertEquals(Control.PRESET, h?.control)
            assertEquals(i, h?.data)
        }
    }

    @Test
    fun navSectorsResolveByAngle() {
        val (cx, cy) = Geometry.RKNOB
        val r = Geometry.RKNOB_HIT * 0.8f          // inside the ring, outside the centre
        val aspect = Geometry.BASE_W / Geometry.BASE_H
        fun at(dxFrac: Float, dyFrac: Float) =
            hitAtFraction(cx + dxFrac, cy + dyFrac * aspect)
        assertEquals(Geometry.NAV_RIGHT, at(r, 0f)?.data)
        assertEquals(Geometry.NAV_LEFT, at(-r, 0f)?.data)
        assertEquals(Geometry.NAV_DOWN, at(0f, r)?.data)
        assertEquals(Geometry.NAV_UP, at(0f, -r)?.data)
    }

    @Test
    fun navCentreBeatsNavRing() {
        val (cx, cy) = Geometry.RKNOB
        assertEquals(Control.NAV_CENTER, hitAtFraction(cx, cy)?.control)
    }

    @Test
    fun volumeKnobCentreResolvesToVolume() {
        val (cx, cy) = Geometry.LKNOB
        assertEquals(Control.VOLUME, hitAtFraction(cx, cy)?.control)
    }

    @Test
    fun bareFaceplateResolvesToNothing() {
        assertNull(hitAtFraction(0.45f, 0.10f))   // the black strip above the screen
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*GeometryTest*'`

Expected: FAIL — `Unresolved reference: Geometry`.

- [ ] **Step 3: Write the geometry table**

`android/app/src/main/java/io/github/youxufkhan/carozerra/Geometry.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import kotlin.math.atan2
import kotlin.math.hypot

enum class Control {
    TA, VOLUME, DISPLAY, TEXT, FUNCTION, AUDIO,
    NAV, NAV_CENTER, OPEN, BAND, ENT, EQEX, PRESET, EQ, SOURCE
}

data class Hit(val control: Control, val data: Int = 0)

/** Centre and half-extents, as fractions of the faceplate. */
data class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float)

/**
 * Fractions of the cropped faceplate (assets/pioneer.png cropped to
 * 22,204 -> 1581,707). Shares its convention with carozerra.py's G dict so the
 * desktop app can adopt these entries later.
 *
 * Control numbers refer to the DEH-P7600MP manual, "What's What" section 02.
 */
object Geometry {

    const val BASE_W = 1559f
    const val BASE_H = 503f

    /** OEL screen rect: left, top, width, height. */
    val SCREEN = floatArrayOf(0.2250f, 0.3117f, 0.4608f, 0.3213f)

    val LKNOB = Pair(0.1430f, 0.4990f)
    const val LKNOB_HIT = 0.0629f          // radius, fraction of BASE_W
    const val LKNOB_DISC_X = 0.0475f       // knob art crop radii
    const val LKNOB_DISC_Y = 0.0822f

    val RKNOB = Pair(0.8743f, 0.5089f)
    const val RKNOB_HIT = 0.0657f
    const val RKNOB_CENTER_HIT = 0.028f    // the flat centre disc, inside the arrows

    const val PRESETS_Y = 0.8070f
    val PRESETS_X = floatArrayOf(0.3194f, 0.3903f, 0.4606f, 0.5309f, 0.6017f, 0.6735f)
    const val PRESET_HW = 0.0339f
    const val PRESET_HH = 0.0533f

    const val NAV_RIGHT = 0
    const val NAV_DOWN = 1
    const val NAV_LEFT = 2
    const val NAV_UP = 3

    /**
     * Measured off a 5%-fraction grid rendered over the cropped faceplate.
     * Iteration order IS hit-test precedence: small edge buttons before the
     * knobs, because the ENT wedge clips the nav knob's circle at one corner.
     */
    val boxes: Map<Control, Box> = linkedMapOf(
        Control.TA to Box(0.080f, 0.263f, 0.023f, 0.067f),       // 1
        Control.SOURCE to Box(0.080f, 0.720f, 0.023f, 0.065f),   // 14
        Control.TEXT to Box(0.250f, 0.415f, 0.0308f, 0.0497f),   // 4
        Control.DISPLAY to Box(0.250f, 0.600f, 0.0308f, 0.0497f),// 3
        Control.EQ to Box(0.182f, 0.810f, 0.042f, 0.035f),       // 13
        Control.AUDIO to Box(0.7525f, 0.418f, 0.0308f, 0.0497f), // 6
        Control.FUNCTION to Box(0.7525f, 0.6082f, 0.0308f, 0.0497f), // 5
        Control.OPEN to Box(0.857f, 0.207f, 0.015f, 0.033f),     // 8
        Control.BAND to Box(0.9480f, 0.2797f, 0.0390f, 0.0675f), // 9
        Control.ENT to Box(0.948f, 0.725f, 0.042f, 0.055f),      // 10
        Control.EQEX to Box(0.820f, 0.807f, 0.036f, 0.030f),     // 11
    )

    /**
     * Map a point in faceplate pixel space to a control. Pure and testable,
     * exactly like carozerra.py:_hit.
     *
     * Precedence, innermost first: rectangles, then the nav centre, then the
     * nav ring, then the volume knob, then the preset row. Rectangles win over
     * circles because the ENT wedge and the nav knob's circle share a corner,
     * and the wedge is the smaller, more specific target.
     */
    fun hit(bx: Float, by: Float): Hit? {
        for ((control, b) in boxes) {
            if (kotlin.math.abs(bx - b.cx * BASE_W) <= b.hw * BASE_W &&
                kotlin.math.abs(by - b.cy * BASE_H) <= b.hh * BASE_H
            ) return Hit(control)
        }

        val rx = RKNOB.first * BASE_W
        val ry = RKNOB.second * BASE_H
        val rd = hypot((bx - rx).toDouble(), (by - ry).toDouble()).toFloat()
        if (rd <= RKNOB_CENTER_HIT * BASE_W) return Hit(Control.NAV_CENTER)
        if (rd <= RKNOB_HIT * BASE_W) {
            val a = Math.toDegrees(atan2((by - ry).toDouble(), (bx - rx).toDouble()))
            val dir = when {
                a >= -45 && a < 45 -> NAV_RIGHT
                a >= 45 && a < 135 -> NAV_DOWN
                a >= 135 || a < -135 -> NAV_LEFT
                else -> NAV_UP
            }
            return Hit(Control.NAV, dir)
        }

        val lx = LKNOB.first * BASE_W
        val ly = LKNOB.second * BASE_H
        if (hypot((bx - lx).toDouble(), (by - ly).toDouble()) <= LKNOB_HIT * BASE_W) {
            return Hit(Control.VOLUME)
        }

        for (i in PRESETS_X.indices) {
            if (kotlin.math.abs(bx - PRESETS_X[i] * BASE_W) <= PRESET_HW * BASE_W &&
                kotlin.math.abs(by - PRESETS_Y * BASE_H) <= PRESET_HH * BASE_H
            ) return Hit(Control.PRESET, i)
        }
        return null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*GeometryTest*'`

Expected: PASS, 7 tests. If `noTwoBoxesIntersect` fails, shrink the offending half-extents rather than reordering — reordering hides an overlap instead of removing it.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/Geometry.kt \
        android/app/src/test/java/io/github/youxufkhan/carozerra/GeometryTest.kt
git commit -m "feat(android): port the faceplate geometry and add ten new hitboxes"
```

---

### Task 4: Asset packaging and clip repository

Copies the shared `assets/` content into the APK without dragging in the README art, then decodes lazily behind an LRU. Preloading all 83 clips the way the desktop app does would cost ~326 MB.

**Files:**
- Modify: `android/app/build.gradle.kts` (add the `Sync` task and asset source dir)
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/ClipRepository.kt`
- Test: `android/app/src/test/java/io/github/youxufkhan/carozerra/ClipCatalogTest.kt`

**Interfaces:**
- Consumes: `LkdDecoder.decode`, `LkdClip`
- Produces:
  - `object ClipCatalog { val categories: List<Category>; val all: List<String>; fun categoryOf(name: String): String }`
  - `data class Category(val name: String, val clips: List<String>)`
  - `class RenderClip(val frames: List<Bitmap>, val blur: List<Bitmap>)`
  - `class ClipRepository(private val assets: AssetManager)` with
    - `fun render(name: String): RenderClip` — the only clip accessor; LRU-cached
    - `fun thumbnail(name: String): Bitmap` — frame 0, cached for the gallery

- [ ] **Step 1: Add the asset sync task**

Append to `android/app/build.gradle.kts`:

```kotlin
val syncSharedAssets by tasks.registering(Sync::class) {
    // Only the two things the app needs — assets/readme/ stays out of the APK.
    from(rootProject.file("../assets/clips")) { into("clips") }
    from(rootProject.file("../assets/pioneer.png"))
    into(layout.buildDirectory.dir("generated/sharedAssets"))
}

android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/sharedAssets")
)

tasks.named("preBuild") { dependsOn(syncSharedAssets) }
```

- [ ] **Step 2: Write the failing catalog test**

`android/app/src/test/java/io/github/youxufkhan/carozerra/ClipCatalogTest.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipCatalogTest {

    @Test
    fun catalogListsEveryBundledClipExactlyOnce() {
        val onDisk = File("../../assets/clips")
            .listFiles { f -> f.name.endsWith(".lkd") }!!
            .map { it.name }.sorted()
        assertEquals(83, onDisk.size)
        assertEquals(onDisk, ClipCatalog.all.sorted())
        assertEquals(ClipCatalog.all.size, ClipCatalog.all.toSet().size)
    }

    @Test
    fun everyClipHasACategory() {
        for (name in ClipCatalog.all) {
            assertTrue(name, ClipCatalog.categoryOf(name).isNotEmpty())
        }
    }

    @Test
    fun categoriesMatchTheWebPlayer() {
        assertEquals(
            listOf("Movies", "Backgrounds", "Stills", "Level Meters", "Color"),
            ClipCatalog.categories.map { it.name }
        )
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*ClipCatalogTest*'`

Expected: FAIL — `Unresolved reference: ClipCatalog`.

- [ ] **Step 4: Write the repository**

`android/app/src/main/java/io/github/youxufkhan/carozerra/ClipRepository.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.LruCache

data class Category(val name: String, val clips: List<String>)

/** The same five groupings the web player uses, in the same order. */
object ClipCatalog {

    val categories: List<Category> = listOf(
        Category("Movies", listOf(
            "movie1.lkd", "movie2.lkd", "movie3.lkd", "movie4.lkd", "movie5.lkd",
            "movie6.lkd", "movie7.lkd", "movie8_f.lkd", "movie9_f.lkd", "movie10_f.lkd",
            "alt_airship.lkd", "alt_diverdolphins.lkd", "alt_dragonrider.lkd",
            "alt_metropolis.lkd", "alt_waterboard.lkd", "alt_wildlife.lkd", "alt_wrc.lkd",
        )),
        Category("Backgrounds", listOf(
            "alt_bgv1.lkd", "alt_bgv2.lkd", "alt_bgv3.lkd", "alt_bgv4.lkd",
        )),
        Category("Stills", listOf(
            "still_bgp01.lkd", "still_bgp02.lkd", "still_bgp03.lkd", "still_bgp04.lkd",
            "still_bgp05.lkd", "still_bgp06.lkd", "still_bgp07.lkd", "still_bgp08.lkd",
            "still_bgp09.lkd",
            "still_bgp_f_10.lkd", "still_bgp_f_11.lkd", "still_bgp_f_12.lkd",
            "still_bgp_f_13.lkd", "still_bgp_f_14.lkd", "still_bgp_f_15.lkd",
            "still_bgp_f_16.lkd", "still_bgp_f_17.lkd", "still_bgp_f_18.lkd",
            "alt_still_bgp_e1.lkd", "alt_still_bgp_e2.lkd", "alt_still_bgp_e3.lkd",
            "alt_still_bgp_e4.lkd", "alt_still_bgp_e5.lkd",
        )),
        Category("Level Meters", listOf(
            "meter_level01.lkd", "meter_level02.lkd", "meter_level03.lkd",
            "meter_level04.lkd", "meter_level05.lkd",
            "meter_level06_f.lkd", "meter_level07_f.lkd",
            "alt_meter_li01.lkd", "alt_meter_li02.lkd", "alt_meter_li03.lkd",
            "alt_meter_li04.lkd", "alt_meter_li05.lkd", "alt_meter_li06.lkd",
            "alt_meter_li07.lkd", "alt_meter_li08.lkd",
        )),
        Category("Color", listOf(
            "color_01_nightcruising.lkd", "color_02_greatbarrierreef.lkd",
            "color_03_redplanet.lkd", "color_04_island.lkd", "color_05_firedragon.lkd",
            "color_06_racingcart.lkd", "color_07_motogp.lkd",
            "color_still_bgp01.lkd", "color_still_bgp02.lkd", "color_still_bgp03.lkd",
            "color_still_bgp04.lkd", "color_still_bgp05.lkd", "color_still_bgp06.lkd",
            "color_still_bgp07.lkd", "color_still_bgp08.lkd", "color_still_bgp09.lkd",
            "color_still_bgp10.lkd",
            "color_bgv01.lkd", "color_bgv02.lkd", "color_bgv03.lkd", "color_bgv04.lkd",
            "color_bgv05.lkd", "color_bgv06.lkd", "color_bgv07.lkd",
        )),
    )

    val all: List<String> = categories.flatMap { it.clips }

    fun categoryOf(name: String): String =
        categories.firstOrNull { name in it.clips }?.name ?: ""
}

/** One clip's render-ready bitmaps: crisp frames plus their downscaled blur copies. */
class RenderClip(val frames: List<Bitmap>, val blur: List<Bitmap>)

/**
 * Decodes on demand and keeps only a few clips alive. One clip costs ~3.9 MB of
 * crisp frames plus ~0.4 MB of blur copies; all 83 at once would be ~326 MB.
 */
class ClipRepository(private val assets: AssetManager) {

    private val clips = LruCache<String, RenderClip>(3)
    private val thumbs = HashMap<String, Bitmap>()

    fun render(name: String): RenderClip = clips.get(name) ?: build(name).also {
        clips.put(name, it)
    }

    fun thumbnail(name: String): Bitmap = thumbs.getOrPut(name) {
        toBitmap(decode(name), 0)
    }

    private fun build(name: String): RenderClip {
        val clip = decode(name)
        val frames = ArrayList<Bitmap>(clip.frames.size)
        val blur = ArrayList<Bitmap>(clip.frames.size)
        val bw = (clip.width / 3).coerceAtLeast(1)
        val bh = (clip.height / 3).coerceAtLeast(1)
        for (i in clip.frames.indices) {
            val full = toBitmap(clip, i)
            frames.add(full)
            blur.add(Bitmap.createScaledBitmap(full, bw, bh, true))
        }
        return RenderClip(frames, blur)
    }

    private fun decode(name: String): LkdClip =
        assets.open("clips/$name").use { LkdDecoder.decode(it.readBytes(), name) }

    private fun toBitmap(clip: LkdClip, index: Int): Bitmap =
        Bitmap.createBitmap(
            clip.frames[index], clip.width, clip.height, Bitmap.Config.ARGB_8888
        )
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*ClipCatalogTest*'`

Expected: PASS, 3 tests. `ClipCatalogTest` touches no Android class, so it runs on the JVM even though `ClipRepository` in the same file does.

- [ ] **Step 6: Verify the assets actually land in the APK**

Run:

```bash
cd android && JAVA_HOME=~/android-studio/jbr ./gradlew assembleDebug && \
  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c 'assets/clips/.*\.lkd'
```

Expected: `83`. Also confirm `assets/pioneer.png` is present and that nothing from `assets/readme/` is.

- [ ] **Step 7: Commit**

```bash
git add android/app/build.gradle.kts \
        android/app/src/main/java/io/github/youxufkhan/carozerra/ClipRepository.kt \
        android/app/src/test/java/io/github/youxufkhan/carozerra/ClipCatalogTest.kt
git commit -m "feat(android): package the shared clips and decode them behind an LRU"
```

---

### Task 5: Faceplate renders and the app launches

First visible result: the faceplate fills the screen with a clip playing on the OEL, and the instrumented selftest proves it drew rather than merely survived.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt`
- Test: `android/app/src/androidTest/java/io/github/youxufkhan/carozerra/SelftestTest.kt`

**Interfaces:**
- Consumes: `ClipRepository.render`, `ClipCatalog.all`, `Geometry.SCREEN`, `Geometry.BASE_W/BASE_H`
- Produces:
  - `class FaceplateView(context: Context) : View(context)` with
    - `var clipName: String` — setting it loads and restarts that clip
    - `var fps: Int` — clamped to 4..30
    - `fun faceplateRect(): RectF` and `fun toBase(x: Float, y: Float): FloatArray`
  - `class MainActivity : Activity()`

- [ ] **Step 1: Write the faceplate view**

`android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View

class FaceplateView(context: Context) : View(context) {

    private val repo = ClipRepository(context.assets)
    private val face: Bitmap = context.assets.open("pioneer.png").use {
        // The source PNG is 1600x893 with a transparent margin; the visible
        // faceplate is the 1559x503 box at (22,204). Cropping keeps the app's
        // coordinate space identical to carozerra.py's.
        val full = BitmapFactory.decodeStream(it)
        Bitmap.createBitmap(full, 22, 204, 1559, 503)
    }

    private val facePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val crisp = Paint()                       // no filtering: hard OEL pixels
    private val frameRect = RectF()
    private val src = Rect()

    private var clip: RenderClip = repo.render(ClipCatalog.all.first())
    private var frame = 0

    var clipName: String = ClipCatalog.all.first()
        set(value) {
            field = value
            clip = repo.render(value)
            frame = 0
            invalidate()
        }

    var fps: Int = 16
        set(value) { field = value.coerceIn(4, 30) }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            frame = (frame + 1) % clip.frames.size
            invalidate()
            ticker.postDelayed(this, (1000L / fps))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.postDelayed(tick, (1000L / fps))
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Contain-fit: same math as carozerra.py's fit(). */
    fun faceplateRect(): RectF {
        val s = minOf(width / Geometry.BASE_W, height / Geometry.BASE_H)
        val fw = Geometry.BASE_W * s
        val fh = Geometry.BASE_H * s
        frameRect.set((width - fw) / 2f, (height - fh) / 2f,
                      (width - fw) / 2f + fw, (height - fh) / 2f + fh)
        return frameRect
    }

    /** Screen pixels -> faceplate pixel space, the inverse of the contain fit. */
    fun toBase(x: Float, y: Float): FloatArray {
        val r = faceplateRect()
        val s = r.width() / Geometry.BASE_W
        return floatArrayOf((x - r.left) / s, (y - r.top) / s)
    }

    fun screenRect(): RectF {
        val r = faceplateRect()
        val left = r.left + Geometry.SCREEN[0] * r.width()
        val top = r.top + Geometry.SCREEN[1] * r.height()
        return RectF(
            left, top,
            left + Geometry.SCREEN[2] * r.width(),
            top + Geometry.SCREEN[3] * r.height()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = faceplateRect()
        src.set(0, 0, face.width, face.height)
        canvas.drawBitmap(face, src, r, facePaint)

        val bmp = clip.frames[frame]
        src.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, src, screenRect(), crisp)
    }
}
```

- [ ] **Step 2: Write the activity**

`android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {

    private lateinit var view: FaceplateView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the dashboard blanks part-way through a clip.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = FaceplateView(this)
        setContentView(view)
    }
}
```

- [ ] **Step 3: Write the instrumented selftest**

`android/app/src/androidTest/java/io/github/youxufkhan/carozerra/SelftestTest.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parallel of carozerra.py --selftest. A packaged build fails quietly — a
 * missing asset, an unsynced assets/ tree, a paint path that draws nothing —
 * and "the process stayed alive" catches none of those. Count colours instead.
 */
@RunWith(AndroidJUnit4::class)
class SelftestTest {

    @Test
    fun activityDrawsTheFaceplateAndAClip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var distinct = 0
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val shot = Bitmap.createBitmap(
                    root.width, root.height, Bitmap.Config.ARGB_8888
                )
                root.draw(android.graphics.Canvas(shot))
                val seen = HashSet<Int>()
                var y = 0
                while (y < shot.height) {
                    var x = 0
                    while (x < shot.width) {
                        seen.add(shot.getPixel(x, y)); x += 7
                    }
                    y += 7
                }
                distinct = seen.size
            }
            assertTrue("only $distinct distinct colours — looks blank", distinct >= 32)
        }
    }
}
```

- [ ] **Step 4: Build and run the selftest on an emulator**

Create the AVD once (1024×600 landscape, API 29) in Android Studio's Device Manager, start it, then run:

`cd android && JAVA_HOME=~/android-studio/jbr ./gradlew connectedDebugAndroidTest`

Expected: PASS. If it fails with too few colours, take a manual screenshot (`adb exec-out screencap -p > /tmp/shot.png`) and look — a black screen means the asset sync or the `pioneer.png` crop is wrong, not the test.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt \
        android/app/src/androidTest/java/io/github/youxufkhan/carozerra/SelftestTest.kt
git commit -m "feat(android): render the faceplate full-screen with a clip playing"
```

---

### Task 6: Glow and scanlines

The OEL phosphor look, ported from `carozerra.py:_draw_screen`. The blur copies were already built in Task 4, so this is composition only.

**Files:**
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`

**Interfaces:**
- Consumes: `RenderClip.blur`
- Produces: on `FaceplateView` — `var glow: Boolean`, `var glowIntensity: Int` (0 off, 1 soft, 2 full), `var scanlines: Boolean`

- [ ] **Step 1: Add the glow state and paints**

Add to `FaceplateView`:

```kotlin
    var glow: Boolean = true
        set(value) { field = value; invalidate() }

    /** 0 = off, 1 = soft, 2 = full. Cycled by the EQ button. */
    var glowIntensity: Int = 2
        set(value) { field = value.coerceIn(0, 2); invalidate() }

    var scanlines: Boolean = true
        set(value) { field = value; invalidate() }

    private val bloom = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = android.graphics.BlendMode.SCREEN
    }
    private val scanPaint = Paint().apply { color = 0x46000000 }
    private var scanLines = FloatArray(0)
    private var scanForHeight = -1f
```

- [ ] **Step 2: Draw the bloom and scanlines**

Replace `onDraw`'s clip-drawing section with:

```kotlin
        val screen = screenRect()
        val bmp = clip.frames[frame]
        src.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, src, screen, crisp)

        if (glow && glowIntensity > 0) {
            val blur = clip.blur[frame]
            src.set(0, 0, blur.width, blur.height)
            val alphas = if (glowIntensity == 1) intArrayOf(120) else intArrayOf(217, 128)
            for (a in alphas) {
                bloom.alpha = a
                canvas.drawBitmap(blur, src, screen, bloom)
            }
        }

        if (scanlines) {
            if (scanForHeight != screen.height()) buildScanLines(screen)
            canvas.drawLines(scanLines, scanPaint)
        }
```

- [ ] **Step 3: Build the scanline array once per size**

```kotlin
    /** One drawLines call beats ~64 drawLine calls per frame. */
    private fun buildScanLines(screen: RectF) {
        val step = maxOf(2f, screen.height() / 64f)
        val n = (screen.height() / step).toInt()
        val pts = FloatArray(n * 4)
        var y = screen.top
        for (i in 0 until n) {
            pts[i * 4] = screen.left
            pts[i * 4 + 1] = y
            pts[i * 4 + 2] = screen.right
            pts[i * 4 + 3] = y
            y += step
        }
        scanLines = pts
        scanForHeight = screen.height()
    }
```

- [ ] **Step 4: Verify visually**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug && adb shell am start -n io.github.youxufkhan.carozerra/.MainActivity`

Then `adb exec-out screencap -p > /tmp/glow.png` and look at it. Expected: the clip has a soft cyan bloom and visible horizontal scanlines, matching `assets/readme/proof-desktop.png`.

- [ ] **Step 5: Re-run the selftest**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew connectedDebugAndroidTest`

Expected: PASS — the colour count rises, never falls.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt
git commit -m "feat(android): composite the OEL bloom and scanlines"
```

---

### Task 7: Debug hitbox overlay

Confirms the ten measured boxes actually sit on their buttons before any of them is wired to an action. Debug-only; never ships.

**Files:**
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`

**Interfaces:**
- Consumes: `Geometry.boxes`, `Geometry.LKNOB`, `Geometry.RKNOB`, `Geometry.PRESETS_X`
- Produces: `var debugHitboxes: Boolean` on `FaceplateView`

- [ ] **Step 1: Add the overlay**

Add to `FaceplateView`:

```kotlin
    /** Debug builds only: strokes every hitbox so the measurements can be checked. */
    var debugHitboxes: Boolean = BuildConfig.DEBUG
        set(value) { field = value; invalidate() }

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF3B30.toInt()
    }
    private val debugLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        textSize = 22f
    }

    private fun drawHitboxes(canvas: Canvas) {
        val r = faceplateRect()
        fun fx(v: Float) = r.left + v * r.width()
        fun fy(v: Float) = r.top + v * r.height()

        for ((control, b) in Geometry.boxes) {
            canvas.drawRect(
                fx(b.cx - b.hw), fy(b.cy - b.hh),
                fx(b.cx + b.hw), fy(b.cy + b.hh), debugPaint
            )
            canvas.drawText(control.name, fx(b.cx - b.hw), fy(b.cy - b.hh) - 4f, debugLabel)
        }
        for (i in Geometry.PRESETS_X.indices) {
            canvas.drawRect(
                fx(Geometry.PRESETS_X[i] - Geometry.PRESET_HW),
                fy(Geometry.PRESETS_Y - Geometry.PRESET_HH),
                fx(Geometry.PRESETS_X[i] + Geometry.PRESET_HW),
                fy(Geometry.PRESETS_Y + Geometry.PRESET_HH), debugPaint
            )
        }
        canvas.drawCircle(fx(Geometry.LKNOB.first), fy(Geometry.LKNOB.second),
            Geometry.LKNOB_HIT * r.width(), debugPaint)
        canvas.drawCircle(fx(Geometry.RKNOB.first), fy(Geometry.RKNOB.second),
            Geometry.RKNOB_HIT * r.width(), debugPaint)
        canvas.drawCircle(fx(Geometry.RKNOB.first), fy(Geometry.RKNOB.second),
            Geometry.RKNOB_CENTER_HIT * r.width(), debugPaint)
    }
```

Call `if (debugHitboxes) drawHitboxes(canvas)` at the end of `onDraw`.

- [ ] **Step 2: Screenshot and verify every box**

Run:

```bash
cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug && \
  adb shell am start -n io.github.youxufkhan.carozerra/.MainActivity && \
  sleep 2 && adb exec-out screencap -p > /tmp/hitboxes.png
```

Open `/tmp/hitboxes.png` and check each of the 11 labelled rectangles is centred on its button: `TA` on the NEWS/TA wedge, `SOURCE` on the S / SRC↔OFF wedge, `TEXT` and `DISPLAY` on the two blue lozenges left of the screen, `EQ` on the bottom-left blue lozenge, `AUDIO` above `FUNCTION` right of the screen, `OPEN` on the round silver button, `BAND` on the ESC/B wedge, `ENT` on the E / ENT↔MODE wedge, `EQEX` on the EQ-EX lozenge.

- [ ] **Step 3: Correct any box that is off**

Edit the value in `Geometry.boxes`, re-run Step 2. Then re-run the geometry test, because moving a box can create an overlap:

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew :app:testDebugUnitTest --tests '*GeometryTest*'`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/Geometry.kt
git commit -m "feat(android): debug overlay confirming every measured hitbox"
```

---

### Task 8: Touch dispatch and clip browsing

Wires touch to the geometry and lights up the controls that need nothing but the clip list. Audio-dependent controls come in Tasks 11–13.

**Files:**
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`

**Interfaces:**
- Consumes: `Geometry.hit`, `FaceplateView.toBase`, `ClipCatalog`
- Produces: on `FaceplateView` —
  - `var onControl: ((Hit) -> Boolean)?` — returns true if the host consumed it
  - `var categoryIndex: Int`, `fun nextClip(delta: Int)`, `fun selectPreset(i: Int)`, `fun cycleCategory()`
  - `var onVolumeDrag: ((Float) -> Unit)?` — receives a delta in percent
  - `var onKnobTap: (() -> Unit)?`

- [ ] **Step 1: Add clip navigation state**

Add to `FaceplateView`:

```kotlin
    var categoryIndex: Int = 0
        private set

    private val categoryClips: List<String>
        get() = ClipCatalog.categories[categoryIndex].clips

    private var indexInCategory = 0

    fun nextClip(delta: Int) {
        val n = categoryClips.size
        indexInCategory = ((indexInCategory + delta) % n + n) % n
        clipName = categoryClips[indexInCategory]
    }

    fun selectPreset(i: Int) {
        if (i in categoryClips.indices) {
            indexInCategory = i
            clipName = categoryClips[i]
        }
    }

    fun cycleCategory() {
        categoryIndex = (categoryIndex + 1) % ClipCatalog.categories.size
        indexInCategory = 0
        clipName = categoryClips[0]
    }
```

- [ ] **Step 2: Add touch dispatch**

```kotlin
    var onControl: ((Hit) -> Boolean)? = null
    var onVolumeDrag: ((Float) -> Unit)? = null
    var onKnobTap: (() -> Unit)? = null
    var onKnobLongPress: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var draggingKnob = false
    private var knobAngle = 0f
    private var swipeCandidate = false

    private val tapSlopPx get() = 0.02f * faceplateRect().width()

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val (bx, by) = toBase(event.x, event.y).let { Pair(it[0], it[1]) }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                val hit = Geometry.hit(bx, by)
                draggingKnob = hit?.control == Control.VOLUME
                swipeCandidate = hit == null
                if (draggingKnob) knobAngle = angleToKnob(bx, by)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (draggingKnob) {
                    val a = angleToKnob(bx, by)
                    var d = a - knobAngle
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    if (kotlin.math.abs(d) > 0.5f) {
                        knobAngle = a
                        onVolumeDrag?.invoke(d / 280f * 100f)
                    }
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val travelled = kotlin.math.hypot(event.x - downX, event.y - downY)
                val held = event.eventTime - downAt

                if (draggingKnob) {
                    draggingKnob = false
                    // A tap is a short, still press. Without this every volume
                    // adjustment would also fire the knob's press action.
                    if (travelled < tapSlopPx) {
                        if (held >= 600L) onKnobLongPress?.invoke() else onKnobTap?.invoke()
                    }
                    return true
                }
                if (swipeCandidate && travelled > 4 * tapSlopPx &&
                    kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY)
                ) {
                    nextClip(if (event.x < downX) 1 else -1)
                    return true
                }
                Geometry.hit(bx, by)?.let { onControl?.invoke(it) }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun angleToKnob(bx: Float, by: Float): Float {
        val lx = Geometry.LKNOB.first * Geometry.BASE_W
        val ly = Geometry.LKNOB.second * Geometry.BASE_H
        return Math.toDegrees(
            kotlin.math.atan2((by - ly).toDouble(), (bx - lx).toDouble())
        ).toFloat()
    }
```

- [ ] **Step 3: Wire the clip-only controls in `MainActivity`**

```kotlin
        view.onControl = { hit ->
            when (hit.control) {
                Control.PRESET -> { view.selectPreset(hit.data); true }
                Control.SOURCE -> { view.cycleCategory(); true }
                Control.FUNCTION -> { view.glow = !view.glow; true }
                Control.EQ -> { view.glowIntensity = (view.glowIntensity + 1) % 3; true }
                Control.EQEX -> { view.scanlines = !view.scanlines; true }
                Control.NAV -> {
                    when (hit.data) {
                        Geometry.NAV_UP -> view.fps += 2
                        Geometry.NAV_DOWN -> view.fps -= 2
                    }
                    true
                }
                else -> false
            }
        }
```

- [ ] **Step 4: Verify by hand on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Check with the debug overlay on: tapping presets 1–6 changes clip; SOURCE cycles category; FUNCTION toggles bloom; EQ steps it; EQ-EX toggles scanlines; nav up/down changes speed; swiping left/right changes clip; dragging the volume knob does not change the clip.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): dispatch touches to controls and browse clips"
```

---

### Task 9: Volume, mute and transport

`AudioBridge` is the only file that talks to `AudioManager`. The knob renders the **quantised** value the system returned, never the continuous drag position — head units commonly expose 15 or 30 steps, and a knob that draws a position the unit is not at is the Windows build's bug in a new place.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/AudioBridge.kt`
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt` (rotating knob)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt` (wiring)

**Interfaces:**
- Consumes: `Control`, `Hit`, `Geometry.LKNOB`, `Geometry.LKNOB_DISC_X/Y`
- Produces:
  - `class AudioBridge(context: Context)` with `volumePercent: Int`, `refreshVolume(): Int`, `setVolumePercent(pct: Int)`, `toggleMute()`, `isMuted: Boolean`, `isMusicActive(): Boolean`, `playPause()`, `nextTrack()`, `previousTrack()`
  - `var volumePercent: Int` on `FaceplateView` — redraws the knob

- [ ] **Step 1: Write the bridge**

`android/app/src/main/java/io/github/youxufkhan/carozerra/AudioBridge.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Everything the app knows about audio. Volume is read back after every write
 * so the knob shows the system's quantised value, not the drag position.
 *
 * Transport is write-only by design: dispatchMediaKeyEvent needs no permission,
 * where MediaController would need a notification-listener grant. The app can
 * command playback but cannot read it, so nothing displays a play state.
 */
class AudioBridge(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val maxVolume: Int
        get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var volumePercent: Int = 0
        private set

    fun refreshVolume(): Int {
        volumePercent =
            Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVolume)
        return volumePercent
    }

    fun setVolumePercent(pct: Int) {
        val index = Math.round(pct.coerceIn(0, 100) * maxVolume / 100f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        refreshVolume()
    }

    /** TA on the real unit interrupts audio; mute is its nearest honest analogue. */
    fun toggleMute() {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        refreshVolume()
    }

    val isMuted: Boolean get() = am.isStreamMute(AudioManager.STREAM_MUSIC)

    fun isMusicActive(): Boolean = am.isMusicActive

    private fun send(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun playPause() = send(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextTrack() = send(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousTrack() = send(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
}
```

- [ ] **Step 2: Draw the rotating knob**

Add to `FaceplateView`:

```kotlin
    var volumePercent: Int = 50
        set(value) { field = value.coerceIn(0, 100); invalidate() }

    private val knob: Bitmap = run {
        val rx = (Geometry.LKNOB_DISC_X * Geometry.BASE_W).toInt()
        val ry = (Geometry.LKNOB_DISC_Y * Geometry.BASE_H).toInt()
        val cx = (Geometry.LKNOB.first * Geometry.BASE_W).toInt()
        val cy = (Geometry.LKNOB.second * Geometry.BASE_H).toInt()
        Bitmap.createBitmap(face, cx - rx, cy - ry, 2 * rx, 2 * ry)
    }
    private val knobClip = android.graphics.Path()

    private fun drawKnob(canvas: Canvas) {
        val r = faceplateRect()
        val cx = r.left + Geometry.LKNOB.first * r.width()
        val cy = r.top + Geometry.LKNOB.second * r.height()
        val radius = Geometry.LKNOB_DISC_X * r.width()
        // -140deg at 0%, +140deg at 100%: the same sweep as the desktop app.
        val angle = -140f + volumePercent / 100f * 280f

        canvas.save()
        canvas.translate(cx, cy)
        // Clip first, rotate second: the circular mask must not rotate with the art.
        knobClip.reset()
        knobClip.addCircle(0f, 0f, radius, android.graphics.Path.Direction.CW)
        canvas.clipPath(knobClip)
        canvas.rotate(angle)
        src.set(0, 0, knob.width, knob.height)
        canvas.drawBitmap(knob, src, RectF(-radius, -radius, radius, radius), facePaint)
        canvas.restore()
    }
```

Call `drawKnob(canvas)` from `onDraw`, after the faceplate and before the overlays.

- [ ] **Step 3: Wire it up in `MainActivity`**

```kotlin
    private lateinit var audio: AudioBridge
    private val poll = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollVolume = object : Runnable {
        override fun run() {
            // The car's own volume buttons move the stream behind our back.
            view.volumePercent = audio.refreshVolume()
            poll.postDelayed(this, 1000L)
        }
    }
```

In `onCreate`, after `setContentView`:

```kotlin
        audio = AudioBridge(this)
        view.volumePercent = audio.refreshVolume()
        view.onVolumeDrag = { delta ->
            audio.setVolumePercent(audio.volumePercent + Math.round(delta))
            view.volumePercent = audio.volumePercent
        }
```

Extend `view.onControl`:

```kotlin
                Control.TA -> { audio.toggleMute(); view.volumePercent = audio.volumePercent; true }
                Control.NAV_CENTER -> { audio.playPause(); true }
                Control.NAV -> {
                    when (hit.data) {
                        Geometry.NAV_UP -> view.fps += 2
                        Geometry.NAV_DOWN -> view.fps -= 2
                        Geometry.NAV_RIGHT -> audio.nextTrack()
                        Geometry.NAV_LEFT -> audio.previousTrack()
                    }
                    true
                }
```

Add lifecycle:

```kotlin
    override fun onResume() {
        super.onResume()
        poll.post(pollVolume)
    }

    override fun onPause() {
        poll.removeCallbacks(pollVolume)
        super.onPause()
    }
```

- [ ] **Step 4: Verify on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Check: dragging the knob moves it and changes the emulator's media volume (`adb shell media volume --show --stream 3 --get`); pressing a hardware volume key and waiting a second moves the knob; tapping TA mutes; nav left/right sends track keys (`adb logcat -s AudioManager` shows the dispatch, or play something in an app and watch it skip).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/AudioBridge.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): real volume, mute and permission-free transport"
```

---

### Task 10: OEL overlay zones — clock, text line, indicators

The display composites rather than switching: the animation plays full-frame while the clock, meters and indicators layer over fixed zones, exactly as the reference photographs show.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt`
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt`
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt`

**Interfaces:**
- Consumes: `FaceplateView.screenRect()`
- Produces:
  - `object Zones { val CLOCK: RectF; val METERS: RectF; val INDICATORS: RectF; val TEXT_LINE: RectF; fun inScreen(zone: RectF, screen: RectF): RectF }`
  - `class OelOverlays` with `var displayMode: Int` (0 clean, 1 clock, 2 clock + metadata), `var textLine: Boolean`, `var showEqEx: Boolean`, `var showLoud: Boolean`, `fun draw(canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int)`

- [ ] **Step 1: Write the overlays**

`android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar

/** Fractions of the 256x64 OEL frame. No two zones overlap. */
object Zones {
    val CLOCK = RectF(0.02f, 0.04f, 0.20f, 0.24f)
    val METERS = RectF(0.80f, 0.08f, 0.98f, 0.62f)
    val INDICATORS = RectF(0.80f, 0.64f, 0.98f, 0.84f)
    val TEXT_LINE = RectF(0.02f, 0.86f, 0.98f, 0.99f)

    fun inScreen(zone: RectF, screen: RectF) = RectF(
        screen.left + zone.left * screen.width(),
        screen.top + zone.top * screen.height(),
        screen.left + zone.right * screen.width(),
        screen.top + zone.bottom * screen.height()
    )
}

const val OEL_CYAN = 0xFF12E0FF.toInt()

/**
 * Text is a system monospace face with antialiasing off, tinted OEL cyan and
 * scaled to the zone. At this size that already yields hard pixel edges, and the
 * bloom and scanline passes run over the result — a hand-authored bitmap font
 * would cost ~40 glyphs of work for no visible gain.
 */
class OelOverlays {

    var displayMode: Int = 0            // 0 clean, 1 clock, 2 clock + metadata
        set(value) { field = ((value % 3) + 3) % 3 }
    var textLine: Boolean = false
    var showEqEx: Boolean = false
    var showLoud: Boolean = false

    private val text = Paint().apply {
        isAntiAlias = false
        color = OEL_CYAN
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var scrollOffset = 0f

    fun draw(canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int) {
        if (displayMode >= 1) drawClock(canvas, screen)
        if (displayMode >= 2) drawMeta(canvas, screen, clipName, category)
        if (showEqEx || showLoud) drawIndicators(canvas, screen)
        if (textLine) drawTextLine(canvas, screen, clipName, category, frames)
    }

    private fun drawClock(canvas: Canvas, screen: RectF) {
        val r = Zones.inScreen(Zones.CLOCK, screen)
        val now = Calendar.getInstance()
        val label = "%d:%02d".format(
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )
        text.textSize = r.height()
        canvas.drawText(label, r.left, r.bottom, text)
    }

    private fun drawMeta(canvas: Canvas, screen: RectF, clipName: String, category: String) {
        val r = Zones.inScreen(Zones.CLOCK, screen)
        text.textSize = r.height() * 0.5f
        canvas.drawText(
            "${clipName.removeSuffix(".lkd")}  $category",
            r.left, r.bottom + r.height() * 0.7f, text
        )
    }

    private fun drawIndicators(canvas: Canvas, screen: RectF) {
        val r = Zones.inScreen(Zones.INDICATORS, screen)
        text.textSize = r.height() * 0.45f
        if (showLoud) canvas.drawText("LOUD", r.left, r.top + r.height() * 0.45f, text)
        if (showEqEx) canvas.drawText("EQ-EX", r.left, r.bottom, text)
    }

    private fun drawTextLine(
        canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int
    ) {
        val r = Zones.inScreen(Zones.TEXT_LINE, screen)
        // No track title: transport is write-only, so the app has no source for
        // one, and scrolling a guessed title would be the knob that lies.
        val label = "${clipName.removeSuffix(".lkd")}  ·  $category  ·  $frames FRAMES      "
        text.textSize = r.height()
        val w = text.measureText(label)
        scrollOffset = (scrollOffset + r.width() * 0.004f) % w
        canvas.save()
        canvas.clipRect(r)
        var x = r.left - scrollOffset
        while (x < r.right) {
            canvas.drawText(label, x, r.bottom, text)
            x += w
        }
        canvas.restore()
    }
}
```

- [ ] **Step 2: Draw the overlays inside the OEL rect**

In `FaceplateView`, add `val overlays = OelOverlays()` and call it after the clip and bloom but before the scanlines, so the scanlines fall over the text too:

```kotlin
        overlays.draw(
            canvas, screen, clipName,
            ClipCatalog.categoryOf(clipName), clip.frames.size
        )
```

- [ ] **Step 3: Wire DISPLAY, TEXT, EQ and EQ-EX**

In `MainActivity`'s `onControl`, replace the `EQ` and `EQEX` branches and add two:

```kotlin
                Control.DISPLAY -> { view.overlays.displayMode += 1; view.invalidate(); true }
                Control.TEXT -> {
                    view.overlays.textLine = !view.overlays.textLine
                    view.invalidate(); true
                }
                Control.EQ -> {
                    view.glowIntensity = (view.glowIntensity + 1) % 3
                    view.overlays.showLoud = view.glowIntensity == 2
                    true
                }
                Control.EQEX -> {
                    view.scanlines = !view.scanlines
                    view.overlays.showEqEx = view.scanlines
                    true
                }
```

- [ ] **Step 4: Verify on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Check: DISPLAY cycles clean → clock → clock + metadata; the clock reads the real time; TEXT scrolls a line along the bottom of the OEL; EQ-EX lights `EQ-EX` and EQ lights `LOUD` in the right-hand zone; none of the zones overlap at any window size.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): composite clock, text line and status indicators on the OEL"
```

---

### Task 11: Live level meters

Drawn from the real audio level, and hidden when there is no real audio level. There is deliberately no fallback that animates the bars to a timer — a meter is a measurement, and bars that bounce without a signal would be the most eye-catching lie on the display.

**Files:**
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/AudioBridge.kt` (add `LevelSource`)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt` (draw the meters)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt` (permission, wiring, lifecycle)

**Interfaces:**
- Consumes: `AudioBridge.isMusicActive`, `Zones.METERS`
- Produces:
  - `class LevelSource` with `val available: Boolean`, `val level: Float` (0..1, smoothed), `fun start(): Boolean`, `fun stop()`
  - `var meters: Boolean` and `var meterLevel: Float` on `OelOverlays`

- [ ] **Step 1: Write the level source**

Append to `AudioBridge.kt`:

```kotlin
import android.media.audiofx.Visualizer

/**
 * Reads the output mix through Visualizer(0). Whether the platform allows that
 * depends on the playback-capture policy of whatever is playing, so the source
 * proves itself: if the waveform stays flat while music is active, capture is
 * being denied and the source marks itself unavailable.
 */
class LevelSource(private val bridge: AudioBridge) {

    var available: Boolean = false
        private set

    /** 0..1, fast attack and slow decay so the bars fall naturally. */
    var level: Float = 0f
        private set

    private var vis: Visualizer? = null
    private var flatSince = 0L

    fun start(): Boolean {
        stop()
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        z: Visualizer?, waveform: ByteArray, rate: Int
                    ) = consume(waveform)

                    override fun onFftDataCapture(z: Visualizer?, fft: ByteArray, rate: Int) = Unit
                },
                Visualizer.getMaxCaptureRate() / 2, true, false
            )
            v.enabled = true
            vis = v
            available = true
            true
        } catch (t: Throwable) {
            // Denied permission, a ROM without the effect, or another app holding
            // the session all land here. None of them is worth crashing over.
            available = false
            false
        }
    }

    fun stop() {
        try { vis?.enabled = false; vis?.release() } catch (_: Throwable) {}
        vis = null
        level = 0f
    }

    private fun consume(waveform: ByteArray) {
        var peak = 0
        var sum = 0.0
        for (b in waveform) {
            val d = (b.toInt() and 0xFF) - 128
            if (kotlin.math.abs(d) > peak) peak = kotlin.math.abs(d)
            sum += (d * d).toDouble()
        }
        val rms = (Math.sqrt(sum / waveform.size) / 128.0).toFloat().coerceIn(0f, 1f)
        level = if (rms > level) rms else level * 0.85f

        // Flat for 3s while music is playing means capture is being denied.
        val now = android.os.SystemClock.elapsedRealtime()
        if (peak < 2 && bridge.isMusicActive()) {
            if (flatSince == 0L) flatSince = now
            if (now - flatSince > 3000L) { stop(); available = false }
        } else {
            flatSince = 0L
        }
    }
}
```

- [ ] **Step 2: Draw the meter zone**

Add to `OelOverlays`:

```kotlin
    var meters: Boolean = false
    var meterLevel: Float = 0f

    private val meterBg = Paint().apply { color = 0xFF000000.toInt() }
    private val meterOn = Paint().apply { isAntiAlias = false; color = OEL_CYAN }
    private val meterOff = Paint().apply { isAntiAlias = false; color = 0xFF0A3540.toInt() }

    private var peakHold = 0f

    private fun drawMeters(canvas: Canvas, screen: RectF) {
        val r = Zones.inScreen(Zones.METERS, screen)
        canvas.drawRect(r, meterBg)

        peakHold = if (meterLevel > peakHold) meterLevel else (peakHold - 0.012f).coerceAtLeast(0f)

        val segments = 10
        val colGap = r.width() * 0.12f
        val colW = (r.width() - colGap) / 2f
        val segH = r.height() / segments
        for (col in 0..1) {
            val x0 = r.left + col * (colW + colGap)
            for (s in 0 until segments) {
                val lit = (segments - s) <= Math.round(meterLevel * segments)
                val top = r.top + s * segH
                canvas.drawRect(
                    x0, top + segH * 0.15f, x0 + colW, top + segH * 0.85f,
                    if (lit) meterOn else meterOff
                )
            }
            val peakSeg = segments - Math.round(peakHold * segments)
            if (peakSeg in 0 until segments) {
                val top = r.top + peakSeg * segH
                canvas.drawRect(x0, top, x0 + colW, top + segH * 0.18f, meterOn)
            }
        }
    }
```

Call it from `draw()`, before the indicators: `if (meters) drawMeters(canvas, screen)`.

- [ ] **Step 3: Request the permission and wire the source**

In `MainActivity`:

```kotlin
    private lateinit var level: LevelSource

    private fun startLevelSource() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        level.start()
        view.overlays.meters = level.available
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 1 &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startLevelSource()
    }
```

In `onCreate`, after `audio = AudioBridge(this)`: `level = LevelSource(audio)`.

Feed the view from the existing 1-second poll — but the bars need the animation rate, so push the level in the ticker instead. Add to `FaceplateView`, called from its `tick`:

```kotlin
    var levelProvider: (() -> Float)? = null
```

and inside `tick.run()`, before `invalidate()`:

```kotlin
            levelProvider?.let { overlays.meterLevel = it() }
```

Then in `MainActivity.onCreate`: `view.levelProvider = { level.level }`.

Wire the AUDIO button:

```kotlin
                Control.AUDIO -> {
                    if (!level.available) {
                        view.overlays.meters = false
                        view.flash("NO SIGNAL")
                    } else {
                        view.overlays.meters = !view.overlays.meters
                    }
                    true
                }
```

- [ ] **Step 4: Add the flash message**

In `FaceplateView`:

```kotlin
    private var flashText: String? = null
    private var flashUntil = 0L

    /** A short message centred on the OEL — used when a control has nothing honest to do. */
    fun flash(message: String) {
        flashText = message
        flashUntil = android.os.SystemClock.elapsedRealtime() + 1500L
        invalidate()
    }
```

Draw it in `onDraw` after the overlays:

```kotlin
        flashText?.let {
            if (android.os.SystemClock.elapsedRealtime() > flashUntil) {
                flashText = null
            } else {
                val s = screenRect()
                val p = Paint().apply {
                    isAntiAlias = false; color = OEL_CYAN
                    textSize = s.height() * 0.28f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                canvas.drawText(it, s.centerX(), s.centerY(), p)
            }
        }
```

- [ ] **Step 5: Release on pause**

In `MainActivity.onPause`, before `super`: `level.stop()`. In `onResume`, after the poll: `startLevelSource()`.

- [ ] **Step 6: Verify on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Expected on an emulator: the permission dialog appears; capture most likely returns silence, so after three seconds of playing audio the source marks itself unavailable and pressing AUDIO flashes `NO SIGNAL` with no bars drawn. That is the correct outcome, not a bug — record which branch the emulator took, because it is what the README's beta note must describe.

If capture does work, play audio and check the bars track loudness with peak caps falling more slowly than the bars.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/AudioBridge.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): draw level meters from real audio, hide them without it"
```

---

### Task 12: Clip gallery

83 clips need a picker; ENTERTAINMENT is the button the manual assigns to it.

**Files:**
- Create: `android/app/src/main/java/io/github/youxufkhan/carozerra/GalleryOverlay.kt`
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/ClipRepository.kt` (thumbnail accessor is already there)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt`

**Interfaces:**
- Consumes: `ClipCatalog.categories`, `ClipRepository.thumbnail`
- Produces: `class GalleryOverlay(context: Context, repo: ClipRepository, onPick: (String) -> Unit) : FrameLayout(context)` with `fun show()`, `fun hide()`, `val isShowing: Boolean`

- [ ] **Step 1: Write the gallery**

`android/app/src/main/java/io/github/youxufkhan/carozerra/GalleryOverlay.kt`:

```kotlin
package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Thumbnails decode off the main thread on bind; 83 eager decodes would stall launch. */
class GalleryOverlay(
    context: Context,
    private val repo: ClipRepository,
    private val onPick: (String) -> Unit,
) : FrameLayout(context) {

    private data class Row(val category: String, val clip: String)

    private val rows: List<Row> =
        ClipCatalog.categories.flatMap { c -> c.clips.map { Row(c.name, it) } }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xE6080C10.toInt())
        visibility = View.GONE

        val list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = Adapter()
        }
        addView(list, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setOnClickListener { hide() }
    }

    fun show() { visibility = View.VISIBLE }
    fun hide() { visibility = View.GONE }

    private inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.getChildAt(0) as ImageView
        val label: TextView = root.getChildAt(1) as TextView
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(12, 12, 12, 12)
                addView(ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                addView(TextView(parent.context).apply {
                    setTextColor(OEL_CYAN)
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
            }
            return Holder(root)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.label.text = "${row.clip.removeSuffix(".lkd")}\n${row.category}"
            holder.image.setImageBitmap(null)
            holder.root.setOnClickListener { onPick(row.clip); hide() }

            val target = row.clip
            Thread {
                val bmp: Bitmap = repo.thumbnail(target)
                holder.image.post {
                    if (holder.label.text.startsWith(target.removeSuffix(".lkd"))) {
                        holder.image.setImageBitmap(bmp)
                    }
                }
            }.start()
        }
    }
}
```

- [ ] **Step 2: Host it and wire ENTERTAINMENT**

In `MainActivity.onCreate`, replace `setContentView(view)` with:

```kotlin
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        gallery = GalleryOverlay(this, ClipRepository(assets)) { name ->
            view.clipName = name
        }
        root.addView(gallery)
        setContentView(root)
```

and add `Control.ENT -> { gallery.show(); true }` to `onControl`.

- [ ] **Step 3: Verify on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Check: ENT opens a 4-column grid of 83 thumbnails grouped by category label; scrolling is smooth; tapping one closes the gallery and switches the clip; tapping the background closes it.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/GalleryOverlay.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): clip gallery on the ENTERTAINMENT button"
```

---

### Task 13: Blackout, control map and the about card

The last three controls: the knob press blacks the screen for night driving, BAND cancels, and OPEN reveals what is behind the panel — the control map, which with 17 live controls cannot be a hidden feature.

**Files:**
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt` (the card)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt` (blackout)
- Modify: `android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt`

**Interfaces:**
- Consumes: `Control`, `FaceplateView.flash`
- Produces:
  - `object ControlMap { val ROWS: List<Pair<String, String>>; val ABOUT: List<String> }`
  - `var blackout: Boolean` on `FaceplateView`
  - `class CardOverlay(context: Context) : View(context)` with `fun show()`, `fun hide()`, `val isShowing: Boolean`

- [ ] **Step 1: Write the control map content**

Append to `Overlays.kt`:

```kotlin
/** Every live control, in the manual's own numbering. */
object ControlMap {
    val ROWS: List<Pair<String, String>> = listOf(
        "TA" to "mute",
        "VOLUME turn" to "system volume",
        "VOLUME press" to "blackout · hold to wake",
        "DISPLAY" to "clean / clock / clock + info",
        "TEXT" to "scrolling text line",
        "FUNCTION" to "glow on/off",
        "AUDIO" to "level meters",
        "NAV left / right" to "previous / next track",
        "NAV up / down" to "animation speed",
        "NAV press" to "play / pause",
        "OPEN" to "this card",
        "BAND" to "close · hold to exit",
        "ENTERTAINMENT" to "clip gallery",
        "EQ-EX" to "scanlines",
        "1 - 6" to "clips 1-6 of the category",
        "EQ" to "glow intensity",
        "SOURCE" to "next category",
        "swipe" to "previous / next clip",
    )

    val ABOUT: List<String> = listOf(
        "CAROZERRA — Pioneer DEH-P7600MP visualizer",
        "83 decoded .lkd animations · MIT licensed",
        "github.com/youxufkhan/carozerra",
    )
}
```

- [ ] **Step 2: Write the card view**

Append to `Overlays.kt` (it needs `android.view.View`, so import it):

```kotlin
class CardOverlay(context: Context) : View(context) {

    private val bg = Paint().apply { color = 0xEA080C10.toInt() }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x9612E0FF.toInt()
    }
    private val key = Paint().apply {
        isAntiAlias = true; color = OEL_CYAN
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val value = Paint().apply { isAntiAlias = true; color = 0xFFD6E2EA.toInt() }
    private val dim = Paint().apply { isAntiAlias = true; color = 0xFF8CA2B0.toInt() }

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        setOnClickListener { hide() }
    }

    fun show() { visibility = VISIBLE; invalidate() }
    fun hide() { visibility = GONE }

    override fun onDraw(canvas: Canvas) {
        // Two columns: 18 rows will not fit legibly in one on a 600px-tall panel.
        val pad = width * 0.04f
        val box = RectF(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(box, 16f, 16f, bg)
        canvas.drawRoundRect(box, 16f, 16f, edge)

        val rowsPerCol = (ControlMap.ROWS.size + 1) / 2
        val rowH = (box.height() - pad * 3f) / (rowsPerCol + 2)
        key.textSize = rowH * 0.62f
        value.textSize = rowH * 0.62f
        dim.textSize = rowH * 0.52f

        canvas.drawText("CAROZERRA — CONTROLS", box.left + pad, box.top + pad + rowH, key)

        val colW = (box.width() - pad * 2f) / 2f
        ControlMap.ROWS.forEachIndexed { i, (k, v) ->
            val col = i / rowsPerCol
            val row = i % rowsPerCol
            val x = box.left + pad + col * colW
            val y = box.top + pad + rowH * (row + 2.4f)
            canvas.drawText(k, x, y, key)
            canvas.drawText(v, x + colW * 0.46f, y, value)
        }

        ControlMap.ABOUT.forEachIndexed { i, line ->
            canvas.drawText(
                line, box.left + pad,
                box.bottom - pad - dim.textSize * (ControlMap.ABOUT.size - i - 1) * 1.3f, dim
            )
        }
    }
}
```

- [ ] **Step 3: Add blackout to the view**

In `FaceplateView`:

```kotlin
    var blackout: Boolean = false
        set(value) { field = value; invalidate() }
```

At the top of `onDraw`:

```kotlin
        if (blackout) { canvas.drawColor(0xFF000000.toInt()); return }
```

- [ ] **Step 4: Wire the last controls**

In `MainActivity`:

```kotlin
        view.onKnobTap = {
            if (view.blackout) wake() else blackoutOn()
        }
        view.onKnobLongPress = { blackoutOn() }
```

```kotlin
    private fun blackoutOn() {
        view.blackout = true
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun wake() {
        view.blackout = false
        window.attributes = window.attributes.apply { screenBrightness = -1f }
    }
```

Any touch while blacked out wakes: at the top of `FaceplateView.onTouchEvent`,

```kotlin
        if (blackout && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            onKnobTap?.invoke()
            return true
        }
```

In `onControl`:

```kotlin
                Control.OPEN -> { card.show(); true }
                Control.BAND -> {
                    when {
                        gallery.isShowing -> gallery.hide()
                        card.isShowing -> card.hide()
                        else -> view.flash("HOLD TO EXIT")
                    }
                    true
                }
```

Long-press on BAND exits. Add to `FaceplateView.onTouchEvent`'s `ACTION_UP`, replacing the plain dispatch:

```kotlin
                val hit = Geometry.hit(bx, by)
                if (hit?.control == Control.BAND && held >= 600L) {
                    onBandLongPress?.invoke()
                } else {
                    hit?.let { onControl?.invoke(it) }
                }
```

with `var onBandLongPress: (() -> Unit)? = null` on the view and `view.onBandLongPress = { finish() }` in the activity.

- [ ] **Step 5: Show the control map at launch**

In `MainActivity.onCreate`, after adding `card` to `root`:

```kotlin
        card.show()
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ card.hide() }, 5000L)
```

- [ ] **Step 6: Verify on the emulator**

Run: `cd android && JAVA_HOME=~/android-studio/jbr ./gradlew installDebug`

Check: the control map appears at launch and clears after ~5 s; OPEN reopens it; BAND closes whatever is open and flashes `HOLD TO EXIT` when nothing is; holding BAND exits; tapping the volume knob blacks the screen and any touch wakes it; dragging the knob still only changes volume.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/io/github/youxufkhan/carozerra/Overlays.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/FaceplateView.kt \
        android/app/src/main/java/io/github/youxufkhan/carozerra/MainActivity.kt
git commit -m "feat(android): blackout, control map and the about card"
```

---

### Task 14: CI

With no physical head unit, the emulator run is the only verification there is — so it has to be in CI rather than in somebody's memory of having tried it once.

**Files:**
- Create: `.github/workflows/android-check.yml`

**Interfaces:**
- Consumes: the Gradle build from Task 1, `SelftestTest` from Task 5
- Produces: an `app-debug.apk` artifact on every relevant push

- [ ] **Step 1: Write the workflow**

`.github/workflows/android-check.yml`:

```yaml
name: Build APK

on:
  push:
    paths: ['android/**', 'assets/**', '.github/workflows/android-check.yml']
  pull_request:
    paths: ['android/**', 'assets/**', '.github/workflows/android-check.yml']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'          # AGP 8.13 does not support the JDK 25 on dev machines
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and unit-test
        working-directory: android
        run: ./gradlew assembleDebug testDebugUnitTest

      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm

      - name: Selftest on an emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          target: default
          arch: x86_64
          profile: pixel
          working-directory: android
          script: ./gradlew connectedDebugAndroidTest

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: carozerra-debug-apk
          path: android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Push and watch it**

```bash
git add .github/workflows/android-check.yml
git commit -m "ci(android): build, unit-test and selftest on an API 29 emulator"
git push -u origin feat/android-head-unit
```

Run: `gh run watch`

Expected: green. If the emulator step times out, raise `api-level` to 30 before reducing coverage — dropping the emulator job removes the only verification this app has.

---

### Task 15: Release wiring and documentation

**Files:**
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`, `ROADMAP.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: the debug APK from Task 14
- Produces: an APK attached to every tagged release

- [ ] **Step 1: Add the Android job to `release.yml`**

Add a `build-android` job mirroring the existing `build-windows` job: `actions/setup-java@v4` with Temurin 21, `cd android && ./gradlew assembleDebug`, then upload
`android/app/build/outputs/apk/debug/app-debug.apk` renamed to
`carozerra_<version>_android.apk`. Add it to the `release` job's `needs:` list and to the file list of the release step, exactly as the `.exe` is handled.

- [ ] **Step 2: Document it in the README**

Add an `### Android (beta)` section under the existing Windows one, covering:

- sideload only, no Play Store — these units ship without Play Services
- `adb install carozerra_<version>_android.apk`
- **debug-signed**, so uninstall before upgrading; a release keystore comes when the app leaves beta
- minSdk 29, landscape, immersive, keeps the screen on
- which branch the emulator took for audio capture (recorded in Task 11 Step 6), stated plainly: if capture is denied, the meter zone stays hidden and AUDIO reports `NO SIGNAL`
- **untested on real head-unit hardware** — the same caveat the Windows build carries, for the same reason

- [ ] **Step 3: Update the roadmap**

Move **Android App for Head Units** out of 🔴 Long-Term into 🟢 with the shipped-as-beta treatment the Windows Build entry uses: what is done, and what is open (hardware verification, release signing, whether output-mix capture works on a real unit).

Leave the **Back-Port the Android Hitboxes to the Desktop App** entry in place — it is still deferred, and the ten values it refers to are now measured and verified.

- [ ] **Step 4: Update the changelog**

Add an `### Added` entry under `[Unreleased]`: the Android head-unit app, 17 live faceplate controls mapped from the DEH-P7600MP manual, composited OEL overlays, live level meters, and permission-free transport.

- [ ] **Step 5: Commit and open the PR**

```bash
git add .github/workflows/release.yml README.md ROADMAP.md CHANGELOG.md
git commit -m "docs(android): document the beta APK and wire it into releases"
git push
gh pr create --title "Android app for head units (beta)" --body "..."
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task: decoder → 2, geometry and the ten new hitboxes → 3 and 7, assets and LRU → 4, rendering and glow → 5 and 6, all 17 controls → 8, 9, 10, 12, 13, overlay zones → 10, meters → 11, gallery → 12, head-unit window flags → 5, build and toolchain → 1, testing → 2, 3, 4, 5, CI and release → 14 and 15.

**Deliberate deviations from the spec, both narrowing:**

1. The spec lists `Overlays.kt` as one file; the plan puts `ControlMap` and `CardOverlay` there too rather than adding a ninth file. Both are OEL-space content drawn over the faceplate, which is that file's stated responsibility.
2. The spec describes left and right meter columns from separate channels. `Visualizer(0)` delivers a mono mix, so Task 11 mirrors one level across both columns — which the spec already permits ("mirrored where it does not").

**Known gap, resolved in-task:** Task 11 Step 6 cannot predict whether the emulator allows output-mix capture. The plan handles this by making *both* outcomes valid results and requiring the observed branch to be recorded, because Task 15 Step 2 has to document it.

**Type consistency:** `LkdClip`, `RenderClip`, `Hit`, `Control`, `Box`, `Category` are defined once and referenced with the same names and members throughout. `ClipRepository.render()` is the only clip accessor after Task 4; no task calls a `frames()`/`glowFrames()` pair that an earlier draft of the interface block mentioned.

