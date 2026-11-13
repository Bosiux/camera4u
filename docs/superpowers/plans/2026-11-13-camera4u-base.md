# camera4u Base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the FilmCamera-Android fork into camera4u: a photo-only Pixel 8a camera with curated cinematic looks, iPhone-style skin protection, cinema framing, real manual controls, DNG beside the JPEG, self-signed and installed over adb.

**Architecture:** One `app` module. CameraX binds the camera and hands frames to an OpenGL ES 3 pipeline (grade → bloom → composite) that already exists in the fork. Looks are presets (LUT + adjustments) on top of that pipeline; manual controls go through CameraX's Camera2 interop; DNG comes from CameraX's `OUTPUT_FORMAT_RAW_JPEG`.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Gradle 9.6, Jetpack Compose (BOM 2026.09.00), Material 3, CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`), OpenGL ES 3.0 GLSL, JUnit 4, AndroidX test runner.

Spec: `docs/superpowers/specs/2026-11-13-camera4u-design.md` (branch `docs`).

## Global Constraints

- Single target device: Google Pixel 8a (`akita`), Android 17, API 37. `minSdk = 29`, `targetSdk = 37`, `compileSdk = 37` stay as they are.
- applicationId and namespace: `it.bosiux.camera4u`. App name: `camera4u`. Album: `Pictures/camera4u`.
- No new dependencies in this plan. (ML Kit arrives only in the phase 2 relighting plan.)
- Everything committed to the repo is in English: code comments, commit messages, README.
- Commit messages: no `Co-Authored-By` trailer, ever.
- Working docs, screenshots and verification images go to the `docs` branch worktree at `~/Documents/camera4u-docs`, under `images/<task-name>/`. Never commit them to `main`.
- Camera ranges (ISO, exposure time, EV, focus) are read from `CameraCharacteristics` at bind time, never hard-coded. Device values for reference only: ISO 50–9846, exposure 59,896 ns – 16 s, EV −24..24 step 1/6, min focus 10.2 diopters.
- Every task ends with: unit tests green (`./gradlew :app:testDebugUnitTest`), app built and installed on the phone, a screenshot captured over adb into the docs worktree, and a commit on `main`.
- The phone is reachable over wireless debugging (it shows in `adb devices` as `adb-43011JEKB20347-...`). If `adb devices` is empty, run `adb mdns services` and `adb connect <ip:port>` from its output.
- Ponytail rules apply: no abstractions for one use, shortest working diff, mark deliberate shortcuts with a `// ponytail:` comment.

## Repository layout after this plan

```
app/src/main/java/it/bosiux/camera4u/
├── MainActivity.kt                      (renamed package, otherwise unchanged)
├── FilmCameraViewModel.kt               (+ selectLook)
├── camera/CameraModel.kt                (+ look, aspect, pro controls, RAW_JPEG capture)
├── camera/ProControls.kt                (new: manual exposure / WB / focus via Camera2 interop)
├── camera/PhotoWriter.kt                (+ DNG output options, shared base name)
├── model/Look.kt                        (new: curated looks)
├── model/Aspect.kt                      (new: framing enum + crop math)
├── model/FilmAdjustments.kt             (+ skinProtect)
├── rendering/FilmShaders.kt             (+ skin protection in the grade pass)
├── rendering/FilmRenderer.kt            (+ uSkinProtect uniform)
├── ui/camera/CameraScreen.kt            (+ aspect and RAW buttons, ProBar, LookStrip)
├── ui/camera/LookStrip.kt               (new: replaces FilmStrip on the main screen)
├── ui/camera/FilmStrip.kt               (StripItem made internal, otherwise unchanged)
├── ui/camera/ProBar.kt                  (new: chips + contextual slider)
├── ui/camera/Viewfinder.kt              (+ aspect matte, long-press lock)
app/src/androidTest/java/it/bosiux/camera4u/PipelineIdentityTest.kt   (new)
app/src/test/java/it/bosiux/camera4u/LookTest.kt                      (new)
app/src/test/java/it/bosiux/camera4u/AspectTest.kt                    (new)
app/src/test/java/it/bosiux/camera4u/ProControlsTest.kt               (new)
tool/screenshot.sh                       (new: adb screenshot into the docs worktree)
tool/perf.sh                             (new: gfxinfo frame stats)
tool/verify_lut.sh                       (new: ffmpeg lut3d vs app render)
```

---

### Task 1: Rename to camera4u, sign, install

**Files:**
- Move: `app/src/main/java/io/github/yahiaangelo/filmcamera/**` → `app/src/main/java/it/bosiux/camera4u/**`
- Move: `app/src/test/java/io/github/yahiaangelo/filmcamera/**` → `app/src/test/java/it/bosiux/camera4u/**`
- Modify: `app/build.gradle.kts` (namespace, applicationId), `settings.gradle.kts` (rootProject.name), `app/src/main/res/values/strings.xml`, `app/src/main/java/it/bosiux/camera4u/camera/PhotoWriter.kt` (album, software tag), `README.md`
- Create (outside repo): `~/Documents/camera4u-keys/camera4u-release.jks`, `~/Documents/camera4u-keys/key.properties`
- Create (git-ignored): `keystore.properties`
- Create: `tool/screenshot.sh`, `tool/perf.sh`

**Interfaces:**
- Produces: package `it.bosiux.camera4u` for every later task; album constant `PhotoWriter.ALBUM = "camera4u"`.

- [ ] **Step 1: Move the source trees and rewrite the package name**

```bash
cd ~/Documents/camera4u
git mv app/src/main/java/io/github/yahiaangelo/filmcamera app/src/main/java/it/bosiux/camera4u
git mv app/src/test/java/io/github/yahiaangelo/filmcamera app/src/test/java/it/bosiux/camera4u
rmdir -p app/src/main/java/io/github/yahiaangelo app/src/test/java/io/github/yahiaangelo 2>/dev/null; true
grep -rl "io.github.yahiaangelo.filmcamera" app/src app/build.gradle.kts README.md docs | xargs sed -i '' 's/io\.github\.yahiaangelo\.filmcamera/it.bosiux.camera4u/g'
grep -n "namespace\|applicationId" app/build.gradle.kts
grep -rn "yahiaangelo.filmcamera" app/src app/build.gradle.kts || echo "no old package left"
```

Expected: both lines show `it.bosiux.camera4u`.

- [ ] **Step 2: Rename the user-visible strings**

`app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">camera4u</string>
</resources>
```

`settings.gradle.kts`: change `rootProject.name = "Film Camera"` (or whatever is there) to `rootProject.name = "camera4u"`.

`app/src/main/java/it/bosiux/camera4u/camera/PhotoWriter.kt`:

```kotlin
    private const val ALBUM = "camera4u"
```

and

```kotlin
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "camera4u")
```

and the file name prefix:

```kotlin
        val name = "C4U_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + ".jpg"
```

- [ ] **Step 3: Update README attribution**

Replace the README title block with:

```markdown
<h1 align="center">camera4u</h1>

<p align="center">
  A Pixel 8a camera with cinematic looks, skin-aware grading, cinema framing and real manual controls.
  Fork of <a href="https://github.com/YahiaAngelo/FilmCamera-Android">Film Camera for Android</a> (MIT).
</p>
```

Keep the rest of the README; update the debug launch command to the new package:

```sh
adb shell am start -n it.bosiux.camera4u/.MainActivity --ez sampleScene true
```

- [ ] **Step 4: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: BUILD SUCCESSFUL, no failures (the tests use `src/main/assets` relative paths, unaffected by the move).

- [ ] **Step 5: Generate the signing key and wire it in**

