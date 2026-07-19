# Repository workflow

- When the user says "push", commit the relevant working-tree changes and push the commit to the current branch's remote.
- After a successful build, always increment both `versionName` and `versionCode` in `app/build.gradle.kts`.

## Optimized APK releases

- Build GitHub release APKs with `./gradlew assembleRelease`. The release build enables R8 code shrinking, resource shrinking, and ABI splits in `app/build.gradle.kts`.
- After the first successful build, increment both version fields as required above, rebuild `assembleRelease`, and publish only artifacts from that final versioned build.
- The final APKs are written to `app/build/outputs/apk/release/`:
  - `app-arm64-v8a-release.apk` for most current Android phones.
  - `app-armeabi-v7a-release.apk` for older 32-bit ARM devices.
  - `app-universal-release.apk` as the larger fallback when the device ABI is unknown.
- Before publishing, confirm `output-metadata.json` contains the intended `versionName` and `versionCode`, run the unit tests, and verify every APK signature with Android SDK `apksigner`.
- Name GitHub assets `FlightLog-v<version>-<abi>.apk`, including `FlightLog-v<version>-universal.apk`, and publish them together in a `v<version>` GitHub Release tagged at the pushed version commit.
- Release APKs must use one persistent signing key. An APK signed with a different key cannot update an existing installation with the same application ID. Never commit a production keystore or its passwords; obtain signing values from local ignored configuration or repository secrets.
- The current release build falls back to Android debug signing because no production release key is configured. Clearly disclose this in release notes, and do not present that key as a stable production-signing solution.
