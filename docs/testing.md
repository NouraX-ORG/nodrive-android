<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android: testing

- **CI** tests on every push: build, unit tests, lint, instrumented tests on an emulator, a
  screenshot of every reachable screen, the branding sweep and a network check.
- **CI cannot sign in.** The Drive's login pages do not open from CI runners. So CI stops at "it
  builds, starts, and the screens are correct".
- **Signing in and syncing are owner tests**, on the owner's phone at home (section 4).

The CI results below are from [run 40188742857](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40188742857)
on `main` `7c0c2df5cc` (2026-10-25). Every later push runs all CI rows again: see the
[Actions tab](https://github.com/NouraX-ORG/nodrive-android/actions/workflows/android.yml).

## 1. Results

| Feature | Who | Result | Evidence |
|---|---|---|---|
| Branding gate: rebrand and icons up to date, keyword sweep, pins, self-test | CI | pass | Job "Branding gate": "rebrand --check: up to date", "make-icons --check: all images up to date", "882 hits: internal 254, legal 628, unclassified 0", "PASS - no unclassified hit, every pin holds"; self-test on stock genericDebug: 1315 hits, 528 unclassified |
| Builds a debug-signed APK of the nodrive flavour | CI | pass | Job "APK and unit tests": `app-nodrive-debug.apk`, SHA-256 `0c2ee105a34838b843a538575e9c36965dd3760571ec4e1bd6f10a1085356c63`, `apksigner verify` OK |
| APK identity and side-by-side ids (package, labels, authorities, account type, scheme, link host, login URL, push URL, user agent) | CI | pass | `tools/check-apk.py`: "Result PASS"; 113 labels all "NoDrive"; all 7 authorities and the declared permission are `org.nourax.nodrive*`; links only `https://drive.nourax.org` |
| Unit tests | CI | pass | `testNodriveDebugUnitTest`: 96 suites, 554 tests, 0 failed, 3 skipped (upstream `@Ignore`) |
| Lint | CI | pass | `lintNodriveDebug`: "No issues found." (upstream config, warnings are errors) |
| Instrumented tests on the emulator (Android 15, API 35) | CI | pass | 81 classes, 486 tests, 0 failed. The list and the reason for every class left out: `tools/ci/instrumented-tests.txt` |
| App name, version, account type and ids at runtime | CI | pass | `NoDriveBrandingIT`: `nameAndVersion`, `accountTypeIsOurs`, `providerAuthoritiesAreOurs` |
| Server is fixed; sign-in uses Login Flow v2 at drive.nourax.org | CI | pass | `NoDriveBrandingIT.serverIsFixed`, `linksOpenOnlyForDriveNouraxOrg` |
| Push, updater, what's new, recommend, help, issue tracker are off | CI | pass | `NoDriveBrandingIT.nextcloudServicesAreOff` |
| User agent matches server 35's Android rule | CI | pass | `NoDriveBrandingIT.userAgentMatchesServerRule`; `check-apk.py`: version group `35.0.1-nourax.1` |
| No string resource names Nextcloud (all languages, also from libraries) | CI | pass | `NoDriveBrandingIT.noStringNamesNextcloud`; `check-apk.py`: "string resources naming Nextcloud (not internal): none" |
| No "Open in Notes" card in a Notes folder | CI | pass | `NoDriveOpenInNotesIT.notesFolderShowsNoOpenInNotesCard` |
| Launches; shows NoDrive's name, icon and colours | CI | pass | Screen sweep: "app launches" in all 5 looks; screens 001, 010, 013, 020, 027 |
| Sign-in opens the browser at drive.nourax.org | CI | pass | Screen sweep: "sign-in opens the browser at drive.nourax.org" in all 5 looks (address bar `drive.nourax.org`) |
| No screen text names Nextcloud; no Nextcloud blue | CI | pass | Screen sweep: "72/72 screens dumped, 0 hits"; "highest share of #0082C9/#00679E/#006AA3 pixels 0.000%" |
| Dark mode, a right-to-left language (Arabic) and another language (German), before and after a stand-in sign-in | CI | pass | Screens in `en-light`, `en-dark`, `de-light`, `ar-light`, `ar-dark` (section 3) |
| Account name in system settings, share target, widget, notification channels | CI | pass | Screens 005, 045, 069 (accounts), 007, 044, 053, 068 (share), 009, 071, 072 (widget), 006, 070 (notifications) |
| No call to any Nextcloud GmbH host | CI | pass | `tools/check-network.py` on the emulator's `network.pcap` (233,255 packets): "PASS - no Nextcloud GmbH host" |
| Independent sweep (round 3) | CI artifacts, read-only | pass | [branding-sweep.md](branding-sweep.md) |
| Sign in with "Log in with Nourax" (owner's normal account, not the emergency admin) | owner | pending | Owner test 2 |
| The login pages in the browser show no Nextcloud text | owner | pending | Owner test 2 (server side, see section 5) |
| Device name under web Settings > Security > Devices | owner | pending | Owner test 3: read it out |
| File list | owner | pending | Owner test 4 |
| Upload, download, rename, move, delete | owner | pending | Owner test 4 |
| Public link with expiry date and password | owner | pending | Owner test 5 |
| Offline file | owner | pending | Owner test 6 |
| Automatic photo upload | owner | pending | Owner test 7 |
| Dark mode and a second language, signed in | owner | pending | Owner test 8 |
| Sync on mobile data, away from home | owner | pending | Owner test 9 |
| Sign out | owner | pending | Owner test 10 |
| Installs next to the real Nextcloud app (optional) | owner | pending | Owner test 11 |

### Known CI flakiness

The emulator job sometimes fails for reasons outside the app. Re-running the failed job is enough
(`gh run rerun <id> --failed`).

- **Run 40188742857, attempt 1:** in `en-light`, the browser did not take the focus in time
  ("sign-in opens the browser ... address bar=''"). Attempt 2 passed every check.
- **Earlier runs:** a system app's "not responding" dialog took the focus. `tools/ci/device.sh` now
  waits for it and closes it. It also retries up to 5 failed instrumented methods once, and shows
  both results in the report.

## 2. What CI does not cover

These screens need a signed-in server: share dialog, file details, notifications list, activities,
media and albums, assistant, passcode, the crash screen, the SSO grant dialog, a configured widget,
the sync badge and the profile "Talk" action. Their code and resources are clean
([branding-sweep.md](branding-sweep.md), advisory A3). Network traffic after sign-in is an owner
test too.

## 3. Screenshot index

72 screens in [screenshots/android/](screenshots/android/README.md), taken by `tools/ci/sweep.py` in
run [40169964556](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40169964556). The
emulator is Android 15 on a Pixel 6 profile.

- The signed-in screens use a local stand-in account `demo@drive.nourax.org`, added in airplane mode.
  It never reaches the server.
- The "Sorry, you have been blocked" browser page is the Drive's firewall, which keeps CI away from
  the login pages. It is expected.
- Every newer run makes the same set in its `nodrive-android-screenshots` artifact. The 72 screens
  of run 40188742857 were all looked at for the final sweep.

The cells link to the images. Empty cells: that screen is taken in English, German and Arabic light
only.

| Screen | en-light | en-dark | de-light | ar-light | ar-dark |
|---|---|---|---|---|---|
| launch | [001](screenshots/android/001-en-light-launch.png) | [010](screenshots/android/010-en-dark-launch.png) | [013](screenshots/android/013-de-light-launch.png) | [020](screenshots/android/020-ar-light-launch.png) | [027](screenshots/android/027-ar-dark-launch.png) |
| sign-in-browser | [002](screenshots/android/002-en-light-sign-in-browser.png) | [011](screenshots/android/011-en-dark-sign-in-browser.png) | [014](screenshots/android/014-de-light-sign-in-browser.png) | [021](screenshots/android/021-ar-light-sign-in-browser.png) | [028](screenshots/android/028-ar-dark-sign-in-browser.png) |
| sign-in-waiting | [003](screenshots/android/003-en-light-sign-in-waiting.png) | [012](screenshots/android/012-en-dark-sign-in-waiting.png) | [015](screenshots/android/015-de-light-sign-in-waiting.png) | [022](screenshots/android/022-ar-light-sign-in-waiting.png) | [029](screenshots/android/029-ar-dark-sign-in-waiting.png) |
| app-info | [004](screenshots/android/004-en-light-app-info.png) |  | [016](screenshots/android/016-de-light-app-info.png) | [023](screenshots/android/023-ar-light-app-info.png) |  |
| add-account | [005](screenshots/android/005-en-light-add-account.png) |  | [017](screenshots/android/017-de-light-add-account.png) | [024](screenshots/android/024-ar-light-add-account.png) |  |
| notification-settings | [006](screenshots/android/006-en-light-notification-settings.png) |  | [018](screenshots/android/018-de-light-notification-settings.png) | [025](screenshots/android/025-ar-light-notification-settings.png) |  |
| share-sheet | [007](screenshots/android/007-en-light-share-sheet.png) |  | [019](screenshots/android/019-de-light-share-sheet.png) | [026](screenshots/android/026-ar-light-share-sheet.png) |  |
| launcher-apps | [008](screenshots/android/008-en-light-launcher-apps.png) |  |  |  |  |
| widget-picker | [009](screenshots/android/009-en-light-widget-picker.png) |  |  |  |  |
| files | [061](screenshots/android/061-en-light-files.png) | [054](screenshots/android/054-en-dark-files.png) | [046](screenshots/android/046-de-light-files.png) | [037](screenshots/android/037-ar-light-files.png) | [030](screenshots/android/030-ar-dark-files.png) |
| drawer | [062](screenshots/android/062-en-light-drawer.png) | [055](screenshots/android/055-en-dark-drawer.png) | [047](screenshots/android/047-de-light-drawer.png) | [038](screenshots/android/038-ar-light-drawer.png) | [031](screenshots/android/031-ar-dark-drawer.png) |
| settings | [063](screenshots/android/063-en-light-settings.png) | [056](screenshots/android/056-en-dark-settings.png) | [048](screenshots/android/048-de-light-settings.png) | [039](screenshots/android/039-ar-light-settings.png) | [032](screenshots/android/032-ar-dark-settings.png) |
| settings-2 | [064](screenshots/android/064-en-light-settings-2.png) | [057](screenshots/android/057-en-dark-settings-2.png) | [049](screenshots/android/049-de-light-settings-2.png) | [040](screenshots/android/040-ar-light-settings-2.png) | [033](screenshots/android/033-ar-dark-settings-2.png) |
| accounts | [065](screenshots/android/065-en-light-accounts.png) | [058](screenshots/android/058-en-dark-accounts.png) | [050](screenshots/android/050-de-light-accounts.png) | [041](screenshots/android/041-ar-light-accounts.png) | [034](screenshots/android/034-ar-dark-accounts.png) |
| uploads | [066](screenshots/android/066-en-light-uploads.png) | [059](screenshots/android/059-en-dark-uploads.png) | [051](screenshots/android/051-de-light-uploads.png) | [042](screenshots/android/042-ar-light-uploads.png) | [035](screenshots/android/035-ar-dark-uploads.png) |
| auto-upload | [067](screenshots/android/067-en-light-auto-upload.png) | [060](screenshots/android/060-en-dark-auto-upload.png) | [052](screenshots/android/052-de-light-auto-upload.png) | [043](screenshots/android/043-ar-light-auto-upload.png) | [036](screenshots/android/036-ar-dark-auto-upload.png) |
| share-receive | [068](screenshots/android/068-en-light-share-receive.png) |  | [053](screenshots/android/053-de-light-share-receive.png) | [044](screenshots/android/044-ar-light-share-receive.png) |  |
| system-accounts | [069](screenshots/android/069-en-light-system-accounts.png) |  |  | [045](screenshots/android/045-ar-light-system-accounts.png) |  |
| notification-channels | [070](screenshots/android/070-en-light-notification-channels.png) |  |  |  |  |
| widget-drop | [071](screenshots/android/071-en-light-widget-drop.png) |  |  |  |  |
| widget-configuration | [072](screenshots/android/072-en-light-widget-configuration.png) |  |  |  |  |

## 4. Owner tests (at home, on the owner's Android phone)

Use the owner's normal Nourax account ("Log in with Nourax"), **not** the emergency admin account.
Sign in at home (test 2). After a phone has signed in, syncing works from anywhere (test 9).

### Test 1: install the test build

**Where:** a computer browser signed in to GitHub (artifacts need a GitHub sign-in), then the phone.

1. Open <https://github.com/NouraX-ORG/nodrive-android/actions/workflows/android.yml>.
2. Click the newest run on branch `main` with a green tick.
3. Under **Artifacts**, click **`nodrive-android-apk`** and download the zip.
4. Unzip it. It holds `app-nodrive-debug.apk`.
5. Copy `app-nodrive-debug.apk` to the phone's `Download` folder (USB cable, or any file-transfer
   app you trust).
6. On the phone, open the **Files** app, then `Download`, then tap `app-nodrive-debug.apk`.
7. Android says installing from this source is not allowed. Tap **Settings**, switch on **Allow
   from this source**, then go back.
8. Tap **Install**. If Play Protect warns about an unknown app, tap **More details**, then
   **Install anyway**. That happens because the build is debug-signed, not store-signed.
9. When it is done, switch "Allow from this source" off again (Settings > Apps > Special app access
   > Install unknown apps > Files).

| Field | Value |
|---|---|
| Artifact | `nodrive-android-apk` |
| File | `app-nodrive-debug.apk` |
| App name on the phone | NoDrive |
| Version (Settings > About in the app) | `35.0.1-nourax.1` |

With a USB cable and `adb`, steps 5 to 8 are one command: `adb install -r app-nodrive-debug.apk`.

**Updating later:** install the newer APK the same way, over the old one. If Android says the
package conflicts, the CI debug key has changed: uninstall NoDrive first, then install the new APK.

### Tests 2 to 11

| # | Test | What to do | What to report |
|---|---|---|---|
| 2 | Sign in | Open NoDrive. The browser opens at drive.nourax.org. Tap "Log in with Nourax" and finish the sign-in | Did NoDrive show your files? Did any browser page name Nextcloud? (Copy the exact words) |
| 3 | Device name | On a computer, open drive.nourax.org > Settings > Security > Devices & sessions | The exact name and icon shown for the phone |
| 4 | Files | Open a folder; upload a photo; download a file; rename, move and delete a test file | Each step: worked or not |
| 5 | Share link | On a test file: share > share link; set an expiry date and a password; open the link in a private browser window | Asked for the password? Expiry shown? |
| 6 | Offline file | On a test file: "Make available offline"; switch on airplane mode; open the file | Did it open? |
| 7 | Auto upload | Settings > Auto upload: switch on the camera folder; take a photo; wait a minute | Did the photo appear on drive.nourax.org? |
| 8 | Dark mode and language | Switch the phone to dark mode, then to another language (for example German or Arabic) | Any screen with Nextcloud's name, logo or blue? |
| 9 | Mobile data | Away from home, on mobile data: open NoDrive and refresh; upload a small file | Did it sync? |
| 10 | Sign out | Drawer > Sign out | Back to the sign-in screen? |
| 11 | Next to Nextcloud (optional) | Install the real Nextcloud app too and sign in to both | Do both work? Two separate accounts in system settings? |

## 5. Server-side items for the Workspace session

These belong to the Drive server, not to the app. They were found in the server 35 source
(`nextcloud/server` tag `v35.0.2`). The owner tests above confirm them.

| # | What the user sees | Why | What the server side could do |
|---|---|---|---|
| S1 | The browser sign-in pages say: Please log in before granting "Mozilla/5.0 (Android) Nextcloud-android/35.0.1-nourax.1 NoDrive" access to your {instance name} account. The grant page says: You are about to grant "..." access ... | Login Flow v2 stores the client's user agent as the client name (`ClientFlowLoginV2Controller::init`) and shows it as `{client}` (`core/src/views/LoginFlowAuth.vue`, `LoginFlowGrant.vue`). The app must keep the `Nextcloud-android` token, because the server detects Android clients by it (`USER_AGENT_CLIENT_ANDROID`) | Show a friendly name ("NoDrive") for user agents that end in ` NoDrive`, through a server-side patch or theming override |
| S2 | Settings > Security > Devices names the phone "{productName} Android app", for example "Nourax Workspace Android app", with no version. The rename box shows the raw user agent | `apps/settings/src/components/AuthToken.vue` (`nameMap.androidClient`, `productName` = the theming name) and `utils/userAgentMap.ts` (`androidClient` has no version group) | Accept it, or patch the settings app to show "NoDrive" for these user agents. The owner reads the real name back in test 3 |
| S3 | The web's "get the mobile apps" links point at Nextcloud's apps | config keys `customclient_android` and `customclient_fdroid` (server `config.sample.php`) | Later, once NoDrive is in a store: point them at NoDrive's listing |
| S4 | Links to drive.nourax.org do not open NoDrive automatically | No `/.well-known/assetlinks.json` yet; the app uses `autoVerify="false"` | After release signing: publish `assetlinks.json` with the release key's fingerprint |
| S5 | No push notifications | Nextcloud's push proxy serves only Nextcloud's apps | Later: our own push service |
