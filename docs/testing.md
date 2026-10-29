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
- **The Workspace apps menu is proven in CI against a throwaway local server** (not drive.nourax.org):
  the emulator signs in to it and the in-app pages are checked. The phone test of the real Drive is
  owner tests 12 to 14 (section 4).
- **The Backup settings are proven in CI on the emulator** against the same kind of throwaway server:
  the switch, the Auto upload rows, a photo that was already on the phone arriving on the server
  exactly once (also after on, off, on), the Documents and Downloads switch, the Stop Google backup
  buttons and the Takeout button. The phone test of the real Drive is owner tests
  15 to 21 (section 4).
- **Smart conflicts are proven in CI on the emulator** against the same kind of throwaway server:
  the "If a file is already in NoDrive" setting, the Smart rule (same size is skipped, another size
  keeps both), the one-time migration, "Resolve all conflicts…" and the one summary notification. The
  test server's own data folder is checked afterwards, file by file. The phone test of the real Drive
  is owner tests 24 to 28 (section 4).
- **Problem reports are proven in CI on the emulator** against the same kind of throwaway server:
  the Settings switch (off by default) and the "Report a problem" row, the crash prompt, a report
  uploaded into `/Problem reports` and checked file by file on the server (never overwritten, the
  folder shared read-only with `nourax-support` when that account exists, a quiet failure when it
  does not), the offline case, a native crash recorded as a crash, and the scrubber on a 4 MB log.
  The 91 scrubber and reports unit tests prove that passwords, tokens and cookies never leave the
  phone. The phone test of the real Drive is owner tests 29 to 34 (section 4).
- **NoAgenda (read only) is proven in CI** twice: an `agenda-server` job runs the auth proof, the API contract and the reads of four people
  against a throwaway Nextcloud 35.0.2 with nc_gantt 2.4.1, Deck 1.19.1, Calendar 6.6.4 and Tasks 0.18.1 (never drive.nourax.org), and an
  `agenda-device` job runs the screens on canned data in light and dark, the real app against the same server, the side-menu entry and the
  offline case on the emulator. See `docs/noagenda.md`. The phone test of the real Drive is owner tests 35 to 44 (section 4). The first NoAgenda run with the final code is [run 40952577428](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40952577428); the `agenda-*.png` images are from it.
- **The DAVx5 entry is gone** from Settings > More (CI: the screen sweep finds no DAVx5 or DAVdroid
  text on 78 screens).

The Problem reports rows come from [run 40852100863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40852100863) on branch `feature/problem-reports`
(`ab584fc70e`, 2026-10-29): the change, the first independent review, three fix rounds and the final
review (approved, `plans/round3-android-reports-final-review-1.md`). All 5 jobs are green. The same
commit is then fast-forwarded to `main`, whose own run repeats every row. The `reports-*.png` images
are from that run.

The Smart conflicts rows come from [run 40770024863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40770024863) on branch `feature/smart-conflicts`
(`8ca686e975`, 2026-10-28): the change, three rounds of independent review and their fixes. All 5 jobs
are green. The same commit is then fast-forwarded to `main`, whose own run repeats every row. The
`conflicts-*.png` images are from that run.

