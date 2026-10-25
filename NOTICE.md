<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NOTICE

## Licensed software

NoDrive for Android by Nourax is a modified version of the
[Nextcloud Android app](https://github.com/nextcloud/android), tag `stable-35.0.1`. Thank you to
Nextcloud GmbH and all Nextcloud contributors.

| Part | Licence | Where |
|---|---|---|
| The program as a whole | GNU GPL version 2 | [LICENSE.txt](LICENSE.txt), as upstream |
| Upstream source files | Each file's own SPDX header. In `app/src/main/java` and `app/src/main/res`: 699 files `AGPL-3.0-or-later OR GPL-2.0-only`, 466 `AGPL-3.0-or-later`, 131 `GPL-2.0-only AND (AGPL-3.0-or-later OR GPL-2.0-only)`, and smaller numbers of `Apache-2.0`, `GPL-3.0-or-later`, `BSD-2-Clause`, `GPL-2.0-only` and `MIT` | The headers, and the texts in [LICENSES/](LICENSES/) |
| Third-party code copied into the source (`app/src/main/java/thirdparties/`) | AOSP `SQLiteTokenizer` and Prism4j grammars: Apache-2.0. ez-vcard Android: BSD-2-Clause. Fresco `BetterImageSpan`: MIT. sufficientlysecure calendar import/export: GPL-3.0-or-later | The file headers |
| Libraries packed into the APK (AndroidX and Jetpack, Material Components, Kotlin, Dagger, Media3, Room, WorkManager, Gson, and others) | Mostly Apache-2.0, some MIT, BSD and others | The exact list for each build is `THIRD-PARTY-NOTICES.txt` in the `nodrive-android-apk` artifact (see below) |
| Files added by Nourax (`tools/`, `.github/workflows/android.yml`, `app/src/nodrive/`, `app/src/androidTestNodrive/`, `src/nodrive/`, the docs) | AGPL-3.0-or-later, `SPDX-FileCopyrightText: 2026 Nourax` | The file headers. Generated files derived from an upstream file keep the upstream copyright line too |

Nourax did not relicense anything. `LICENSE.txt`, `LICENSES/`, every upstream copyright header and
upstream's author lists are unchanged.

### What travels with the APK

The CI job "APK and unit tests" puts these files next to the APK in the `nodrive-android-apk`
artifact:

- `LICENSE.txt`, the GPL-2.0 text;
- this `NOTICE.md`;
- `THIRD-PARTY-NOTICES.txt`, written by `tools/ci/third-party-notices.py`. It lists every library of
  the `nodriveDebugRuntimeClasspath` with the licence its Maven POM states. It also copies, word for
  word, every NOTICE file inside those libraries' jars and aars;
- `runtime-dependencies.txt`, Gradle's dependency tree for that configuration.

`THIRD-PARTY-NOTICES.txt` also links to the exact source commit of the build. The artifact is
downloaded from the same public repository page that holds that source.

## Trademarks

- "Nextcloud" and the Nextcloud logo are trademarks of **Nextcloud GmbH**.
- **NoDrive is not made, sponsored or endorsed by Nextcloud GmbH.**
- The NoDrive app does not show Nextcloud's name, logo, colours or links on any screen. The `nodrive`
  product flavour replaces them. The sweep in `tools/check-branding.py` checks this on every CI run,
  and [docs/branding-sweep.md](docs/branding-sweep.md) has the results.
- Upstream's logo files (`LicenseRef-NextcloudTrademarks`) stay in the repository for the upstream
  flavours. They are not packed into NoDrive.
- Names such as `com.nextcloud.*` packages, the `Nextcloud-android` user-agent token and WebDAV
  namespaces stay in the code. They are technical identifiers that the server and the build depend
  on, not branding.
- "Nourax", "NoDrive" and the Nourax logo belong to Nourax.
- Google Play is a trademark of Google LLC.

## What Nourax changed

Everything below is made by `tools/rebrand.py` or `tools/make-icons.py`, so it can be made again after
an upstream merge. Details are in [docs/branding.md](docs/branding.md).

| Change | Where |
|---|---|
| A new product flavour `nodrive` (`org.nourax.nodrive`, version `35.0.1-nourax.1`, no Google services; it reuses the stubs of upstream's `generic` flavour). Upstream's flavours are not edited | One marked block at the end of `app/build.gradle.kts` |
| Name, ids, fixed server `https://drive.nourax.org`, browser sign-in, colours, switched-off features | `app/src/nodrive/res/values/setup.xml`, `bools.xml`, `colors.xml`, `values-night/colors.xml` |
| Every upstream string (all languages) that named Nextcloud, rewritten for NoDrive or Nourax: 494 strings in 60 files. Also 107 library strings in 36 files | `app/src/nodrive/res/values*/strings.xml`, `nodrive_library_strings.xml` |
| Icons made from the Nourax logo, logo wrappers, recoloured drawables, neutral glyphs instead of the Nextcloud, Talk, Deck and Notes logos | `app/src/nodrive/res/drawable*`, `mipmap*` |
| Manifest: no install-packages permission, no login deep link, links only for `https://drive.nourax.org` | `app/src/nodrive/AndroidManifest.xml` |
| Three small code hooks, marked `NoDrive: begin` / `NoDrive: end`. They do nothing in upstream's flavours | `AuthenticatorActivity` (refuses any other server host), `ShowErrorActivity` (no "report issue" without a tracker link), `OCFileListAdapter` (no "Open in Notes" card in a branded client) |
| Store text and icon for NoDrive (en-US, de-DE) | `src/nodrive/fastlane/metadata/` |
| Tests for the branding | `app/src/androidTestNodrive/` |
| Switched off: built-in updater, push, what's new, recommend, community links, help links, source-code link, issue reporting, the "More apps" banner, the "Open in Notes" card | `setup.xml` and the hooks |
| Our CI. Upstream's workflows need Nextcloud's secrets and machines, so they are kept for reference only | `.github/workflows/android.yml`; upstream's in `.github/upstream-workflows/` |
| Our README. Upstream's README moved | `README.md`; upstream's in `docs/upstream-README.md` |

## Legal-notice decision (not legal advice)

This is Nourax's own reading of the licences. **It is not legal advice.** Check it with a lawyer
before any store release.

**What upstream shows today** (Settings > About): Privacy (a link), Source code (a link), License
("GNU General Public License, version 2", opens gnu.org) and the app version. Upstream has no
third-party licences screen.

**What the licences ask for:**

- **GPL-2.0, section 2(c):** a program that prints an announcement when it starts interactively must
  keep printing it. Upstream prints none, so NoDrive does not have to.
- **AGPL-3.0, section 5(d):** if the program has interactive user interfaces, each must show
  Appropriate Legal Notices. But if the original program's interfaces do not show them, the
  modified work does not have to make them show them. Upstream shows only the licence row.
- **Apache-2.0, section 4(d)** (AndroidX, Dagger, Gson, Media3, OkHttp and others): if a library ships
  a NOTICE file, its attribution notices must go into at least one of these: a NOTICE text file
  shipped with the work, the source or documentation, or a display the work generates. An in-app
  screen is one option, not a requirement. NoDrive uses the NOTICE file shipped with the APK.

**Decision: no new in-app page.**

- NoDrive keeps exactly one text-only row, the existing "License" row ("GNU General Public License,
  version 2", opens gnu.org). It names no Nextcloud and has no Nextcloud link.
- The Source-code row is off.
- The Privacy row is hidden until the build setting `NOURAX_PRIVACY_URL` is set (owner to-do).
- The licence and notice texts travel with the APK in the CI artifact (see "What travels with the
  APK").

**Reason:** this keeps the screens free of Nextcloud and matches what upstream itself shows. It
still gives every recipient of the APK the licence, the notices and the source.

**Re-check before any store release** whether the NOTICE texts must also be inside the APK. A store
download does not come with the artifact's extra files.
