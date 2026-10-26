# NoDrive for Android: screenshots

<!-- SPDX-FileCopyrightText: 2026 Nourax -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

Taken by `tools/ci/sweep.py` on the CI emulator (Android 15, API 35, `google_apis` x86_64, Pixel 6
profile) in run [40399452749](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749)
from the debug APK `35.0.1-nourax.1` (versionCode 350000190) built from `a6d9e8fe7a` (branch
`feature/backup-settings`).

- Looks: English, German and Arabic (right to left), light and dark (`en-light`, `en-dark`,
  `de-light`, `ar-light`, `ar-dark` in the file names).
- Signed out (network on): launch, the browser that sign-in opens at drive.nourax.org, the
  "complete the sign-in in your browser" screen, and system screens (app info, add account,
  notification settings, share sheet, app list, widget picker).
- Signed in: CI cannot sign in (the Drive login pages only open from the owner's network), so a
  local stand-in account `demo@drive.nourax.org` is added with airplane mode on. It is never sent
  to the server. That gives the file list ("Server not available"), drawer, every Settings page with
  About, accounts, uploads, auto upload, the share target, notification categories, the widget
  setup screen and the system account list.
- The browser page "Sorry, you have been blocked" is the owner's Cloudflare WAF rule, which keeps
  CI away from the Drive login pages. It is expected; the address bar shows drive.nourax.org.
- Every screen was also checked by machine: no text in the uiautomator dump names Nextcloud, and
  no screen has Nextcloud blue (#0082C9, #00679E, #006AA3) pixels. A person looked at every image.


Screens: 78. Looks: en-light, en-dark, de-light, ar-light, ar-dark.

## Workspace apps screens

Taken by the instrumented tests of the same run (https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749) against a throwaway local Nextcloud
server 35 with the test user `citest`. They are not part of the 78-screen sweep above. The server
sends six navigation entries (dashboard, files, office, photos, circles, activity); the menu shows
only the apps that are not Files, Activity or Photos.

| File | What it shows |
|---|---|
| `workspace-menu-light.png`, `workspace-menu-dark.png` | The drawer: NoDrive's own entries (All files, Activities, Media, Albums, ...), then the server's apps (Dashboard, Office, Teams). No second Activity or Photos entry |
| `workspace-page-light.png`, `workspace-page-dark.png` | The Dashboard opened inside NoDrive, signed in |

## Backup screens

Taken by `BackupSettingsIT` in the same run, on the emulator, against the throwaway server. Light and dark.

| File | What it shows |
|---|---|
| `backup-settings-light.png`, `backup-settings-dark.png` | Settings: the Backup section at the top (the two switches, "Backed-up folders", "Stop Google backup", "Move your Google data to NoDrive") |
| `backup-setup-result-light.png`, `backup-setup-result-dark.png` | The result screen after the main switch is turned on: the folders that are now backed up |
| `backup-auto-upload-light.png`, `backup-auto-upload-dark.png` | Upstream's Auto upload screen, showing the folder the switch turned on |
| `backup-offer-light.png`, `backup-offer-dark.png` | The one-time offer after the first sign-in ("Back up your photos and videos to NoDrive?") |
| `backup-stop-google-light.png`, `backup-stop-google-dark.png` | Stop Google backup: one card per place with a short guide and an Open button |
| `backup-move-google-light.png`, `backup-move-google-dark.png` | Move your Google data to NoDrive: the Takeout guide and the "Open Google Takeout" button |

## Checks

| Check | Result | Evidence |
|---|---|---|
| emulator | info | 15 / 35 / userdebug / sdk_gphone64_x86_64 / Physical size: 1080x2400 / Physical density: 420 |
| browser on the image | pass | com.android.chrome |
| install app-nodrive-debug.apk | pass | Success |
| install app-nodrive-debug-androidTest.apk | pass | Success |
| installed version | info | versionCode=350000190 minSdk=28 targetSdk=36 versionName=35.0.1-nourax.1 |
| en-light: default network is mobile data (captured) | pass | Wi-Fi off |
| en-light: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| en-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(  570): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| widget picker lists NoDrive | pass | entry 'NoDrive' found and opened |
| en-dark: default network is mobile data (captured) | pass | Wi-Fi off |
| en-dark: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| en-dark: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(  570): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| switch locale to de-DE | pass | setprop persist.sys.locale + stop/start; now de-DE |
| de-light: default network is mobile data (captured) | pass | Wi-Fi off |
| de-light: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| de-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(10680): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher} with LAUNCH_MULTIPLE from uid 10217 (BAL_ALL |
| switch locale to ar-EG | pass | setprop persist.sys.locale + stop/start; now ar-EG |
| ar-light: default network is mobile data (captured) | pass | Wi-Fi off |
| ar-light: app launches | pass | focus after launch: org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity |
| ar-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(14237): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| ar-dark: default network is mobile data (captured) | pass | Wi-Fi off |
| ar-dark: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| ar-dark: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(14237): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| airplane mode for the signed-in screens | pass | airplane_mode_on=1 |
| stand-in account (local only, airplane mode) | pass | instrumentation OK; Account {name=demo@drive.nourax.org, type=org.nourax.nodrive} Session: expectLaunch false, connected false, stats (0/0/0), lifetime 0.099, hasFeatures, Account {name=demo@drive.nourax.org, type=org.nourax.nodrive}, android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED |
| ar-dark: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| ar-light: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| switch locale to de-DE | pass | setprop persist.sys.locale + stop/start; now de-DE |
| de-light: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| switch locale to en-US | pass | setprop persist.sys.locale + stop/start; now en-US |
| en-dark: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| en-light: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| widget placed | warn | Bound widget 2 to provider ProviderId{user:0, app:10217, cmp:ComponentInfo{org.nourax.nodrive/com.nextcloud.client.widget.DashboardWidgetProvider}}; Error: appWidgetId (EXTRA_APPWIDGET_ID) was not returned from the widget configuration activity.; deleteAppWidgetId() 0; deleteAppWidgetId() 0 |
| no screen text names Nextcloud | pass | 78/78 screens dumped, 0 hits |
| browser pages naming Nextcloud (server side, listed only) | info | none |
| Nextcloud blue on screens | pass | 78 screens, highest share of #0082C9/#00679E/#006AA3 pixels 0.000% |
