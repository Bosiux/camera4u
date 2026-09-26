# Google Play Submission

Draft listing text and checklist for the Google Play Console. Character limits are shown in brackets.

## Listing

**App name** [30]
Film Camera

> Play doesn't require unique app names, but a generic name can be rejected as misleading if it looks like it imitates another app. If the Console flags it, use a longer name such as "Film Camera: Analog Looks". The launcher label stays "Film Camera" either way; it comes from `app_name`.

**Short description** [80]

```
Shoot on classic film stocks. Real grain, glow and halation, live.
```

**Full description** [4000]

```
Film Camera turns your phone into an analog camera. Pick a film stock, see its look live in the viewfinder, and take photos that come out exactly as they looked when you pressed the shutter.

AROUND 300 FILM LOOKS
Color negative, slide, black & white, instant, cinema print and more. Browse and search the full collection, and download the stocks you love to keep them available offline. Eight popular looks are built in, so you can start shooting right away.

LIVE, NOT AFTERWARDS
Every frame is processed on the GPU, so the look is live in the viewfinder. Full-resolution photos go through the same pipeline, so what you see is what you get.

FILM CHARACTER
• Organic film grain that moves like projected film
• Glow and halation around highlights
• Vignette and faded blacks
• Adjustable film strength

FINE CONTROL
Exposure, contrast, highlights, shadows, saturation, warmth and tint, in a panel that keeps the viewfinder live while you adjust.

A REAL CAMERA
Lens switching, pinch to zoom, tap to focus, flash, grid and the front camera. Press a volume key to take a photo. Photos are saved to your gallery with their original camera metadata.

PRIVATE BY DESIGN
No account, no ads, no tracking. Your photos never leave your device.
```

> Keep film brand names (Kodak, Fuji, Portra…) out of the name, short description and full description. They're third-party trademarks, and Play's metadata policy doesn't allow them in listing text. The film names that appear in the app and in screenshots describe each look; see Review risks below.

**App category:** Photography
**Tags:** Camera, Photo editor, Filters (pick up to 5 in the Console)
**Contact email:** your developer email (required, shown publicly)
**Website:** https://github.com/YahiaAngelo/FilmCamera-Android
**Privacy policy:** https://github.com/YahiaAngelo/FilmCamera-Android/blob/main/PRIVACY.md

## Graphics

Ready-to-upload assets are in [`play-store`](play-store):

| Asset | File | Play requirement |
|-------|------|------------------|
| App icon | `icon-512.png` | 512 × 512 PNG |
| Feature graphic | `feature-graphic.png` | 1024 × 500 PNG or JPEG, no alpha |
| Phone screenshots | `screenshots/01…05-*.png` | 2–8 images, 1080 × 1920 (9:16), no alpha |

| # | File | Headline |
|---|------|----------|
| 1 | `01-shoot-on-film.png` | Shoot on film. |
| 2 | `02-black-and-white.png` | Timeless black & white. |
| 3 | `03-every-film.png` | Every film. Offline. |
| 4 | `04-glow-halation.png` | Glow & halation. |
| 5 | `05-what-you-see.png` | What you see is what you get. |

Screenshots at 1080 px or wider with a 9:16 ratio keep the app eligible for promotion in Play's recommendations.

### Regenerating

Debug builds accept launch extras that feed the bundled sample scene (`src/debug/assets/SampleScene.jpg`, left out of release builds) through the pipeline instead of the camera, select a film, set adjustments and open a sheet:

```sh
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell "am start -n io.github.yahiaangelo.filmcamera/.MainActivity \
  --ez sampleScene true --es film 'luts/bw/kodak_tri-x_400.cube' \
  --es adjustments '{\"grain\":0.45}' --es sheet adjustments"
adb exec-out screencap -p > raw/2-bw.png
```

`sheet` is `library` or `adjustments`. Name the raw screenshots `1-portra`, `2-bw`, `3-library`, `4-adjust` and `5-velvia`, then compose them (needs Google Chrome):

```sh
python3 scripts/make-play-store-graphics.py raw docs/play-store
```

Headlines and subtitles are set in the `PAGES` list at the top of the script.

## Data safety

Answer the Data safety form like this:

- **Does your app collect or share any of the required user data types?** No.
- **Is all of the user data collected by your app encrypted in transit?** Not applicable (no data is collected). Downloads use HTTPS.
- **Do you provide a way for users to request that their data is deleted?** Not applicable.

The app has no analytics, crash reporting, ads or accounts. Photos stay on the device. Network requests only download public LUT files and thumbnails from GitHub.

## App content

- **Privacy policy:** the URL above.
- **Ads:** No, the app doesn't contain ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating:** complete the IARC questionnaire with category *Utility, Productivity, Communication or Other* and answer No to every content question. Expect Everyone / PEGI 3.
- **Target audience:** 13 and over. Including younger age groups brings in the Families policy requirements, which the app doesn't need.
- **News app:** No. **Health, financial and government features:** None.
- **Permissions:** `CAMERA` and `INTERNET` are both covered by the app's core functionality; neither needs a declaration form.

## Release

Build the signed App Bundle:

```sh
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

It's signed with the upload key configured in `keystore.properties` (see the README). Enroll in **Play App Signing** when you create the app. Google then holds the app signing key, and a lost upload key can be reset through Play Console support.

Bump `versionCode` in `app/build.gradle.kts` for every upload, and `versionName` for every user-facing release.

**Release notes** [500]

```
First release of Film Camera for Android: around 300 film looks, live in the viewfinder, with real grain, glow and halation.
```

## Review risks

- **Trademarked film names.** Film names such as "Kodak Portra 400" come from the LUT collection and appear in the app and in screenshots to describe each look. The listing text avoids them. If Play raises an impersonation or intellectual property issue, the usual fix is a short disclaimer in the app and description. One is already in the README.
- **Downloaded content.** LUTs are data files, not executable code, so the Device and Network Abuse policy on downloading code doesn't apply.
- **Portrait-only.** Since Android 16, large screens ignore orientation locks. The camera screen still works there (the viewfinder keeps its 3:4 frame), but check it on a tablet emulator before listing for tablets.

## Checklist before uploading

- [ ] Developer account set up. New **personal** accounts must run a closed test with at least 12 testers for 14 days before they can apply for production access.
- [ ] App created in the Play Console with package `io.github.yahiaangelo.filmcamera`. The package name is permanent.
- [ ] Play App Signing enabled; upload key backed up (see README)
- [ ] `versionCode` / `versionName` set (1 / 1.0); bump `versionCode` for every upload
- [ ] `./gradlew :app:bundleRelease`, then upload `app-release.aab` to an internal testing track first
- [ ] Listing text, graphics, data safety, content rating and target audience filled in
- [ ] Tested on a physical device from the internal testing track
