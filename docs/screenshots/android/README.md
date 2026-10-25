# NoDrive for Android: screenshots

<!-- SPDX-FileCopyrightText: 2026 Nourax -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

Taken by `tools/ci/sweep.py` on the CI emulator (Android 15, API 35, `google_apis` x86_64, Pixel 6
profile) in run [40169964556](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40169964556)
from the debug APK `35.0.1-nourax.1` (versionCode 350000190, SHA-256
`9d79556866ae5451b449c90354177d42ed86979c125613655a5a960d0fbb2dcd`).

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


Screens: 72. Looks: en-light, en-dark, de-light, ar-light, ar-dark.

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
| en-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(  569): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| widget picker lists NoDrive | pass | entry 'NoDrive' found and opened |
| en-dark: default network is mobile data (captured) | pass | Wi-Fi off |
| en-dark: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| en-dark: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(  569): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| switch locale to de-DE | pass | setprop persist.sys.locale + stop/start; now de-DE |
| de-light: default network is mobile data (captured) | pass | Wi-Fi off |
| de-light: app launches | pass | focus after launch: org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity |
| de-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager( 8433): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher} with LAUNCH_MULTIPLE from uid 10213 (BAL_ALL |
| switch locale to ar-EG | pass | setprop persist.sys.locale + stop/start; now ar-EG |
| ar-light: default network is mobile data (captured) | pass | Wi-Fi off |
| ar-light: app launches | pass | focus after launch: org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity |
| ar-light: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(12025): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| ar-dark: default network is mobile data (captured) | pass | Wi-Fi off |
| ar-dark: app launches | pass | focus after launch: com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity |
| ar-dark: sign-in opens the browser at drive.nourax.org | pass | browser focus=com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity; address bar='drive.nourax.org'; activity start: I/ActivityTaskManager(12025): START u0 {act=android.intent.action.VIEW dat=https://drive.nourax.org/... cmp=com.android.chrome/com.google.android.apps.chrome.IntentDispatcher (has extras)} with LAUNCH_MULTIPLE from uid 1 |
| airplane mode for the signed-in screens | pass | airplane_mode_on=1 |
| stand-in account (local only, airplane mode) | pass | instrumentation OK; Account {name=demo@drive.nourax.org, type=org.nourax.nodrive} Session: expectLaunch false, connected false, stats (0/0/0), lifetime 0.106, hasFeatures, Account {name=demo@drive.nourax.org, type=org.nourax.nodrive}, android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED |
| ar-dark: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| ar-light: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| switch locale to de-DE | pass | setprop persist.sys.locale + stop/start; now de-DE |
| de-light: file list has the focus | pass | focus after 2 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| switch locale to en-US | pass | setprop persist.sys.locale + stop/start; now en-US |
| en-dark: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| en-light: file list has the focus | pass | focus after 1 start(s): org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity |
| widget placed | warn | Bound widget 2 to provider ProviderId{user:0, app:10213, cmp:ComponentInfo{org.nourax.nodrive/com.nextcloud.client.widget.DashboardWidgetProvider}}; Error: appWidgetId (EXTRA_APPWIDGET_ID) was not returned from the widget configuration activity.; deleteAppWidgetId() 0; deleteAppWidgetId() 0 |
| no screen text names Nextcloud | pass | 72/72 screens dumped, 0 hits |
| browser pages naming Nextcloud (server side, listed only) | info | none |
| Nextcloud blue on screens | pass | 72 screens, highest share of #0082C9/#00679E/#006AA3 pixels 0.000% |

## Screens

| # | Look | Screen | File | Window | Text naming Nextcloud | NC blue |
|---|---|---|---|---|---|---|
| 1 | en-light | launch | 001-en-light-launch.png | org.nourax.nodrive/com.nmc.android.ui.LauncherActivity | none | 0.00% |
| 2 | en-light | sign-in-browser | 002-en-light-sign-in-browser.png | com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity | none | 0.00% |
| 3 | en-light | sign-in-waiting | 003-en-light-sign-in-waiting.png | org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity | none | 0.00% |
| 4 | en-light | app-info | 004-en-light-app-info.png | com.android.settings/com.android.settings.spa.SpaActivity | none | 0.00% |
| 5 | en-light | add-account | 005-en-light-add-account.png | com.android.settings/com.android.settings.Settings$ChooseAccountActivity | none | 0.00% |
| 6 | en-light | notification-settings | 006-en-light-notification-settings.png | com.android.settings/com.android.settings.Settings$AppNotificationSettingsActivity | none | 0.00% |
| 7 | en-light | share-sheet | 007-en-light-share-sheet.png | android/com.android.internal.app.ResolverActivity | none | 0.00% |
| 8 | en-light | launcher-apps | 008-en-light-launcher-apps.png | com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity | none | 0.00% |
| 9 | en-light | widget-picker | 009-en-light-widget-picker.png | com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity | none | 0.00% |
| 10 | en-dark | launch | 010-en-dark-launch.png | org.nourax.nodrive/com.nmc.android.ui.LauncherActivity | none | 0.00% |
| 11 | en-dark | sign-in-browser | 011-en-dark-sign-in-browser.png | com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity | none | 0.00% |
| 12 | en-dark | sign-in-waiting | 012-en-dark-sign-in-waiting.png | org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity | none | 0.00% |
| 13 | de-light | launch | 013-de-light-launch.png | org.nourax.nodrive/com.nmc.android.ui.LauncherActivity | none | 0.00% |
| 14 | de-light | sign-in-browser | 014-de-light-sign-in-browser.png | com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity | none | 0.00% |
| 15 | de-light | sign-in-waiting | 015-de-light-sign-in-waiting.png | org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity | none | 0.00% |
| 16 | de-light | app-info | 016-de-light-app-info.png | com.android.settings/com.android.settings.spa.SpaActivity | none | 0.00% |
| 17 | de-light | add-account | 017-de-light-add-account.png | com.android.settings/com.android.settings.Settings$ChooseAccountActivity | none | 0.00% |
| 18 | de-light | notification-settings | 018-de-light-notification-settings.png | com.android.settings/com.android.settings.Settings$AppNotificationSettingsActivity | none | 0.00% |
| 19 | de-light | share-sheet | 019-de-light-share-sheet.png | android/com.android.internal.app.ResolverActivity | none | 0.00% |
| 20 | ar-light | launch | 020-ar-light-launch.png | org.nourax.nodrive/com.nmc.android.ui.LauncherActivity | none | 0.00% |
| 21 | ar-light | sign-in-browser | 021-ar-light-sign-in-browser.png | com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity | none | 0.00% |
| 22 | ar-light | sign-in-waiting | 022-ar-light-sign-in-waiting.png | org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity | none | 0.00% |
| 23 | ar-light | app-info | 023-ar-light-app-info.png | com.android.settings/com.android.settings.spa.SpaActivity | none | 0.00% |
| 24 | ar-light | add-account | 024-ar-light-add-account.png | com.android.settings/com.android.settings.Settings$ChooseAccountActivity | none | 0.00% |
| 25 | ar-light | notification-settings | 025-ar-light-notification-settings.png | com.android.settings/com.android.settings.Settings$AppNotificationSettingsActivity | none | 0.00% |
| 26 | ar-light | share-sheet | 026-ar-light-share-sheet.png | android/com.android.internal.app.ResolverActivity | none | 0.00% |
| 27 | ar-dark | launch | 027-ar-dark-launch.png | org.nourax.nodrive/com.nmc.android.ui.LauncherActivity | none | 0.00% |
| 28 | ar-dark | sign-in-browser | 028-ar-dark-sign-in-browser.png | com.android.chrome/org.chromium.chrome.browser.customtabs.CustomTabActivity | none | 0.00% |
| 29 | ar-dark | sign-in-waiting | 029-ar-dark-sign-in-waiting.png | org.nourax.nodrive/com.owncloud.android.authentication.AuthenticatorActivity | none | 0.00% |
| 30 | ar-dark | files | 030-ar-dark-files.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 31 | ar-dark | drawer | 031-ar-dark-drawer.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 32 | ar-dark | settings | 032-ar-dark-settings.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 33 | ar-dark | settings-2 | 033-ar-dark-settings-2.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 34 | ar-dark | accounts | 034-ar-dark-accounts.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ManageAccountsActivity | none | 0.00% |
| 35 | ar-dark | uploads | 035-ar-dark-uploads.png | org.nourax.nodrive/com.owncloud.android.ui.activity.UploadListActivity | none | 0.00% |
| 36 | ar-dark | auto-upload | 036-ar-dark-auto-upload.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SyncedFoldersActivity | none | 0.00% |
| 37 | ar-light | files | 037-ar-light-files.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 38 | ar-light | drawer | 038-ar-light-drawer.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 39 | ar-light | settings | 039-ar-light-settings.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 40 | ar-light | settings-2 | 040-ar-light-settings-2.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 41 | ar-light | accounts | 041-ar-light-accounts.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ManageAccountsActivity | none | 0.00% |
| 42 | ar-light | uploads | 042-ar-light-uploads.png | org.nourax.nodrive/com.owncloud.android.ui.activity.UploadListActivity | none | 0.00% |
| 43 | ar-light | auto-upload | 043-ar-light-auto-upload.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SyncedFoldersActivity | none | 0.00% |
| 44 | ar-light | share-receive | 044-ar-light-share-receive.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ReceiveExternalFilesActivity | none | 0.00% |
| 45 | ar-light | system-accounts | 045-ar-light-system-accounts.png | com.android.settings/com.android.settings.Settings$AccountDashboardActivity | none | 0.00% |
| 46 | de-light | files | 046-de-light-files.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 47 | de-light | drawer | 047-de-light-drawer.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 48 | de-light | settings | 048-de-light-settings.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 49 | de-light | settings-2 | 049-de-light-settings-2.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 50 | de-light | accounts | 050-de-light-accounts.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ManageAccountsActivity | none | 0.00% |
| 51 | de-light | uploads | 051-de-light-uploads.png | org.nourax.nodrive/com.owncloud.android.ui.activity.UploadListActivity | none | 0.00% |
| 52 | de-light | auto-upload | 052-de-light-auto-upload.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SyncedFoldersActivity | none | 0.00% |
| 53 | de-light | share-receive | 053-de-light-share-receive.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ReceiveExternalFilesActivity | none | 0.00% |
| 54 | en-dark | files | 054-en-dark-files.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 55 | en-dark | drawer | 055-en-dark-drawer.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 56 | en-dark | settings | 056-en-dark-settings.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 57 | en-dark | settings-2 | 057-en-dark-settings-2.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 58 | en-dark | accounts | 058-en-dark-accounts.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ManageAccountsActivity | none | 0.00% |
| 59 | en-dark | uploads | 059-en-dark-uploads.png | org.nourax.nodrive/com.owncloud.android.ui.activity.UploadListActivity | none | 0.00% |
| 60 | en-dark | auto-upload | 060-en-dark-auto-upload.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SyncedFoldersActivity | none | 0.00% |
| 61 | en-light | files | 061-en-light-files.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 62 | en-light | drawer | 062-en-light-drawer.png | org.nourax.nodrive/com.owncloud.android.ui.activity.FileDisplayActivity | none | 0.00% |
| 63 | en-light | settings | 063-en-light-settings.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 64 | en-light | settings-2 | 064-en-light-settings-2.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SettingsActivity | none | 0.00% |
| 65 | en-light | accounts | 065-en-light-accounts.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ManageAccountsActivity | none | 0.00% |
| 66 | en-light | uploads | 066-en-light-uploads.png | org.nourax.nodrive/com.owncloud.android.ui.activity.UploadListActivity | none | 0.00% |
| 67 | en-light | auto-upload | 067-en-light-auto-upload.png | org.nourax.nodrive/com.owncloud.android.ui.activity.SyncedFoldersActivity | none | 0.00% |
| 68 | en-light | share-receive | 068-en-light-share-receive.png | org.nourax.nodrive/com.owncloud.android.ui.activity.ReceiveExternalFilesActivity | none | 0.00% |
| 69 | en-light | system-accounts | 069-en-light-system-accounts.png | com.android.settings/com.android.settings.Settings$AccountDashboardActivity | none | 0.00% |
| 70 | en-light | notification-channels | 070-en-light-notification-channels.png | com.android.settings/com.android.settings.Settings$AppNotificationSettingsActivity | none | 0.00% |
| 71 | en-light | widget-drop | 071-en-light-widget-drop.png | com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity | none | 0.00% |
| 72 | en-light | widget-configuration | 072-en-light-widget-configuration.png | org.nourax.nodrive/com.nextcloud.client.widget.DashboardWidgetConfigurationActivity | none | 0.00% |

## Result

PASS

