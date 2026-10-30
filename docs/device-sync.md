<!--
  ~ SPDX-FileCopyrightText: 2026 Nourax
  ~ SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Calendar and contacts on this phone (device sync)

Binding spec: `plans/noagenda-decisions.md`, section 10 (approved 2026-10-28). Platform plan: `plans/noagenda-android-plan.md`, section 4
("device-sync"). Branch: `feature/device-calendar-sync`.

NoDrive's own two-way sync between the person's NoDrive calendars and address books (CalDAV and CardDAV on their own server) and the
phone's own **Calendar** and **Contacts** apps. It is **off until the person switches it on**. Tasks never go to the phone's calendar.

## What the user gets

**Settings > More > "Calendar and contacts on this phone"** (summary "Off", or "On . 2 calendars, 1 address book"). Switching on walks
through these steps (`DeviceSyncActivity`, Compose):

1. an explanation ("Changes go both ways. Tasks stay in NoAgenda.");
2. the calendar and contacts permissions (a refusal shows "Open settings");
3. **choose** the calendars and address books (subscriptions, the birthday calendar and read-only address books are off by default and say
   why; the board app's calendars, NoGantt's copy calendar and lists with only to-dos are never offered);
4. **the backup**: upstream's "Contacts and calendar backup" saves the phone's contacts and calendars into NoDrive first (`.Contacts-Backup`,
   `.Calendar-Backup`). The first sync does not start until a new file of each is on the server (at most 15 minutes);
5. **the first sync, which only adds** ("Nothing is deleted");
6. then two-way sync with sync tokens and ETags, every 30 minutes and after a change on the phone. Event alarms become phone reminders.