The Backup, Workspace menu and unit-test rows come from [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397)
on branch `feature/backup-settings-fixes` (`5b09df7b08`, 2026-10-27): the Backup change plus the fixes of its independent review (S1 to S8, section 6). All 5 jobs are green.
The 78-screen sweep and the other screenshots come from [run 40399452749](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749)
on branch `feature/backup-settings` (`a6d9e8fe7a`, 2026-10-26); the `backup-*.png` and `workspace-*.png` images are from run 40441111397.
The Workspace apps rows come from [run 40352108524](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40352108524) on branch `feature/workspace-apps`
(`c02bc5970a`). The other CI rows are from [run 40188742857](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40188742857)
on `main` `7c0c2df5cc` (2026-10-25). Every later push runs all CI rows again: see the
[Actions tab](https://github.com/NouraX-ORG/nodrive-android/actions/workflows/android.yml).

## 1. Results

| Feature | Who | Result | Evidence |
|---|---|---|---|
| Branding gate: rebrand and icons up to date, keyword sweep, pins, self-test | CI | pass | Job "Branding gate": "rebrand --check: up to date", "make-icons --check: all images up to date", "882 hits: internal 254, legal 628, unclassified 0", "PASS - no unclassified hit, every pin holds"; self-test on stock genericDebug: 1315 hits, 528 unclassified |
| Builds a debug-signed APK of the nodrive flavour | CI | pass | Job "APK and unit tests": `app-nodrive-debug.apk`, SHA-256 `0c2ee105a34838b843a538575e9c36965dd3760571ec4e1bd6f10a1085356c63`, `apksigner verify` OK |
| APK identity and side-by-side ids (package, labels, authorities, account type, scheme, link host, login URL, push URL, user agent) | CI | pass | `tools/check-apk.py`: "Result PASS"; 113 labels all "NoDrive"; all 7 authorities and the declared permission are `org.nourax.nodrive*`; links only `https://drive.nourax.org` |
| Unit tests | CI | pass | `testNodriveDebugUnitTest`: 96 suites, 554 tests, 0 failed, 3 skipped (upstream `@Ignore`) before the Backup change. [Run 40399452749](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749) after the Backup change: 110 suites, 662 tests, 0 failed, 3 skipped. [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397) with the review fixes: 110 suites, 679 tests, 0 failed, 3 skipped (counted from the unit-test report artifact). [Run 40770024863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40770024863) with Smart conflicts: 117 suites, 739 tests, 0 failed, 3 skipped (counted from the unit-test report artifact). [run 40852100863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40852100863) with Problem reports: 126 suites, 830 tests, 0 failed, 3 skipped (counted from the unit-test report artifact; 91 of them are the reports tests) |
| Lint | CI | pass | `lintNodriveDebug`: "No issues found." (upstream config, warnings are errors) |
| Instrumented tests on the emulator (Android 15, API 35) | CI | pass | 74 listed classes (81 in the JUnit report, which counts nested test classes on their own), 486 tests, 0 failed. The list and the reason for every class or method left out: `tools/ci/instrumented-tests.txt`. In [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397): "instrumented tests: 74 classes, 6 method(s) left out" and "instrumented tests: PASS". The 6th method is an upstream clock flake (see "Known CI flakiness") |
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
| NoAgenda: side-menu entry, Day/Week/Month lists, agents, My team, Sources, offline, auth proof | CI | pass | [Run 40952577428](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40952577428) (`f310a9be01`, all 7 jobs green): "NoAgenda API checks" (auth proof: every NoGantt, Deck and CalDAV call HTTP 200/207, 412 without the OCS header, contract ok; `report.md` 27 PASS, 0 FAIL) and "NoAgenda on the emulator" (`AgendaUiIT` 13 and `AgendaLiveIT` 4 in light and dark, `AgendaDatabaseIT` 3, `AgendaDrawerIT`, `AgendaOfflineIT`, `AgendaWorkerIT`); 169 NoAgenda unit tests (10 server tests skip themselves in the unit job, 0 failed) |
| NoAgenda: Room schema committed, read only (no write request), no connection to drive.nourax.org | CI | pass | CI step "NoAgenda Room schema is committed" (artifact `1.json` equals `app/schemas/.../1.json`); `grep` of write verbs in `agenda/` = 0; `network-report.md`: "Forbidden connection to drive.nourax.org: none"; leak check: no throwaway password in any artifact |
| Account name in system settings, share target, widget, notification channels | CI | pass | Screens 005, 045, 069 (accounts), 007, 044, 053, 068 (share), 009, 071, 072 (widget), 006, 070 (notifications) |
| No call to any Nextcloud GmbH host | CI | pass | `tools/check-network.py` on the emulator's `network.pcap` (233,255 packets): "PASS - no Nextcloud GmbH host" |
| Independent sweep (round 3) | CI artifacts, read-only | pass | [branding-sweep.md](branding-sweep.md) |
| Workspace apps: the navigation list is parsed, Files is left out, the server order is kept, the real answer of a throwaway server matches the unit-test fixture | CI | pass | [Run 40352108524](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40352108524) on branch `feature/workspace-apps` `c02bc5970a`. Unit tests: 104 suites, 607 tests, 0 failed, 3 skipped (53 tests in `org.nourax.nodrive.workspace`: parser, repository cache and ETag, origin, cookies, session, routing, guards). Job "Server login proof": navigation list "status 200, 6 entries"; "answers 304 for its ETag" |
| Workspace apps: sign-in proof against a throwaway Nextcloud server 35 | CI | pass | Job "Server login proof": the cookies of the app's one request open the page signed in ("page with the cookies only (no Authorization header) is signed in: status 200"); without the session cookie, with no credential and with a wrong app password the page is not signed in (401); the web view user agent loads the dashboard |
| Workspace apps: the menu shows the server's apps (not Files); tapping one opens the page inside the app, already signed in | CI | pass | Emulator job: "workspace IT (light): OK (4 tests)" and "workspace IT (dark): OK (1 test)" (`WorkspaceAppsIT`: the page is signed in as the test user, back goes through the page history, an expired session signs in again). Screens: [menu light](screenshots/android/workspace-menu-light.png), [page dark](screenshots/android/workspace-page-dark.png) |
| Workspace apps: the credential never leaves the account's own server | CI | pass | `WorkspaceWebGuardsIT` (inside "workspace IT (light): OK"): another host, http, another port and a redirect get no credential and no cookie, and a probe server on a second host received none (a positive control proves the probe works). Unit tests `WorkspaceOriginTest`, `WorkspaceWebGuardsTest`, `WorkspaceCookiesTest`. The workflow's token grep over the logs: no hit |
| Workspace apps: links in the notification list that point to the account's own server open inside the app, other links still leave the app | CI | pass | Unit test `WorkspaceRoutingTest` (run 40352108524, unit tests green) |
| Workspace apps: removing the account clears the in-app web data | CI | pass | Emulator job: "workspace IT (removal): OK (1 test)". It runs last, because the removal revokes the app password and the server then throttles for a while |
| Backup: planning and safety rules (which folders, the remote path, own versus the user's rows, no delete operation exists) | CI | pass | Unit tests in [run 40399452749](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749): `BackupPlannerTest` (21), `BackupControllerTest` (10), `BackupPathsTest` (9), `BackupStateTest` (5), `BackupSafetyTest` (2: the operation list has no delete, and a source check finds no delete, overwrite, move or delete-after-upload call in `org.nourax.nodrive.backup`). Plans are replayed on a fake store: switching on twice changes nothing, switching off touches only the rows the switch made, another account's rows are never touched |
| Backup switch: turning it on makes Auto upload rows for the phone's media folders, with existing files, folder names kept, Wi-Fi only, remote root `/Phone backup/<phone name>`; Auto upload shows them | CI | pass | Emulator job: "backup IT settings (light): OK (4 tests)" and "(dark): OK (4 tests)" (`BackupSettingsIT`: row fields, the Auto upload list, the kept file, the result screen). Screens: [Settings](screenshots/android/backup-settings-light.png), [result](screenshots/android/backup-setup-result-light.png), [Auto upload](screenshots/android/backup-auto-upload-light.png) |
| Backup switch: a photo that was already on the phone is uploaded, under the backup folder, on the server | CI | pass | Emulator job: "backup IT upload (light): OK (1 test)" (`BackupUploadIT`) and "backup server check: 1 file(s) under Phone backup on the server" (a `find` on the throwaway server's data folder). [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397) (fix S7): "backup IT upload (light): OK (2 tests)" including `onOffOnUploadsEveryFileExactlyOnce` (each photo once on the server, the first photo's etag unchanged), and "backup server check: exactly 3 file(s) under Phone backup on the server" (the check is now an exact count) |
| Backup switch off: stops the folders it turned on, cancels their running upload job, deletes nothing (fix S2) | CI | pass | Unit tests `BackupControllerTest` and the synced-folder store tests (only owned rows of the same account are disabled; the folder's `immediate_files_sync_<id>` work is cancelled, then the row is disabled; user-made rows and other accounts' rows are never touched); [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397): 125 Backup and Workspace unit tests passed. Emulator: "backup IT rows (light): OK (3 tests)", including `aRowTheUserMadeIsNeverRewrittenAndNeverEnabled` (16-field fingerprint identical after on and off). Known weak spot (R1 in the re-check): the device test waits for the work to finish but does not assert the CANCELLED state (section 6). Owner test 22 |
| Stop Google backup: cards for Google Photos, WhatsApp and Google device backup; an app's card shows only when the app is installed (device backup always); every intent has a safe fallback; no QUERY_ALL_PACKAGES | CI | pass | Unit tests `GoogleBackupPlacesTest` (8). `BackupSettingsIT` checks the stubbed Open intents. `tools/check-apk.py`: QUERY_ALL_PACKAGES absent, and package visibility declared for `com.google.android.apps.photos`, `com.whatsapp`, `com.whatsapp.w4b`. Screen: [Stop Google backup](screenshots/android/backup-stop-google-light.png) |
| Move your Google data to NoDrive: guide text and the Open Google Takeout button (browser tab at https://takeout.google.com, no NoDrive credential) | CI | pass | `BackupSettingsIT`: the button starts a Custom Tab with the data `https://takeout.google.com`; no header other than Accept-Language and no sign-in extra is sent. Screen: [Move from Google](screenshots/android/backup-move-google-light.png) |
| First-sign-in offer: shown once, then not again | CI | pass | `BackupSettingsIT`: the dialog "Back up your photos and videos to NoDrive?" shows once for a new sign-in and not again. Screen: [offer](screenshots/android/backup-offer-light.png) |
| Workspace menu leaves out the server's Files, Activity and Photos entries (NoDrive has its own) | CI | pass | Unit test `WorkspaceAppsParserTest` (hidden by id or by app name). Server proof: the throwaway server sends `dashboard, files, office, photos, circles, activity` and the menu shows Dashboard, Office and Teams ([menu](screenshots/android/workspace-menu-light.png)). `WorkspaceAppsIT` has a positive control that the server did send both entries |
| Branding gate and checks with the Backup change | CI | pass | Run 40441111397 (fixes): `rebrand --check: up to date`, `check-branding: PASS - no unclassified hit, every pin holds`, `backup tests: PASS`, `workspace apps test: PASS`, `screen sweep: PASS`, `check-network: PASS - no Nextcloud GmbH host`; its sweep has 77 screens because the fourth Arabic settings page is not always reached. Run 40399452749: "rebrand --check: up to date"; "check-branding: PASS - no unclassified hit"; `check-apk.py` "Result PASS"; "backup tests: PASS", "workspace apps test: PASS", "screen sweep: PASS" (78/78 screens dumped, 0 hits for Nextcloud); lint green. No new string, layout or colour names Nextcloud |
| Backup fixes S1 to S7: folders saved after each step; a user's own Auto upload rows are left alone and listed; auto-add of new folders reuses the detected folders; a battery-optimization note with an "Allow background activity" button; Arabic, Urdu and emoji phone names stay readable in the folder name; Documents and Downloads switch tested on the emulator; on, off, on uploads each file once | CI | pass | [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397): unit tests `aFailurePartWayKeepsTheRowsAlreadyMadeAsOwned...`, `aRowOfTheUserIsNeverChangedAndIsReported`, `autoAddUsesTheFoldersItWasGiven...`, `anArabicPhoneNameStaysReadable`, `aStorageVolumeAsABucketIsSkipped`; emulator "backup IT docs-off (light): OK (1 test)" and "backup IT docs-on (light): OK (1 test)" (All files access set with `appops`: off shows no folders, on shows Download and Documents rows with the right remote paths, Wi-Fi only, no date folders, and the file stays). Screen: [result with the battery note](screenshots/android/backup-setup-result-light.png) |
| Workspace menu shows the server's NoMail entry with its icon and opens it in an in-app browser panel (a Custom Tab), without any NoDrive credential (fix S8) | CI | pass | [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397): unit tests `noMailOnAnotherOriginIsKeptAsAnOutsideEntry...`, `entriesOfAnotherOriginThatAreNotPlainHttpsAreDropped` (14 bad links are dropped: http, ftp, javascript, mailto, intent, user info, no host, ...), `workspaceEntriesOfAnotherHttpsOriginGoToACustomTabWithoutAnAccount`; emulator "workspace IT (light): OK (4 tests)": the test adds a NoMail entry with the href `https://nomail.invalid/` to the throwaway server's answer; NoMail is the last menu entry, a tap starts an `ACTION_VIEW` for that URL with no Authorization or Cookie header, and `WorkspaceWebActivity` does not open. In the [menu screenshot](screenshots/android/workspace-menu-light.png) the entry is below the visible part (to-do N3 in section 6). Owner test 23 |
| Independent re-check of the fixes | CI artifacts, read-only | approved, no blocking finding | Review file `plans/backup-settings-fixes-review-1.md` (nodrive-clients folder) on head `5b09df7b08`: S1 to S8 fixed, data safety holds (no delete call added), no upstream file touched, 1 should-fix (R1) and 9 nits, listed in section 6 |
| Independent review of the Backup change | CI artifacts, read-only | approved, no blocking finding | 7 should-fix and 13 nits, all with a fix in the review file `plans/backup-settings-review-1.md` (nodrive-clients folder). They are the to-dos in section 6 |
| Smart conflicts: the Smart rule. Same size on the Drive is counted as backed up (no upload, no notification); another size is uploaded under a new numbered name and both are kept; a size that cannot be read is retried later, never guessed | CI | pass | [Run 40770024863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40770024863): unit tests `SmartRuleTest` (7), `SmartUploadsMatchTest` (10). Emulator: "conflicts IT upload (light): OK (12 tests)", "(dark): OK (2 tests)", including `smartCountsTheSameSizeAsBackedUpAndKeepsBothForAnotherSize`. "conflicts server check: PASS - 34 file(s), exactly the expected ones" (a listing of the throwaway server's data folder: the same-size file stays alone, the other-size file stays and a `(2)` copy sits next to it) |
| Smart conflicts: nothing is ever deleted, and Smart never overwrites | CI | pass | Unit tests `ConflictSafetyTest` (4: the code of `org.nourax.nodrive.conflicts` has no delete, move or remove call; the word OVERWRITE appears only in `ConflictChoice.kt`; every requeue keeps the phone file; the guard itself is tested). Emulator: `resolveAllSmartNeverOverwritesWhenTheSettingIsKeepThePhoneCopy` and the two switch-during-a-run cases (the Drive file's etag is unchanged and a `(2)` copy exists); the server check confirms them independently |
| Smart is the default and one setting covers every folder the backup switch made: "If a file is already in NoDrive" with Smart (recommended), Keep the NoDrive copy, Keep the phone copy, Keep both, Ask me every time | CI | pass | Emulator "conflicts IT settings (light): OK (2 tests)" and "(dark): OK (2 tests)" (`ConflictsSettingsIT`: Smart shows by default; a change reaches only the owned folders and never a row the user made; the Auto upload folder dialog tells owned folders to use the Settings choice and keeps upstream's picker for other folders). Screens: [Settings](screenshots/android/conflicts-setting-light.png), [choice list](screenshots/android/conflicts-setting-dialog-light.png), [folder dialog](screenshots/android/conflicts-folder-dialog-light.png) |
| Smart conflicts: one-time migration. Backup folders that were set to Ask or Skip become Smart; Rename and Overwrite stay; rows the user made are never touched; it runs once and never undoes a later choice | CI | pass | Unit test `ConflictMigrationTest` (17, on a fake store, including running it twice). Emulator "conflicts IT migration (light): OK (2 tests)" (`ownedAskAndSkipBecomeSmartRenameAndOverwriteStayAndUserRowsAreNeverTouched`, `aChoiceTheUserMakesLaterIsNeverUndoneByAnotherRun`) |
| Smart conflicts: an old Auto upload run cannot put an old choice back (review finding R2-S1) | CI | pass | Owned rows are put back to the saved choice at the start of every Auto upload run, in the upload hook and at app start (hook `OwnedFolderPolicy.healBeforeRun`). Unit tests in `ConflictMigrationTest` (`everyChoiceHealsAStaleRowToItsOwnValue`, 5 choices times 4 stale values, and others). Emulator: `switchingFromKeepThePhoneCopyToSmartDuringARunNeverOverwritesTheDriveFile`, `switchingFromKeepBothToSmartDuringARunStillCountsTheSameSizeAsBackedUp`. Known limit: see "Smart conflicts: what the owner should know" in section 4 |
| Smart conflicts: "Resolve all conflicts…" in the Uploads menu with the four choices, for every waiting conflict of the account; the phone's files are never deleted | CI | pass | Emulator (`ConflictsUploadIT`): `resolveAllKeepBothUploadsACopyNextToEveryOldFile`, `resolveAllKeepTheNoDriveCopyChangesNothingOnTheDrive`, `resolveAllKeepThePhoneCopyReplacesTheDriveFile`, `resolveAllSmartSkipsTheSameSizeAndKeepsBothForTheRest`, `resolveAllKeepThePhoneCopyIsNotIgnoredWhenTheSettingIsKeepTheNoDriveCopy`, `resolveAllForAnAccountWithoutConflictsSaysSoAndChangesNothing`. Unit test `ResolveAllPlannerTest` (9: the plan has no delete action). Screens: [Uploads menu](screenshots/android/conflicts-uploads-menu-light.png), [dialog](screenshots/android/conflicts-resolve-all-light.png) |
| Smart conflicts: one summary notification "N files are already in NoDrive" with a "Resolve all" action, not one per file; the per-file "Resolve conflict" flow stays | CI | pass | Emulator: `threeConflictsGiveOneSummaryNotificationAndNoPerFileOne`, `theNotificationActionOpensTheDialogForItsAccountAndResolves` (the server check: three files, nothing resolved by the notice alone). Screen: [notification](screenshots/android/conflicts-notification-light.png) |
| Smart conflicts: branding, upstream hooks and the CI network rule | CI | pass | [Run 40770024863](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40770024863): `rebrand --check: up to date`, `make-icons --check: all images up to date`, `check-branding: PASS - no unclassified hit, every pin holds` (882 hits, 0 unclassified); `instrumented tests: PASS` (74 classes, 6 methods left out as before); lint green; "screen sweep" 78/78 screens, 0 hits for Nextcloud. 8 marked hooks in upstream files, none removed. The emulator no longer reaches drive.nourax.org at all: `dns-check: drive.nourax.org -> rcode 3, 0 answer(s): blocked`, and `check-network` passes |
| Independent review of Smart conflicts | CI artifacts, read-only | approved, no blocking finding | Three rounds. Round 1: 1 blocking and 2 should-fix items, fixed. Round 2: 1 should-fix (a stale Auto upload write), fixed. Round 3 on head `8ca686e975`: approved; 4 nits, listed in section 6. Review files `plans/round3-android-conflicts-review-1.md`, `-2.md`, `-3.md` (nodrive-clients folder) |
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
| Workspace apps menu on the real Drive: lists the user's apps, opens them inside NoDrive already signed in, back returns to the files | owner | pending | Owner test 12 |
| A notification link to drive.nourax.org opens inside NoDrive, signed in | owner | pending | Owner test 13 |
| Sign out: the in-app pages are signed out too; signing in again works | owner | pending | Owner test 14 |
| Backup: the switch "Back up all photos and videos to NoDrive" turns on every media folder; old and new photos arrive on the Drive under Phone backup; Auto upload lists the folders | owner | pending | Owner test 15 |
| Backup: "Also back up Documents and Downloads" and the All files access step | owner | pending | Owner test 16 |
| Backup: the one-time offer after the first sign-in | owner | pending | Owner test 17 |
| Backup: switching off stops uploads and deletes nothing | owner | pending | Owner test 18 |
| Stop Google backup: each Open button takes you to the right place | owner | pending | Owner test 19 |
| Move your Google data to NoDrive: the guide and the Open Google Takeout button | owner | pending | Owner test 20 |
| Workspace menu: no second Activity and Photos entries | owner | pending | Owner test 21 |
| Backup: switching the backup off during the first big upload stops the uploading | owner | pending | Owner test 22 |
| Workspace menu: NoMail appears with its icon and opens in the in-app browser panel, not signed in to NoDrive | owner | pending | Owner test 23 |
| Smart conflicts: backing up a phone whose photos are already on the Drive no longer asks once per file; the same photos are counted as done | owner | pending | Owner test 24 |
| Smart conflicts: a different photo with the same name is kept next to the old one, never replacing it | owner | pending | Owner test 25 |
| Smart conflicts: "If a file is already in NoDrive" in Settings > Backup, and the old Ask or Skip folders switch to Smart | owner | pending | Owner test 26 |
| Smart conflicts: "Resolve all conflicts…" and the one summary notification | owner | pending | Owner test 27 |
| Smart conflicts: Keep the phone copy replaces the Drive file, and only when you choose it | owner | pending | Owner test 28 |
| Problem reports: Settings has the switch "Send problem reports to Nourax" (off by default, contract text) and the "Report a problem" row; upstream's raw "Logs" row is gone | CI | pass | `ReportsSettingsIT` 3 tests in light and 3 in dark (`reports IT settings (light): OK (3 tests)`, same for dark); screens `reports-settings-light/dark.png` |
| Problem reports: the DAVx5 entry ("Set up DAVx5") is gone from Settings > More; "Contacts and calendar backup" stays | CI | pass | `davdroid_integration_enabled=false` in the generated `setup.xml` (`rebrand --check: up to date`); screen sweep: "no screen text names DAVx5 or DAVdroid: 78/78 screens dumped, 0 hits" |
| Problem reports: after a crash or an unclean exit the next start asks once ("NoDrive closed unexpectedly. Send a problem report?"), Not now sends nothing; with the switch on the report is sent at once | CI | pass | `ReportsPromptIT` prepare, question and clean steps in light and dark (6 `reports IT prompt-*: OK` lines); a native crash probe is recorded as `CRASH_NATIVE`; screens `reports-crash-prompt-*`, `reports-auto-sent-light` |
| Problem reports: one zip per report (`report.json` with 12 fields, `crash.txt`, `logs/`) goes by WebDAV into `/Problem reports`, never overwrites a taken name, and the folder is shared read-only with `nourax-support` once per run (quietly skipped when the account is missing) | CI | pass | `ReportsUploadIT`: `reports IT upload-b1 ... b5: OK`, `reports server check: PASS` (the test server's data folder is checked file by file; the test user `nourax-support` is created between b1 and b2); 11 sender, 10 flow and 7 builder unit tests |
| Problem reports: without a network the report waits on the phone and the screen says so; it is sent when the network is back | CI | pass | `ReportsOfflineIT`: `reports IT offline-c1` and `offline-c2: OK` ("network is back" between them); `reports-offline-light.png`, `reports-saved-light.png` |
| Problem reports: the scrubber removes Authorization headers, Basic and Bearer values, app passwords, tokens, cookies, session names, URL secrets, private keys, e2ee material and quoted secrets, replaces e-mail addresses with `<email>`, keeps file and folder names, and is fast on a 4 MB log on Android | CI | pass | 91 reports unit tests, 0 failed (Scrubber 25, ReportSmallPieces 12, Sender 11, Flow 10, ExitClassifier 9, Safety 8, Builder 7, Messages 5, Json 4); `ReportsScrubberSpeedIT`: "reports IT scrubber-speed (light): OK (2 tests)" (a 4 MB log with 5,000 addresses, ended in under 4 s); the reviewer's 34 fixtures with random fake secrets: 34/34 removed |
| Problem reports: nothing is deleted on the phone or the Drive (the only delete is inside the app's own report folder); no host is written in the code; reports go only through the signed-in account | CI | pass | `ReportsSafetyTest` (8 tests: only `deleteOwn` deletes, no `http://` or `https://` literal, no Matcher per match); network check: "1 request(s), 0 carrying the account" for the probe server; "PASS - no Nextcloud GmbH host" |
| Problem reports: Settings > More has the switch (off) and the Report a problem row, in plain English; no DAVx5 entry and no Logs row | owner | pending | Owner test 29 |
| Problem reports: "Report a problem" with the switch off sends one report to the Drive's Problem reports folder, and the zip holds no password | owner | pending | Owner test 30 |
| Problem reports: "What's included" tells the truth about what the report holds | owner | pending | Owner test 31 |
| Problem reports: with no network the report is kept and sent later | owner | pending | Owner test 32 |
| Problem reports: with the switch on, a crash or unclean exit sends a report by itself (optional, only if the app ever closes on its own) | owner | pending | Owner test 33 |
| Problem reports: the folder is shared read-only with Nourax Support (needs the server account to exist) | owner | pending | Owner test 34 |

### Known CI flakiness

The emulator job sometimes fails for reasons outside the app. Re-running the failed job is enough
(`gh run rerun <id> --failed`).

- **Run 40188742857, attempt 1:** in `en-light`, the browser did not take the focus in time
  ("sign-in opens the browser ... address bar=''"). Attempt 2 passed every check.
- **Upstream clock test:** `UploadDateTests#getRelativeDateTimeStringReturnsRelativeStringForHoursAgo`
  fails when the emulator's clock is between 00:00 and 02:00 UTC. It is left out of the list in
  `tools/ci/instrumented-tests.txt` (6 methods are left out in total). It tests upstream's date text
  only; no branding is involved.
- **Run 40441111397:** three earlier runs of the same branch failed in the NoMail tap test of the
  Workspace menu (the drawer runs a tap only after it has closed). Fixed in the later commits; the
  head run is green.
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

78 screens in [screenshots/android/](screenshots/android/README.md), taken by `tools/ci/sweep.py` in
run [40399452749](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40399452749). The `backup-*.png` and `workspace-*.png` images were replaced by those of [run 40441111397](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40441111397) (the review fixes; that run's own sweep has 77 screens, all with 0 Nextcloud hits). The Backup change added Settings pages (Settings has a new Backup section at the top) and moved the numbers, so every link below is to the new image. The
emulator is Android 15 on a Pixel 6 profile.

- The signed-in screens use a local stand-in account `demo@drive.nourax.org`, added in airplane mode.
  It never reaches the server.
- The "Sorry, you have been blocked" browser page is the Drive's firewall, which keeps CI away from
  the login pages. It is expected.
- Every newer run makes the same set in its `nodrive-android-screenshots` artifact. The 72 screens
  of run 40188742857 were all looked at for the final sweep; the Backup screens and the Settings
  pages of run 40399452749 were looked at for the independent review.

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
| files | [066](screenshots/android/066-en-light-files.png) | [058](screenshots/android/058-en-dark-files.png) | [049](screenshots/android/049-de-light-files.png) | [038](screenshots/android/038-ar-light-files.png) | [030](screenshots/android/030-ar-dark-files.png) |
| drawer | [067](screenshots/android/067-en-light-drawer.png) | [059](screenshots/android/059-en-dark-drawer.png) | [050](screenshots/android/050-de-light-drawer.png) | [039](screenshots/android/039-ar-light-drawer.png) | [031](screenshots/android/031-ar-dark-drawer.png) |
| settings | [068](screenshots/android/068-en-light-settings.png) | [060](screenshots/android/060-en-dark-settings.png) | [051](screenshots/android/051-de-light-settings.png) | [040](screenshots/android/040-ar-light-settings.png) | [032](screenshots/android/032-ar-dark-settings.png) |
| settings-2 | [069](screenshots/android/069-en-light-settings-2.png) | [061](screenshots/android/061-en-dark-settings-2.png) | [052](screenshots/android/052-de-light-settings-2.png) | [041](screenshots/android/041-ar-light-settings-2.png) | [033](screenshots/android/033-ar-dark-settings-2.png) |
| settings-3 | [070](screenshots/android/070-en-light-settings-3.png) | [062](screenshots/android/062-en-dark-settings-3.png) | [053](screenshots/android/053-de-light-settings-3.png) | [042](screenshots/android/042-ar-light-settings-3.png) | [034](screenshots/android/034-ar-dark-settings-3.png) |
| settings-4 |  |  |  | [043](screenshots/android/043-ar-light-settings-4.png) |  |
| accounts | [071](screenshots/android/071-en-light-accounts.png) | [063](screenshots/android/063-en-dark-accounts.png) | [054](screenshots/android/054-de-light-accounts.png) | [044](screenshots/android/044-ar-light-accounts.png) | [035](screenshots/android/035-ar-dark-accounts.png) |
| uploads | [072](screenshots/android/072-en-light-uploads.png) | [064](screenshots/android/064-en-dark-uploads.png) | [055](screenshots/android/055-de-light-uploads.png) | [045](screenshots/android/045-ar-light-uploads.png) | [036](screenshots/android/036-ar-dark-uploads.png) |
| auto-upload | [073](screenshots/android/073-en-light-auto-upload.png) | [065](screenshots/android/065-en-dark-auto-upload.png) | [056](screenshots/android/056-de-light-auto-upload.png) | [046](screenshots/android/046-ar-light-auto-upload.png) | [037](screenshots/android/037-ar-dark-auto-upload.png) |
| share-receive | [074](screenshots/android/074-en-light-share-receive.png) |  | [057](screenshots/android/057-de-light-share-receive.png) | [047](screenshots/android/047-ar-light-share-receive.png) |  |
| system-accounts | [075](screenshots/android/075-en-light-system-accounts.png) |  |  | [048](screenshots/android/048-ar-light-system-accounts.png) |  |
| notification-channels | [076](screenshots/android/076-en-light-notification-channels.png) |  |  |  |  |
| widget-drop | [077](screenshots/android/077-en-light-widget-drop.png) |  |  |  |  |
| widget-configuration | [078](screenshots/android/078-en-light-widget-configuration.png) |  |  |  |  |

### Workspace apps screens

Four extra screens from the same emulator run, taken against the throwaway server with the test user
`citest`. They are in [screenshots/android/](screenshots/android/README.md), named `workspace-*.png`.
The server sends Files, Activity and Photos too; the menu leaves them out. The test server also sends a NoMail entry on another address: it is the last item, below the visible part of the menu in the screenshot.

| Screen | Light | Dark |
|---|---|---|
| The drawer: the files entries, then the server's apps (Dashboard, Office, Teams, ...) | [workspace-menu-light.png](screenshots/android/workspace-menu-light.png) | [workspace-menu-dark.png](screenshots/android/workspace-menu-dark.png) |
| An app opened inside NoDrive, signed in (Dashboard) | [workspace-page-light.png](screenshots/android/workspace-page-light.png) | [workspace-page-dark.png](screenshots/android/workspace-page-dark.png) |

### Backup screens

Twelve extra screens from the same run (`BackupSettingsIT`), named `backup-*.png`, light and dark.

| Screen | Light | Dark |
|---|---|---|
| Settings, Backup section at the top | [backup-settings-light.png](screenshots/android/backup-settings-light.png) | [backup-settings-dark.png](screenshots/android/backup-settings-dark.png) |
| Result after the main switch is turned on | [backup-setup-result-light.png](screenshots/android/backup-setup-result-light.png) | [backup-setup-result-dark.png](screenshots/android/backup-setup-result-dark.png) |
| Auto upload with the folder the switch turned on | [backup-auto-upload-light.png](screenshots/android/backup-auto-upload-light.png) | [backup-auto-upload-dark.png](screenshots/android/backup-auto-upload-dark.png) |
| The one-time offer after the first sign-in | [backup-offer-light.png](screenshots/android/backup-offer-light.png) | [backup-offer-dark.png](screenshots/android/backup-offer-dark.png) |
| Stop Google backup | [backup-stop-google-light.png](screenshots/android/backup-stop-google-light.png) | [backup-stop-google-dark.png](screenshots/android/backup-stop-google-dark.png) |
| Move your Google data to NoDrive | [backup-move-google-light.png](screenshots/android/backup-move-google-light.png) | [backup-move-google-dark.png](screenshots/android/backup-move-google-dark.png) |

### Smart conflicts screens

Twelve extra screens from the same run (`ConflictsSettingsIT` and `ConflictsUploadIT`), named
`conflicts-*.png`, light and dark. The file names in the lists are test names.

| Screen | Light | Dark |
|---|---|---|
| Settings, Backup section with "If a file is already in NoDrive: Smart (recommended)" | [conflicts-setting-light.png](screenshots/android/conflicts-setting-light.png) | [conflicts-setting-dark.png](screenshots/android/conflicts-setting-dark.png) |
| The choice list (five choices) | [conflicts-setting-dialog-light.png](screenshots/android/conflicts-setting-dialog-light.png) | [conflicts-setting-dialog-dark.png](screenshots/android/conflicts-setting-dialog-dark.png) |
| Auto upload folder dialog of a backup folder: the text that points to the Settings choice | [conflicts-folder-dialog-light.png](screenshots/android/conflicts-folder-dialog-light.png) | [conflicts-folder-dialog-dark.png](screenshots/android/conflicts-folder-dialog-dark.png) |
| Uploads menu with "Resolve all conflicts…" | [conflicts-uploads-menu-light.png](screenshots/android/conflicts-uploads-menu-light.png) | [conflicts-uploads-menu-dark.png](screenshots/android/conflicts-uploads-menu-dark.png) |
| Resolve all conflicts dialog (Smart, Keep the NoDrive copy, Keep the phone copy, Keep both) | [conflicts-resolve-all-light.png](screenshots/android/conflicts-resolve-all-light.png) | [conflicts-resolve-all-dark.png](screenshots/android/conflicts-resolve-all-dark.png) |
| The one summary notification "3 files are already in NoDrive" with "Resolve all" | [conflicts-notification-light.png](screenshots/android/conflicts-notification-light.png) | [conflicts-notification-dark.png](screenshots/android/conflicts-notification-dark.png) |

### Problem reports screens

Twelve extra screens from the same run (`ReportsSettingsIT`, `ReportsPromptIT`, `ReportsUploadIT`,
`ReportsOfflineIT` and the device steps in `tools/ci/reports-device.sh`), named `reports-*.png`. Only
the steps that show something new have a dark image.

| Screen | Light | Dark |
|---|---|---|
| Settings > More: the switch (off) and "Report a problem" | [reports-settings-light.png](screenshots/android/reports-settings-light.png) | [reports-settings-dark.png](screenshots/android/reports-settings-dark.png) |
| The "Report a problem" screen | [reports-report-light.png](screenshots/android/reports-report-light.png) | [reports-report-dark.png](screenshots/android/reports-report-dark.png) |
| "What's included" | [reports-included-light.png](screenshots/android/reports-included-light.png) | [reports-included-dark.png](screenshots/android/reports-included-dark.png) |
| The crash question after an unexpected close | [reports-crash-prompt-light.png](screenshots/android/reports-crash-prompt-light.png) | [reports-crash-prompt-dark.png](screenshots/android/reports-crash-prompt-dark.png) |
| "Problem report sent" after Send | [reports-sent-light.png](screenshots/android/reports-sent-light.png) | |
| "Problem report sent" after an automatic send (switch on) | [reports-auto-sent-light.png](screenshots/android/reports-auto-sent-light.png) | |
| Offline: the report is saved on the phone | [reports-offline-light.png](screenshots/android/reports-offline-light.png) | |
| "Saved in your NoDrive > Problem reports." | [reports-saved-light.png](screenshots/android/reports-saved-light.png) | |

### NoAgenda screens

Thirty-two screens from the NoAgenda emulator job (`AgendaUiIT`, `AgendaLiveIT`, `AgendaDrawerIT`, `AgendaOfflineIT`; `tools/ci/agenda-device.sh`),
named `agenda-*.png`, in light and dark. The side menu with NoAgenda first: [agenda-menu-light.png](screenshots/android/agenda-menu-light.png);
offline with the real server's data: [agenda-offline-live-light.png](screenshots/android/agenda-offline-live-light.png).

| Screen | Light | Dark |
|---|---|---|
| Day: the three kinds of work, sections, filters | [agenda-day-light.png](screenshots/android/agenda-day-light.png) | [agenda-day-dark.png](screenshots/android/agenda-day-dark.png) |
| Week | [agenda-week-light.png](screenshots/android/agenda-week-light.png) | [agenda-week-dark.png](screenshots/android/agenda-week-dark.png) |
| Month: grid for jumping, list below | [agenda-month-light.png](screenshots/android/agenda-month-light.png) | [agenda-month-dark.png](screenshots/android/agenda-month-dark.png) |
| My team: problems with the person and the agent | [agenda-my-team-light.png](screenshots/android/agenda-my-team-light.png) | [agenda-my-team-dark.png](screenshots/android/agenda-my-team-dark.png) |
| Sources: every calendar, project and board with its setting | [agenda-sources-light.png](screenshots/android/agenda-sources-light.png) | [agenda-sources-dark.png](screenshots/android/agenda-sources-dark.png) |
| Sources: no agent list in the person's NoDrive | [agenda-registry-missing-light.png](screenshots/android/agenda-registry-missing-light.png) | [agenda-registry-missing-dark.png](screenshots/android/agenda-registry-missing-dark.png) |
| "Which of your calendars are personal?" | [agenda-personal-question-light.png](screenshots/android/agenda-personal-question-light.png) | [agenda-personal-question-dark.png](screenshots/android/agenda-personal-question-dark.png) |
| Detail sheet (only "Open in ...") | [agenda-detail-light.png](screenshots/android/agenda-detail-light.png) | [agenda-detail-dark.png](screenshots/android/agenda-detail-dark.png) |
| Not linked to a person in NoGantt | [agenda-empty-not-linked-light.png](screenshots/android/agenda-empty-not-linked-light.png) | [agenda-empty-not-linked-dark.png](screenshots/android/agenda-empty-not-linked-dark.png) |
| Offline: the saved copy with its time | [agenda-offline-light.png](screenshots/android/agenda-offline-light.png) | [agenda-offline-dark.png](screenshots/android/agenda-offline-dark.png) |
| One source down, the others fresh | [agenda-gantt-down-light.png](screenshots/android/agenda-gantt-down-light.png) | [agenda-gantt-down-dark.png](screenshots/android/agenda-gantt-down-dark.png) |
| Against the throwaway server: Ali's day | [agenda-live-day-light.png](screenshots/android/agenda-live-day-light.png) | [agenda-live-day-dark.png](screenshots/android/agenda-live-day-dark.png) |
| Against the throwaway server: the manager's My team | [agenda-live-my-team-light.png](screenshots/android/agenda-live-my-team-light.png) | [agenda-live-my-team-dark.png](screenshots/android/agenda-live-my-team-dark.png) |
| Against the throwaway server: Sara's invalid agent list | [agenda-live-sources-invalid-registry-light.png](screenshots/android/agenda-live-sources-invalid-registry-light.png) | [agenda-live-sources-invalid-registry-dark.png](screenshots/android/agenda-live-sources-invalid-registry-dark.png) |
| Against the throwaway server: Olaf (another organisation) | [agenda-live-olaf-light.png](screenshots/android/agenda-live-olaf-light.png) | [agenda-live-olaf-dark.png](screenshots/android/agenda-live-olaf-dark.png) |

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
| 7 | Auto upload | (The one-switch backup in tests 15 to 21 also covers this.) Settings > Auto upload: switch on the camera folder; take a photo; wait a minute | Did the photo appear on drive.nourax.org? |
| 8 | Dark mode and language | Switch the phone to dark mode, then to another language (for example German or Arabic) | Any screen with Nextcloud's name, logo or blue? |
| 9 | Mobile data | Away from home, on mobile data: open NoDrive and refresh; upload a small file | Did it sync? |
| 10 | Sign out | Drawer > Sign out | Back to the sign-in screen? |
| 11 | Next to Nextcloud (optional) | Install the real Nextcloud app too and sign in to both | Do both work? Two separate accounts in system settings? |

### Tests 12 to 14: the Workspace apps

Use the same test build and the owner's normal Nourax account. Do these after test 2.

| # | Test | What to do | What to report |
|---|---|---|---|
| 12 | Workspace apps menu | Open the side menu. Below the files entries, find the Workspace apps (for example Mail, Calendar, Talk). Tap one | Which apps are listed? Is "Files" missing (it should be)? Did the page open inside NoDrive, with no browser address bar and **no sign-in page**, already showing your account? Press back: do you return to the files? Do the icons look right? |
| 13 | Notification link | Open the notification list. Tap a notification that has a link to drive.nourax.org (for example a Talk message or a calendar invitation) | Did the page open inside NoDrive, signed in, instead of Chrome? Does a link to another website still open in the phone's browser? |
| 14 | Sign out and in | Open one Workspace app. Then side menu > Sign out. Sign in again with "Log in with Nourax". Open a Workspace app again | After signing out, are the in-app pages signed out (open NoDrive's menu again only after signing in)? After signing in again, does a Workspace app open signed in with no sign-in page? |

### Tests 15 to 23: Backup, Stop Google backup, Move from Google, NoMail

Use the newest test build (install the `nodrive-android-apk` of the newest green run on `main`, over
the old one) and the owner's normal Nourax account. Do these after test 2. **Do test 15 on Wi-Fi.**
The first backup can be large: leave the phone on Wi-Fi (plugged in if you like) and give it time.

Where things are on the Drive: everything goes under the folder **Phone backup**, then a folder with
the phone's name, then the phone's own folder names (for example `Phone backup/Pixel 8/Camera`,
`.../Screenshots`, `.../WhatsApp Images`). No date folders. Two phones with the same default name
(for example two "Pixel 8") would share one folder; a different file with the same name is kept next
to the old one (the "Smart" choice, tests 24 to 28), and nothing is overwritten.

| # | Test | What to do | What to report |
|---|---|---|---|
| 15 | One backup switch | Open NoDrive > Settings. At the top is **Backup**. Switch on **Back up all photos and videos to NoDrive**. Allow the photo and video access if Android asks. A result screen lists the folders. Tap **Open Auto upload**. Then, on a computer, open drive.nourax.org > Files > **Phone backup** | Which folders did the result screen list (Camera, Screenshots, Pictures, Movies, WhatsApp, ...)? Does Auto upload show the same folders as switched on, with Wi-Fi only on? Did **old** photos (from before you switched on) and a **new** photo (take one now) appear on the Drive, in a folder named like the phone's folder, with no date folders? How long did it take? Did the photos stay on the phone? If the screen offers "allow background activity", did that work? |
| 16 | Documents and Downloads | In Settings > Backup, switch on **Also back up Documents and Downloads**. Android sends you to **All files access**: allow it for NoDrive, then go back | Did the switch ask for All files access and then turn on? Do the phone's Documents and Download files appear on the Drive under Phone backup? If you say no to All files access, does NoDrive say nothing was changed? |
| 17 | The offer after sign-in | On a phone that has **never** signed in to NoDrive (or after signing out and signing in as a new account): after the first sign-in a box asks "Back up your photos and videos to NoDrive?" with **Turn on** and **Not now**. (A phone that was already signed in before this update does not get the box: use Settings > Backup.) | Did the box show once? Does **Not now** leave the switch off, and does the box not come back? Does **Turn on** do the same as test 15? |
| 18 | Switch off | Settings > Backup: switch off the main switch (and the Documents switch if on). Take a new photo and wait a few minutes. Look at the Drive and at Auto upload | Do new photos stay off the Drive? Is Auto upload now off for those folders? Is everything that was already on the Drive still there, and everything on the phone still there? (Nothing is ever deleted.) Switching on again: does it carry on without uploading the same photos twice? (A file that was already uploading when you switched off may still finish.) |
| 19 | Stop Google backup | Settings > Backup > **Stop Google backup**. For each card tap **Open**: Google Photos (only if installed), Google device backup, WhatsApp (only if installed). Follow the short guide on the card and switch the backup off yourself | Which cards were listed? Did **Open** take you to Google Photos, to Android's Google backup settings (or to the main Settings screen with the guide), and to WhatsApp? Could you follow each guide and find the switch? Did anything crash or do nothing? |
| 20 | Move from Google | Settings > Backup > **Move your Google data to NoDrive**. Read the guide. Tap **Open Google Takeout** | Is the guide clear (export Drive and Google Photos, download the zip files on a computer, unzip them all into one folder inside the NoDrive folder, the desktop app uploads it with the same folder structure)? Did the browser open takeout.google.com, with no NoDrive sign-in or password involved? |
| 21 | No duplicates in the menu | Open the side menu | Below NoDrive's own entries (Activities, Media, Albums, ...) are the Workspace apps (for example Dashboard, Office, Talk). Is there **no** second "Activity", **no** second "Photos" and no "Files" there? |
| 22 | Switch off during the first big upload | Do test 15 on a phone with many photos and videos. While the first upload is still running (the upload notification is visible, or Uploads shows files waiting), open Settings > Backup and switch the main switch **off** | Does the uploading stop within a minute or so (the notification goes away; at most the file that was being sent finishes)? Do no more new files appear on the Drive? Is Auto upload off for those folders? Is everything already on the Drive and everything on the phone still there? Switching on again: does it carry on and send only what is missing? |
| 23 | NoMail in the menu | Open the side menu and scroll to the Workspace apps | Is **NoMail** listed, with its icon, in the server's order? Tap it: does it open in the in-app browser panel (a Custom Tab, with an address bar) at the NoMail address? Does it show NoMail's own sign-in (you sign in there yourself) and **not** your NoDrive account? Does back return to the files? |

### Tests 24 to 28: Smart conflicts ("If a file is already in NoDrive")

Use the newest test build (install the `nodrive-android-apk` of the newest green run on `main`, over
the old one) and the owner's normal Nourax account. Do these after tests 2 and 15. **Use Wi-Fi.**
What changed in plain words: the backup no longer asks "the file already exists, what to do?" once for
every photo. By default ("Smart") a photo that is already on the Drive with the same size is counted as
backed up and left alone; a photo with the same name but another size is uploaded under a new numbered
name, for example `IMG_1234 (2).jpg`, so both are kept. Smart never replaces and never deletes anything.

| # | Test | What to do | What to report |
|---|---|---|---|
| 24 | Photos already on the Drive | On a phone whose photos are already in **Phone backup** on the Drive (for example after a reinstall, or if you used the old backup): open NoDrive > Settings > Backup and switch the backup on (off and on again if it was on). Wait a few minutes. Then look at the notification shade and at the **Uploads** screen | Did NoDrive stop asking "file already exists" for every photo? Were the photos that are already on the Drive simply left alone (no new copies, no `(2)` names for them)? Is there at most **one** notice, "N files are already in NoDrive", and not hundreds? |
| 25 | Same name, another photo | Take a photo and let it upload. On a computer, replace that file on the Drive with a **different** picture of a different size under the same name (or upload another file named like a phone photo). On the phone, make the backup look at it again (switch the backup off and on). Wait a few minutes | Did the Drive keep your computer's file unchanged **and** get a second file with a number, for example `name (2).jpg`, holding the phone's photo? Was nothing replaced and nothing deleted, on the phone or on the Drive? |
| 26 | The setting | Settings > Backup: find **If a file is already in NoDrive**. Open it. Then open Settings > Auto upload and tap one of the backup folders, then **What to do if the file already exists?** | Does it show **Smart (recommended)** with the choices Keep the NoDrive copy, Keep the phone copy, Keep both and Ask me every time? In Auto upload, does a backup folder tell you to change it in Settings > Backup (and a folder you made yourself still show Android's usual picker)? If a backup folder was set to Ask or Skip before this update, does it now say Smart? (A folder set to Keep both or Keep the phone copy keeps that choice.) |
| 27 | Resolve all | Open the **Uploads** screen. Open its three-dot menu and tap **Resolve all conflicts…** (if conflicts are waiting, you will also see the one notice "N files are already in NoDrive" with a **Resolve all** button: tap it). Pick **Smart (recommended)** and tap **Apply**. Look at the Uploads screen again | Is **Resolve all conflicts…** in the menu? Does the box offer Smart, Keep the NoDrive copy, Keep the phone copy and Keep both? After Apply, are the waiting conflicts gone from the list, with the same-size files counted as done and the others kept as numbered copies? Are all your phone files still on the phone? If nothing waits, does it say "There are no conflicts to resolve."? |
| 28 | Keep the phone copy | **Only on a test file.** Settings > Backup > If a file is already in NoDrive > **Keep the phone copy**. Make a file on the phone that has the same name as one on the Drive (backed-up folder) but different content. Wait for the backup | Did the Drive file now hold the phone's content (this choice replaces the Drive file, as its name says)? Then set the choice back to **Smart (recommended)** and say so |

### Smart conflicts: what the owner should know

- **Same size means the same file.** Smart compares the size in bytes only (the rule in the contract). A different photo with the same name **and** the same size would count as backed up.
- **After a reinstall,** a photo that once got a numbered copy (`name (2).jpg`) is compared only with `name.jpg`, so it can get another copy (`name (3).jpg`). Checking the numbered copies too is a proposal, not built.
- **A Keep choice also settles files that already wait.** Choosing Keep the phone copy in Settings replaces the Drive file of every conflict that is already waiting, without Resolve all. Keep the NoDrive copy and Keep both settle them too. Use Smart if unsure.
- **One summary notice per account**, not one for the whole app, because Resolve all works per account.
- **A run that is already going** when you change the choice finishes with the choice it started with. This matters only when you switch away from Keep the phone copy during a running backup: the rest of that one run can still replace files. The next run uses the new choice.
- **The database still says "Ask".** A backup folder with Smart is stored as Ask in the upstream column; the NoDrive choice is the truth. Anyone reading the Auto upload database sees that.
- Waiting conflicts from before the update are not changed by the migration. Tap Resolve all once.
- Release-only behaviour (the crash screen) is not exercised in CI, which builds the debug flavour.

### Tests 29 to 34: Problem reports and the removed DAVx5 entry

Use the newest test build (install the `app-nodrive-debug.apk` from `owner-tests/android/` or the
`nodrive-android-apk` of the newest green run on `main`, over the old one) and the owner's normal
Nourax account. Do these after test 2. What changed in plain words: Settings > More has a switch
**Send problem reports to Nourax** (off) and a row **Report a problem**. A report is a zip with the
app version, phone model, your note, the technical details of a crash and the app's recent log, with
passwords, tokens and cookies removed and e-mail addresses replaced by `<email>`. It is saved in your
own Drive in the folder **Problem reports** and shared, read-only, with the user `nourax-support`.
Nothing is sent unless you press Send, or unless you switched the switch on. The old "Set up DAVx5"
row is gone.

| # | Test | What to do | What to report |
|---|---|---|---|
| 29 | Settings | Open NoDrive > Settings, scroll to **More** | Is **Send problem reports to Nourax** there, switched **off**, with the text "If the app has a problem, send a report to Nourax Support so we can fix it. Reports are saved in your NoDrive and shared with Nourax Support. They never include passwords."? Is **Report a problem** below it? Is there **no** "Sync calendar and contacts / Set up DAVx5" row and no "Logs" row? Is "Contacts and calendar backup" still there? |
| 30 | Report a problem | With the switch **off**, tap **Report a problem**. Type "test report" in **What went wrong?** and tap **Send**. Then, on a computer, open drive.nourax.org > Files > **Problem reports** | Did the screen say "Problem report sent" (or "Saved in your NoDrive > Problem reports.")? Is there a zip named like `2026-10-29_14-05-33_Android_35.0.1-nourax.1.zip`? Download it and open it: does it hold `report.json` (with your note) and a `logs` folder? **Search the files for your password, your e-mail address and "Authorization": do you find any?** (You must not: e-mail addresses read `<email>`.) |
| 31 | What's included | On the Report a problem screen tap **What's included** | Is the list in plain words (what you typed, app and phone details, date and server name, crash details, the recent activity log with file and folder names, never passwords, saved in NoDrive and shared read-only with Nourax Support)? Does any text name Nextcloud? |
| 32 | No network | Switch on airplane mode. Report a problem > Send. Wait about 5 seconds. Then switch airplane mode off and wait a minute or two. Check the Problem reports folder on the Drive | Did the screen say the report is saved on the phone and let you leave? Did the zip arrive on the Drive after the network came back, once and not twice? |
| 33 | Automatic report (optional) | Only if NoDrive ever closes by itself (a crash): on the next start, with the switch **off** it asks "NoDrive closed unexpectedly. Send a problem report?" with Send and Not now. Switch the switch **on** to try the automatic send. (Force stopping the app from Android's settings is **not** a crash and asks nothing.) | Did the question show once? Did Not now send nothing and not ask again for the same crash? With the switch on, did a "Problem report sent" message show and a zip appear on the Drive? |
| 34 | Shared with Support | After test 30, on drive.nourax.org open Files > **Problem reports** and look at its sharing. This works only after the server has the `nourax-support` account (section 5) | Is the folder shared with `nourax-support`, **read only**? If the account does not exist yet, did the app still say "Saved in your NoDrive > Problem reports." and nothing else broke? |

### Problem reports: what the owner should know

- **Opt-in.** The switch is off. Without it only the **Report a problem** button and the one crash question can send anything, and only when you press Send.
- **Only your own Drive.** The report goes through your signed-in account into your own files. There is no other server, no analytics and no background ping.
- **File and folder names are kept** in the report on purpose (they are needed to find a problem). Passwords, app passwords, tokens and cookies are removed, e-mail addresses read `<email>`. A file name like `icon@2x.png` can also become `<email>`.
- **The share to Nourax Support fails quietly** until the server side has the `nourax-support` account and allows sharing with it (section 5). The report is still saved in your Drive.
- **The logs stay on the phone**, inside the app's private folder (4 files of 1 MB), and leave it only inside a scrubbed report.
- Release-only behaviour (the crash screen of the release build) is not exercised in CI, which builds the debug flavour.

### Tests 35 to 44: NoAgenda (read only)

Use the newest test build (the `app-nodrive-debug.apk` of the newest green run, installed over the old one) and the owner's normal
Nourax account. What changed in plain words: the side menu has a new first item in the Apps section, **NoAgenda**. It shows, on one day,
week or month, everything you are responsible for: your calendar events, your to-dos, your NoGantt project tasks and your NoBoard cards,
and who does each one (one of your agents, or you yourself: **Self**). If you run a project or board it also shows the problems in them
under **My team**. In this step you can only look: nothing can be changed, and **Open in ...** opens the page of the Drive. Do these
after test 2. If you have no NoGantt project or NoBoard board, tests 40 and 41 only show your calendars; say so.

| # | Test | What to do | What to report |
|---|---|---|---|
| 35 | Side menu | Open the side menu | Is **NoAgenda** the first item of the Apps section, above Dashboard and the other apps? Does a tap open the NoAgenda screen with the menu button at the top left? |
| 36 | The question | Open NoAgenda for the first time | Does **Which of your calendars are personal?** appear once, with all your calendars ticked? Untick the work calendars and tap **Save**. Does the question stay away when you open NoAgenda again? Is the setting under **Sources**? |
| 37 | Today | Look at **Today** with the **Day** tab | Do your real events and to-dos of today show, in time order? Does each row show the time, the source name and **Self** or an agent's name? Are late and due-today to-dos in their own sections? |
| 38 | Week and Month | Tap **Week**, then **Month** | Is the week a list of seven days, and the month a small grid with a dot under days that have something, and a list below? Do the arrows and the date step by a week or a month? |
| 39 | Filters | Tap the chips **Me**, **My agents**, **My team** one by one, and **Work** / **Personal** | Does each chip hide only its kind of work? Does **Work** hide the personal calendars and **Personal** hide the work ones? |
| 40 | Projects and boards | Open **Sources** | Are your calendars, NoGantt projects and NoBoard boards listed with a setting? Is a project you only look at **Hidden**, and a project or board you run **Do + Oversee**? Does a calendar made by NoBoard say **Hidden automatically**? |
| 41 | My team (only if you run a project or board) | Look at **Today** | Under **My team**: do you see only the problems (late, due today, blocked, nobody assigned, an agent without its person), each with the person and the agent? Does **Show all team tasks** list the others? |
| 42 | Offline | Switch on airplane mode and open NoAgenda again | Does it say **You're offline. Showing what was saved at ...** and still show the last day? |
| 43 | Open in ... | Tap a row, then **Open in NoCalendar** / **NoTasks** / **NoGantt** / **NoBoard** | Does the page of the Drive open inside the app, already signed in? |
| 44 | Dark mode | Switch the phone to dark mode and look at NoAgenda | Is every screen readable, with the Nourax blue as the accent? |

### NoAgenda: what the owner should know

- **Read only.** This step changes nothing on the Drive or on the phone. Editing, the Timeline and the phone's own Calendar and Contacts sync are the next steps.
- **The agent list is a file.** NoAgenda reads `NoAgenda/agent-registry.json` in your own NoDrive (the format of `plans/agent-registry.placeholder.json`). Without it everything shows as **Self**; **Sources** says so. The Nourax agents app will replace the file later.
- **Only your own work.** An agent of another organisation is never named. An invitation to a meeting is always **Self**. Someone else's personal calendar is never read.
- **Background refresh** runs about every 15 minutes, and only for accounts where NoAgenda was opened at least once.
- **NoGantt shows no version** to a normal user, so NoAgenda checks that NoGantt's answers have the expected shape. If a NoGantt update changes the shape, the project rows turn read only with **Open in NoGantt**.

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
| S6 | The names of the Workspace apps in NoDrive's menu come straight from the server's navigation list (`GET /ocs/v2.php/core/navigation/apps`). If an app's name there still says "Nextcloud", NoDrive shows it as is | The app only displays what the server sends | Read the list on the Drive: every entry's name and icon should read "Nourax" or be neutral. NoDrive leaves out `files`, `activity` and `photos` (NoDrive has its own Activity and Photos screens; owner decision of 2026-10-26) |
| S7 | The in-app pages are signed in with the phone's app password from Login Flow v2: one request to the server makes a web session. Revoking the password in Settings > Security > Devices stops new pages from opening signed in, but an already open web session is not ended at once (the CI server proof shows this) | Nextcloud's web sessions live on their own after they start | Expect a short delay after a revoke. Ending a web session on the server does not sign the phone out |

Problem reports (contract E; the Workspace session does these, the app does not):

| # | What | Why |
|---|---|---|
| S8 | Create and reserve the user account **`nourax-support`** | The app shares `/Problem reports` read-only with it (`shareType=0`, `permissions=1`). Until it exists the share fails quietly and the user sees "Saved in your NoDrive > Problem reports." |
| S9 | Allow every user to share with `nourax-support` while "privacy between organisations" is on | Otherwise the share is refused for users who may not see that account |
| S10 | Decide who reads the reports (the account's files or the shares) and how long they are kept | The app never deletes a report on the Drive and never overwrites one |

NoAgenda (design section 11; the Workspace session does these, the app changes no server):

| # | What | Why |
|---|---|---|
| S11 | The **Nourax agents app**: it hosts the real agent registry in the format of `plans/agent-registry.placeholder.json`, answers per user with only that organisation's agents, and has one fixed URL for the apps | Until then NoAgenda reads `NoAgenda/agent-registry.json` in the person's own NoDrive; without it everything shows as Self. The switch is one new `RegistrySource` in `RegistrySources.create` |
| S12 | The **registry sync step**: create and update the agents' planning people in NoGantt (`🤖 <name> (<person>)`, with working pattern and capacity), record `links.nogantt_resource_ids`, repair agents left without their person, create the missing board labels | NoAgenda recognises an agent by these names and links |
| S13 | The **Workspace AI tools**: person first, then agent, on every assignment tool (also project creation, plan import and template copy); refuse an agent without its person or one that belongs to another person; `agents_list`; later `agenda_get` and `agenda_conflicts` | Keeps "agent with no person" and "belongs to ..." from happening |
| S14 | Optional: make NoGantt's calendar copy two-way; show a NoGantt version (a capability) to normal users | NoGantt 2.4.1 shows no version, so the app only checks the shape of the answers (contract guard) |
| S15 | Before updating NoGantt or Deck on the Drive, run NoAgenda's API checks (the `agenda-server` CI job) against the new version | They are the only guard against a changed API |
| S16 | NoGantt viewers cannot change their own tasks; NoGantt's and Deck's default colour is the server's blue; Deck's welcome board names the server software; keep the calendar trash bin on | Found while testing |
| S17 | A daily repeating event is about 400 rows in the 14-month window (the server expands it) | Only a size note |

## 6. To-dos for later

Nothing here blocks the owner's backup. The independent review of the Backup change
(`plans/backup-settings-review-1.md`) found 7 should-fix items, S1 to S7. All 7 are **fixed** on
`main` (re-check `plans/backup-settings-fixes-review-1.md`, approved, head `5b09df7b08`):

| # | Was | Now |
|---|---|---|
| S1 | The folders were saved only at the end | Fixed: each created row is saved as owned right after it is made; unit test for a failure on the second step |
| S2 | A running upload was not stopped at switch-off | Fixed: switching off cancels the folder's upload job, then disables the row (the file being sent finishes) |
| S3 | The switch took over the user's own disabled rows | Fixed (decision: do not take over): such rows are never changed and are listed on the result screen |
| S4 | Auto-add re-read the phone's folders | Fixed: it reuses the folders it was given |
| S5 | No battery-optimization offer | Fixed: note and "Allow background activity" button on the result screen |
| S6 | Arabic and other scripts were dropped from the phone name | Fixed: only illegal path characters are replaced |
| S7 | Missing tests (on, off, on; Documents switch; user rows) | Fixed: device tests for each, and an exact server file count |
| S8 | NoMail missing from the Workspace menu (owner report) | Fixed: entries on another https address open in a Custom Tab, never with a NoDrive credential |

Still open (all optional):

- **R1 (should-fix):** make the device test show the CANCELLED state of the upload work after
  switch-off, for example with "charging only" plus `adb shell dumpsys battery unplug`, or a JVM test
  with a mocked store (`BackupRowsIT.switchingOffStopsTheUploadWorkOfTheRowsItOwns`).
- **N1:** after a failed or killed switch-on the rows made so far are on while the switch shows off;
  stop them on failure, or show the switch on when any owned row is enabled.
- **N2:** disable the row first, then cancel the work (closes a millisecond window in the synced-folder store).
- **N3 and N4:** scroll the menu to NoMail before the screenshot; assert the Custom Tab extra and that
  only `accept-language` is sent.
- **N5:** merge the two KDoc blocks on `forget` in `BackupState.kt`.
- **N7:** keep the upstream clock test `UploadDateTests#getRelativeDateTimeStringReturnsRelativeStringForHoursAgo`
  by running it with a fixed clock.
- From the first review: the Open-settings dialog only for a permanent denial (N2), database reads on
  the main thread on resume (N5), an old sign-in never gets the offer and the offer is lost when the
  screen is recreated (N11), two phones with the same name share one backup folder (N13).
- **Translations:** the new texts are English only (`app/src/main/res/values/nodrive_backup.xml`;
  the fixes added `nodrive_backup_battery_note`, `nodrive_backup_battery_button` and
  `nodrive_backup_result_left_alone`, and changed the docs summary). German, Arabic and the other
  languages are still to do.
- On the first real Takeout export, check the zip layout (`Takeout/Drive/...` and
  `Takeout/Google Photos/...`) and that the NoDrive desktop app uploads it with the same folder
  structure (design risk R9). Later name those two folders in the guide (review N12).

### To-dos for Smart conflicts

From the independent review (round 3, approved). None of them blocks the owner:

- **N1 (known limit):** a backup run that was already going when the choice moved away from Keep the phone
  copy still replaces the remaining clashes of that run. Optional fix: when the choice leaves Keep the phone copy,
  cancel and restart the owned rows' unique Auto upload work (`JOB_IMMEDIATE_FILES_SYNC_<rowId>`), as
  `BackupControllers.cancelAutoUpload` does; `healBeforeRun` then repairs any write the old run still makes.
- **N3 (tests):** in the two stale-write emulator cases add `assertNull(support.uploadOf(...))` before `startRun`
  so they prove the clash was still unhandled; add a unit test for `healAll` pruning the kept values of rows that are gone.
- **N4 (style):** in `tools/rebrand.py` move the `CONFLICTS_RUN_*` block below `CONFLICTS_START_BLOCK`.
- **R3 (proposal):** compare numbered copies too (after a reinstall) so that no `(3)` copy appears. It needs the same
  rule on iOS, so it is only listed.
- **Translations:** the new texts are English only (`app/src/main/res/values/nodrive_conflicts.xml`). German,
  Arabic and the other languages are still to do.

### To-dos for Problem reports

From the independent reviews (the final one approved). None of them blocks the owner:

- **N1 (limit):** the quoted-secret pass uses one backtrack frame per escape pair: on Android it stops quietly from about 77,000
  escape pairs in a row inside one quoted value (about 154 KB of escapes, not a realistic log). Optional: cut each log line to about
  8 KB before the quoted passes, or name the limit in `docs/problem-reports.md`.
- **N2 (test):** `ReportsScrubberSpeedIT` allows 20 s but takes under 4 s; lower the limit to about 8 s and print the time into the
  instrumentation result so the CI log shows it.
- **N3 (process):** the 6 commits of this branch carry `Co-Authored-By: Claude Sonnet 5.5`, not `Claude Opus 5.5`. They are not
  rewritten (the branch is shared history); later commits use the run rule's trailer.
- **N4 (scrubber):** a raw `"` inside a URL password leaves the part before it (`https://alice:pa"<email>/...`). A raw quote is not legal
  in URL user info and OkHttp writes `%22`, which is removed fully. Optional: exclude `"` only from the user part of `urlUserInfo`.
- **Not tested:** the release-only crash screen (`ShowErrorActivity`, CI builds debug only).
- **Screens in the sweep:** 78 here against 81 in an earlier run (three `settings-5` pages were not captured; the sweep's own
  scrolling, no code change). The last Settings page is still captured.
- **Upstream log leak at sign-in:** `AuthenticatorActivity` writes the Login Flow v2 poll response (with the new app password) into
  the private log. Reports remove it; closing it at the source needs a small marked hook (see `docs/problem-reports.md`).
- **Translations:** the new texts are English only (`app/src/main/res/values/nodrive_reports.xml`). German, Arabic and the other
  languages are still to do.
- **Owner:** C: on the build PC has about 16 GB free, below the 40 GB the run rules ask for.

### To-dos for NoAgenda core

From the independent reviews (review 2 approved, `plans/round3-android-agenda-core-review-2.md`). None of them blocks the owner:

- **N11 (rare):** a linked card with more people than its Gantt task shows one of them twice (the safe side of "one row, never two").
  Optional: drop the covered people from the card row's names (`DeckMapper.kt`).
- **N12 (narrow):** removing the account while a refresh is running can write that account's rows back (a private copy that stays
  until the app data is cleared). Optional: let `AgendaAccounts.forget` take the repository's mutex, or mark the repository as forgotten.
- **Owner question (rule 4.5 b, review 1 N1):** should an agent found by name whose person is not on the task raise "belongs to ..."
  or "agent with no person" (as built), or "unknown agent" (as the design text says)? And may a planning person's own `name` count,
  besides `uid_display_name`? Say so after the phone test; it is a small change.
- **Next steps:** editing and the offline queue (feature/noagenda-edit), the Timeline (feature/noagenda-timeline), the phone's own
  Calendar and Contacts sync (feature/device-calendar-sync).
- **Sync tokens:** a calendar is read again when its sync token changes; `sync-collection` / `calendar-multiget` are not used yet.
- **Open in NoCalendar** opens the Calendar app on the event's day; the address of a single event was not verified against the live app.
- **Translations:** the new texts are English only (`app/src/main/res/values/nodrive_agenda.xml`). German, Arabic and the other
  languages are still to do.
- **Owner:** C: on the build PC is below the 40 GB the run rules ask for (see the Problem reports to-dos).
