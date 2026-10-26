<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android

NoDrive is the Android app for **Nourax Workspace** files at <https://drive.nourax.org>. It is made by
**Nourax**.

- See your files, upload, download, rename, move and delete them, share links, keep files offline and
  upload new photos automatically.
- **Workspace apps:** the side menu also lists the Workspace apps the signed-in user has (Mail,
  Calendar, Talk, ...), read from the Drive's navigation list. Tapping one opens the page inside
  NoDrive, already signed in with the phone's app password, so there is no sign-in page. Links in the
  notification list that point to the Drive open there too. The password goes only to the account's
  own server and is never in a URL or a log. Signing out or removing the account clears the in-app
  web data. Details: [docs/testing.md](docs/testing.md).
- Sign in happens in the browser, with Nextcloud's Login Flow v2. The web page offers "Log in with
  Nourax".
- The server is fixed. NoDrive connects only to `https://drive.nourax.org` and has no "enter server
  address" screen.
- Package `org.nourax.nodrive`. It installs and runs next to the real Nextcloud app without clashing.
- No Google services. No built-in updater, no crash reporting, no usage reporting. Push
  notifications are off for now.

## Based on Nextcloud Android, with thanks

NoDrive is a modified version of the **Nextcloud Android app**:
<https://github.com/nextcloud/android>, tag `stable-35.0.1`.

Thank you to Nextcloud GmbH and to every Nextcloud contributor. Almost all of the code in this
repository is their work. Nourax added a product flavour called `nodrive`, three small marked code
hooks, the tools in `tools/` and our own CI. [NOTICE.md](NOTICE.md) lists every change.

Upstream's own README is kept at [docs/upstream-README.md](docs/upstream-README.md). Credits to
Nextcloud live only in this repository (README, NOTICE, licence files and source headers). The app's
screens show only NoDrive and Nourax.

## Licence

- The program is licensed under the **GNU General Public License, version 2**, as upstream:
  [LICENSE.txt](LICENSE.txt).
- Every source file keeps its upstream header. Most say `AGPL-3.0-or-later OR GPL-2.0-only` or
  `AGPL-3.0-or-later`. The licence texts are in [LICENSES/](LICENSES/).
- Files added by Nourax carry `SPDX-FileCopyrightText: 2026 Nourax` and
  `SPDX-License-Identifier: AGPL-3.0-or-later`.
- Trademarks, third-party libraries and the legal-notice decision: [NOTICE.md](NOTICE.md).

## Test builds

Test builds are GitHub Actions artifacts only. There are no GitHub releases and no store uploads yet.

1. Open the repository's **Actions** tab, then the workflow **Android**.
2. Open the newest run on `main` with a green tick.
3. Under **Artifacts**, download **`nodrive-android-apk`**. The zip holds:
   - `app-nodrive-debug.apk`, the app;
   - `app-nodrive-debug.apk.sha256` and `signature.txt`;
   - `LICENSE.txt`, `NOTICE.md` and `THIRD-PARTY-NOTICES.txt`.

The APK is **debug-signed**: it is a test build, not a release. Artifacts expire after 14 days.
[docs/testing.md](docs/testing.md) says how to install it on a phone.

## How to build

The short version (JDK 21 and the Android SDK are needed):

```sh
./gradlew :app:assembleNodriveDebug
# -> app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
```

Call only the `nodrive` tasks. `assembleDebug`, `build` and `check` build all five upstream flavours
as well. [docs/build.md](docs/build.md) has the details, the tests and the CI.

## Documentation

| File | What it covers |
|---|---|
| [NOTICE.md](NOTICE.md) | Licensed software, trademarks, what Nourax changed, legal-notice decision |
| [docs/branding.md](docs/branding.md) | Every id, colours, icons, and how the branding tools work |
| [docs/branding-sweep.md](docs/branding-sweep.md) | The no-Nextcloud sweep: hits before and after, hits kept on purpose |
| [docs/build.md](docs/build.md) | Building locally and in CI, Gradle tasks, artifacts |
| [docs/testing.md](docs/testing.md) | Test results (CI and owner), screenshots, installing on a phone |
| [docs/upstream-updates.md](docs/upstream-updates.md) | Merging a new Nextcloud Android release |
| [docs/screenshots/android/](docs/screenshots/android/README.md) | Screenshots of every reachable screen |
