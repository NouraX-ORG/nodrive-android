<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android: branding

This page lists every id, colour and icon of NoDrive, and how the three branding tools work.

- All branding lives in the product flavour `nodrive`. Upstream's flavours (`generic`, `gplay`,
  `huawei`, `versionDev`, `qa`) are not edited.
- Every file of the flavour is written by `tools/rebrand.py` (text) or `tools/make-icons.py`
  (images). Nothing is edited by hand. After an upstream merge, run both again
  ([upstream-updates.md](upstream-updates.md)).
- `tools/check-branding.py` checks on every CI run that no Nextcloud name, logo, colour or link comes
  back.

## 1. Name and version

| Item | Value | Where it is set |
|---|---|---|
| App name | **NoDrive** (all languages) | `app_name` in `app/src/nodrive/res/values/setup.xml` |
| Maker | **Nourax** | store text, docs |
| versionName | `35.0.1-nourax.1`, the pattern is `<upstream version>-nourax.<n>` | Gradle flavour block |
| versionCode | `350000190`, from `major*10000000 + minor*10000 + patch*100 + 90 + (n - 1)` | Gradle flavour block |
| `n` (the NoDrive revision) | `1`. Allowed: 1 to 10 | `NOURAX_REVISION` in `tools/rebrand.py` |

The versionCode always rises with the upstream version (35.0.2 gives `350000290`, 35.1.0 gives
`350010090`), and leaves room for 10 NoDrive revisions per upstream patch.

## 2. Every id

NoDrive's ids differ from the real Nextcloud app's, so both install and run side by side.
`tools/check-apk.py` checks the built APK for these on every CI run.

| What | Nextcloud (upstream `generic`) | NoDrive |
|---|---|---|
| Package (applicationId) | `com.nextcloud.client` | **`org.nourax.nodrive`** |
| Test package | `com.nextcloud.client.test` | `org.nourax.nodrive.test` (AGP default) |
| Kotlin/Java namespace, `R` class | `com.owncloud.android` | unchanged (internal) |
| Account type (system settings > Accounts) | `nextcloud` | **`org.nourax.nodrive`**, label "NoDrive" |
| Files content provider and sync-adapter authority | `org.nextcloud` | `org.nourax.nodrive.provider` |
| Documents provider (Android Files app) | `org.nextcloud.documents` | `org.nourax.nodrive.documents` |
| FileProvider (sharing files) | `org.nextcloud.files` | `org.nourax.nodrive.files` |
| Image cache provider | `org.nextcloud.imageCache.provider` | `org.nourax.nodrive.imageCache.provider` |
| Users-and-groups search provider | `com.nextcloud.android.providers.UsersAndGroupsSearchProvider` | `org.nourax.nodrive.providers.UsersAndGroupsSearchProvider` |
| Search "share with" action | `com.nextcloud.android.providers.UsersAndGroupsSearchProvider.action.SHARE_WITH` | `org.nourax.nodrive.providers.UsersAndGroupsSearchProvider.action.SHARE_WITH` |
| AndroidX startup provider | `com.nextcloud.client.androidx-startup` | `org.nourax.nodrive.androidx-startup` (from the package) |
| Image cropper FileProvider (library) | `com.nextcloud.client.cropper.fileprovider` | `org.nourax.nodrive.cropper.fileprovider` (from the package) |
| Declared permission (library) | `com.nextcloud.client.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `org.nourax.nodrive.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (from the package) |
| Login URL scheme (Auth Tab redirect) | `nc://login/...` | `nodrive://login/...` |
| Login deep-link activity (`DeepLinkLoginActivity`) | exported, accepts any server | removed from the manifest |
| Web links that open the app | `http` and `https`, any host, `/f/...` and `/app/...`, `autoVerify` | `https://drive.nourax.org` only, same paths, no `autoVerify` (no `assetlinks.json` yet) |
| Ecosystem intent action `com.nextcloud.intent.OPEN_ECOSYSTEM_APP` | yes | removed |
| `REQUEST_INSTALL_PACKAGES` permission | yes | removed (no self-updater) |
| Local data folder | `nextcloud` | `nodrive` (and `exposed_filepaths.xml` to match) |
| Root folder title | `Nextcloud` | `NoDrive` |
| Files-app root, share-sheet target, widget, crash title | app name | "NoDrive" |
| Text-selection action | "Nextcloud Assistant" | "NoDrive Assistant" |
| Database file | `nextcloud.db` | unchanged (app-private; Room schemas and migrations depend on it) |
| Notification channel ids, pinned-shortcut ids, broadcast actions | upstream names | unchanged (scoped to the package; names on screen are neutral) |
| Store id (later) | `com.nextcloud.client` | `org.nourax.nodrive` |

