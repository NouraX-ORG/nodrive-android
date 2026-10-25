<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android: the no-Nextcloud sweep

The rule: no screen of NoDrive may show Nextcloud's name, logo, colours, links or assets, in any
language. That covers the app name, every screen, notifications, widgets, share texts, the account
name in system settings, and the store text. Technical identifiers stay (API paths, WebDAV
namespaces, class, package and library names, the user-agent token the server needs).
Credits to Nextcloud live only in this repository.

Final state: `main` at `7c0c2df5cc` (2026-10-25), checked against the base tag `stable-35.0.1`.

## Result: PASS

| Gate condition | Result | Evidence |
|---|---|---|
| Zero unclassified user-visible hits | **Yes, 0** | Every resource value, layout, XML file, manifest, store text, code literal, and the APK's resources, binary XML, DEX strings and text files. Each hit that is left is classified below |
| Every screenshot clean | **Yes, 72 of 72** | Looked at all 72 (en light 21, en dark 10, de light 15, ar light 16, ar dark 10). Their UI dumps hold 1,025 visible texts; 0 name Nextcloud |
| `tools/check-branding.py` green in CI on `main` | **Yes** | [Run 40188742857](https://github.com/NouraX-ORG/nodrive-android/actions/runs/40188742857), job "Branding gate": "882 hits: internal 254, legal 628, unclassified 0", "PASS - no unclassified hit, every pin holds" |
| The gate still catches the stock app | **Yes** | Same job, `--self-test` on upstream's stock `genericDebug`: 1315 hits, 528 unclassified, every pin fails, as it must |
| No call to a Nextcloud GmbH service | **Yes** | CI network capture: "PASS - no Nextcloud GmbH host". No push, update, crash, news or app-store host in the code or resources |

## How the sweep was done

1. **In every CI run (the gate):**
   - `tools/check-branding.py --source` sweeps what ships in `nodriveDebug` (see
     [branding.md](branding.md), section 8);
   - `tools/check-apk.py` checks the built APK;
   - `tools/ci/sweep.py` checks every screen's text and colours;
   - `tools/check-network.py` checks the network capture.
2. **Three independent read-only sweeps** (2026-10-25). They used their own scripts, not the CI tools:
   - they parsed the APK's `resources.arsc`, binary XML and DEX string tables;
   - they rendered every vector drawable and looked at every image and screenshot;
   - they parsed the network capture themselves.

| Round | Commit | Result | What was found, and the fix |
|---|---|---|---|
| 1 | `main` `60f111bb08`, branch `794339a782` | FAIL on `main` only | `main` still shipped stock Nextcloud (the branch was not merged yet). Fixed by merging after green CI; the gate got "pins" so these leftovers can never come back |
| 2 | `main` `76abf42bec` | FAIL, 1 hit (U1) | The file list's "Open in Notes" card opened Nextcloud's Notes app or its store page. The store id sits in a library, so no keyword found it. Fixed in `ddc7ad363d` with a `tools/rebrand.py` hook, a new pin and the test `NoDriveOpenInNotesIT` |
| 3 | `main` `7c0c2df5cc` | **PASS** | Nothing user-visible. Four advisories (below) |

**Keywords searched** (case-insensitive):

- names: `next\s*-?\s*cloud`, `own\s*-?\s*cloud`, `mirall`;
- store ids: `com.nextcloud`, `it.niedermann`;
- colours: `0082c9`, `00679e`;
- misspellings such as Nekstklaud, Nexcloud and Next bulut;
- transliterations in Arabic, Persian, Uyghur, Cyrillic, Greek, Hebrew, Georgian, Armenian,
  Japanese, Korean, Thai and Indic scripts.

The spec's links (`nextcloud.com`, `help.nextcloud`, `docs.nextcloud`, `apps.nextcloud`,
`github.com/nextcloud`, `push-notifications.nextcloud`, `updates.nextcloud`) are covered by the name
pattern. A second pass looked for the word "cloud" in 20 languages and scripts, and found only
generic words such as "Federated Cloud ID".

## Hits before and after

**Scope:** everything that ships in `nodriveDebug`:

- the resources of `main`, `nodrive` and `debug`, merged per values folder and key;
- the three manifests and `app/build.gradle.kts`;
- the flavour's store text;
- Java and Kotlin literals in `main`, `debug` and `generic/java` (which nodrive reuses).

