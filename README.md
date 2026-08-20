# Sundial

Google Calendar for Android without Google Play Services. Built for GrapheneOS.

It talks to the [Google Calendar REST API v3](https://developers.google.com/calendar/api/v3/reference)
over plain HTTPS with an OAuth 2.0 token. No Google libraries, no Play Services, no
microG, no analytics, no third-party servers.

---

## Why this exists

Google's own Calendar app requires Play Services, and the Google Sign-In SDK that most
third-party apps use requires it too. But the Calendar API itself is an ordinary REST
API, and OAuth works fine in a normal browser. Sundial does the authorization-code +
PKCE flow in a Custom Tab and catches the redirect on `http://127.0.0.1:<port>`, which
needs nothing from Google beyond the network.

**Before you build this**, know that [DAVx⁵](https://f-droid.org/packages/at.bitfire.davdroid/)
plus [Etar](https://f-droid.org/packages/ws.xsoh.etar/) already syncs Google Calendar
over CalDAV with no Play Services, from F-Droid, today. Sundial exists if you want your
own app, your own credentials, and no CalDAV layer in between.

---

## Features

**Views** — Schedule, Day, 3-day, Week and Month, all swipeable. Month shows event chips
per day with the selected day's agenda underneath; the time grids lay overlapping events
out side by side and mark the current time.

**Events** — create, edit and delete, with title, all-day toggle, start/end date and
time, time zone, location, description, calendar, busy/free, guest list and per-event
reminders. Recurring events can be edited or deleted as a single occurrence or as the
whole series. RSVP to invitations from the event screen.

**Repeat rules** — daily, weekly, every weekday, monthly, yearly. Existing rules of any
complexity are preserved and described in plain language rather than being flattened.

**Multiple calendars** — every calendar on the account, with its Google colours,
per-calendar visibility, and write access respected (read-only calendars are not
editable).

**Offline** — every synced event is cached in SQLite. All views, search and event details
work with no network. Writes go straight to Google and report failure rather than
silently queueing.

**Reminders** — local notifications scheduled with `AlarmManager` from the synced
reminder settings, re-armed after every sync and after reboot.

**ICS import** — open a `.ics` file from anywhere on the device, or let Sundial handle
`.ics` files opened from a file manager or email. It parses folded lines, `TZID` and
UTC timestamps, `DURATION`, `RRULE`/`EXDATE`/`RDATE`, and `VALARM` triggers, shows a
preview, and imports into a calendar you pick. Events with a `UID` go through Google's
`events.import`, so re-importing the same file updates rather than duplicates.

**ICS export** — write the cached events back out as an `.ics` file.

**Search** — instant search over the local cache, with an option to search the whole
account server-side for anything outside the cached window.

---

## Setting up Google credentials

Sundial has no credentials baked in — you create your own OAuth client, so nothing about
this app depends on me. It takes about five minutes, once.

1. Go to the [Google Cloud console](https://console.cloud.google.com/) and create a
   project (any name).

2. **Enable the API.** APIs & Services → Library → search "Google Calendar API" →
   Enable.

3. **Configure the consent screen.** APIs & Services → OAuth consent screen.
   - User type: **External**
   - Fill in the app name and your email where required
   - Add the scope `https://www.googleapis.com/auth/calendar`
   - **Set the publishing status to "In production."**

   > This step matters. While the app is in **Testing**, Google expires refresh tokens
   > after **7 days** and you will have to sign in again every week. In production, an
   > unverified personal app still works — you get a "Google hasn't verified this app"
   > interstitial at sign-in, where you tap **Advanced → Go to <your app name>
   > (unsafe)**. Verification is only required to let *other* people use it.

4. **Create the client.** APIs & Services → Credentials → Create credentials → OAuth
   client ID → Application type: **Desktop app**.

   Desktop is correct here, not Android: Sundial uses a loopback redirect rather than a
   custom URI scheme, so the credential is not tied to the APK's signing certificate and
   the same build works for anyone.

5. Copy the **client ID** and **client secret** into Sundial's first screen.

6. **At sign-in, tick the calendar checkbox.** Google's consent screen lists each
   requested permission with its own checkbox, and it will happily complete sign-in
   with the calendar one unticked. Sundial checks the granted scopes and refuses such
   a sign-in with an explanation rather than landing you on a calendar that can never
   load.

The "client secret" of a desktop OAuth client is not actually a secret —
[Google documents it](https://developers.google.com/identity/protocols/oauth2/native-app)
as embedded in installed apps, and PKCE is what actually protects the flow. Sundial
stores both values encrypted with an AES-256-GCM key held in the Android Keystore.

---

## Building

The repo has no wrapper checked in; `build.ps1` points at a portable toolchain:

```bash
powershell -ExecutionPolicy Bypass -File build.ps1 assembleRelease
```

Requirements: JDK 17, Android SDK with platform 35 and build-tools 35.0.0, Gradle 8.11+.
Set `sdk.dir` in `local.properties` to your SDK path.

To sign the release build, create `keystore.properties` in the project root:

```properties
storeFile=sundial-release.jks
storePassword=...
keyAlias=sundial
keyPassword=...
```

Without it the release build is produced unsigned and you will need to sign it yourself
before Android will install it.

Release builds are minified with R8 — material-icons-extended alone is ~35 MB of icon
classes without shrinking. The keep rules in `app/proguard-rules.pro` pin the
kotlinx.serialization models (verified against the release `mapping.txt`: the OAuth
`TokenResponse` and its serializer survive un-renamed).

---

## Installing on GrapheneOS

Transfer the APK and open it, or `adb install -r app-release.apk`.

At first launch the app asks for notification permission — decline it and reminders
simply stay silent. `USE_EXACT_ALARM` (Android 13+) and `SCHEDULE_EXACT_ALARM`
(Android 12/12L) are declared so reminders fire at the right minute; both are granted
at install time and need no prompt.

---

## How syncing works, and what it cannot do

Sundial re-reads a bounded window of each calendar (by default 180 days back and 540 days
forward, both configurable) with `singleEvents=true`, then mark-and-sweeps the cache so
events deleted elsewhere disappear locally.

It deliberately does **not** use Google's incremental `syncToken`. A sync token cannot be
combined with `timeMin`/`timeMax`, and it only returns master events — using it would
mean shipping a full RRULE expander on the device and getting recurrence edge cases
wrong. A windowed re-read is a handful of requests for a personal calendar and is always
correct.

**There are no push notifications.** Google delivers calendar changes to devices through
Play Services, and the API's own push channels need a public HTTPS webhook. Without
either, Sundial polls — every 30 minutes by default, 15 minutes minimum (WorkManager's
floor), or manually. An event created on another device will not appear here instantly.
This is a consequence of running without Play Services, not something the app can work
around.

---

## What has actually been verified

Tested on an **AOSP** Android 15 emulator image — no Play Services installed, confirmed
with `pm list packages com.google.android.gms` returning nothing. That is the closest
available stand-in for GrapheneOS.

Verified on device:

- Installs and launches from a signed release APK, no crash, first frame in 533 ms.
- WorkManager reflectively instantiates `SyncWorker` and it runs to `SUCCESS` under R8 —
  the shrinker kept what it needed.
- The sign-in flow runs against Google's real servers: PKCE challenge, authorization URL,
  browser handoff, and Google parsing the request. With a deliberately fake client ID it
  comes back `Error 401: invalid_client — The OAuth client was not found`, which is the
  correct response and proves everything up to Google's end works.

Verified by unit test (45 tests, `gradlew testDebugUnitTest`):

- `LoopbackServerTest` runs the redirect server over real sockets: it captures the
  authorization code, handles a denial from the consent screen, keeps waiting through
  favicon probes, query-less stray requests and wrong-state requests (none of which
  may consume the one-shot server), survives an idle connection that sends nothing,
  unblocks on cancel, and HTML-escapes error text.
- `IcsParserTest` covers TZID and UTC timestamps, all-day exclusive end dates, folded
  lines, escaped TEXT, `DURATION`, `VALARM` triggers, `RRULE`/`EXDATE`, modified
  occurrences, and a realistic Google Calendar export.
- `RecurrenceTest` covers preset round-trips, custom-rule detection and descriptions.

**Not verified end-to-end**: everything behind sign-in — real calendar sync, event
create/edit/delete, ICS import against a live account. That needs a real Google OAuth
client, which is yours to create. Those paths are covered by code review and the unit
tests above, not by execution.

---

## Known limitations

These are real gaps, not oversights waiting to be discovered:

- **One Google account.** The token store is keyed for a single account.
- **"This and following occurrences"** is not offered when editing a series — only
  "this event" and "all events". Doing it properly means rewriting the master rule's
  `UNTIL` and creating a second series.
- **No offline write queue.** Creating or editing an event with no network fails with an
  error rather than pretending to succeed.
- **ICS import skips modified occurrences** of recurring series (`RECURRENCE-ID`), and
  says so in the import summary. Google's import endpoint has no way to attach them.
- **VTIMEZONE definitions are ignored.** Windows/Outlook TZIDs are mapped by name to
  IANA zones; an event with a truly custom TZID is skipped with a warning rather than
  imported at the wrong wall-clock time. End-relative alarm triggers
  (`TRIGGER;RELATED=END`) are skipped too — Google's reminder model cannot express
  them.
- **Guests are add-by-email only** — no contact picker, and no per-guest permissions.
- **No attachments, no conference/Meet creation, no Google Tasks**, no calendar sharing
  or ACL management.
- **All-day event boundaries** are cached as local midnight in the device's time zone. A
  time-zone change triggers a resync to correct them.
- **Event colours** come from the calendar or the event's `colorId`; picking a custom
  per-event colour in the editor is not implemented.

---

## Project layout

```
core/       time conversion and the domain models
auth/       OAuth PKCE, loopback redirect server, Keystore-backed token store
net/        Calendar REST client and DTOs
data/       SQLite cache, repository, windowed sync engine, event drafts
ics/        iCalendar parser, importer, exporter
reminders/  AlarmManager scheduling and notifications
sync/       WorkManager periodic sync
ui/         Compose screens (calendar views, event detail/edit, search, settings, import)
```

No dependency injection framework, no Room, no Retrofit — OkHttp, kotlinx.serialization,
Compose and WorkManager, and hand-written SQL.