## 3. Server, sign-in and what is switched off

All in `app/src/nodrive/res/values/setup.xml` and `bools.xml`.

| Setting | NoDrive value | Effect |
|---|---|---|
| `webview_login_url` | `https://drive.nourax.org/index.php/login/v2` | Sign-in with Login Flow v2 in the browser. No server-address screen |
| `show_server_url_input`, `show_provider_or_own_installation` | `false` | No URL field, no intro, no "host your own server" |
| `disable_intro` | `true` | No intro slides |
| Code hook in `AuthenticatorActivity` | on, because `webview_login_url` is set | Refuses a server on any other host (login links, poll results) |
| `is_beta`, `dev_version_direct_download_enabled`, `dev_link`, `dev_latest`, `dev_changelog` | off, empty | No built-in updater |
| `push_server_url`, `show_push_warning` | empty, `false` | No push and no warning about it. Nextcloud's push proxy serves only Nextcloud's apps |
| `show_whats_new` | `false` | No "what's new" |
| `recommend_enabled`, `url_app_download`, `fdroid_link` | off, empty | No "recommend to a friend" |
| `is_branded_client` | `true` | No "More apps" banner, no ecosystem-apps setting, no "Open in Notes" card (code hook in `OCFileListAdapter`), drawer uses our logo |
| `participate_enabled` and the beta, GitHub, Transifex and forum links | off, empty | No community screen |
| `help_enabled`, `url_help` | `false`, `https://drive.nourax.org` | No help row |
| `report_issue_link`, `report_issue_empty_link` | empty | Code hook in `ShowErrorActivity`: no "report issue" on the crash screen. Sharing the error text still works |
| `privacy_url` | `@string/nourax_privacy_url` (Gradle property or env `NOURAX_PRIVACY_URL`, empty by default) | The Privacy row is hidden while empty |
| `sourcecode_enabled` | `false` | No source-code row |
| `license_enabled` | `true` | The one licence row stays (see NOTICE.md) |
| `osm_geocoder_contact` | empty | No Nextcloud contact address sent to OpenStreetMap Nominatim |
| `show_drawer_logout` | `true` | "Sign out" in the drawer |

## 4. Colours

From `nourax/docs/THEME_BIBLE.md`. After sign-in, most screens take the server's theme colour (the
Nourax server's colour); these are the app's own colours.

