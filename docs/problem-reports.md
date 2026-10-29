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
