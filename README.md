# XGrid Yellow Native

A clean **Android Studio source project** for the next generation of XGrid Yellow.

**Status: Stage 1 — native foundation only.** This initial project contains a standalone Android launcher and build setup. It does **not** yet include the existing Yellow APK's browser, Google search UI, GIF engine, ranking, or MediaPipe inference.

## Safety / migration boundary

- Native application ID: `com.xgrid.yellownative`
- Original Yellow application ID: `com.xgrid.yellow1`
- These are deliberately separate so the new prototype **cannot overwrite the installed Yellow app**.
- Do **not** put signing keys, signing passwords, or any private media in this public repository.
- The frozen Yellow v1.2.5 APK remains a reference and is **not** a source project.

## Stage 1: run the foundation

1. Install a current Android Studio: https://developer.android.com/studio
2. In Android Studio select **Get from VCS** or **File → New → Project from Version Control**.
3. Clone: `https://github.com/maasdorpc98-art/XGrid-Yellow.git`
4. Allow Gradle to sync using the installed JDK 17+ (Gradle Java 17 toolchain).
5. If asked to install Android SDK Platform 35, accept it.
6. Connect a phone with USB debugging or create an emulator; press **Run ▶**.
7. Confirm the app opens with the title **XGrid Yellow Native** and the **Foundation ready** label.

If the first clone reports a missing `gradle-wrapper.jar`, visit **GitHub → Actions**; the first build workflow restores the verified official Gradle 8.11.1 wrapper JAR into the repository. Pull the new commit (Git → Pull) and sync again. Alternatively run the included `scripts/restore-gradle-wrapper.sh` on macOS/Linux or `scripts/restore-gradle-wrapper.ps1` on Windows before sync.

## Planned incremental development

- **Stage 2:** Add bundled MediaPipe Pose Landmarker with local model and device verification.
- **Stage 3:** Implement deterministic GIF frame decoding and reliable media acquisition.
- **Stage 4:** Build Geometry/Motion signatures, multi-anchor matching, and candidate ranking.
- **Stage 5:** Recreate the chosen XSearch Quality UI, FIND MORE, and RE-SEARCH on top of reliable native search/retrieval.

New features are built and tested separately, one stage at a time. No cloud inference required.

## Build configuration

- Android Gradle Plugin 8.9.2
- Gradle 8.11.1
- Java 17 source/target
- compileSdk 35, minSdk 26, targetSdk 35
- Java Android activity (no Kotlin or third-party runtime dependencies yet)

## Checkpoints

- [ ] Android project Gradle syncs on the development machine
- [ ] Native app launches on the phone or emulator
- [ ] User returns to ChatGPT with result of Stage 1

See `docs/ROADMAP.md` for the architecture contract.
