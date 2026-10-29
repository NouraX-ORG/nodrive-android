# Problem reports: notes for maintainers

- The code is in `app/src/main/java/org/nourax/nodrive/reports/`. Reports are scrubbed on the phone (`Scrubber`) and go only to the
  signed-in account's own server (`WebDavReportServer`), into `/Problem reports`.
- Logging is on in the `nodrive` flavour (upstream's file logger, `filesDir/logs/`, four files of 1 MB). The logs stay in the
  app's private folder (`allowBackup="false"`) and leave the phone only inside a scrubbed report. Upstream's raw "Logs" row in
  Settings is removed.
- Known: at every sign-in upstream's `AuthenticatorActivity` writes the Login Flow v2 poll response, which contains the new app
  password, into that private log. It is removed from reports (`Scrubber` names `appPassword`, and any 32+ character run of letters
  and digits), and `ScrubberTest` has fixtures for it. If this ever needs to be closed at the source, mask the value at log time
  with a small marked hook in `AuthenticatorActivity`.
- Without a network the upload run stays queued. The "Report a problem" screen then says (after 5 seconds) that the report is
  saved on the phone and enables Send again. The main screen shows the final message when the run ends.
- A report name is never reused: a taken name gets `_2` ... `_99`; if all are taken the report stays on the phone.
- Scrubber limits, on purpose: an e-mail local part is matched up to 64 characters (RFC 5321), so a very long run of such
  characters cannot slow a report down (an 80 KB run took 90 s before the cap; `ScrubberTest` times 200 KB lines). A file name like
  `icon@2x.png` is replaced by `<email>` (a small over-scrub, accepted). A quoted secret is taken up to its closing quote; an
  unquoted one up to the first space. Unlabelled base64 of 40 to 63 characters with `+` or `/` is kept; labelled ones (for example
  `requesttoken`) are removed.
- Speed on Android: every scrub pass steps ONE `java.util.regex.Matcher` with `find()`. Kotlin's `Regex.replace { }` and `find(text, start)`
  make a new Matcher per match, and on Android a new Matcher copies the whole text, which made a log with thousands of account
  addresses very slow. `ReportsSafetyTest` guards the source, and `ReportsScrubberSpeedIT` times a 4 MB log on the emulator. Quoted values
  are unrolled loops (no stack overflow on a 200 KB value). A URL user-info part never spans a JSON quote.
- If a second Send (or an automatic crash run) is queued while the first run is waiting, the first run sends every zip in the
  outbox; the run watched by the screen then finds it empty, and the screen says "Saved in your NoDrive > Problem reports."
- Known limits of the quoted passes (review nits N1 and N4): on Android a quoted value stops matching only after about 77,000 escape pairs in a row (a 154 KB run of escapes); a raw `"` inside a URL password keeps the part before it (OkHttp writes `%22`, which is removed fully).
