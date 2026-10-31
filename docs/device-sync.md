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
   why; the board app's calendars, NoGantt's copy calendar and lists with only to-dos are never offered; a calendar that was chosen and
   that the server no longer lists says "No longer in NoDrive" and is taken off the phone when it is unticked);
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
| Rules (plain Kotlin, unit-tested) | `SyncPlans.kt`: conflicts, delete guard (the first-sync rules live in the two engines and are tested there) |
| The list of calendars and address books, what is waiting, taking a copy away | `CollectionRefresh.kt` (`CollectionRefresh`, `WaitingCount`, `CollectionRemoval`) |
| Own database | `store/DeviceSyncDatabase.kt` (`nodrive_devicesync.db`, schema exported in `app/schemas`) |
| Backup before the first sync | `BackupGate.kt` (`BackupFlow` decides, `BackupGate` talks to the phone and the server) |
| Switching on and off, stop | `DeviceSyncControl.kt` |

- **Identity.** Events: `_SYNC_ID` = the object's address, `SYNC_DATA1` = ETag, `UID_2445` = UID. Contacts: `SOURCE_ID` = address, `SYNC1` =
  ETag, `SYNC2` = UID. The server's last text of every object is kept in `nodrive_devicesync.db`, so a phone edit **patches that text**: only
  the properties the person changed are rewritten, every other line stays byte for byte (`X-NOURAX-AGENT-ID`, `CATEGORIES`, attendees,
  unknown `X-` lines, the photo, time zones). A change to one occurrence of a repeating event is a `RECURRENCE-ID` block of the same object.
- **Tasks.** Only `VEVENT`s are read; a to-do in a mixed calendar is remembered by its ETag and never written to the phone.
- **Reminders.** `VALARM` with a relative trigger before the start becomes a `Reminders` row (alert); edited on the phone, the alarms of
  that event are rewritten.