The status screen shows the last sync, the counts, "Sync now", the "Not saved" list ("Apply my change again" / "Keep the NoDrive
version"), "Choose calendars and contacts" and "Stop syncing". It is also what the phone's account settings open.

There is **no second account and no second credential**: the NoDrive account (type `org.nourax.nodrive`, shown as "NoDrive") gets two more
sync adapters, for the system authorities `com.android.calendar` and `com.android.contacts`. Until the person switches sync on in NoDrive,
the adapters answer the phone's first request with "not syncable", so the phone's own account settings show no Calendar or Contacts toggle
that could bypass the backup and the first-sync rules.

## How the sync works

| Part | File (`org.nourax.nodrive.devicesync`) |
|---|---|
| Sync adapters and services | `DeviceSyncAdapters.kt`, `CalendarSyncService.kt` (`CalendarSyncAdapter`, `ContactsSyncAdapter`, in the generated nodrive manifest) |
| One sync of one account (one at a time per process) | `DeviceSyncRunner.kt`; engines `calendar/CalendarSyncEngine.kt`, `contacts/ContactsSyncEngine.kt` |
| Server side | `dav/DeviceDav.kt` (`sync-collection`, `multiget`), `dav/CardDavClient.kt` (collections, `If-Match` / `If-None-Match`), `DeviceSyncWritePolicy.kt` |
| The phone's providers (the **only** writers) | `ProviderScope.kt` (always the sync adapter, with **our** account name and type), `calendar/CalendarProviderStore.kt`, `contacts/ContactsProviderStore.kt` |
| iCalendar / vCard <-> rows | `calendar/EventMapper.kt`, `calendar/EventDocument.kt`, `contacts/ContactMapper.kt`, `contacts/VCardText.kt` |
| Rules (plain Kotlin, unit-tested) | `SyncPlans.kt`: first sync, conflicts, delete guard |
| Own database | `store/DeviceSyncDatabase.kt` (`nodrive_devicesync.db`, schema exported in `app/schemas`) |
| Backup before the first sync | `BackupGate.kt` |
| Switching on and off, stop | `DeviceSyncControl.kt` |

- **Identity.** Events: `_SYNC_ID` = the object's address, `SYNC_DATA1` = ETag, `UID_2445` = UID. Contacts: `SOURCE_ID` = address, `SYNC1` =
  ETag, `SYNC2` = UID. The server's last text of every object is kept in `nodrive_devicesync.db`, so a phone edit **patches that text**: only
  the properties the person changed are rewritten, every other line stays byte for byte (`X-NOURAX-AGENT-ID`, `CATEGORIES`, attendees,
  unknown `X-` lines, the photo, time zones). A change to one occurrence of a repeating event is a `RECURRENCE-ID` block of the same object.
- **Tasks.** Only `VEVENT`s are read; a to-do in a mixed calendar is remembered by its ETag and never written to the phone.
- **Reminders.** `VALARM` with a relative trigger before the start becomes a `Reminders` row (alert); edited on the phone, the alarms of
  that event are rewritten.
- **Groups.** The server's contact groups are `CATEGORIES` on each card; they map to the phone's `Groups` and `GroupMembership`.
- **Resumable.** The sync token is stored only after a complete pass; objects already added are recognised by address, so a sync that the
  system cut off carries on next time.

## Data safety (round rule and design 10)

- **The first sync only adds.** On the phone and on the server, nothing is deleted or overwritten while a collection's first sync runs. Objects
  on both sides (same UID) are linked. A phone row with an old address whose server object is missing is added again, never deleted.
- **After it, a delete is a delete of an object that the person deleted**, sent with `If-Match`. Calendar objects land in the server's
  calendar trash bin. Address books have **no trash bin on the server**, so before any CardDAV `DELETE` the server's last version of every
  contact about to be deleted is saved as one new file `removed-contacts-<UTC time>.vcf` in `.Contacts-Backup` (created with
  `If-None-Match: *`, never replaced). If that file can not be saved, the deletes wait for the next sync.
- **Delete guard.** More than 20 deletes **and** more than 20 % of a collection, in either direction: nothing is deleted and
  `SyncResult.tooManyDeletions` is set; Android asks the person. The adapter honours `SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS` (go ahead) and
  `SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS` (the phone's deletes are undone from what the server had).
- **Conflicts.** A phone change and a server change to the same object: the **server wins on the phone** and the phone version is kept in
  "Not saved" with a notification. "Apply my change again" puts only what the phone changed on top of the server's newer version. A phone
  **edit** against a server **delete**, or the other way round: the **edit wins**. Nothing is lost silently.
- **Stop syncing** (or taking one calendar away) first runs a final sync. If a phone change can not be sent (no network, a conflict),
  nothing is removed: "Some changes on this phone haven't reached NoDrive yet." Only then are **our account's** phone calendars and
  contacts removed (they are copies; everything stays on the server).
- Other accounts' rows are never touched: every provider address carries our account name and type, and `DeviceSyncSafetyTest` fails if any
  other file writes to a provider, if a `DELETE` leaves the app anywhere but the DAV client, or if a file of the Drive could be replaced or
  deleted (`DeviceSyncWritePolicy`). The test also keeps `contacts_backup_expire` at -1 in the nodrive build.
- Files on the phone or the Drive are never deleted by this feature.
- Privacy: only the signed-in account's own server is asked (the account's own client and credential, redirects off). The log holds
  counts, codes and status numbers, never titles, names or numbers.

## Licence check (design 10)

| Code or library | Licence | Decision |
|---|---|---|
| DAVx5 (`bitfireAT/davx5-ose`) | GPL-3.0 | **Not used, not copied, not read for copying.** |
| synctools, ical4android, vcard4android (DAVx5's sync libraries) | GPL-3.0 | **Not used, not copied.** |
| dav4jvm 3.0.2 | MPL-2.0 | On the runtime path through the server library of the app; **not used directly** (its version belongs to upstream). The DAV requests are NoDrive's own small code, shared with NoAgenda |
| ez-vcard 0.12.2 | BSD-2-Clause | Shipped already; **not needed**: NoDrive reads and patches vCards with its own small code (`VCardText`) so that every unchanged line stays byte for byte |
| `thirdparties/sufficientlysecure` (in tree) | GPL-3.0-or-later | Used only by upstream's own calendar backup, unchanged; no NoDrive code calls or copies it |
| OkHttp, Room, WorkManager, Compose | Apache-2.0 | Already shipped |
| New NoDrive files | AGPL-3.0-or-later | No GPL-3.0-only code enters any file |

Result: **no new library**, and no GPL-3.0-only code in the app.

## Where this differs from the plan

- vCards are read and patched with NoDrive's own line code (`VCardText`, `VCardPatch`), not ez-vcard (plan 4.2). Reason: a patch that keeps
  every other line byte for byte is what the plan asks of events and the safe choice for contacts; ez-vcard would rewrite the whole card.
- The status screen's "Sync now" asks the phone's sync system first (so the adapters are the ones that run) and, if nothing happens within
  25 seconds, runs the same sync in the app's own process.
- The photo of a contact only travels from the server to the phone (a photo changed on the phone is not sent), attendees and the organiser
  of an event only travel from the server to the phone (a phone edit never changes them).
- The backup gate saves only what exists: with no contacts (or no calendars) on the phone, that backup is not made.

## Tests

- **Unit tests** (JVM): `EventMapperTest` (every field both ways, all day, time zones, repeat and exceptions, reminders, what a phone edit
  keeps byte for byte), `ContactMapperTest` (every field, groups, unknown lines, vCard 3.0 kept), `SyncPlansTest` (first sync only adds,
  conflict rules, delete guard thresholds), `DeviceSyncSafetyTest` (the guard over the sources, the write policy).
- **Emulator tests** (`androidTestNodrive/.../devicesync/`, throwaway server, Ali's account; CI job "Device calendar and contacts sync on the
  emulator", `tools/ci/devicesync-device.sh`): `DeviceSyncSettingsIT`, `DeviceSyncFirstSyncIT`, `DeviceSyncTwoWayIT`,
  `DeviceSyncReminderIT`, `DeviceSyncContactsIT`, `DeviceSyncStopIT`. Screenshots in light and dark mode:
  `devicesync-{settings,intro,permissions,choose,backup,first-sync,status}-{light,dark}.png`. Then `tools/ci/devicesync-server-check.py` asks the
  server itself (backup files, the removed-contacts file, the trash bin, the cards).

## Owner test rows (plain English)

1. In Settings > More, "Calendar and contacts on this phone" says Off. In the phone's own account settings, the NoDrive account shows no
   Calendar or Contacts switch.
2. Switch it on, choose calendars and an address book. The backup appears in NoDrive (`.Calendar-Backup`, `.Contacts-Backup`) before
   anything is added to the phone.
3. The phone's Calendar app shows the NoDrive calendars with the right colours. The phone's Contacts app shows the NoDrive contacts with
   their groups and photos.
4. Make an event in the phone's Calendar app: it appears in NoAgenda and on the web. Make one on the web: it appears on the phone.
5. An alarm of a NoDrive event rings on the phone.
6. **Edit a tagged event in the phone's own Calendar app** and check its agent in NoAgenda (design 4.2, with the phone's real app).
7. A contact edited in the phone's Contacts app appears on the web with its group.
8. Tasks never appear in the phone's calendar.
9. Delete one contact on the phone: a file `removed-contacts-...` appears in `.Contacts-Backup` before it is gone on the web.
10. Stop syncing: the NoDrive calendars and contacts leave the phone, NoDrive still has them. With the network off, stopping is refused.

## Server-side notes (for the Workspace session; this workflow changed no server)

- Keep the calendar trash bin on, with its default retention: a phone delete of an event relies on it.
- Address books have no trash bin. A server-side contacts trash or versioning would add safety on top of the removed-contacts file.
- Removing the NoDrive account from the phone removes the phone copies (Android does that for every account); changes not yet sent go with
  them. The status screen shows how many are waiting, so the person can sync first.
