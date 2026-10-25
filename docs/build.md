<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android: building

Nourax builds NoDrive only in GitHub Actions (workflow **Android**, `.github/workflows/android.yml`).
A local build works the same way and is described here too.

## Toolchain (from the source at `stable-35.0.1`)

| Item | Version | Where it is set |
|---|---|---|
| Gradle | 9.7.1 (wrapper) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.3.2, with built-in Kotlin | `gradle/libs.versions.toml` |
| Kotlin | 2.4.10 | `gradle/libs.versions.toml` |
| JDK | 21 (CI: Temurin) | `app/build.gradle.kts` (`JavaVersion.VERSION_21`) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 28 (Android 9) | `app/build.gradle.kts` |
| NDK and CMake (for one small native library) | 29.0.14206865 and 4.1.2 | `ndk.env`. AGP installs them when they are missing |
| ABIs | `arm64-v8a`, `x86_64` | `app/build.gradle.kts` |

Gradle checks every downloaded dependency against `gradle/verification-metadata.xml`. NoDrive adds no
dependency and no plugin, so that file is unchanged.

## Gradle tasks

Always call the `nodrive` tasks. `assembleDebug`, `build` and `check` would build all five upstream
flavours too.

| Purpose | Task | Output |
|---|---|---|
| Debug APK (debug-signed) | `./gradlew :app:assembleNodriveDebug` | `app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk` |
| Test APK | `./gradlew :app:assembleNodriveDebugAndroidTest` | `app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk` |
| Unit tests | `./gradlew :app:testNodriveDebugUnitTest` | `app/build/reports/tests/testNodriveDebugUnitTest/` |
| Lint | `./gradlew :app:lintNodriveDebug` | `app/build/reports/lint-results-nodriveDebug.*` |
| Instrumented tests (device or emulator connected) | `./gradlew :app:connectedNodriveDebugAndroidTest` | `app/build/reports/androidTests/connected/` |
| Runtime libraries | `./gradlew -q --no-configuration-cache :app:dependencies --configuration nodriveDebugRuntimeClasspath` | dependency tree (input for `THIRD-PARTY-NOTICES.txt`) |

Variants: `nodriveDebug` is the test build. `nodriveRelease` exists but is not built: there is no
release signing yet.

## Build settings

| Setting | How | Default |
|---|---|---|
| Privacy-policy link in Settings > About | `-PNOURAX_PRIVACY_URL=https://...` or the environment variable `NOURAX_PRIVACY_URL` | empty: the Privacy row is hidden |
| NoDrive revision (the `n` in `35.0.1-nourax.n`) | change `NOURAX_REVISION` in `tools/rebrand.py`, then run `python tools/rebrand.py` | `1` |

## A local build, step by step

1. Install JDK 21 and the Android SDK (command-line tools are enough). Set `ANDROID_HOME`.
2. Clone the repository: `git clone https://github.com/NouraX-ORG/nodrive-android.git`.
3. Check the branding is current (needs Python 3 and Pillow):
   `python tools/rebrand.py --check`, `python tools/make-icons.py --check`,
   `python tools/check-branding.py --source`.
4. Build: `./gradlew :app:assembleNodriveDebug`.
5. The APK is at `app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk`.

A full build needs several GB of disk for the Gradle cache, the SDK and the NDK.

## Signing

- Only **debug signing**, with the Android Gradle Plugin's own debug key. It is not a secret.
- CI caches that key (`~/.android/debug.keystore`, cache key `nodrive-debug-keystore-v1`), so each
  new test APK can be installed over the previous one.
- If the cache expires (GitHub drops caches unused for 7 days), the next APK has a new key. Then
  uninstall the old app before you install the new one.
- No keystore is committed. Release signing is a later owner to-do.

## CI: the workflow "Android"

It runs on every push to `main` and `platform/android`, and by hand (`workflow_dispatch`). It needs
no secrets and has `contents: read` permission only. A new push to the same branch cancels the run
still in progress.

| Job | Needs | What it does | Timeout |
|---|---|---|---|
| Branding gate | — | `rebrand.py --check`, `make-icons.py --check`, `check-branding.py --source`, and `check-branding.py --self-test` | 15 min |
| APK and unit tests | Branding gate | `assembleNodriveDebug`; the licence and notice files; `apksigner verify`; `tools/check-apk.py`; `testNodriveDebugUnitTest` | 120 min |
| Lint | Branding gate | `lintNodriveDebug` (upstream's lint config: warnings are errors) | 120 min |
| Emulator tests and screenshots | Branding gate | Builds the APK and test APK. Starts an Android 15 (API 35, `google_apis`, x86_64, Pixel 6) emulator that captures its network. Runs `tools/ci/device.sh` (instrumented tests, then the screen sweep) and `tools/check-network.py` | 150 min |

Actions used, pinned to their current major versions: `actions/checkout@v7`, `actions/setup-java@v6`,
`gradle/actions/setup-gradle@v6` (basic cache provider), `actions/setup-python@v7`,
`actions/cache@v6` (`restore` and `save`), `actions/upload-artifact@v7`,
`reactivecircus/android-emulator-runner@v2`.

### Artifacts (kept 14 days)

| Artifact | Contents |
|---|---|
| `nodrive-android-apk` | `app-nodrive-debug.apk`, its `.sha256`, `signature.txt` (apksigner), `apk-check.md`, `aapt2-dumps/`, `LICENSE.txt`, `NOTICE.md`, `THIRD-PARTY-NOTICES.txt`, `runtime-dependencies.txt` |
| `nodrive-android-screenshots` | One PNG and one UI dump per screen, `index.md` (checks and screen list), `checks.json`, contact sheets |
| `nodrive-android-instrumented-test-report` | The connected-test report and JUnit XML, `logcat.txt`, `network.pcap`, `network-report.md`, device logs |
| `nodrive-android-unit-test-report` | Unit test HTML report and JUnit XML |
| `nodrive-android-lint-report` | Lint HTML, XML, SARIF and text |
| `nodrive-android-branding-report` | `branding-report.md` from `check-branding.py` |

### Reading a failure

```sh
gh run list -R NouraX-ORG/nodrive-android --limit 5
gh run view <run id> -R NouraX-ORG/nodrive-android --log-failed | tail -n 150
gh run rerun <run id> -R NouraX-ORG/nodrive-android --failed   # e.g. a flaky emulator job
```

Upstream's own workflows (they need Nextcloud's secrets and machines) are kept unchanged for
reference in `.github/upstream-workflows/`.