- **Groups.** The server's contact groups are `CATEGORIES` on each card; they map to the phone's `Groups` and `GroupMembership`.
- **Phone data that the card has no place for stays.** When the server's version of a contact is written to the phone, only the kinds of data
  that the sync manages are written again. The phone's photo (when the card has none), other kinds of event, a SIP address and the data
  that other apps add are kept. A photo set on the phone is sent when the card has none, and `RELATED` is sent both ways. A photo that was
  removed on the server (the card had one, now has none) is removed from the phone too, so it does not come back with the next phone edit.
  Guests that were added to an event on the phone are put back when the server's version of the event is written. A new phone contact whose
  only content is a data row of a kind the sync does not read (a SIP address, a custom date, another app's data) is not called empty: it is
  sent, so Stop can not remove it unseen.
- **Read-only copies are read only on the phone too.** A change that an app makes in one anyway can never be sent, so the server's version
  is put back and the phone's edit is kept in "Not saved" first (a delete is simply undone).
- **A phone copy that was made again starts over.** If the phone's Calendar Storage or Contacts Storage was cleared (or the calendar was
  added again), the collection is read again from the start with the first-sync rules, which only add.
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
- **Stop syncing** (or taking one calendar away) first runs a final sync. Nothing is removed while a phone change could not be sent
  (no network, an error): "Some changes on this phone haven't reached NoDrive yet." Nothing is removed either while a phone version that
  lost a conflict still waits in "Not saved" (`StopRule`, `notSaved` of the account, or of that one collection): "Some changes on this phone
  weren't saved ... Look at Not saved first." After Stop the "Not saved" list can not be reached, so the person decides first ("Apply my
  change again" or "Keep the NoDrive version"). Only then are **our account's** phone calendars and contacts removed (they are copies;
  everything stays on the server). The final sync and the removal run under one lock, so a sync that the phone starts in between can not
  add an empty copy again.
  - **The refusal is about the person's unsent changes, never about the sync's own health.** A sync that fails for a lasting reason with
    nothing waiting (the calendar list can not be read, the server answers an error) does not hold Stop up. A dirty row in a chosen calendar
    is counted whether or not the server can be asked (a changed occurrence of a repeating event counts for its event).
  - Only a change that can still be sent counts as waiting: a change in a calendar that the server deleted, in a read-only copy, or a new
    contact that is empty does not hold Stop up. Unsent changes in a calendar or address book that the server no longer lists **are lost
    with the phone copy**; the Stop question and the question when such a collection is unticked name how many ("%d changes on this phone
    that were never sent will be lost ..."). A copy calendar of NoGantt that is taken off the phone by the sync goes without a question
    (it holds copies of tasks that are never sent).
- **NoGantt's copy calendar** (copies of tasks) never reaches the phone. It is read the way NoAgenda reads it and fails closed: when the
  server can not say which calendar it is, no calendar is offered or synced until it can (contacts still sync). A copy calendar that was
  chosen once leaves the phone. **Decision on purpose:** a `412 "App is not enabled"` (the server's answer to a person outside a
  group-restricted NoGantt) is **not** read as "there is no copy calendar": such a person may still have the copy calendar from the time
  they were in the group, and it would be offered with tasks in it. It stays "unknown": no calendar is offered or synced, the choose screen
  says "NoDrive can't check your calendars right now. Contacts still sync." and so does the status screen. A server that restricts NoGantt to
  some groups must tell the client teams first (see the server notes). Only 404 (NoGantt is not on the server) and a well-formed answer count as known.
- **The status screen says why** when a sync keeps failing: the calendars can not be checked, or "The last sync didn't finish. NoDrive will
  try again." (any other failure code, for example `server_403`, `safety_copy_not_saved_*`, `multiget_*`). A chosen calendar or address book
  that is no longer in NoDrive is listed with "No longer in NoDrive. Untick it to take it off this phone."
- **The backup gate fails closed.** A phone whose contacts or calendars can not be read is not "nothing to save": the backup fails with Try
  again and the first sync does not start.
- **A write needs an ETag.** A change or a delete is only sent with `If-Match`; without a known ETag nothing is sent and the next sync reads
  the object again.
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
- The photo of a contact travels from the phone to the server only when the card has none. A photo that the card already has is never replaced
  from the phone: the phone keeps its own re-encoded copy, so the bytes can not be compared, and a photo changed on the phone while the card has
  one is overwritten by the next server edit of that card. Attendees and the organiser of an event only travel from the server to the phone (a
  phone edit never changes them).
- The phone's finer relation types (father, mother, manager) go to the nearest vCard kind; custom labels of a phone number or address are sent as
  the nearest standard type (the label text is not kept).
- With several writable address books chosen, a contact made on the phone goes to the first of them (by name); the others never get it.
- **Commit trailer.** Commit `a58e3c1a98` and the commits of this fix round end with `Co-Authored-By: Claude Sonnet 5.5`, not the
  `Claude Opus 5.5` of the round rule: the session that wrote them is Sonnet 5.5 and the session's attribution rule names that model. The
  eleven earlier commits say Opus 5.5. Noted here for the merge.
- **Not built (listed so nothing is silent):** the NoAgenda overflow-menu entry of plan 4.1 (the screen is reached from Settings and from the
  phone's account settings); a change made on the phone while it is being sent is cleared with the send (a window of a few milliseconds);
  the backup gate takes the first new calendar backup file as done (upstream writes one file per calendar).
- The status screen shows how many events or contacts each chosen collection holds (plan 4.7) and the date of a sync that was not today.
- The backup gate saves only what exists: with no contacts (or no calendars) on the phone, that backup is not made.

## Tests

- **Unit tests** (JVM): `EventMapperTest` (every field both ways, all day, time zones, repeat and exceptions, reminders, what a phone edit
  keeps byte for byte), `ContactMapperTest` (every field, groups, relations, photo rules, unknown lines, vCard 3.0 kept), `SyncPlansTest`
  (conflict rules, delete guard thresholds), `DeviceSyncSafetyTest` (the guard over the sources, the write policy). The engines are tested
  against a server and a phone in memory (`DeviceSyncFakes.kt`): `CalendarEngineTest` and `ContactsEngineTest` (the first sync only adds, edit
  against delete in both directions, discarding local deletions, the delete guard, the removed-contacts copy before a delete and a failed
  copy holding the deletes back, read-only copies, an empty contact, one default address book, a phone copy that was made again, a photo
  and a relation sent, no write without an ETag), `CollectionRefreshTest` (the copy calendar fails closed, collections that are gone) and
  `BackupFlowTest` (offline, a failed job, a timeout and a phone that can not be read all stop the first sync) and `StopRuleTest` (Stop refuses
  for unsent changes and for "Not saved" only; a failing task-sync, 412 or 500, does not hold it up).
- **Emulator tests** (`androidTestNodrive/.../devicesync/`, throwaway server, Ali's account; CI job "Device calendar and contacts sync on the
  emulator", `tools/ci/devicesync-device.sh`): `DeviceSyncSettingsIT` (also: the phone knows our settings activity as a class name),
  `DeviceSyncFirstSyncIT`, `DeviceSyncTwoWayIT`, `DeviceSyncReminderIT`, `DeviceSyncContactsIT`, `DeviceSyncSafetyIT` (a calendar deleted
  on the server then Stop; a photo set on the phone and an edit of the card on the server; an account whose NoGantt copy calendar is on),
  `DeviceSyncStopIT` (also: a phone edit and a server edit of one event, then Stop is refused and the entry is still in "Not saved").
  `run_it` of the CI script reads the raw output of `am instrument -r` and counts a test that skipped itself (an unmet assumption) as a
  failure, so a missing server can not pass silently. Screenshots in light and dark mode:
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
11. Change one event on the phone and the same event on the web, then Stop syncing: stopping is refused with "Look at Not saved first", and
    the phone's version is listed under "Not saved" ("Apply my change again" or "Keep the NoDrive version"). After you decide, Stop works.

## Server-side notes (for the Workspace session; this workflow changed no server)

- Keep the calendar trash bin on, with its default retention: a phone delete of an event relies on it.
- If NoGantt is ever limited to some groups, people outside them get 412 on `task-sync`: device calendar sync (not contacts, not Stop) is off
  for them, with a reason on screen. Tell the client teams before restricting the app.
- Address books have no trash bin. A server-side contacts trash or versioning would add safety on top of the removed-contacts file.
- Removing the NoDrive account from the phone removes the phone copies (Android does that for every account); changes not yet sent go with
  them. The status screen shows how many are waiting, so the person can sync first.