The "before" column uses the same scope for `genericDebug` at `stable-35.0.1`.

| Measure | Before: `stable-35.0.1` (generic) | After: `main` `7c0c2df5cc` (nodrive) |
|---|---|---|
| `tools/check-branding.py` (CI gate) | 1315 hits, **528 unclassified** | 882 hits: internal 254, legal 628, **unclassified 0** |
| Merged resource values naming a keyword | **396**: 362 strings in 52 keys, `primary #0082c9`, 33 style entries | **37**: 4 internal strings (`db_file`, `db_name`, `nextcloud_user_agent`, `office_user_agent`) and 33 style names (internal) |
| ...of these, a transliteration or misspelling only | 13 (for example ar `نكست كلاود`, fa `نکست‌کلود`, sr `Некстклауд`, sl `Nexcloud`) | 0 |
| Resource files with a keyword line (layouts, menus, `xml/`, drawables) | 83 | 77, all class names, tool attributes, style or drawable references, and 2 file names |
| Manifest lines (outside comments) | 50 | 53 (class names and the Deck `<queries>`) |
| `app/build.gradle.kts` (outside comments) | 8 | 8 (namespace, test runner class, the upstream flavours' ids) |
| Store text in the flavour's fastlane | 216 hits in 56 of 92 files | 0 hits in 8 files; the only image is the Nourax icon |
| Java and Kotlin literals | 66 | 66, all internal (rows 13 to 24 below) |
| **Effective total** | **819** | **241**, all internal or legal |
| **User-visible** | 362 strings; the Nextcloud launcher, logo, background and gradient art; `#0082C9` in 6 places; 56 store files | **0** |

The APK itself (artifact of run 40188742857):

| Part of the APK | Hits | Class |
|---|---|---|
| `resources.arsc`: 103,331 entries, 76,211 strings | 6 values: the 4 internal strings and 2 file paths (`nextcloud_logo.xml`, `nextcloud_splash_logo.xml`); 35 resource names | internal |
| Binary XML (1,465 files) | 116: class and package names, the 3 Deck `<queries>`, 2 file names | internal |
| DEX (23 files, 551,007 strings) | 10,155 unique: class descriptors, signatures, debug data, and 346 readable strings (Compose source keys, Kotlin and Room messages, log and exception texts, each traced to its method) | internal |
| Other files (1,529) and native libraries | `ical4j.properties` names a class; `libcms_verifier.so` has a JNI symbol | internal |

## Images

| Set | Count | Finding |
|---|---|---|
| Vector drawables in the APK, all libraries | 459, all rendered and looked at | No Nextcloud, Talk, Deck or Notes logo. `ic_talk` is a chat bubble, `ic_deck` three bars, `ic_notes` a pencil, `notification_icon` a white folder |
| Raster images in the APK | 159, all looked at | Only the Nourax logo, plus neutral images (emoji, Material and AppCompat 9-patches, map icons, store badges on a hidden screen). The logo tile equals the brand logo placed unchanged on white (largest channel difference: 1). `background_nc18.png` is one colour, `#2563EB` |
| Colours in the APK (321 distinct) | compared with `#0082C9`, `#00679E`, `#006AA3`, `#1CAFFF` | No match. Nearest: `#2268AB` (advisory A1) and `#17597D` (re-tinted with the theme colour at runtime) |
| Store icon, adaptive icon | 1 each | The Nourax logo on white. No monochrome layer (owner to-do) |

## Screenshots

72 screens from the green `main` run, all looked at (the committed set in
[screenshots/android/](screenshots/android/README.md) comes from run 40169964556, with the same screens).

| Screens | Result |
|---|---|
| Launch and splash, 5 looks | The Nourax tile on `#2563EB` |
| Browser at drive.nourax.org, 5 looks | The Cloudflare "Sorry, you have been blocked" page. CI may not open the Drive login pages; the login page itself is an owner test |
| "Please complete login process in your browser", 5 looks | Clean in every language |
| App info, add account, notification settings and channels, share sheet, launcher, widget picker, widget setup | "NoDrive" and the Nourax icon everywhere |
| System accounts (en, ar) | `demo@drive.nourax.org` with the label NoDrive |
| Files, drawer, both Settings pages, accounts, uploads, auto upload and its dialog, upload-folder chooser, 5 looks | Clean. "Search in NoDrive". About shows only "License: GNU General Public License, version 2" and "NoDrive Android app, version 35.0.1-nourax.1" |

## No calls to Nextcloud GmbH

| Check | Result |
|---|---|
| `setup.xml` and `bools.xml` | Push URL, updater, what's new, recommend, help, community, issue tracker, source code and the Nominatim contact are all off or empty |
| SDKs in the APK | No Firebase, Google Play services, Play Core, Sentry, ACRA, Bugsnag, Huawei or App Center |
| URL hosts in DEX and resources (80) | Nextcloud appears only as WebDAV namespaces, Compose-preview fixtures and the `@nextcloud.com` suffix of calendar-backup event ids. No network host |
| CI network capture (233,255 packets) | 40 DNS names, 36 TLS names, 1 HTTP host: Google/Android, Cloudflare, `drive.nourax.org` and the test fixture `nextcloud.localhost`. **No Nextcloud GmbH host** |
| Limit | CI cannot sign in, so traffic after sign-in is checked through code and settings only. It stays an owner test |

## Hits kept on purpose

| # | Pattern | Where it ships | Class | Reason |
|---|---|---|---|---|
| 1 | `db_file` = `nextcloud.db`, `db_name` = `nextcloud` | main `setup.xml` | internal | App-private database name; Room schemas and migrations depend on it. Never shown |
| 2 | `Nextcloud-android/` in `nextcloud_user_agent` and `office_user_agent` | `setup.xml`, DEX | internal | Server 35's `USER_AGENT_CLIENT_ANDROID` needs it. NoDrive appends " NoDrive" |
| 3 | `com.nextcloud.*`, `com.owncloud.*`, `com.nmc.*`, `org.nextcloud.*` class names | manifest, widget config, 36 layout tags, `layout_behavior`, data-binding types | internal | Class and package names |
| 4 | `com.nextcloud.test.InjectionTestActivity`, `TestActivity` | `app/src/debug` manifest | internal | Debug test activities; not exported, no launcher entry |
| 5 | `Theme.ownCloud.*`, `Nextcloud.Material.TextButton` and other style names | values, layouts, manifest | internal | Style resource names. Never shown |
| 6 | `nextcloud_logo`, `nextcloud_splash_logo`, `background_nc18`, `placeholder_search_in_nextcloud`, `nextcloud_user_agent` and similar | resource and file names | internal | `R` identifiers that upstream code loads. The content is NoDrive's |
| 7 | `namespace = "com.owncloud.android"`, `com.nextcloud.client` and the other upstream flavour ids, `com.nextcloud.client.TestRunner` | `app/build.gradle.kts` | internal | `R` package and test runner. The nodrive flavour uses `org.nourax.nodrive`; upstream flavours are not built |
| 8 | `com.owncloud.android.workaround.accounts.CREATE`, `com.nextcloud.client.(upload\|download\|folderDownload).` broadcasts, `vnd.owncloud.file` MIME, `nextcloud_shortcut_` | manifest, code | internal | Intent, broadcast, MIME and shortcut ids. Never shown |
| 9 | `it.niedermann.nextcloud.deck(.play\|.dev)` `<queries>`, `DeckApiImpl` | main manifest, code | internal | Lets Deck notifications open in Deck if it is installed. No store link |
| 10 | `com.nextcloud.talk2` in `ProfileBottomSheetDialog.openTalk` | code | internal | Opens Talk only if installed, otherwise the server's own page. No store link |
| 11 | `com.nextcloud.client`, `.android.beta`, `.android.qa`, `com.nextcloud.talk2`, `it.niedermann.owncloud.notes` in `EcosystemApp` | library DEX | internal | Used only by the drawer banner and the Notes card, both hidden |
| 12 | `market://details?id=`, `https://play.google.com/store/...` | `EcosystemManager`, `LinkHelper` | internal | Reachable only for `org.nourax.nodrive` itself; the Nextcloud paths are hidden |
| 13 | `http://owncloud.org/ns`, `http://nextcloud.org/ns`, `http://nextcloud.com/ns` | library DEX | internal | WebDAV namespaces, not network hosts |
| 14 | `@nextcloud.com` | `ProcessVEvent`, `SaveCalendar` | internal | Event-id suffix inside calendar-backup data. Not shown, not a network call |
| 15 | Log-only texts such as "Synchronization of ownCloud account" (14) | code, DEX | internal | Every use goes to the log |
| 16 | "No valid Nextcloud version was found at the server", "The Nextcloud server is not configured!" (`getLogMessage()`) | library | internal | Log only. Screens use `getLogMessage(Context)`, which reads the rebranded strings |
| 17 | SSO keys and messages (`NextcloudSSO`, "...added by the Nextcloud SSO Library...") | `com.nextcloud.android.sso` | internal | Protocol keys and replies to calling apps. Not on a NoDrive screen |
| 18 | Developer exceptions ("Instanced with a NULL ownCloud Account" and others) | code, library | internal | Programming-error texts, only inside a stack trace |
| 19 | "Temporary workaround: Legacy OwnCloudAccount access..." | `@Deprecated` messages | internal | Compiler annotation text |
| 20 | Compose source keys, Kotlin cast messages, Room schema messages, lambda names, debug data, signatures | DEX | internal | Compiler and runtime metadata naming classes |
| 21 | Stack-trace frames `com.nextcloud...` on the crash screen | `ShowErrorActivity` | internal | Class names. "Report issue" is hidden |
| 22 | "Nextcloud accounts information", "Nextcloud preferences" | engineering test mode | internal (unreachable) | Needs `is_beta`, which is false |
| 23 | "Nextcloud Android Files Logs" | `LogsEmailSender` | internal (unreachable) | The Logs row is off (`logger_enabled=false`) |
| 24 | `openAppStore("Nextcloud", true)`, the "More apps" banner | `DrawerActivity` | internal (unreachable) | The banner is hidden by `is_branded_client` |
| 25 | `https://nextcloud.com`, `http://nextcloud.local`, `partner@nextcloud.org` | Compose previews, `MockShareRepository` | internal (not run) | Preview fixtures (advisory A4) |
| 26 | `tools:context="com.nextcloud..."`, `tools:text=...` | layouts | internal | Design-time attributes, removed from the APK |
| 27 | `ical4j.properties` class name, JNI symbol, AIDL package `com.nextcloud.android.sso.aidl` | APK files | internal | Class, symbol and package names |
| 28 | `nextcloud.localhost` | network capture | internal | Upstream test-fixture account of the test APK |
| 29 | Developer comments in the nodrive XML | XML comments | internal | aapt2 drops comments |
| 30 | SPDX and copyright headers (also kept in the generated files) | comments, source headers | legal | Upstream copyright headers stay untouched |
| 31 | `LICENSE.txt`, `LICENSES/`, `NOTICE.md`, README and docs credits | repository | legal | Credits live only in the repository |
| 32 | `src/generic/fastlane`, root `fastlane/`, `app/src/main/ic_launcher-web*.png`, upstream flavours' resources | repository | not shipped | Upstream's store files and art; nodrive has its own (advisory A2) |
| 33 | `app/src/androidTest*` fixtures, `.github/upstream-workflows/` | repository | not shipped | Test APK only, and reference only |

## Advisories (not gate failures)

| # | Item | Why it is not a failure | Suggestion |
|---|---|---|---|
| A1 | The sync badge `ic_synchronizing` uses upstream's `#2268AB`. It shows on a file while it syncs | Not one of Nextcloud's colours and not a keyword; a small status dot | Recolour it to `#2563EB` with a `tools/rebrand.py` override |
| A2 | Repository-only Nextcloud art: `app/src/main/ic_launcher-web*.png`, the root `fastlane/` (`Appfile` = `com.nextcloud.client`) and `src/generic/fastlane` | Nothing of it is in the APK or in the nodrive store folder | Add a NoDrive fastlane lane before any store work |
| A3 | Screens without a screenshot, because they need a signed-in server: share dialog, file details, notifications, activities, media, assistant, passcode, crash screen, SSO grant dialog, a configured widget, the sync badge, the profile "Talk" action | Their code and resources are clean | Owner test at home |
| A4 | The debug APK exports `androidx.compose.ui.tooling.PreviewActivity`. Its preview fixtures say `https://nextcloud.com` | Reachable only by an explicit intent from adb or another app; not in a release build | Check again when a release build type is set up |

## Server side (not this app)

The browser sign-in pages and the web settings belong to the server. They are listed for the
Workspace session in [testing.md](testing.md), section "Server-side items".
