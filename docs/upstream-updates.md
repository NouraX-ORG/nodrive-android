<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# NoDrive for Android: updating from upstream

How to move NoDrive to a new Nextcloud Android release. Always use a **stable tag**
(`stable-X.Y.Z`), never upstream's `master`.

## Before you start

| Item | Value |
|---|---|
| Our repository | `origin` = `https://github.com/NouraX-ORG/nodrive-android.git` |
| Upstream | `upstream` = `https://github.com/nextcloud/android.git` (fetch only, never push) |
| Current base | `stable-35.0.1` |
| Work branch | `platform/android`. `main` moves only by fast-forward after green CI |

You need Python 3 with Pillow, `git` and `gh`. No local Gradle build is needed: CI builds and tests.

## Steps

1. **Get the new tag.**

   ```sh
   git fetch upstream --tags
   git tag -l 'stable-*' --sort=-v:refname | head -n 5     # e.g. stable-35.0.2
   ```

2. **See what upstream changed in the files we care about** (old tag to new tag):

   ```sh
   git diff --stat stable-35.0.1 stable-35.0.2 -- app/build.gradle.kts \
       app/src/main/java/com/owncloud/android/authentication/AuthenticatorActivity.java \
       app/src/main/java/com/nextcloud/client/errorhandling/ShowErrorActivity.kt \
       app/src/main/java/com/owncloud/android/ui/adapter/OCFileListAdapter.java \
       app/src/main/AndroidManifest.xml app/src/main/res/values/setup.xml README.md
   git diff --name-status stable-35.0.1 stable-35.0.2 -- .github/workflows/
   ```

3. **Start from an up-to-date work branch.**

   ```sh
   git switch platform/android
   git merge origin/main
   ```

4. **Merge the tag** (this keeps upstream's history):

   ```sh
   git merge --no-ff stable-35.0.2 -m "merge: upstream stable-35.0.2"
   ```

5. **Resolve conflicts** with the table below. The rule: for an upstream file, take upstream's
   version, then let `tools/rebrand.py` put our marked blocks back. Never hand-merge inside a
   `NoDrive: begin` / `NoDrive: end` block.

6. **Keep upstream's CI switched off.** Changes to upstream's old workflow files follow the rename
   into `.github/upstream-workflows/`. A **new** upstream workflow lands in `.github/workflows/` and
   would run here. Move each one:

   ```sh
   git mv .github/workflows/<new upstream file>.yml .github/upstream-workflows/
   ls .github/workflows/        # must list only android.yml
   ```

7. **Run the rebrand, then check it.**

   ```sh
   python tools/rebrand.py            # rewrites the Gradle block, the hooks, the strings, the manifest
   python tools/rebrand.py            # must print "rebrand: nothing to do"
   python tools/rebrand.py --check    # must print "rebrand --check: up to date"
   ```

   - If it stops with "anchor not found exactly once", upstream changed the line a hook sits in
     front of. Open the upstream file, find the same place, and update the anchor and block in
     `tools/rebrand.py` (`AUTH_ANCHOR`, `ERROR_ANCHOR`, `NOTES_ANCHOR`). Then run it again.
   - If it stops on `app/build.gradle.kts`, upstream renamed its version variables or the `generic`
     flavour. Update `patch_gradle` and the Gradle block.
   - New upstream strings that name Nextcloud are picked up on their own. Check the new ones in
     `git diff app/src/nodrive/res` and read a few languages.

8. **Regenerate and check the icons.**

   ```sh
   python tools/make-icons.py --check   # "all images up to date"; if not, run without --check
   ```

   The icons depend only on the logo. But if upstream added or renamed a launcher, logo or splash
   resource, add an override to `tools/rebrand.py` (XML) or `tools/make-icons.py` (images). Then
   look at the new upstream art.

9. **Re-run the sweep locally.**

   ```sh
   python tools/check-branding.py --source     # must end "PASS - no unclassified hit, every pin holds"
   python tools/check-branding.py --self-test  # must pass (the gate still fails on stock upstream)
   ```

   - An unclassified hit that a user can see: fix it in `tools/rebrand.py`, never in the allowlist.
   - A hit that is truly internal (a new class name, say): add an allowlist entry with the reason.
   - New upstream Nextcloud art: add its SHA-256 to `NEXTCLOUD_IMAGES` (raster) or its path start
     to `LOGO_PATHS` (vector) in `tools/check-branding.py`.

10. **Version.** The versionName follows upstream on its own (`35.0.2-nourax.1`). Set
    `NOURAX_REVISION = 1` in `tools/rebrand.py` for a new upstream version, and run step 7 again.
    Add a store changelog for the new versionCode if the store text is used.

11. **Commit and push the branch.**

    ```sh
    git add -A
    git commit -m "chore: rebrand after upstream stable-35.0.2"
    git push origin platform/android
    ```

12. **Wait for green CI** on the branch (all four jobs). Read failures with
    `gh run view <id> --log-failed | tail -n 150`.
    - **Lint:** upstream treats warnings as errors.
    - **"APK and unit tests":** `tools/check-apk.py` fails if a library string names Nextcloud. Then
      refresh the snapshot from that run's artifact:
      `python tools/rebrand.py --import-library-strings aapt2-dumps/resources.txt`, run step 7 again
      and push.
    - **Emulator:** an upstream test that cannot work in NoDrive (fixed server, no server field)
      goes into `tools/ci/instrumented-tests.txt` with a one-line reason.

13. **Look at the screenshots** in the run's `nodrive-android-screenshots` artifact (`sheets/` has
    contact sheets). Update `docs/screenshots/android/` if screens changed. Then update
    `docs/branding-sweep.md`, `docs/testing.md`, and the tag named in `README.md` and `NOTICE.md`.

14. **Merge into `main`** only with a green run on the branch head:

    ```sh
    git merge origin/main                    # must say "Already up to date."
    git push origin HEAD:main                # fast-forward only; never force-push main
    ```

    Then watch the run on `main` until it is green.

## Files that will conflict, and how to resolve them

| File | Why it conflicts | Resolution |
|---|---|---|
| `app/build.gradle.kts` | Our block sits at the end; upstream edits versions and flavours | `git checkout --theirs app/build.gradle.kts`, then `python tools/rebrand.py` |
| `AuthenticatorActivity.java`, `ShowErrorActivity.kt`, `OCFileListAdapter.java` | Each has one marked hook | `git checkout --theirs <file>`, then `python tools/rebrand.py` (step 7 if an anchor moved) |
| `README.md` | We replaced it; upstream's lives in `docs/upstream-README.md` | Keep ours: `git checkout --ours README.md`. Refresh the copy: `git show stable-35.0.2:README.md > docs/upstream-README.md` |
| `.github/workflows/*` | We moved upstream's workflows | Step 6 |
| `app/src/nodrive/**`, `src/nodrive/**`, `tools/**`, `docs/**`, `NOTICE.md` | Upstream never touches them, so no conflict. But generated files may be stale | `python tools/rebrand.py` regenerates them |
| `app/src/main/res/values*/strings.xml` (upstream translations) | No conflict: we never edit them | Nothing to do; rebrand picks up the changes |

Never edit upstream's flavours (`generic`, `gplay`, `huawei`, `versionDev`, `qa`), `LICENSE.txt`,
`LICENSES/` or upstream copyright headers.
