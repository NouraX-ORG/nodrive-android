<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NOTICE

## Licensed software

NoDrive for Android by Nourax is a modified version of the
[Nextcloud Android app](https://github.com/nextcloud/android), tag `stable-35.0.1`.
Thank you to Nextcloud GmbH and all Nextcloud contributors.

- The licence of the whole program is in `LICENSE.txt` (GNU GPL version 2) and `LICENSES/`.
- Source files keep their upstream headers (`AGPL-3.0-or-later OR GPL-2.0-only`, or
  `AGPL-3.0-or-later`). Files added by Nourax carry `SPDX-FileCopyrightText: 2026 Nourax`.
- Upstream `LICENSE.txt`, `LICENSES/`, copyright headers and author lists are unchanged.

## Trademarks

"Nextcloud" and the Nextcloud logo are trademarks of Nextcloud GmbH. The NoDrive app does not
show them: the `nodrive` product flavour replaces every logo, name, colour and link (see
`tools/rebrand.py`, `tools/make-icons.py` and the sweep in `tools/check-branding.py`).
Upstream's logo files (`LicenseRef-NextcloudTrademarks`) stay in the repository for the
upstream flavours but are not packaged in NoDrive. "Nourax", "NoDrive" and the Nourax logo
belong to Nourax.

## What Nourax changed

- Added the `nodrive` product flavour (`org.nourax.nodrive`) without Google services.
- New name, icons, colours, ids, store text, fixed server `https://drive.nourax.org`.
- Switched off: built-in updater, push, what's new, recommend, community links, issue reporting,
  and the "Open in Notes" card that pointed to the upstream Notes app and its store page.
- Three small code hooks, marked `NoDrive: begin/end`, in `AuthenticatorActivity`,
  `ShowErrorActivity` and `OCFileListAdapter`.

## Legal-notice decision (not legal advice)

What upstream shows today (Settings > About): Privacy (link), Source code (link), License
("GNU General Public License, version 2", opens gnu.org) and the app version. There is no
third-party licences screen.

- GPL-2.0 section 2(c): an announcement is needed only when the program normally prints one;
  upstream does not.
- AGPL-3.0 section 5(d): if the original program's interface does not show Appropriate Legal
  Notices, the modified one need not either. Upstream shows only the licence row.
- Apache-2.0 section 4(d) (AndroidX, Dagger, Gson, Media3, OkHttp and others): NOTICE text may go
  into a NOTICE file shipped with the work or into the documentation; an in-app screen is not
  required.

Decision: **no new in-app page.** NoDrive keeps exactly the one existing text-only "License" row
(no Nextcloud name, no Nextcloud link). The Source-code row is off. The Privacy row is hidden until
the build setting `NOURAX_PRIVACY_URL` is set (owner to-do). Third-party NOTICE texts go into the
repository and next to the APK in the CI artifact. Re-check before any store release whether they
must also ship inside the APK.
