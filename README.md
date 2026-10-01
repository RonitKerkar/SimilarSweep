# Similar Sweep — Android photo cleaner

**Source-code prototype for Android 11 or newer. No compiled APK is included.**

Pick an example, find similar screenshots or photos, review the matches, and move selected originals to Android's Trash in bulk. Designed for batches of YouTube, X/Twitter, Discord and other similar images.

## Get an installable APK

### Option A: GitHub Actions (no Android setup on your computer)

1. Create a private GitHub repository and upload the **contents** of this folder, including `.github/workflows/build-apk.yml`, at its root. No personal photos are needed.
2. Open **Actions → Build Android APK → Run workflow**.
3. Wait for a successful build. Download **SimilarSweep-debug-APK** from the run's Artifacts and unzip it.
4. Transfer `app-debug.apk` to your Android phone. Open it and grant installation permission to the browser/file manager if Android asks. This is a personal debug build, not a Play Store release.

The workflow has been provided but has not been run from this workspace. GitHub may require account verification or available Actions minutes. Debug signing keys can change between workflow runs: a later APK may require uninstalling the previous app first. Uninstalling this cleaner does not remove gallery originals.

### Option B: Build on your computer

Install Android Studio, JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME` to the SDK folder, or put `sdk.dir=/absolute/path/to/Android/Sdk` in `local.properties` (use forward slashes for Windows paths). Set `JAVA_HOME` to JDK 17.

- Windows PowerShell: `powershell -ExecutionPolicy Bypass -File .\build.ps1`
- macOS/Linux: `bash build.sh` (requires curl and unzip)

These scripts download Gradle 8.11.1 from the official Gradle service and verify its published SHA-256 checksum. AGP 8.9.2 and bundled ML Kit text recognition 16.0.1 are pinned in the project. Build dependencies require internet access. No Gradle wrapper JAR is included; use the supplied bootstrap scripts, local Gradle 8.11.1, or the GitHub workflow.

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Using the app

1. Tap **Photo access**. Full photo access scans the whole accessible library; selected-photo access scans only the images you allow.
2. Leave **Screenshots only** on for screenshot folders/names, or turn it off for any image type or renamed screenshots.
3. Tap one representative image. It is marked **EXAMPLE** and excluded from removal.
4. Tap **Find similar images**. The first scan reads image thumbnails and text locally. Keep the app open during a large scan. Use **Cancel scan** to stop without changing files.
5. Scroll the controls panel if needed. Adjust **Match strength**: lower finds more, higher narrows the list. Changing it clears prior selection to avoid hidden selections.
6. Tap individual matches or **Select matches**. Hold any image for a larger preview.
7. Tap **Trash**, then **Continue**, and approve Android's system confirmation. More than 500 selected images are split into batches, each with a system confirmation. Cancelling leaves subsequent batches unchanged.

The default action moves files to Trash, not immediate permanent deletion. Android/provider retention rules apply. Restore through your phone's gallery where supported. Trashed bytes may still occupy storage until permanent deletion/expiry. The app does not claim immediate freed space.

## What matching can and cannot do

- On-device bundled Latin-script OCR detects visible app names and combinations of common interface labels.
- A normalized color/layout descriptor, aspect ratio, difference hash and text overlap rank similar-looking content.
- Recognized conflicting app hints lower a match's score. Generic words alone are insufficient for a hint.
- **Near-duplicates only** skips OCR and compares visual features. It is approximate, not an exact byte comparison.
- A match score is a heuristic, **not a confidence percentage**. It is not a semantic AI classifier and cannot reliably group arbitrary subjects like all pets or all cars.
- Same-app screenshots with different layouts, crops, themes, languages or no interface may be missed. Different apps sharing a layout can match incorrectly. Full-screen video screenshots cannot reliably reveal their source app.
- Review matches before removal. No images are preselected. The selected example is always kept.
- Screenshot filtering uses filenames/folders, not a universal screenshot-detection API.
- Local MediaStore images only; cloud-only albums and videos are outside scope.

## Privacy and architecture

No account, server, advertising, analytics or upload feature. The app manifest explicitly removes INTERNET permission, including library contributions. OCR uses a bundled model. Image analysis and in-memory feature caching stay on the device. No photos are copied into the app's permanent storage, and app backup is disabled.

MediaStore handles library access and system-confirmed batch trash requests. Decoding and recognition run off the UI thread. Thumbnails use a bounded memory cache. Scans can be cancelled. Access denial, limited photo access, unreadable files, OCR failures and deletion cancellation have UI handling. Returning from another app refreshes the library and clears scan results; analysis caches last only for this app process. Scan results are not persisted across process death.

## Validation status

Completed in the creation environment:
- 8 synthetic matcher assertions compiled and passed using the JDK compiler module.
- All Java source parsed successfully with the Java compiler parser.
- Manifest, styles and vector icon parsed as XML.
- Build/test shell scripts passed shell syntax checks.

**Not completed:** Android compilation, dependency resolution, Android Lint, emulator/device execution, permission dialogs, actual trash/restore behavior, and real screenshot accuracy evaluation. Android SDK/build tools were absent, and required downloads were blocked. The APK build workflow is unexecuted. This is an unverified Android prototype until built and tested.

Before using with a large library, test with disposable copied screenshots:
- Android 11/12 full photo access; Android 13 permission denial; Android 14/15 selected vs full photo access.
- Matching across themes and apps, full-screen screenshots, renamed screenshots, cancellation, unreadable photos.
- Confirm/cancel Trash, restore from the system gallery, and multiple batches.
- Small screens, enlarged text, rotation and interruption during scanning.

Run core tests: `bash tests/run.sh` with JDK 17 installed.

## Primary documentation

- Android shared media and batch deletion: https://developer.android.com/training/data-storage/shared/media
- Selected photo access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Bundled OCR: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Android build compatibility: https://developer.android.com/build/releases/agp-8-9-0-release-notes
