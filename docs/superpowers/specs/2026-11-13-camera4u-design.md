# camera4u — design spec

Date: 2026-11-13
Status: approved in conversation, pending written review
Target: one device, Google Pixel 8a (`akita`), Android 17 (API 37, build CP3A.261005.005)
Distribution: self-signed APK installed with `adb install`, no store

## Goal

A photo-only camera app for the Pixel 8a that makes photos look professional
and cinematic the way iPhone Photographic Styles and cinema print emulation
do, with real manual exposure controls. No video, no social-style overlay
filters.

## Starting point

Fork of [FilmCamera-Android](https://github.com/YahiaAngelo/FilmCamera-Android)
(MIT). Kotlin, Jetpack Compose, Material 3, CameraX 1.6.2, OpenGL ES 3.0.
About 4000 lines. It already provides the GPU pipeline (sRGB to linear
exposure and white balance, trilinear 3D LUT, highlights/shadows, contrast,
saturation, fade, bloom, halation, PCG grain, vignette), a `.cube` parser, a
catalog of about 300 film LUTs, JPEG capture with EXIF copy, lens stops,
pinch zoom, tap to focus and a film strip UI.

Baseline verified on the device on 2026-11-13: debug build installs and runs,
viewfinder at 90th percentile 25 ms frame time without halation.

## Device facts (from `adb shell dumpsys media.camera`, not from the web)

Logical back camera 0, physical ids 2 (main), 3 (ultrawide), 4 (main variant):

| Fact | Value |
|---|---|
| Hardware level | FULL |
| Capabilities | MANUAL_SENSOR, MANUAL_POST_PROCESSING, RAW, READ_SENSOR_SETTINGS, BURST_CAPTURE, LOGICAL_MULTI_CAMERA, STREAM_USE_CASE |
| Main sensor | 4624x3472 active array (64 MP sensor exposed binned), RGGB, white level 1023 |
| Main lens | f/1.89, 5.43 mm, OIS, min focus 10.2 diopters, calibration APPROXIMATE |
| ISO range | 50 – 9846 (analog up to 615) |
| Exposure time | 59,896 ns – 16 s |
| AE compensation | -24..24 in 1/6 EV steps (±4 EV) |
| AWB modes | 0..8 (OFF, AUTO, INCANDESCENT, FLUORESCENT, WARM_FLUORESCENT, DAYLIGHT, CLOUDY_DAYLIGHT, TWILIGHT, SHADE) |
| AF modes | OFF, AUTO, MACRO, CONTINUOUS_VIDEO, CONTINUOUS_PICTURE, EDOF |
| Tonemap | CONTRAST_CURVE, FAST, HIGH_QUALITY, 65 curve points |
| Zoom ratio | 0.53 – 8.0 |
| RAW_SENSOR output | 4624x3472 |
| Dynamic range profiles | none (no 10-bit HDR output for third-party apps) |
| DEPTH16 | not available |
| Ultrawide | f/2.2, 1.835 mm, fixed focus, ISO 49 – 4729 |
| Front camera | FULL, RAW, f/2.2, fixed focus |
| GPU | ARM Mali-G715, OpenGL ES 3.2 |
| Vendor extensions | service present (`com.google.android.apps.camera.services`); availability to third-party apps to be probed in-app |

The full dump and a parsed summary live in the `docs` branch under
`docs/camera2/`.

## Decisions taken with the owner

1. Photo only. Video is out of scope.
2. "Professional light" means both: real manual exposure controls now, and
   subject relighting (iPhone Portrait Lighting style) as phase 2.
3. Fork and adapt FilmCamera-Android rather than rewrite.
4. Capture base: the ISP-processed JPEG goes through the GPU look pipeline;
   a DNG is saved beside it when enabled. No in-house RAW development.
5. App name `camera4u`, applicationId `it.bosiux.camera4u`, GitHub fork
   `Bosiux/camera4u`.

## Section 1 — Features and controls

Single screen, full-screen viewfinder, dark minimal pro-camera UI.

Looks. A look is a LUT plus a preset of adjustments (contrast, halation,
bloom, grain, vignette). Curated initial set, all from LUTs already in the
fork's catalog:

| Look | LUT | Character |
|---|---|---|
| None | identity | pass-through |
| Cinema Print | Kodak 2383 | the print-film emulation used in real post-production |
| Cinema Print Fuji | Fuji 3513 (not bundled upstream, to be added to assets) | cooler print stock |
| Natural | Kodak Portra 400 | light, skin-friendly |
| Chrome | Fuji X-Trans III Classic Chrome | muted, documentary |
| Noir | Kodak Tri-X 400 | black and white |

Each look has an intensity slider. The full film library stays reachable
from a secondary button.

Skin protection, like iPhone Photographic Styles. In the grade shader a mask
over skin hues attenuates the look's hue and saturation changes on skin.
Slider "Skin" 0..1.

Cinema framing. Aspect 4:3, 3:2, 16:9, 2.39:1. Real crop at save time,
matte in the viewfinder.

Pro bar. Chips ISO · S · EV · WB · AF with one contextual slider.

- Exposure AUTO/M. In M: ISO 50–9846 and shutter 1/16700–16 s. In AUTO:
  compensation ±4 EV.
- WB: auto, daylight, cloudy, shade, incandescent, fluorescent.
- Focus: AF or MF with a distance slider (0 = infinity, up to 10.2 diopters).
- Long press on the viewfinder locks AE/AF.
- Ranges are read from `CameraCharacteristics` at bind time, never hard-coded.

Capture. ISP JPEG rendered through the shaders at up to 12 MP (as today),
with a DNG saved beside it (toggle, default on). EXIF copied from the
original capture.

Relighting (phase 2, after the base ships). Applied to the just-captured
photo, not live. ML Kit Subject Segmentation on device produces a foreground
mask; a GPU pass applies one of Studio (brighten subject, soften
background), Contour (directional light with falloff), Stage (black
background). Intensity slider, then save.

Out of scope: video, Google HDR+ (not accessible to third parties), zebras,
histogram. Focus peaking is reconsidered in phase 2 together with MF.

## Section 2 — Architecture

One `app` module, fork structure kept.

- `model/Look.kt` (new): `Look(id, name, lutId, adjustments)` and the curated
  list. Selected look and intensity persist in SharedPreferences.
- `model/FilmAdjustments.kt`: add `skinProtect` (0..1) and `aspect` enum.
- `camera/ProControls.kt` (new): exposure state (auto/manual, ISO, shutter,
  EV), WB mode, focus (AF/MF, distance). Applies values with
  `Camera2CameraControl.setCaptureRequestOptions` (CameraX interop). Reads
  ranges from `CameraCharacteristics` on bind.
- `camera/CameraModel.kt`: `ImageCapture` with `OUTPUT_FORMAT_RAW_JPEG` when
  DNG is on; aspect crop applied to the JPEG before rendering; exposes
  `ProControls`.
- `camera/PhotoWriter.kt`: also writes the DNG to `Pictures/camera4u`.
- `rendering/FilmShaders.kt`: in the grade pass, after the LUT, a skin mask
  (hue roughly 10–40°, medium saturation) mixes the pre-look chroma back by
  `skinProtect`; the composite pass draws the aspect matte for the preview
  and `renderPhoto` crops for real.
- `ui/`: `LookStrip` replaces `FilmStrip` (library reachable from a button),
  `ProBar` with chips and one slider, aspect selector in the top row.
  `AdjustmentsSheet` stays for fine tuning.
- Phase 2 `relight/` (new): ML Kit `subject-segmentation` on the captured
  bitmap, mask uploaded as a texture, `relight` shader with 3 presets.

New dependencies: ML Kit only, and only in phase 2. No DI framework, no
extra modules, no database.

Data flow. Preview: CameraX frame → FilmGlThread → grade (LUT + skin) →
bloom → composite (matte) → screen. Capture: JPEG (+DNG) → crop → same
shaders at full resolution → JPEG + EXIF → MediaStore.

Error handling. Manual values are clamped to device ranges. If RAW_JPEG is
unsupported the app falls back to JPEG and disables the DNG toggle with a
notice. If ML Kit fails, relighting shows the error and does not save.

## Section 3 — Verification

- JVM unit tests (existing `CubeLutTest`, `ModelTest`): add look
  serialization and manual-control clamping.
- Instrumented identity test on the device: a known bitmap rendered with
  look None, effects at zero, aspect 4:3 must equal the input within ±2/255
  per channel. Run with `./gradlew connectedDebugAndroidTest`.
- LUT against a reference: the same `.cube` applied to a test image with
  `ffmpeg -vf lut3d` and with the app must match within mean ΔE < 2.
  Documented as a manual step if ffmpeg is absent.
- Manual controls verified on output: after a shot in M at ISO 400 and
  1/100 s, the JPEG EXIF must report those values.
- Performance: `adb shell dumpsys gfxinfo` on the viewfinder with a look and
  halation on, target 90th percentile ≤ 33 ms.
- Before/after screenshots of every visible feature captured over adb and
  committed to the `docs` branch under `images/<feature-branch>/`.
- Side-by-side comparison with the stock Pixel Camera on the same scene,
  committed to the `docs` branch; final judgement is the owner's.

## Section 4 — Naming, signing, installation, repository

- App name `camera4u`, applicationId and namespace `it.bosiux.camera4u`,
  source folder renamed, album `Pictures/camera4u`. Upstream MIT license and
  attribution kept.
- Signing: local keystore generated with `keytool`, stored outside the repo
  in `~/Documents/camera4u-keys/`, loaded through the git-ignored
  `keystore.properties` the build already supports. Release build minified
  and signed, installed with `adb install` over the already-paired wireless
  debugging. No store, no developer verification needed.
- Repository: local folder `~/Documents/camera4u`, remote `upstream` =
  YahiaAngelo/FilmCamera-Android, remote `origin` = Bosiux/camera4u (GitHub
  fork; forks of public repositories are public). Branch `main` for code,
  orphan branch `docs` for specs, plans, the Camera2 dump and screenshots.
- Work order, each step built, installed and tried on the phone before the
  next: 1) rename and signing, 2) looks and skin protection, 3) aspect,
  4) pro controls, 5) DNG, 6) instrumented test and verification,
  7) phase 2 relighting.