```bash
mkdir -p ~/Documents/camera4u-keys
PASS=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
keytool -genkeypair -v -keystore ~/Documents/camera4u-keys/camera4u-release.jks -alias camera4u \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=camera4u, O=bosiux, C=IT"
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=camera4u\nkeyPassword=%s\n' \
  "$HOME/Documents/camera4u-keys/camera4u-release.jks" "$PASS" "$PASS" > ~/Documents/camera4u-keys/key.properties
cp ~/Documents/camera4u-keys/key.properties ~/Documents/camera4u/keystore.properties
cd ~/Documents/camera4u && git check-ignore keystore.properties
```

Expected: `keystore.properties` is printed by `git check-ignore` (already ignored by the fork's `.gitignore`).

- [ ] **Step 6: Add the screenshot and perf helper scripts**

`tool/screenshot.sh`:

```bash
#!/bin/sh
# Captures the phone screen into the docs worktree: tool/screenshot.sh <task-name> <file-name>
set -e
DIR="$HOME/Documents/camera4u-docs/images/$1"
mkdir -p "$DIR"
adb exec-out screencap -p > "$DIR/$2.png"
echo "$DIR/$2.png"
```

`tool/perf.sh`:

```bash
#!/bin/sh
# Prints viewfinder frame statistics. Reset, wait, then read.
adb shell dumpsys gfxinfo it.bosiux.camera4u reset > /dev/null
sleep "${1:-10}"
adb shell dumpsys gfxinfo it.bosiux.camera4u | grep -E "Total frames|Janky frames:|50th|90th|99th"
```

Run: `chmod +x tool/*.sh`

- [ ] **Step 7: Build release, remove the upstream app, install, launch, screenshot**

```bash
cd ~/Documents/camera4u
./gradlew :app:assembleRelease -q
adb uninstall io.github.yahiaangelo.filmcamera || true
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant it.bosiux.camera4u android.permission.CAMERA
adb shell am start -n it.bosiux.camera4u/.MainActivity
sleep 3
tool/screenshot.sh task1-rename launch
tool/perf.sh 10
```

Expected: `Success` from install, viewfinder visible in the screenshot, 90th percentile ≤ 33 ms.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Rename to camera4u (it.bosiux.camera4u), add release signing and adb helper scripts"
git push origin main
```

---

### Task 2: Curated looks

**Files:**
- Create: `app/src/main/java/it/bosiux/camera4u/model/Look.kt`
- Create: `app/src/main/assets/films/fuji_3513_cuspclip.cube`, `app/src/main/assets/films/fuji_3513_cuspclip.jpg`
- Modify: `app/src/main/java/it/bosiux/camera4u/library/FilmLibrary.kt` (bundledIds)
- Modify: `app/src/main/java/it/bosiux/camera4u/camera/CameraModel.kt` (selectedLookId, selectLook)
- Modify: `app/src/main/java/it/bosiux/camera4u/FilmCameraViewModel.kt` (selectLook)
- Create: `app/src/main/java/it/bosiux/camera4u/ui/camera/LookStrip.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/FilmStrip.kt` (`StripItem` becomes `internal`)
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/CameraScreen.kt` (use LookStrip)
- Test: `app/src/test/java/it/bosiux/camera4u/LookTest.kt`

**Interfaces:**
- Produces: `data class Look(id: String, name: String, lutId: String?, adjustments: FilmAdjustments)`, `object Looks { val all: List<Look>; fun byId(id: String?): Look? }`, `CameraModel.selectedLookId: String?`, `CameraModel.selectLook(look: Look, openLut: (() -> String)?)`, `FilmCameraViewModel.selectLook(look: Look)`.

- [ ] **Step 1: Bundle the Fuji 3513 print LUT**

```bash
cd ~/Documents/camera4u
curl -fsSL https://raw.githubusercontent.com/YahiaAngelo/Film-Luts/main/luts/print/fuji_3513_cuspclip.cube -o app/src/main/assets/films/fuji_3513_cuspclip.cube
curl -fsSL https://raw.githubusercontent.com/YahiaAngelo/Film-Luts/main/thumbnails/print/fuji_3513_cuspclip.jpg -o app/src/main/assets/films/fuji_3513_cuspclip.jpg
head -3 app/src/main/assets/films/fuji_3513_cuspclip.cube
```

Expected: a `.cube` header (`TITLE`/`LUT_3D_SIZE`), not HTML.

In `FilmLibrary.kt` add to `bundledIds`:

```kotlin
            "luts/print/fuji_3513_cuspclip.cube",
```

- [ ] **Step 2: Write the failing test**

`app/src/test/java/it/bosiux/camera4u/LookTest.kt`:

```kotlin
package it.bosiux.camera4u

import it.bosiux.camera4u.library.FilmLibrary
import it.bosiux.camera4u.model.Looks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test
    fun everyLookLutIsBundled() {
        for (look in Looks.all) {
            val lut = look.lutId ?: continue
            assertTrue("${look.name} uses $lut which is not bundled", lut in FilmLibrary.bundledIds)
        }
    }

    @Test
    fun idsAreUniqueAndNoneComesFirst() {
        assertEquals(Looks.all.size, Looks.all.map { it.id }.toSet().size)
        assertNull(Looks.all.first().lutId)
        assertEquals(Looks.all.first(), Looks.byId("none"))
        assertNull(Looks.byId("missing"))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.LookTest -q`
Expected: compilation error, `Looks` unresolved.

- [ ] **Step 4: Implement `Look.kt`**

```kotlin
package it.bosiux.camera4u.model

/** A look is a LUT plus the adjustments that give it its character. */
data class Look(
    val id: String,
    val name: String,
    /** Film id (`luts/...cube`) or `null` for no LUT. */
    val lutId: String?,
    val adjustments: FilmAdjustments,
)

object Looks {
    // ponytail: hand-tuned constants; no editor for user-defined looks until someone asks.
    val all: List<Look> = listOf(
        Look("none", "None", null, FilmAdjustments(grain = 0f, vignette = 0f)),
        Look(
            "cinema", "Cinema Print", "luts/print/kodak_2383_cuspclip.cube",
            FilmAdjustments(contrast = 0.1f, glow = 0.15f, halation = 0.35f, grain = 0.2f, vignette = 0.25f),
        ),
        Look(
            "cinema_fuji", "Cinema Print Fuji", "luts/print/fuji_3513_cuspclip.cube",
            FilmAdjustments(contrast = 0.1f, temperature = -0.1f, glow = 0.15f, halation = 0.3f, grain = 0.2f, vignette = 0.25f),
        ),
        Look(
            "natural", "Natural", "luts/negative_new/kodak_portra_400.cube",
            FilmAdjustments(lutIntensity = 0.8f, grain = 0.15f, vignette = 0.1f),
        ),
        Look(
            "chrome", "Chrome", "luts/fujixtransiii/fuji_xtrans_iii_classic_chrome.cube",
            FilmAdjustments(contrast = 0.15f, saturation = -0.1f, grain = 0.25f, vignette = 0.2f),
        ),
        Look(
            "noir", "Noir", "luts/bw/kodak_tri-x_400.cube",
            FilmAdjustments(contrast = 0.25f, grain = 0.45f, vignette = 0.35f),
        ),
    )

    fun byId(id: String?): Look? = all.firstOrNull { it.id == id }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.LookTest -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Add look selection to `CameraModel`**

In `CameraModel.kt`, next to `selectedFilmId`:

```kotlin
    /** The curated look in use; `null` once the user picks a film or edits adjustments by hand. */
    var selectedLookId: String? by mutableStateOf(prefs.getString(KEY_SELECTED_LOOK, null))
        private set
```

Add `const val KEY_SELECTED_LOOK = "selectedLook"` to the companion object.

Add after `selectFilm`:

```kotlin
    /** Applies a look: its LUT and its adjustments together. */
    fun selectLook(look: Look, openLut: (() -> String)?) {
        selectFilm(null, null)
        selectedLookId = look.id
        prefs.edit { putString(KEY_SELECTED_LOOK, look.id) }
        updateAdjustments(look.adjustments)
        if (look.lutId != null && openLut != null) {
            lutJob = scope.launch {
                try {
                    val cube = withContext(Dispatchers.Default) { CubeLut.parse(openLut()) }
                    if (selectedLookId == look.id) {
                        selectedFilmId = look.lutId
                        renderer.setLut(cube)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    captureError = "Couldn't load ${look.name}. ${e.message.orEmpty()}"
                }
            }
        }
    }
```

Import `it.bosiux.camera4u.model.Look`.

In `selectFilm`, clear the look when the user picks a film directly: add as the first line

```kotlin
        if (film?.id != selectedFilmId) { selectedLookId = null; prefs.edit { remove(KEY_SELECTED_LOOK) } }
```

Note: `selectLook` calls `selectFilm(null, null)` first, which clears the look id; `selectLook` then sets it again. That order is intentional.

- [ ] **Step 7: Expose it from the view model and restore it at launch**

`FilmCameraViewModel.kt`:

```kotlin
    init {
        val look = Looks.byId(camera.selectedLookId)
        if (look != null) selectLook(look) else select(library.film(camera.selectedFilmId))
        viewModelScope.launch { library.refreshCatalog() }
    }

    fun selectLook(look: Look) {
        val film = look.lutId?.let(library::film)
        camera.selectLook(look, film?.let(library::lutReader))
    }
```

Imports: `it.bosiux.camera4u.model.Look`, `it.bosiux.camera4u.model.Looks`.

- [ ] **Step 8: Add `LookStrip` and show it on the main screen**

In `FilmStrip.kt` change `private fun StripItem(` to `internal fun StripItem(`.

`app/src/main/java/it/bosiux/camera4u/ui/camera/LookStrip.kt`:

```kotlin
package it.bosiux.camera4u.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import it.bosiux.camera4u.FilmCameraViewModel
import it.bosiux.camera4u.model.Looks
import it.bosiux.camera4u.ui.components.FilmThumbnail
import it.bosiux.camera4u.ui.components.NoFilmThumbnail

/** Horizontal picker of the curated looks, plus a button into the full film library. */
@Composable
fun LookStrip(viewModel: FilmCameraViewModel, onBrowse: () -> Unit) {
    val camera = viewModel.camera
    val library = viewModel.library
    val haptics = LocalHapticFeedback.current

    LazyRow(
        modifier = Modifier.fillMaxWidth().height(112.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Looks.all, key = { it.id }) { look ->
            val film = look.lutId?.let(library::film)
            StripItem(title = look.name, isSelected = camera.selectedLookId == look.id, onClick = {
                viewModel.selectLook(look)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }) { modifier ->
                if (film != null) FilmThumbnail(library.thumbnailModel(film), modifier) else NoFilmThumbnail(modifier)
            }
        }
        item(key = "library") {
            Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalIconButton(
                    onClick = onBrowse,
                    shape = RoundedCornerShape(14.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White,
                    ),
                    modifier = Modifier.size(60.dp),
                ) { Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Film Library") }
                Text("Films", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

In `CameraScreen.kt` replace the `FilmStrip(...)` call with:

```kotlin
                LookStrip(viewModel = viewModel, onBrowse = { showsLibrary = true })
```

and change the viewfinder chip label so a look shows its own name:

```kotlin
                    filmName = Looks.byId(camera.selectedLookId)?.name
                        ?: library.film(camera.selectedFilmId)?.displayName ?: "Original",
```

Import `it.bosiux.camera4u.model.Looks` in `CameraScreen.kt`.

- [ ] **Step 9: Build, install, try every look, screenshot**

```bash
./gradlew :app:testDebugUnitTest -q && ./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n it.bosiux.camera4u/.MainActivity
sleep 3; tool/screenshot.sh task2-looks strip-none
```

Tap "Cinema Print" on the phone (or over adb: `adb shell input tap <x> <y>` using the strip position from the screenshot), then:

```bash
tool/screenshot.sh task2-looks strip-cinema
tool/perf.sh 10
```

Expected: chip at the top of the viewfinder reads "Cinema Print", the image shows halation around bright areas, 90th percentile ≤ 33 ms.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add curated looks strip with cinema print, natural, chrome and noir presets"
git push origin main
```

---

### Task 3: Skin protection

**Files:**
- Modify: `app/src/main/java/it/bosiux/camera4u/model/FilmAdjustments.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/rendering/FilmShaders.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/rendering/FilmRenderer.kt`
- Test: `app/src/test/java/it/bosiux/camera4u/ModelTest.kt` (existing `FilmAdjustmentsTest`)

**Interfaces:**
- Produces: `FilmAdjustments.skinProtect: Float` (0..1, default 0.5), uniform `uSkinProtect` in the grade shader.

- [ ] **Step 1: Write the failing test**

Add to `FilmAdjustmentsTest` in `ModelTest.kt`:

```kotlin
    @Test
    fun skinProtectRoundTripsAndDefaultsToHalf() {
        assertEquals(0.5f, FilmAdjustments.Default.skinProtect)
        val value = FilmAdjustments(skinProtect = 0.9f)
        assertEquals(0.9f, FilmAdjustments.fromJson(value.toJson()).skinProtect)
        assertEquals(1f, FilmAdjustments.fromJson("""{"skin": 7}""").skinProtect)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "it.bosiux.camera4u.FilmAdjustmentsTest" -q`
Expected: compilation error, `skinProtect` unresolved.

- [ ] **Step 3: Add the field and its slider control**

In `FilmAdjustments`:

```kotlin
    val skinProtect: Float = 0.5f, // 0..1, keeps skin hue and saturation through the look
```

In `AdjustmentControl.all`, after the `tint` entry:

```kotlin
            AdjustmentControl("skin", "Skin", Icons.Outlined.Face, Group.Color, 0f..1f,
                { it.skinProtect }, { a, v -> a.copy(skinProtect = v) }),
```

Import `androidx.compose.material.icons.outlined.Face`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "it.bosiux.camera4u.FilmAdjustmentsTest" -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Add the skin mask to the grade shader**

In `FilmShaders.grade(...)`, add the uniform next to the others:

```glsl
uniform float uSkinProtect;
```

Add these helpers below `linearToSrgb`:

```glsl
// Hue in 0..1 (red = 0) and saturation, for the skin mask.
vec2 hueSat(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    float s = mx > 1e-4 ? d / mx : 0.0;
    if (d < 1e-4) return vec2(0.0, s);
    float h = (mx == c.r) ? mod((c.g - c.b) / d, 6.0) : (mx == c.g) ? (c.b - c.r) / d + 2.0 : (c.r - c.g) / d + 4.0;
    return vec2(h / 6.0, s);
}

// 1 on skin-like colours (hue ~10..40 degrees, medium saturation), 0 elsewhere.
float skinMask(vec3 c) {
    vec2 hs = hueSat(c);
    float hue = smoothstep(0.015, 0.035, hs.x) * (1.0 - smoothstep(0.09, 0.125, hs.x));
    float sat = smoothstep(0.1, 0.22, hs.y) * (1.0 - smoothstep(0.7, 0.9, hs.y));
    float val = smoothstep(0.12, 0.3, max(c.r, max(c.g, c.b)));
    return hue * sat * val;
}
```

In `main()`, right after the exposure/white balance block (`c = clamp(linearToSrgb(lin), 0.0, 1.0);`) add:

```glsl
    vec3 pre = c;
```

and right after the saturation line (`c = mix(vec3(luma(c)), c, 1.0 + uSaturation);`) add:

```glsl
    // Skin protection: keep the look's luminance but restore the original hue and saturation on skin.
    if (uSkinProtect > 0.0) {
        float m = skinMask(pre) * uSkinProtect;
        vec3 restored = pre * (luma(c) / max(luma(pre), 1e-4));
        c = mix(c, clamp(restored, 0.0, 1.0), m);
    }
```

- [ ] **Step 6: Feed the uniform**

In `FilmRenderer.encodeGradeAndBloom`, after `program.set("uFade", a.fade)`:

```kotlin
        program.set("uSkinProtect", a.skinProtect)
```

- [ ] **Step 7: Build, install, verify on a face**

```bash
./gradlew :app:testDebugUnitTest -q && ./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n it.bosiux.camera4u/.MainActivity
```

Point the phone at a face or a hand, select "Noir" (a B&W look makes the effect obvious), open Adjustments, set Skin to 0 and then to 1, screenshot both:

```bash
tool/screenshot.sh task3-skin noir-skin-0
tool/screenshot.sh task3-skin noir-skin-1
```

Expected: with Skin at 1 the hand keeps its colour while the rest stays black and white; the sample scene (`--ez sampleScene true` on a debug build) can be used instead of a live subject.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add skin protection to the grade pass, iPhone Photographic Styles style"
git push origin main
```

---

### Task 4: Cinema framing (aspect ratio)

**Files:**
- Create: `app/src/main/java/it/bosiux/camera4u/model/Aspect.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/camera/CameraModel.kt` (aspect state, crop on capture)
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/Viewfinder.kt` (matte overlay)
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/CameraScreen.kt` (aspect button)
- Test: `app/src/test/java/it/bosiux/camera4u/AspectTest.kt`

**Interfaces:**
- Produces: `enum class Aspect(label: String, ratio: Float)` with `Aspect.cropRect(width: Int, height: Int): android.graphics.Rect` (pure, testable), `CameraModel.aspect: Aspect`, `CameraModel.cycleAspect()`.

Deviation from the spec, agreed as a simplification: the matte is a Compose overlay, not a shader pass, and `aspect` lives in `CameraModel`, not in `FilmAdjustments`, because it is framing rather than look.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/it/bosiux/camera4u/AspectTest.kt`:

```kotlin
package it.bosiux.camera4u

import it.bosiux.camera4u.model.Aspect
import org.junit.Assert.assertEquals
import org.junit.Test

class AspectTest {
    @Test
    fun fourThreeIsNoCrop() {
        val r = Aspect.FourThree.cropRect(4000, 3000)
        assertEquals(listOf(0, 0, 4000, 3000), listOf(r.left, r.top, r.right, r.bottom))
    }

    @Test
    fun landscapeCropsHeight() {
        val r = Aspect.Wide.cropRect(4000, 3000)
        assertEquals(4000, r.width())
        assertEquals(2250, r.height())
        assertEquals(375, r.top)
    }

    @Test
    fun portraitCropsWidth() {
        val r = Aspect.Scope.cropRect(3000, 4000)
        assertEquals(4000, r.height())
        assertEquals(1674, r.width())
        assertEquals(663, r.left)
    }

    @Test
    fun shortSideFraction() {
        assertEquals(0.75f, Aspect.Wide.shortSideFraction, 1e-4f)
        assertEquals(1f, Aspect.FourThree.shortSideFraction, 1e-4f)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.AspectTest -q`
Expected: compilation error, `Aspect` unresolved.

- [ ] **Step 3: Implement `Aspect.kt`**

```kotlin
package it.bosiux.camera4u.model

import android.graphics.Rect
import kotlin.math.roundToInt

/** Framing applied as a centred crop of the long side's ratio to the short side. */
enum class Aspect(val label: String, val ratio: Float) {
    FourThree("4:3", 4f / 3f),
    ThreeTwo("3:2", 3f / 2f),
    Wide("16:9", 16f / 9f),
    Scope("2.39:1", 2.39f);

    /** How much of the sensor's short side survives the crop (the sensor is 4:3). */
    val shortSideFraction: Float get() = (4f / 3f) / ratio

    /** Centred crop of an upright image of any orientation. */
    fun cropRect(width: Int, height: Int): Rect {
        val landscape = width >= height
        val long = if (landscape) width else height
        val short = (long / ratio).roundToInt().coerceAtMost(if (landscape) height else width)
        return if (landscape) {
            val top = (height - short) / 2
            Rect(0, top, width, top + short)
        } else {
            val left = (width - short) / 2
            Rect(left, 0, left + short, height)
        }
    }

    fun next(): Aspect = entries[(ordinal + 1) % entries.size]
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.AspectTest -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Add aspect state and crop on capture**

In `CameraModel.kt`:

```kotlin
    var aspect: Aspect by mutableStateOf(
        prefs.getString(KEY_ASPECT, null)?.let { id -> Aspect.entries.firstOrNull { it.name == id } } ?: Aspect.FourThree,
    )
        private set

    fun cycleAspect() {
        aspect = aspect.next()
        prefs.edit { putString(KEY_ASPECT, aspect.name) }
    }
```

Add `const val KEY_ASPECT = "aspect"` to the companion. Import `it.bosiux.camera4u.model.Aspect`.

In `capture()`, replace

```kotlin
                    val processed = renderer.renderPhoto(source, rotation, mirrored)
```

with

```kotlin
                    val rendered = renderer.renderPhoto(source, rotation, mirrored)
                    val processed = cropToAspect(rendered, aspect)
```

and add the helper:

```kotlin
    /** Centred crop to the chosen framing; 4:3 returns the bitmap untouched. */
    private fun cropToAspect(bitmap: Bitmap, aspect: Aspect): Bitmap {
        val rect = aspect.cropRect(bitmap.width, bitmap.height)
        if (rect.width() == bitmap.width && rect.height() == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height()).also { bitmap.recycle() }
    }
```

- [ ] **Step 6: Draw the matte in the viewfinder**

In `Viewfinder.kt`, after `if (camera.showsGrid) GridOverlay()` add:

```kotlin
            if (camera.aspect != Aspect.FourThree) AspectMatte(camera.aspect.shortSideFraction)
```

and the composable:

```kotlin
/** Darkens the sides that the chosen framing will crop away. The viewfinder is portrait, so the short side is its width. */
@Composable
private fun AspectMatte(shortSideFraction: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val band = size.width * (1f - shortSideFraction) / 2f
        val color = Color.Black.copy(alpha = 0.7f)
        drawRect(color, Offset.Zero, Size(band, size.height))
        drawRect(color, Offset(size.width - band, 0f), Size(band, size.height))
    }
}
```

Imports: `androidx.compose.ui.geometry.Size`, `it.bosiux.camera4u.model.Aspect`.

- [ ] **Step 7: Add the aspect button to the top bar**

In `CameraScreen.kt` `TopBar`, after the grid button:

```kotlin
        AspectButton(camera.aspect.label, onClick = camera::cycleAspect)
```

and:

```kotlin
@Composable
private fun AspectButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        modifier = Modifier.height(44.dp).semantics { contentDescription = "Aspect ratio $label" },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}
```

Imports: `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.ui.text.font.FontWeight`, `androidx.compose.ui.unit.sp`.

- [ ] **Step 8: Build, install, verify crop on disk**

```bash
./gradlew :app:testDebugUnitTest -q && ./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n it.bosiux.camera4u/.MainActivity
```

Tap the aspect button until it reads `2.39:1`, screenshot, press volume-down to shoot, then check the saved file's size:

```bash
tool/screenshot.sh task4-aspect matte-scope
adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 4
adb shell "ls -t /sdcard/Pictures/camera4u | head -1"
adb pull "/sdcard/Pictures/camera4u/$(adb shell 'ls -t /sdcard/Pictures/camera4u | head -1' | tr -d '\r')" /tmp/scope.jpg
sips -g pixelWidth -g pixelHeight /tmp/scope.jpg
```

Expected: portrait shot of 1674×4000 (or 1255×3000 at 12 MP: height / 2.39 wide).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add cinema framing: 3:2, 16:9 and 2.39:1 crop with viewfinder matte"
git push origin main
```

---

### Task 5: Pro controls (manual exposure, white balance, focus, lock)

**Files:**
- Create: `app/src/main/java/it/bosiux/camera4u/camera/ProControls.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/camera/CameraModel.kt` (own ProControls, bind, focus guard, long-press lock)
- Create: `app/src/main/java/it/bosiux/camera4u/ui/camera/ProBar.kt`
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/CameraScreen.kt` (ProBar between viewfinder and looks)
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/Viewfinder.kt` (long press)
- Test: `app/src/test/java/it/bosiux/camera4u/ProControlsTest.kt`

**Interfaces:**
- Produces: `class ProControls` with `Ranges`, state properties `manualExposure`, `iso`, `exposureNs`, `evIndex`, `awbMode`, `manualFocus`, `focusDiopters`, `locked`, `fun bind(camera: Camera)`, `fun apply()`, pure helpers `ProControls.sliderToLog(t, min, max)`, `ProControls.logToSlider(v, min, max)`, `ProControls.shutterLabel(ns)`.
- `CameraModel.pro: ProControls`, `CameraModel.lockAt(x, y, previewWidth, previewHeight)`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/it/bosiux/camera4u/ProControlsTest.kt`:

```kotlin
package it.bosiux.camera4u

import it.bosiux.camera4u.camera.ProControls
import org.junit.Assert.assertEquals
import org.junit.Test

class ProControlsTest {
    @Test
    fun logSliderMapsEndsAndMiddle() {
        assertEquals(50.0, ProControls.sliderToLog(0f, 50.0, 9846.0), 1e-6)
        assertEquals(9846.0, ProControls.sliderToLog(1f, 50.0, 9846.0), 1e-6)
        assertEquals(0.5f, ProControls.logToSlider(Math.sqrt(50.0 * 9846.0), 50.0, 9846.0), 1e-4f)
    }

    @Test
    fun shutterLabels() {
        assertEquals("1/100", ProControls.shutterLabel(10_000_000L))
        assertEquals("1/16700", ProControls.shutterLabel(59_896L))
        assertEquals("0.5 s", ProControls.shutterLabel(500_000_000L))
        assertEquals("16 s", ProControls.shutterLabel(16_000_000_000L))
    }

    @Test
    fun evLabel() {
        assertEquals("+1.0", ProControls.evLabel(6, 1.0 / 6))
        assertEquals("-0.5", ProControls.evLabel(-3, 1.0 / 6))
        assertEquals("0.0", ProControls.evLabel(0, 1.0 / 6))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.ProControlsTest -q`
Expected: compilation error, `ProControls` unresolved.

- [ ] **Step 3: Implement `ProControls.kt`**

```kotlin
package it.bosiux.camera4u.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Manual exposure, white balance and focus on top of CameraX, through the
 * Camera2 interop. Ranges come from the bound camera, never from constants.
 */
@OptIn(ExperimentalCamera2Interop::class)
class ProControls {
    class Ranges(
        val iso: Range<Int>,
        val exposureNs: Range<Long>,
        val ev: Range<Int>,
        val evStep: Double,
        /** 0 when the lens has fixed focus. */
        val maxDiopters: Float,
        val awbModes: List<Int>,
    )

    class WhiteBalance(val mode: Int, val label: String)

    var ranges: Ranges? by mutableStateOf(null)
        private set

    var manualExposure by mutableStateOf(false)
    var iso by mutableIntStateOf(400)
    var exposureNs by mutableLongStateOf(10_000_000L) // 1/100 s
    var evIndex by mutableIntStateOf(0)
    var awbMode by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    var manualFocus by mutableStateOf(false)
    var focusDiopters by mutableFloatStateOf(0f)
    /** AE and AWB lock, toggled by a long press on the viewfinder. */
    var locked by mutableStateOf(false)

    private var camera: Camera? = null

    /** Reads the bound camera's ranges and re-applies the current state. */
    fun bind(camera: Camera) {
        this.camera = camera
        val c = Camera2CameraInfo.from(camera.cameraInfo)
        val iso = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        val exposure = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
        val ev = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val maxDiopters = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(1)
        ranges = Ranges(iso, exposure, ev, step, maxDiopters, awb)
        this.iso = this.iso.coerceIn(iso.lower, iso.upper)
        exposureNs = exposureNs.coerceIn(exposure.lower, exposure.upper)
        evIndex = evIndex.coerceIn(ev.lower, ev.upper)
        if (awbMode !in awb) awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        if (maxDiopters == 0f) manualFocus = false
        focusDiopters = focusDiopters.coerceIn(0f, maxDiopters)
        locked = false
        apply()
    }

    /** Pushes the current state to the camera as capture request options. */
    fun apply() {
        val camera = camera ?: return
        val r = ranges ?: return
        val options = CaptureRequestOptions.Builder()
        if (manualExposure) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(r.iso.lower, r.iso.upper))
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(r.exposureNs.lower, r.exposureNs.upper))
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
        }
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked && awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (manualFocus && r.maxDiopters > 0f) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f, r.maxDiopters))
        }
        // setCaptureRequestOptions replaces everything set before, so a cleared key goes back to CameraX's default.
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        if (!manualExposure) camera.cameraControl.setExposureCompensationIndex(evIndex.coerceIn(r.ev.lower, r.ev.upper))
    }

    val whiteBalances: List<WhiteBalance>
        get() = listOf(
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO, "Auto"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Sun"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Cloud"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_SHADE, "Shade"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "Bulb"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "Tube"),
        ).filter { ranges?.awbModes?.contains(it.mode) ?: false }

    companion object {
        /** Maps a 0..1 slider to a log-spaced value between [min] and [max]. */
        fun sliderToLog(t: Float, min: Double, max: Double): Double = min * (max / min).pow(t.toDouble())

        fun logToSlider(value: Double, min: Double, max: Double): Float =
            (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

        /** "1/250" below a third of a second, "0.5 s" / "16 s" above. */
        fun shutterLabel(ns: Long): String {
            val s = ns / 1e9
            return when {
                s < 0.3 -> "1/" + (1.0 / s).roundToInt()
                s < 10 -> String.format(Locale.US, "%.1f s", s)
                else -> "${s.roundToInt()} s"
            }
        }

        fun evLabel(index: Int, step: Double): String = String.format(Locale.US, "%+.1f", index * step).replace("+0.0", "0.0")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests it.bosiux.camera4u.ProControlsTest -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Wire `ProControls` into `CameraModel`**

In `CameraModel.kt`:

```kotlin
    val pro = ProControls()
```

At the end of `bind(...)`, after `status = Status.Running`:

```kotlin
        pro.bind(camera)
```

In `focus(...)`, as the first line:

```kotlin
        if (pro.manualFocus) return
```

Add the long-press lock:

```kotlin
    /** Long press: focus and meter at the point, then hold exposure and white balance there. */
    fun lockAt(x: Float, y: Float, previewWidth: Float, previewHeight: Float) {
        if (pro.locked) { pro.locked = false; pro.apply(); camera?.cameraControl?.cancelFocusAndMetering(); return }
        focus(x, y, previewWidth, previewHeight)
        pro.locked = true
        pro.apply()
    }
```

Flash and manual exposure do not mix (AE off means no flash metering): in `cycleFlash()` add as the first line

```kotlin
        if (pro.manualExposure) return
```

and in the UI the flash button is hidden while manual exposure is on (Step 7).

- [ ] **Step 6: Add the `ProBar`**

`app/src/main/java/it/bosiux/camera4u/ui/camera/ProBar.kt`:

```kotlin
package it.bosiux.camera4u.ui.camera

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.bosiux.camera4u.camera.ProControls

private enum class ProField { Iso, Shutter, Ev, Wb, Focus }

/** AUTO/M, ISO, S, EV, WB and AF chips; the tapped chip opens one slider or a row of choices above. */
@Composable
fun ProBar(pro: ProControls, modifier: Modifier = Modifier) {
    val r = pro.ranges ?: return
    var open by rememberSaveable { mutableStateOf<ProField?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (open) {
            ProField.Iso -> LogSlider(
                value = pro.iso.toDouble(), min = r.iso.lower.toDouble(), max = r.iso.upper.toDouble(),
                label = "ISO ${pro.iso}",
            ) { pro.iso = it.toInt(); pro.apply() }
            ProField.Shutter -> LogSlider(
                value = pro.exposureNs.toDouble(), min = r.exposureNs.lower.toDouble(), max = r.exposureNs.upper.toDouble(),
                label = ProControls.shutterLabel(pro.exposureNs),
            ) { pro.exposureNs = it.toLong(); pro.apply() }
            ProField.Ev -> ValueSlider(
                value = pro.evIndex.toFloat(), range = r.ev.lower.toFloat()..r.ev.upper.toFloat(),
                steps = (r.ev.upper - r.ev.lower - 1).coerceAtLeast(0), label = "EV " + ProControls.evLabel(pro.evIndex, r.evStep),
            ) { pro.evIndex = it.toInt(); pro.apply() }
            ProField.Wb -> Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (wb in pro.whiteBalances) {
                    Chip(wb.label, selected = pro.awbMode == wb.mode) { pro.awbMode = wb.mode; pro.apply() }
                }
            }
            ProField.Focus -> if (pro.manualFocus) ValueSlider(
                value = pro.focusDiopters, range = 0f..r.maxDiopters, steps = 0,
                label = if (pro.focusDiopters < 0.05f) "∞" else String.format(java.util.Locale.US, "%.2f m", 1f / pro.focusDiopters),
            ) { pro.focusDiopters = it; pro.apply() }
            null -> Unit
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(if (pro.manualExposure) "M" else "AUTO", selected = pro.manualExposure) {
                pro.manualExposure = !pro.manualExposure
                pro.apply()
                open = if (pro.manualExposure) ProField.Iso else null
            }
            if (pro.manualExposure) {
                Chip("ISO ${pro.iso}", selected = open == ProField.Iso) { open = open.toggle(ProField.Iso) }
                Chip("S ${ProControls.shutterLabel(pro.exposureNs)}", selected = open == ProField.Shutter) { open = open.toggle(ProField.Shutter) }
            } else {
                Chip("EV ${ProControls.evLabel(pro.evIndex, r.evStep)}", selected = open == ProField.Ev) { open = open.toggle(ProField.Ev) }
            }
            Chip("WB " + (pro.whiteBalances.firstOrNull { it.mode == pro.awbMode }?.label ?: "Auto"), selected = open == ProField.Wb) {
                open = open.toggle(ProField.Wb)
            }
            if (r.maxDiopters > 0f) {
                Chip(if (pro.manualFocus) "MF" else "AF", selected = pro.manualFocus) {
                    pro.manualFocus = !pro.manualFocus
                    pro.apply()
                    open = if (pro.manualFocus) ProField.Focus else null
                }
            }
            if (pro.locked) Text("AE/AWB LOCK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun ProField?.toggle(field: ProField): ProField? = if (this == field) null else field

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.SemiBold) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.1f), labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        border = null,
        modifier = Modifier.height(32.dp),
    )
}

@Composable
private fun LogSlider(value: Double, min: Double, max: Double, label: String, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = ProControls.logToSlider(value, min, max),
            onValueChange = { onChange(ProControls.sliderToLog(it, min, max)) },
            modifier = Modifier.height(36.dp),
        )
    }
}

@Composable
private fun ValueSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, modifier = Modifier.height(36.dp))
    }
}
```

- [ ] **Step 7: Place the bar and the long press**

In `CameraScreen.kt`, between `Viewfinder(...)` and `LookStrip(...)`:

```kotlin
                ProBar(pro = camera.pro)
```

In `TopBar`, hide flash while manual exposure is on: change `if (camera.deviceInfo?.hasFlash == true)` to `if (camera.deviceInfo?.hasFlash == true && !camera.pro.manualExposure)`.

In `Viewfinder.kt`, the tap gesture block becomes:

```kotlin
                        detectTapGestures(
                            onTap = { offset ->
                                val point = Offset(offset.x / width, offset.y / height)
                                camera.focus(point.x, point.y, width, height)
                                focusPoint = point
                                focusId++
                            },
                            onLongPress = { offset ->
                                val point = Offset(offset.x / width, offset.y / height)
                                camera.lockAt(point.x, point.y, width, height)
                                focusPoint = point
                                focusId++
                            },
                        )
```

- [ ] **Step 8: Build, install, verify EXIF of a manual shot**

```bash
./gradlew :app:testDebugUnitTest -q && ./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n it.bosiux.camera4u/.MainActivity
```

On the phone: tap AUTO → M, set ISO to about 400 and S to about 1/100 (the chips show the values), screenshot, shoot with volume-down, pull the file and read its EXIF with the ExifInterface-equivalent `sips`:

```bash
tool/screenshot.sh task5-pro manual-iso400
adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 4
F=$(adb shell 'ls -t /sdcard/Pictures/camera4u/*.jpg | head -1' | tr -d '\r'); adb pull "$F" /tmp/manual.jpg
python3 - <<'EOF'
import struct
# Minimal EXIF reader: prints ISO (0x8827) and ExposureTime (0x829A) from /tmp/manual.jpg
data = open('/tmp/manual.jpg','rb').read()
i = data.find(b'Exif\x00\x00'); t = i + 6
end = '<' if data[t:t+2] == b'II' else '>'
off = struct.unpack(end+'I', data[t+4:t+8])[0]
def ifd(o):
    n = struct.unpack(end+'H', data[t+o:t+o+2])[0]
    return [(struct.unpack(end+'H', data[t+o+2+12*k:t+o+4+12*k])[0], data[t+o+2+12*k:t+o+14+12*k]) for k in range(n)]
for tag, e in ifd(off):
    if tag == 0x8769:
        for tag2, e2 in ifd(struct.unpack(end+'I', e[8:12])[0]):
            if tag2 == 0x8827: print('ISO', struct.unpack(end+'H', e2[8:10])[0])
            if tag2 == 0x829A:
                p = struct.unpack(end+'I', e2[8:12])[0]; a, b = struct.unpack(end+'II', data[t+p:t+p+8]); print('ExposureTime', a, '/', b)
EOF
```

Expected: `ISO` close to 400 and `ExposureTime` close to 1/100 (the sensor quantises both; within 10% is a pass).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add pro controls: manual ISO and shutter, EV, white balance, manual focus, AE/AWB lock"
git push origin main
```

---

### Task 6: DNG beside the JPEG

**Files:**
- Modify: `app/src/main/java/it/bosiux/camera4u/camera/CameraModel.kt` (RAW_JPEG binding and capture path)
- Modify: `app/src/main/java/it/bosiux/camera4u/camera/PhotoWriter.kt` (shared base name, DNG output options)
- Modify: `app/src/main/java/it/bosiux/camera4u/ui/camera/CameraScreen.kt` (RAW toggle)
- Test: `app/src/test/java/it/bosiux/camera4u/ModelTest.kt` (file naming)

**Interfaces:**
- Produces: `PhotoWriter.baseName(now: Long): String` (`C4U_yyyyMMdd_HHmmss_SSS`), `PhotoWriter.dngOutputOptions(context, baseName): ImageCapture.OutputFileOptions`, `CameraModel.saveDng: Boolean`, `CameraModel.dngSupported: Boolean`, `CameraModel.toggleDng()`.

- [ ] **Step 1: Write the failing test**

Add to `ModelTest.kt`:

```kotlin
class PhotoWriterTest {
    @Test
    fun baseNameIsStableAndPrefixed() {
        val name = it.bosiux.camera4u.camera.PhotoWriter.baseName(0L)
        assertTrue(name.startsWith("C4U_"))
        assertEquals(name, it.bosiux.camera4u.camera.PhotoWriter.baseName(0L))
        assertEquals("C4U_".length + "yyyyMMdd_HHmmss_SSS".length, name.length)
    }
}
```

(add `import org.junit.Assert.assertTrue` if missing).

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "it.bosiux.camera4u.PhotoWriterTest" -q`
Expected: compilation error, `baseName` unresolved.

- [ ] **Step 3: Refactor `PhotoWriter` naming and add DNG options**

In `PhotoWriter.kt`:

```kotlin
    fun baseName(now: Long): String = "C4U_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))

    /** Where CameraX writes the DNG: the same album and base name as the JPEG. */
    fun dngOutputOptions(context: Context, baseName: String): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
        ).build()
    }
```

Change `saveToGallery(context, file)` to `saveToGallery(context, file, baseName: String)` and use `"$baseName.jpg"` as the display name (remove the local `name` computation; keep `DATE_TAKEN = System.currentTimeMillis()`). Import `androidx.camera.core.ImageCapture`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "it.bosiux.camera4u.PhotoWriterTest" -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Bind `ImageCapture` with RAW_JPEG when supported**

In `CameraModel.kt` add state:

```kotlin
    var saveDng: Boolean by mutableStateOf(prefs.getBoolean(KEY_SAVE_DNG, true))
        private set
    /** False when the bound camera cannot deliver RAW and JPEG together. */
    var dngSupported: Boolean by mutableStateOf(false)
        private set

    fun toggleDng() {
        saveDng = !saveDng
        prefs.edit { putBoolean(KEY_SAVE_DNG, saveDng) }
        val provider = provider ?: return
        val owner = lifecycleOwner ?: return
        if (status == Status.Running) runCatching { bind(provider, owner, lensFacing) }
    }
```

`const val KEY_SAVE_DNG = "saveDng"` in the companion.

In `bind(...)`, replace the `capture` builder with:

```kotlin
        val wantsRaw = saveDng && ImageCapture.getImageCaptureCapabilities(provider.getCameraInfo(selector))
            .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        dngSupported = wantsRaw
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode.cameraX)
            .apply {
                if (wantsRaw) {
                    // RAW has a single sensor size; let CameraX pick matching streams.
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                } else {
                    // 12 MP keeps full-resolution GPU processing fast and memory friendly.
                    setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            )
                            .build(),
                    )
                }
            }
            .build()
```

- [ ] **Step 6: Capture RAW+JPEG through files**

Replace the `capture()` body's first line inside `scope.launch { try {` from

```kotlin
                val (jpeg, rotation) = if (imageCapture != null) takePicture(imageCapture) else null to 0
```

to

```kotlin
                val baseName = PhotoWriter.baseName(System.currentTimeMillis())
                val (jpeg, rotation) = when {
                    imageCapture == null -> null to 0
                    dngSupported -> takePictureRawJpeg(imageCapture, baseName)
                    else -> takePicture(imageCapture)
                }
```

and pass `baseName` to the gallery save:

```kotlin
                        val uri = withContext(Dispatchers.IO) { PhotoWriter.saveToGallery(context, file, baseName) }
```

Add the new capture helper next to `takePicture`:

```kotlin
    /**
     * RAW + JPEG: CameraX writes the DNG straight into the gallery and the JPEG
     * into a temp file we then grade. `onImageSaved` fires once per file.
     */
    private suspend fun takePictureRawJpeg(imageCapture: ImageCapture, baseName: String): Pair<ByteArray, Int> {
        val jpegFile = File.createTempFile("raw_jpeg", ".jpg", context.cacheDir)
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                var remaining = 2
                imageCapture.takePicture(
                    PhotoWriter.dngOutputOptions(context, baseName),
                    ImageCapture.OutputFileOptions.Builder(jpegFile).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (--remaining == 0 && continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    },
                )
            }
            val bytes = jpegFile.readBytes()
            val rotation = ExifInterface(jpegFile).rotationDegrees
            return bytes to rotation
        } finally {
            jpegFile.delete()
        }
    }
```

Import `androidx.exifinterface.media.ExifInterface`.

The decoded JPEG may now be 16 MP; cap it so the GPU pass stays at 12 MP. In `decode(...)`, after obtaining the bitmap, replace the `return` with:

```kotlin
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("The camera did not return a photo.")
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= 4000) return bitmap
        // ponytail: scale instead of a second subsample; 12 MP is the ceiling the renderer was sized for.
        val scale = 4000f / longSide
        return bitmap.scale((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt()).also { if (it !== bitmap) bitmap.recycle() }
```

- [ ] **Step 7: RAW toggle in the top bar**

In `CameraScreen.kt` `TopBar`, after the aspect button:

```kotlin
        if (camera.dngSupported || camera.saveDng) {
            AspectButton(if (camera.saveDng) "RAW" else "JPG", onClick = camera::toggleDng)
        }
```

(`AspectButton` is a generic pill with a label; reuse it as is.)

- [ ] **Step 8: Build, install, shoot, verify both files exist**

```bash
./gradlew :app:testDebugUnitTest -q && ./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n it.bosiux.camera4u/.MainActivity
sleep 3; tool/screenshot.sh task6-dng topbar-raw
adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 6
adb shell "ls -lt /sdcard/Pictures/camera4u | head -3"
```

Expected: a `C4U_<stamp>.dng` (about 30 MB) and a `C4U_<stamp>.jpg` with the same stamp. Pull the DNG and confirm macOS can read it: `adb pull <dng> /tmp/shot.dng && sips -g pixelWidth -g pixelHeight /tmp/shot.dng` prints 4624×3472.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Save a DNG beside the graded JPEG using CameraX RAW+JPEG capture"
git push origin main
```

---

### Task 7: Instrumented identity test and LUT verification

**Files:**
- Create: `app/src/androidTest/java/it/bosiux/camera4u/PipelineIdentityTest.kt`
- Create: `app/src/androidTest/java/it/bosiux/camera4u/LutRenderTest.kt`
- Create: `tool/verify_lut.sh`
- Modify: `app/build.gradle.kts` (androidTest runner args, nothing else)

**Interfaces:**
- Consumes: `FilmGlThread.renderPhoto(bitmap, rotationDegrees, mirrored)`, `FilmGlThread.adjustments`, `FilmGlThread.setLut(CubeLut?)`, `CubeLut.parse(String)`.

- [ ] **Step 1: Write the identity test**

`app/src/androidTest/java/it/bosiux/camera4u/PipelineIdentityTest.kt`:

```kotlin
package it.bosiux.camera4u

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.bosiux.camera4u.model.FilmAdjustments
import it.bosiux.camera4u.rendering.FilmGlThread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** With no LUT and every effect at zero, the pipeline must return its input. */
@RunWith(AndroidJUnit4::class)
class PipelineIdentityTest {
    @Test
    fun identityPipelineReturnsInputWithinTwoLevels() {
        val gl = FilmGlThread()
        assertNull(gl.failure, gl.failure)
        gl.adjustments = FilmAdjustments(grain = 0f, vignette = 0f, skinProtect = 0f)
        gl.setLut(null)

        val w = 256; val h = 192
        val input = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            input.setPixel(x, y, Color.rgb(x, y * 255 / (h - 1), (x + y) % 256))
        }

        val output = runBlocking { gl.renderPhoto(input, 0, false) }
        gl.release()

        var worst = 0
        for (y in 0 until h) for (x in 0 until w) {
            val a = input.getPixel(x, y); val b = output.getPixel(x, y)
            worst = maxOf(worst, abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
        }
        assertTrue("Worst channel difference $worst > 2", worst <= 2)
    }
}
```

- [ ] **Step 2: Write the LUT render test (produces a file for ffmpeg comparison)**

`app/src/androidTest/java/it/bosiux/camera4u/LutRenderTest.kt`:

```kotlin
package it.bosiux.camera4u

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.bosiux.camera4u.model.FilmAdjustments
import it.bosiux.camera4u.rendering.CubeLut
import it.bosiux.camera4u.rendering.FilmGlThread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the Kodak 2383 LUT alone over a gradient; `tool/verify_lut.sh` compares it with ffmpeg. */
@RunWith(AndroidJUnit4::class)
class LutRenderTest {
    @Test
    fun renderKodak2383OverGradient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gl = FilmGlThread()
        assertNull(gl.failure, gl.failure)
        gl.adjustments = FilmAdjustments(grain = 0f, vignette = 0f, skinProtect = 0f)
        gl.setLut(CubeLut.parse(context.assets.open("films/kodak_2383_cuspclip.cube").bufferedReader().readText()))

        val w = 256; val h = 256
        val input = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) input.setPixel(x, y, android.graphics.Color.rgb(x, y, ((x / 16) * 16 + (y / 16)) % 256))

        val out = File(context.getExternalFilesDir(null), "lut_check")
        out.mkdirs()
        File(out, "input.png").outputStream().use { input.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val output = runBlocking { gl.renderPhoto(input, 0, false) }
        gl.release()
        File(out, "app.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
```

- [ ] **Step 3: Write the ffmpeg comparison script**

`tool/verify_lut.sh`:

```bash
#!/bin/sh
# Compares the app's Kodak 2383 render with ffmpeg's lut3d on the same input. Run after connectedDebugAndroidTest.
set -e
DIR=/tmp/lut_check; mkdir -p "$DIR"
adb pull /sdcard/Android/data/it.bosiux.camera4u/files/lut_check/input.png "$DIR/input.png" > /dev/null
adb pull /sdcard/Android/data/it.bosiux.camera4u/files/lut_check/app.png "$DIR/app.png" > /dev/null
ffmpeg -loglevel error -y -i "$DIR/input.png" -vf "lut3d=file=app/src/main/assets/films/kodak_2383_cuspclip.cube:interp=trilinear" "$DIR/ffmpeg.png"
python3 - "$DIR" <<'EOF'
import sys, zlib, struct
def png(path):
    d = open(path,'rb').read(); pos = 8; idat = b''; w = h = 0
    while pos < len(d):
        n, = struct.unpack('>I', d[pos:pos+4]); t = d[pos+4:pos+8]; c = d[pos+8:pos+8+n]
        if t == b'IHDR': w, h, bd, ct = struct.unpack('>IIBB', c[:10]); assert bd == 8 and ct in (2, 6), (bd, ct); bpp = 3 if ct == 2 else 4
        if t == b'IDAT': idat += c
        pos += 12 + n
    raw = zlib.decompress(idat); out = []; prev = bytearray(w*bpp); stride = w*bpp; i = 0
    for y in range(h):
        f = raw[i]; i += 1; line = bytearray(raw[i:i+stride]); i += stride
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x]+a) & 255
            elif f == 2: line[x] = (line[x]+b) & 255
            elif f == 3: line[x] = (line[x]+(a+b)//2) & 255
            elif f == 4:
                p = a+b-c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        out.append([tuple(line[x*bpp:x*bpp+3]) for x in range(w)]); prev = line
    return out
A = png(sys.argv[1] + '/app.png'); B = png(sys.argv[1] + '/ffmpeg.png')
import math
total = 0.0; n = 0
for ra, rb in zip(A, B):
    for pa, pb in zip(ra, rb):
        total += math.sqrt(sum((x-y)**2 for x, y in zip(pa, pb))); n += 1
mean = total / n
print(f"mean RGB distance (0..441): {mean:.2f}  ->  roughly dE {mean/2.55:.2f}")
assert mean / 2.55 < 2.0, "LUT render differs from ffmpeg"
EOF
echo "LUT verification passed"
```

(The script decodes PNG by hand because numpy and Pillow are not installed; `ffmpeg` is at `/opt/homebrew/bin/ffmpeg`.)

- [ ] **Step 4: Run the instrumented tests on the phone**

```bash
chmod +x tool/verify_lut.sh
./gradlew :app:connectedDebugAndroidTest -q
tool/verify_lut.sh
```

Expected: both tests pass, `LUT verification passed`. If the ΔE assertion fails, the usual cause is the sRGB round trip around the LUT in the grade shader versus ffmpeg's direct application; investigate before loosening the threshold.

- [ ] **Step 5: Performance check with a heavy look**

On the phone select "Cinema Print" (halation and glow on), then:

```bash
tool/perf.sh 15
tool/screenshot.sh task7-verify cinema-print-perf
```

Expected: 90th percentile ≤ 33 ms. Record the numbers in `docs/verification.md` on the docs branch.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add instrumented pipeline identity test, LUT render check and ffmpeg comparison script"
git push origin main
```

---

### Task 8: Side-by-side with the stock camera and docs

**Files (docs branch worktree `~/Documents/camera4u-docs`):**
- Create: `images/comparison/stock.jpg`, `images/comparison/camera4u-cinema.jpg`
- Create: `docs/verification.md`
- Modify: `README.md` on `main` (features list)

- [ ] **Step 1: Shoot the same scene twice**

Put the phone on a stable support. Shoot once with the stock Pixel Camera and once with camera4u ("Cinema Print", AUTO exposure, 4:3). Pull both:

```bash
adb pull "/sdcard/DCIM/Camera/$(adb shell 'ls -t /sdcard/DCIM/Camera | head -1' | tr -d '\r')" ~/Documents/camera4u-docs/images/comparison/stock.jpg
adb pull "/sdcard/Pictures/camera4u/$(adb shell 'ls -t /sdcard/Pictures/camera4u/*.jpg | head -1' | tr -d '\r')" ~/Documents/camera4u-docs/images/comparison/camera4u-cinema.jpg
```

- [ ] **Step 2: Write `docs/verification.md`**

List: date, build commit, device build id, each task's screenshot path, the identity test result, the LUT ΔE number, the gfxinfo percentiles, the EXIF readout of the manual shot, the DNG size and dimensions.

- [ ] **Step 3: Update the README feature list on `main`**

Replace the Features section with the camera4u set: looks, skin protection, framing, pro controls, DNG, and keep the upstream credits.

- [ ] **Step 4: Commit both branches**

```bash
cd ~/Documents/camera4u-docs && git add -A && git commit -m "Add verification record, screenshots and stock camera comparison" && git push origin docs
cd ~/Documents/camera4u && git add README.md && git commit -m "Document camera4u features" && git push origin main
```

---

## Phase 2 (separate plan): subject relighting

Not part of this plan. It adds `com.google.mlkit:subject-segmentation`, a `relight/` package and a post-capture screen with Studio, Contour and Stage. Write its plan after Task 8 is verified on the phone.