| Resource | Upstream | NoDrive |
|---|---|---|
| `primary` | `#0082c9` | `#2563EB` |
| `primary_dark` (status bar) | `#006AA3` | `#2563EB` |
| `color_accent` (buttons, headers) | `#007cc2` | `#2563EB` |
| `login_text_hint_color` | `#7fC0E3` | `#B3FFFFFF` (upstream's neutral white) |
| `bg_default` light | `#FFFFFF` | `#F8FAFC` |
| `bg_default` dark | `#121212` | `#0B1220` |
| `appbar` dark | `#1E1E1E` | `#0F172A` |
| `file_zip`, `folder`, `ic_checkbox_marked`, `ic_list_empty_create_folder` drawables | `#0082c9` | `#2563EB` |
| `background_nc18.png` (profile header) | Nextcloud blue gradient | solid `#2563EB` |

The orange accent (`#F97316` / `#EA580C`) is not used. The theme bible keeps it for rare hero use.

## 5. Icons and images

The only source is `branding/nourax-logo.png`, the Nourax logo (500×500 RGBA). Its SHA-256
`74a8b3fdcb8b16290698f3afb0bdbb46d6a9d89e6987f5cd0d26ad45f9ebfa8a` is pinned in
`tools/make-icons.py`, so an edited logo is refused.

Rules: the whole logo image is only scaled, in proportion. It is never redrawn, recoloured, cropped
or stretched. It is enlarged above 500 px only on a plain white square (approved by the owner). No
monochrome variant is made.

### Images made by `tools/make-icons.py`

| File (under `app/src/nodrive/res/` unless noted) | Size | What it is |
|---|---|---|
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 48, 72, 96, 144, 192 px | Legacy launcher icon: the logo on a white disc, transparent corners (lint's round-icon check) |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/nodrive_ic_launcher_foreground.png` | 108, 162, 216, 324, 432 px | Adaptive-icon foreground: the logo inside the 66 dp safe circle, transparent around it |
| `drawable-nodpi/nodrive_logo_tile.png` | 640 px | Logo tile: the logo at its own size on a white square (splash, sign-in screen, drawer) |
| `drawable-xxxhdpi/background_nc18.png` | 984×656 | Solid `#2563EB` (replaces Nextcloud's gradient) |
| `drawable-xxxhdpi/background.png` | 984×656 | Fully transparent (replaces Nextcloud's dotted world map) |
| `src/nodrive/fastlane/metadata/en-US/images/icon.png` | 512 px | Store icon: the logo on white |

### XML written by `tools/rebrand.py`

| File | Content |
|---|---|
| `mipmap-anydpi/ic_launcher.xml` | Adaptive icon: white background and the logo foreground. **No `<monochrome>` layer**, so no themed icon (owner to-do). `tools:ignore="MonochromeLauncherIcon"` because upstream treats lint warnings as errors |
| `drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml` | Solid white; a bitmap of `nodrive_ic_launcher_foreground` |
| `drawable/logo.xml`, `nextcloud_logo.xml`, `nextcloud_splash_logo.xml` | The logo tile at 100, 40 and 160 dp. The file names stay because upstream layouts load them |
| `drawable/notification_icon.xml` | Neutral white folder glyph (Android draws status-bar icons as one-colour shapes; a logo there would be a monochrome variant). Owner to-do |
| `drawable/ic_talk.xml`, `ic_deck.xml`, `ic_notes.xml` | Neutral Material glyphs (chat bubble, three bars, pencil) instead of the Talk, Deck and Notes logos |
| `drawable/first_run_files.xml`, `first_run_groupware.xml`, `first_run_talk.xml` | Transparent shapes (the intro is off, and this keeps the art out of the APK) |
| `drawable/file_zip.xml`, `folder.xml`, `ic_checkbox_marked.xml`, `ic_list_empty_create_folder.xml` | Upstream's drawables with Nextcloud blue replaced by `#2563EB` |

## 6. User agent

The server recognises the Android client by its user agent (`USER_AGENT_CLIENT_ANDROID` in server
35's `lib/public/IRequest.php`:
`/^Mozilla\/5\.0 \(Android\) (?:ownCloud|Nextcloud)\-android\/([^ ]*).*$/`). So the
`Nextcloud-android` token stays, and NoDrive appends ` NoDrive`.

| Use | Final string |
|---|---|
| App requests (WebDAV, OCS, sign-in) | `Mozilla/5.0 (Android) Nextcloud-android/35.0.1-nourax.1 NoDrive` |
| Office and Text web views | `Mozilla/5.0 (Android <release>) Mobile Nextcloud-android/35.0.1-nourax.1 NoDrive` |

`NoDriveBrandingIT.userAgentMatchesServerRule` and `tools/check-apk.py` test the first string against
that rule.

## 7. Strings

- `app_name` is "NoDrive". Upstream code reads it in 21 places and resources in 4 (for example the
  system account label), so they all follow.
- `tools/rebrand.py` finds every upstream string, in every language, whose text names Nextcloud or
  ownCloud. That includes transliterations such as Arabic `نكست كلاود` or Serbian `Некстклауд`. It
  writes 494 strings in 60 `values*/strings.xml` files of the flavour:
  - server and organisation wording becomes "Nourax", everything else "NoDrive";
  - the `store_*` keys get the NoDrive store text;
  - the `placeholder_*` keys get neutral examples.
- Library strings that name Nextcloud (`bad_oc_version`, `instance_not_configured`,
  `notification_channel_name`) are rewritten into 36 `nodrive_library_strings.xml` files. The source
  text comes from the snapshot `tools/branding/library-strings.json`, taken from an `aapt2 dump` of
  the APK.
- For every element, the tool checks that the format placeholders (`%1$s`, `%d`, ...) are the same
  before and after.
- Only four values still say Nextcloud, all internal: `db_file`, `db_name`, `nextcloud_user_agent`
  and `office_user_agent`.

## 8. How the tools work

All tools are Python 3 and change only files inside this repository. Only `make-icons.py` needs a
package: Pillow (CI pins `Pillow==12.2.0`).

### `tools/rebrand.py`

```sh
python tools/rebrand.py           # write every generated file and code patch
python tools/rebrand.py --check   # change nothing; exit 1 if a run would change anything
python tools/rebrand.py --import-library-strings <aapt2 dump resources file>   # refresh the snapshot
```

- It reads upstream files (`app/src/main/...`) and its own constants (`APP`, `ORG`, `APP_ID`,
  `SERVER`, `NOURAX_REVISION`). From them it writes:
  - the Gradle block;
  - `app/src/nodrive/res/values/{setup,bools,colors}.xml`, `values-night/colors.xml`;
  - the strings (section 7), the drawable XML (section 5), `xml/exposed_filepaths.xml`;
  - `app/src/nodrive/AndroidManifest.xml` (built from main's `FileDisplayActivity` element);
  - the store text in `src/nodrive/fastlane/metadata/{en-US,de-DE}/`.
- Code in upstream files changes only inside marked blocks:

  ```
  // NoDrive: begin (managed by tools/rebrand.py, do not edit by hand)
  ...
  // NoDrive: end
  ```

  There are four such blocks: one at the end of `app/build.gradle.kts`, and one each in
  `AuthenticatorActivity.checkOcServer()`, `ShowErrorActivity` and
  `OCFileListAdapter.shouldShowOpenInNotes()`. Each code hook goes in front of an anchor line that
  must occur exactly once in the upstream file. If an upstream merge changes or removes that line,
  the tool stops with an error and does not guess. The Gradle block needs upstream's version
  variables and flavour block, and stops the same way without them.
- **Idempotent:** a second run prints `rebrand: nothing to do`. It removes its old blocks before it
  writes new ones. A language file (`strings.xml`, `nodrive_library_strings.xml`) that it no longer
  generates is deleted as stale.
- Generated XML starts with a header naming the tool and the upstream file it came from. It keeps
  the upstream copyright line and adds `SPDX-FileCopyrightText: 2026 Nourax`.
- CI runs `--check` in the "Branding gate" job, so a hand edit, or an upstream change that the tool
  would rewrite, fails the build.

### `tools/make-icons.py`

```sh
python tools/make-icons.py           # write all images
python tools/make-icons.py --check   # exit 1 if any image is missing or differs
```

- It refuses a logo whose SHA-256 differs from the pinned one.
- It scales the whole logo with Lanczos on premultiplied alpha and centres it on the canvas (section
  5).
- `--check` compares pixels (mode, size, RGBA bytes), not PNG bytes, so another zlib build cannot
  make it fail.

### `tools/check-branding.py` and `tools/branding/allowlist.txt`

```sh
python tools/check-branding.py --source [--report build/branding/branding-report.md]
python tools/check-branding.py --self-test
```

- **What it sweeps:** what ends up in the `nodriveDebug` variant after Android's merge (debug >
  nodrive > main):
  - every `res/` file in every language (values, layouts, menus, xml, drawables, images);
  - the manifests (elements that a higher manifest removes or replaces are skipped);
  - the store text and images in `src/nodrive/fastlane/`.
- **Keywords**, case-insensitive: `nextcloud` (also covers every `*.nextcloud.com` link,
  `github.com/nextcloud` and `com.nextcloud`), `next cloud`, `owncloud`, the transliterations,
  `mirall`, `it.niedermann`, and the colours `0082c9` and `00679e`.
- **Images:** vector drawables are checked for the path data of Nextcloud's logos. Raster images are
  checked against SHA-256 fingerprints of upstream's Nextcloud art. Every shipped image is listed, so
  a person can look at it.
- **Allowlist:** every hit becomes a "subject" line. The allowlist (15 entries) classifies it as
  `internal` or `legal`, with a reason, and the first matching entry wins:

  ```
  pattern | <regex searched in the subject> | internal|legal | reason
  path    | <glob on the file path>         | internal|legal | reason
  ```

  Only hits that a user can never see, or that the law requires, may go there.
- **Pins:** checked apart from the allowlist, so no allowlist entry can hide them:

  | Pin | What must hold |
  |---|---|
  | flavour | `nodrive` is declared with `org.nourax.nodrive` and has its own `setup.xml` |
  | app name | `app_name` is "NoDrive" in every values folder |
  | values | only `db_file`, `db_name`, `nextcloud_user_agent` and `office_user_agent` may name Nextcloud |
  | colours | no `#0082c9` or `#00679e`; `primary` is `#2563EB` |
  | art | no Nextcloud logo path data, no upstream Nextcloud image |
  | store listing | every `title.txt` is "NoDrive"; no store text names Nextcloud |
  | open in notes | `is_branded_client` is true and the `OCFileListAdapter` hook is in place |
  | app links | every call that opens another app or a store page is a known one, and the guarded ones stay hidden |
- **Exit code** 1 when there is an unclassified hit or a pin fails. `--self-test` runs the same gate
  on upstream's stock `generic` variant, and passes only if the gate fails there. That proves the
  checker still catches the stock app.
- Repository-only files (README, NOTICE, docs, tools, upstream flavours) are not swept. They do not
  ship in the app, and the credits there are attribution, not UI.

### The other CI tools

| Tool | What it does |
|---|---|
| `tools/check-apk.py` | Reads the built APK with `aapt2` and its own DEX parser. It fails on a wrong package, label, authority, permission, account type, URL scheme, link host, login URL, push URL or user agent, or on a string resource that names Nextcloud. It records every Nextcloud URL in the resources and DEX strings for the sweep |
| `tools/check-network.py` | Reads the emulator's `network.pcap` (DNS names, TLS SNI, HTTP Host). It fails on any Nextcloud GmbH host, or when the capture saw no DNS or missed `drive.nourax.org` |
| `tools/ci/device.sh` | Runs on the emulator: the instrumented tests listed in `tools/ci/instrumented-tests.txt` (with reasons for every test left out), then the screen sweep |
| `tools/ci/sweep.py` | Takes a screenshot and a UI dump of every reachable screen in English, German and Arabic, light and dark. It fails if any screen text names Nextcloud or a screen shows Nextcloud blue |
| `tools/ci/third-party-notices.py` | Writes `THIRD-PARTY-NOTICES.txt` for the APK artifact (see NOTICE.md) |

## 9. Owner to-dos for branding

- **Monochrome artwork, which needs approval:** the themed launcher icon (adaptive `<monochrome>`
  layer) and a NoDrive status-bar notification icon. Until then the launcher has no themed icon and
  notifications use a neutral folder glyph.
- **Privacy-policy URL:** build with `-PNOURAX_PRIVACY_URL=https://...` (or the environment variable)
  to show the Privacy row.
- **Nominatim contact:** a Nourax e-mail for OpenStreetMap place names (`osm_geocoder_contact`).
- **App links:** `assetlinks.json` on drive.nourax.org, then `autoVerify="true"`, after real signing.
