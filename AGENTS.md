# Repository workflow

- When the user says "push", commit the relevant working-tree changes and push the commit to the current branch's remote.
- Before building a release APK, increment both `versionName` and `versionCode` in `app/build.gradle.kts`. Do not perform a preliminary release build just to decide the next version.

## Optimized APK releases

- Build GitHub release APKs with `./gradlew assembleRelease`. The release build enables R8 code shrinking, resource shrinking, and ABI splits in `app/build.gradle.kts`; R8 compact mode is enabled for lower memory pressure and Gradle uses a 2 GB heap.
- Use one final versioned build for a release: update the version first, then run `./gradlew testDebugUnitTest assembleRelease`. This combines unit-test validation with production APK generation and avoids rebuilding the release solely because the version changed. If the command fails, fix the issue and rerun it at the same version; do not publish artifacts from a failed or superseded build.
- The final APKs are written to `app/build/outputs/apk/release/`:
  - `app-arm64-v8a-release.apk` for most current Android phones.
  - `app-universal-release.apk` as the larger fallback when the device ABI is unknown.
- Before publishing, confirm `output-metadata.json` contains the intended `versionName` and `versionCode`, run the unit tests, and verify every APK signature with Android SDK `apksigner`.
- Keep compact R8 shrinking, resource shrinking, and ABI splits enabled. Release outputs are ARM64 (`arm64-v8a`) plus universal; do not switch to a universal-only build or debug build to save release time, since those changes make the distributable larger or less optimized.
- Name GitHub assets `FlightLog-v<version>-<abi>.apk`, including `FlightLog-v<version>-universal.apk`, and publish them together in a `v<version>` GitHub Release tagged at the pushed version commit.
- Release APKs must use one persistent signing key. An APK signed with a different key cannot update an existing installation with the same application ID. Never commit a production keystore or its passwords; obtain signing values from local ignored configuration or repository secrets.
- The current release build falls back to Android debug signing because no production release key is configured. Clearly disclose this in release notes, and do not present that key as a stable production-signing solution.
