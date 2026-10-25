#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Screen sweep of NoDrive on a running emulator (CI only; spec section 6, screenshot pass).

Usage:
    python3 tools/ci/sweep.py --apk APK --test-apk TEST_APK --out DIR [--adb ADB]

Phase A, signed out, network on. For every look (English, German, Arabic; light and dark) the
app is cleared and launched: the launch screen, the browser that sign-in opens and the "complete
the sign-in in your browser" screen. System screens in en/de/ar light: app info, the add-account
list, the app's notification settings and the share sheet; in en light also the app list and the
widget picker.
Phase B, signed in, airplane mode: a local stand-in account (NoDriveSweepAccount, never sent to
the server) gives the file list, drawer, every page of Settings (with About), accounts, uploads
and auto upload in every look, and the system account list.

Every screen is saved as NN-<look>-<screen>.png with the uiautomator dump next to it (.xml).
Checks, exit code 1 when one fails:
- the APK installs, and in every look the launch reaches a NoDrive or browser window;
- sign-in opens a browser at drive.nourax.org (address bar or the activity start in logcat);
- no text on any screen names Nextcloud (browser pages, which the server serves, are only listed);
- no screen except browser pages has more than 0.5 % of its pixels in Nextcloud blue.
Writes index.md (screens and checks), checks.json and contact sheets in sheets/ (needs Pillow).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PKG = "org.nourax.nodrive"
HOST = "drive.nourax.org"
LAUNCHER = f"{PKG}/com.nmc.android.ui.LauncherActivity"
ACTIVITY = f"{PKG}/com.owncloud.android.ui.activity."
SWEEP_TEST = "org.nourax.nodrive.NoDriveSweepAccount"
# The name and its transliterations, as in tools/rebrand.py.
NAME_RE = re.compile(r"next\s*cloud|owncloud|نكست\s*كلاود|نکست[‌\s]*کلود|некстклауд|nekstklaud|nexcloud"
                     r"|next\s+bulut", re.I)
NEXTCLOUD_BLUES = [(0x00, 0x82, 0xC9), (0x00, 0x67, 0x9E), (0x00, 0x6A, 0xA3)]
BLUE_TOLERANCE = 10
BLUE_LIMIT = 0.005
CHROME_FRE_IDS = ["terms_accept", "signin_fre_dismiss_button", "negative_button", "button_secondary",
                  "ack_button", "fre_bottom_group_button"]
CHROME_FRE_TEXT = re.compile(r"^(Accept & continue|Use without an account|No thanks|No, thanks|Got it)$", re.I)


@dataclass
class Look:
    locale: str
    night: bool

    @property
    def name(self) -> str:
        return f"{self.locale.split('-')[0]}-{'dark' if self.night else 'light'}"


LOOKS = [Look("en-US", False), Look("en-US", True), Look("de-DE", False), Look("ar-EG", False), Look("ar-EG", True)]


@dataclass
class Screen:
    number: int
    look: str
    name: str
    png: str
    xml: str | None
    focus: str
    note: str = ""
    browser: bool = False
    text_hits: list[str] = field(default_factory=list)
    blue: float | None = None


class Device:
    def __init__(self, adb: str, out: Path):
        self.adb, self.out = adb, out
        self.log = (out / "sweep-adb.log").open("w", encoding="utf-8")

    def run(self, args: list[str], timeout: int = 90, binary: bool = False):
        try:
            res = subprocess.run([self.adb, *args], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            self.log.write(f"$ adb {' '.join(args)}\n  TIMEOUT after {timeout}s\n")
            return 124, b"" if binary else ""
        out = res.stdout if binary else res.stdout.decode("utf-8", "replace") + res.stderr.decode("utf-8", "replace")
        if not binary:
            self.log.write(f"$ adb {' '.join(args)}\n{out[:4000]}\n")
        return res.returncode, out

    def sh(self, cmd: str, timeout: int = 90) -> str:
        return self.run(["shell", cmd], timeout)[1]

    def focus(self) -> str:
        out = self.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        m = re.search(r"mCurrentFocus=Window\{\S+ u\d+ ([^}\s]+)", out)
        if m:
            return m.group(1)
        m = re.search(r"mFocusedApp=\S+\{\S+ u\d+ ([^}\s]+)", out)
        return m.group(1) if m else "none"

    def wait_focus(self, test, timeout: float) -> str:
        end = time.time() + timeout
        f = self.focus()
        while not test(f) and time.time() < end:
            time.sleep(1)
            f = self.focus()
        return f

    def screencap(self, path: Path) -> bool:
        for _ in range(3):
            rc, data = self.run(["exec-out", "screencap", "-p"], timeout=60, binary=True)
            if rc == 0 and data[:8] == b"\x89PNG\r\n\x1a\n":
                path.write_bytes(data)
                return True
            time.sleep(1)
        return False

    def dump(self) -> str | None:
        for _ in range(2):
            self.sh("rm -f /sdcard/nd-ui.xml; uiautomator dump /sdcard/nd-ui.xml", timeout=60)
            rc, data = self.run(["exec-out", "cat", "/sdcard/nd-ui.xml"], timeout=30, binary=True)
            text = data.decode("utf-8", "replace")
            if rc == 0 and text.lstrip().startswith("<?xml"):
                return text
            time.sleep(1)
        return None

    def key(self, name: str):
        self.sh(f"input keyevent {name}")

    def tap_node(self, xml: str | None, test) -> bool:
        return any(self.tap(node) for node in nodes(xml) if test(node))

    def tap(self, node) -> bool:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not m:
            return False
        x1, y1, x2, y2 = map(int, m.groups())
        self.sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
        return True


def nodes(xml: str | None):
    if not xml:
        return []
    try:
        return list(ET.fromstring(xml.encode("utf-8")).iter("node"))
    except ET.ParseError:
        return []


def has_dialog(xml: str | None, focus: str) -> bool:
    if "permissioncontroller" in focus:
        return True
    return any(n.get("resource-id", "") in ("android:id/button1", "android:id/button2", "android:id/button3")
               for n in nodes(xml))


def texts(xml: str | None) -> list[tuple[str, str]]:
    found = []
    for n in nodes(xml):
        for attr in ("text", "content-desc"):
            v = (n.get(attr) or "").strip()
            if v:
                found.append((n.get("package", ""), v))
    return found


class Sweep:
    def __init__(self, dev: Device, out: Path):
        self.dev, self.out = dev, out
        self.screens: list[Screen] = []
        self.checks: list[dict] = []
        self.browser_pkg = ""
        self.look = LOOKS[0]

    def check(self, name: str, ok: bool | None, evidence: str, hard: bool = True):
        result = "pass" if ok else ("info" if ok is None else ("FAIL" if hard else "warn"))
        self.checks.append({"check": name, "result": result, "evidence": evidence})
        print(f"[{result}] {name}: {evidence}", flush=True)

    def shot(self, name: str, note: str = "", browser: bool | None = None) -> Screen | None:
        number = len(self.screens) + 1
        base = f"{number:03d}-{self.look.name}-{name}"
        png = self.out / f"{base}.png"
        focus = self.dev.focus()
        if not self.dev.screencap(png):
            self.check(f"screenshot {base}", False, "screencap failed", hard=False)
            return None
        xml = self.dev.dump()
        if xml:
            (self.out / f"{base}.xml").write_text(xml, encoding="utf-8")
        is_browser = browser if browser is not None else bool(self.browser_pkg) and focus.startswith(self.browser_pkg)
        s = Screen(number, self.look.name, name, png.name, f"{base}.xml" if xml else None, focus, note, is_browser)
        s.text_hits = [f"{pkg}: {t}" for pkg, t in texts(xml) if NAME_RE.search(t)]
        self.screens.append(s)
        print(f"  shot {png.name} focus={focus}", flush=True)
        return s

    # ---- device state --------------------------------------------------------------------
    def wait_ready(self, timeout: int = 300) -> bool:
        end = time.time() + timeout
        time.sleep(10)
        while time.time() < end:
            out = self.dev.sh("getprop sys.boot_completed; pm path android 2>/dev/null | head -n 1")
            if out.startswith("1") and "package:" in out and self.dev.focus() != "none":
                self.dev.sh("wm dismiss-keyguard")
                time.sleep(5)
                return True
            time.sleep(3)
        return False

    def set_look(self, look: Look):
        current = self.dev.sh("getprop persist.sys.locale").strip() or "en-US"
        if current != look.locale:
            print(f"== locale {current} -> {look.locale} (framework restart)", flush=True)
            self.dev.sh(f"setprop persist.sys.locale {look.locale}; stop; sleep 5; start", timeout=120)
            ready = self.wait_ready()
            self.check(f"switch locale to {look.locale}", ready,
                       "setprop persist.sys.locale + stop/start; now " + self.dev.sh("getprop persist.sys.locale").strip())
        self.dev.sh(f"cmd uimode night {'yes' if look.night else 'no'}")
        time.sleep(2)
        self.look = look

    def prepare(self) -> bool:
        d = self.dev
        d.run(["root"], timeout=60)
        d.run(["wait-for-device"], timeout=120)
        time.sleep(3)
        d.sh("locksettings set-disabled true; settings put global private_dns_mode off; "
             "settings put secure show_ime_with_hard_keyboard 0; wm dismiss-keyguard")
        facts = d.sh("getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.type; "
                     "getprop ro.product.model; wm size; wm density")
        self.check("emulator", None, " / ".join(x.strip() for x in facts.splitlines() if x.strip()))
        resolved = d.sh("cmd package resolve-activity --brief -a android.intent.action.VIEW "
                        "-c android.intent.category.BROWSABLE -d https://example.org")
        comp = [x for x in resolved.splitlines() if "/" in x]
        self.browser_pkg = comp[-1].split("/")[0].strip() if comp else ""
        self.check("browser on the image", bool(self.browser_pkg), self.browser_pkg or resolved.strip()[:200])
        if self.browser_pkg:
            # Chrome reads these flags on debuggable builds or as debug app: no first-run screens.
            flags = "_ --disable-fre --no-default-browser-check --no-first-run"
            d.sh(f"echo '{flags}' > /data/local/chrome-command-line; echo '{flags}' > "
                 "/data/local/tmp/chrome-command-line; chmod 644 /data/local/chrome-command-line "
                 f"/data/local/tmp/chrome-command-line; am set-debug-app --persistent {self.browser_pkg}")
        return True

    def install(self, apk: Path, test_apk: Path) -> bool:
        ok = True
        for p, extra in ((apk, []), (test_apk, ["-t"])):
            rc, out = self.dev.run(["install", "-r", *extra, str(p)], timeout=300)
            good = rc == 0 and "Success" in out
            self.check(f"install {p.name}", good, out.strip().splitlines()[-1] if out.strip() else f"rc={rc}")
            ok = ok and good
        info = self.dev.sh(f"dumpsys package {PKG} | grep -E 'versionName|versionCode' | head -n 2")
        self.check("installed version", None, " ".join(info.split()))
        return ok

    # ---- phase A ---------------------------------------------------------------------------
    def dismiss_chrome_fre(self):
        for _ in range(4):
            xml = self.dev.dump()
            hit = self.dev.tap_node(xml, lambda n: n.get("package") == self.browser_pkg and (
                any(n.get("resource-id", "").endswith(":id/" + i) for i in CHROME_FRE_IDS)
                or bool(CHROME_FRE_TEXT.match(n.get("text", "").strip()))))
            if not hit:
                return
            self.shot("browser-first-run", "browser first-run screen, dismissed", browser=True)
            time.sleep(3)

    def browser_url(self, xml: str | None) -> str:
        for n in nodes(xml):
            rid = n.get("resource-id", "")
            if rid.endswith(":id/url_bar") or rid.endswith(":id/title_bar") or rid.endswith(":id/url_bar_text"):
                if n.get("text"):
                    return n.get("text")
        return ""

    def phase_a(self, look: Look, system_screens: bool, launcher_screens: bool):
        d = self.dev
        d.sh(f"am force-stop {PKG}; pm clear {PKG}")
        if self.browser_pkg:
            d.sh(f"am force-stop {self.browser_pkg}")
        d.sh("logcat -c")
        d.key("KEYCODE_HOME")
        time.sleep(1)
        d.sh(f"am start -W -n {LAUNCHER}", timeout=60)
        d.wait_focus(lambda f: f.startswith(PKG), 10)
        self.shot("launch", "first frame of the launch")
        focus = d.wait_focus(lambda f: f.startswith(PKG) or (self.browser_pkg and f.startswith(self.browser_pkg)), 30)
        self.check(f"{look.name}: app launches", focus.startswith(PKG) or focus.startswith(self.browser_pkg or "-"),
                   f"focus after launch: {focus}")
        focus = d.wait_focus(lambda f: bool(self.browser_pkg) and f.startswith(self.browser_pkg), 45)
        opened = bool(self.browser_pkg) and focus.startswith(self.browser_pkg)
        url = ""
        if opened:
            time.sleep(6)
            self.dismiss_chrome_fre()
            s = self.shot("sign-in-browser", "browser opened by sign-in", browser=True)
            if s and s.xml:
                url = self.browser_url((self.out / s.xml).read_text(encoding="utf-8"))
        log = d.sh("logcat -d -v brief ActivityTaskManager:I '*:S'")
        starts = [ln.strip() for ln in log.splitlines() if "START" in ln and "dat=" in ln and HOST in ln]
        host_ok = HOST in url or bool(starts)
        evidence = f"browser focus={focus}; address bar={url!r}; activity start: {starts[0][:220] if starts else 'none'}"
        self.check(f"{look.name}: sign-in opens the browser at {HOST}", opened and host_ok, evidence,
                   hard=look == LOOKS[0])
        if not opened:
            self.shot("sign-in-no-browser", "sign-in did not open a browser")
        for _ in range(4):  # a first Back may only close a browser tip
            if d.focus().startswith(PKG):
                break
            d.key("KEYCODE_BACK")
            d.wait_focus(lambda f: f.startswith(PKG), 5)
        time.sleep(2)
        self.shot("sign-in-waiting", "waiting for the browser sign-in")
        if system_screens:
            screens = [
                ("app-info", f"am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:{PKG}"),
                ("add-account", "am start -a android.settings.ADD_ACCOUNT_SETTINGS"),
                ("notification-settings", "am start -a android.settings.APP_NOTIFICATION_SETTINGS "
                                          f"--es android.provider.extra.APP_PACKAGE {PKG}"),
                ("share-sheet", "am start -a android.intent.action.SEND -t text/plain "
                                "--es android.intent.extra.TEXT NoDrive-sweep"),
            ]
            for name, cmd in screens:
                d.sh(cmd)
                time.sleep(4)
                self.shot(name, "system screen")
                d.key("KEYCODE_BACK")
                d.key("KEYCODE_HOME")
                time.sleep(1)
        if launcher_screens:
            self.launcher_and_widgets()

    def launcher_and_widgets(self):
        d = self.dev
        size = re.search(r"(\d+)x(\d+)", d.sh("wm size"))
        w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
        d.key("KEYCODE_HOME")
        time.sleep(2)
        d.sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.2)} 400")
        time.sleep(3)
        self.shot("launcher-apps", "home screen app list")
        d.key("KEYCODE_HOME")
        time.sleep(2)
        d.sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
        time.sleep(2)
        xml = d.dump()
        if not d.tap_node(xml, lambda n: (n.get("text") or n.get("content-desc") or "").strip() == "Widgets"):
            self.check("widget picker", False, "no 'Widgets' entry after a long press on the home screen", hard=False)
            d.key("KEYCODE_HOME")
            return
        time.sleep(3)
        found = False
        for _ in range(12):
            xml = d.dump()
            if any(t == "NoDrive" for _, t in texts(xml)):
                found = d.tap_node(xml, lambda n: (n.get("text") or "") == "NoDrive")
                time.sleep(3)
                break
            d.sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.35)} 400")
            time.sleep(1)
        self.shot("widget-picker", "widget picker, NoDrive entry" if found else "widget picker")
        self.check("widget picker lists NoDrive", found, "entry 'NoDrive' found and opened" if found
                   else "entry 'NoDrive' not found", hard=False)
        d.key("KEYCODE_BACK")
        d.key("KEYCODE_HOME")

    # ---- phase B ---------------------------------------------------------------------------
    def stand_in_account(self) -> bool:
        d = self.dev
        d.sh("cmd connectivity airplane-mode enable; svc wifi disable; svc data disable")
        time.sleep(5)
        airplane = d.sh("settings get global airplane_mode_on").strip()
        self.check("airplane mode for the signed-in screens", airplane == "1", f"airplane_mode_on={airplane}")
        if airplane != "1":
            return False
        inst = d.sh("pm list instrumentation")
        m = re.search(r"instrumentation:(\S+/\S+) \(target=" + re.escape(PKG) + r"\)", inst)
        if not m:
            self.check("stand-in account", False, "no instrumentation for the app: " + inst.strip()[:200], hard=False)
            return False
        out = d.sh(f"am instrument -w -e class {SWEEP_TEST} -e nodriveSweep addAccount {m.group(1)}", timeout=180)
        ok = "OK (1 test)" in out
        accounts = d.sh("dumpsys account | grep -E 'Account \\{name=' | head -n 5")
        self.check("stand-in account (local only, airplane mode)", ok,
                   ("instrumentation OK; " if ok else out.strip()[-200:] + "; ") + " ".join(accounts.split()),
                   hard=False)
        d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
        return ok

    def phase_b(self, look: Look, system_accounts: bool):
        d = self.dev
        d.sh(f"am force-stop {PKG}")
        d.sh(f"am start -n {LAUNCHER}")
        d.wait_focus(lambda f: "FileDisplayActivity" in f, 30)
        time.sleep(5)
        s = self.shot("files", "file list (offline)")
        xml = (self.out / s.xml).read_text(encoding="utf-8") if s and s.xml else d.dump()
        for i in range(3):
            if not has_dialog(xml, d.focus()):
                break
            d.key("KEYCODE_BACK")
            time.sleep(2)
            s = self.shot(f"files-after-dialog-{i + 1}", "after closing the dialog with Back")
            xml = (self.out / s.xml).read_text(encoding="utf-8") if s and s.xml else d.dump()
        if d.tap_node(xml, lambda n: n.get("package") == PKG and n.get("resource-id", "").endswith(
                (":id/menu_button", ":id/toolbar_menu_button")) and n.get("visible-to-user", "true") == "true"):
            time.sleep(2)
            self.shot("drawer", "navigation drawer")
            d.key("KEYCODE_BACK")
        else:
            self.check(f"{look.name}: drawer", False, "drawer button not found", hard=False)
        for name, activity in (("settings", "SettingsActivity"), ("accounts", "ManageAccountsActivity"),
                               ("uploads", "UploadListActivity"), ("auto-upload", "SyncedFoldersActivity")):
            d.sh(f"am start -n {ACTIVITY}{activity}")
            time.sleep(4)
            s = self.shot(name)
            if name == "settings" and s:
                self.scroll_pages(name, s)
            d.key("KEYCODE_BACK")
            time.sleep(1)
        if not look.night:
            d.sh("am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT NoDrive-sweep "
                 f"-n {ACTIVITY}ReceiveExternalFilesActivity")
            time.sleep(4)
            self.shot("share-receive", "NoDrive as share target (upload screen)")
            d.key("KEYCODE_BACK")
            time.sleep(1)
        if system_accounts:
            d.key("KEYCODE_HOME")
            d.sh("am start -a android.settings.SYNC_SETTINGS")
            time.sleep(4)
            self.shot("system-accounts", "system Settings > accounts")
            d.key("KEYCODE_HOME")
        if look == LOOKS[0]:
            d.sh("am start -a android.settings.APP_NOTIFICATION_SETTINGS "
                 f"--es android.provider.extra.APP_PACKAGE {PKG}")
            time.sleep(4)
            self.shot("notification-channels", "notification categories after a signed-in start")
            d.key("KEYCODE_HOME")
            self.place_widget()

    def place_widget(self):
        """Best effort: drag the NoDrive widget onto the home screen to reach its configuration screen."""
        d = self.dev
        size = re.search(r"(\d+)x(\d+)", d.sh("wm size"))
        w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
        d.key("KEYCODE_HOME")
        time.sleep(2)
        d.sh(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2} 1500")
        time.sleep(2)
        if not d.tap_node(d.dump(), lambda n: (n.get("text") or n.get("content-desc") or "").strip() == "Widgets"):
            self.check("widget placed", False, "no 'Widgets' entry after a long press", hard=False)
            return
        time.sleep(3)
        header = None
        for _ in range(12):
            xml = d.dump()
            header = next((n for n in nodes(xml) if (n.get("text") or "") == "NoDrive"), None)
            if header is not None:
                break
            d.sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.35)} 400")
            time.sleep(1)
        if header is None:
            self.check("widget placed", False, "NoDrive not found in the widget picker", hard=False)
            d.key("KEYCODE_HOME")
            return
        d.tap(header)
        time.sleep(3)
        xml = d.dump()
        top = int(re.findall(r"\d+", header.get("bounds", "[0,0][0,0]"))[3])
        preview = next((n for n in nodes(xml) if n.get("long-clickable") == "true"
                        and int(re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))[1]) > top), None)
        if preview is None:
            self.check("widget placed", False, "no widget preview to drag", hard=False)
            d.key("KEYCODE_HOME")
            return
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", preview.get("bounds")))
        d.sh("logcat -c")
        d.sh(f"input draganddrop {(x1 + x2) // 2} {(y1 + y2) // 2} {w // 2} {int(h * 0.4)} 2500")
        time.sleep(5)
        self.shot("widget-drop", "after dropping the NoDrive widget on the home screen")
        log = d.sh("logcat -d -v brief AppWidgetServiceImpl:I Launcher:E '*:S'")
        events = [ln.split(": ", 1)[-1].strip()[:160] for ln in log.splitlines()
                  if "Bound widget" in ln or "deleteAppWidgetId" in ln or "not returned" in ln]
        kept = any("Bound widget" in e for e in events) and not any("deleteAppWidgetId" in e for e in events)
        self.check("widget placed", kept, "; ".join(events) or "no widget events in logcat", hard=False)
        d.key("KEYCODE_HOME")
        # The widget's own setup screen (normally opened by the launcher after the drop).
        d.sh(f"am start -n {PKG}/com.nextcloud.client.widget.DashboardWidgetConfigurationActivity "
             "--ei appWidgetId 1")
        time.sleep(4)
        self.shot("widget-configuration", "widget setup screen, opened directly (offline)")
        d.key("KEYCODE_BACK")
        d.key("KEYCODE_HOME")

    def scroll_pages(self, name: str, first: Screen):
        size = re.search(r"(\d+)x(\d+)", self.dev.sh("wm size"))
        w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
        last = [t for _, t in texts((self.out / first.xml).read_text(encoding="utf-8"))] if first.xml else []
        for page in range(2, 8):
            self.dev.sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.3)} 500")
            time.sleep(2)
            s = self.shot(f"{name}-{page}")
            if not s or not s.xml:
                return
            now = [t for _, t in texts((self.out / s.xml).read_text(encoding="utf-8"))]
            if now == last:
                (self.out / s.png).unlink(missing_ok=True)
                (self.out / s.xml).unlink(missing_ok=True)
                self.screens.remove(s)
                return
            last = now

    # ---- checks and report ---------------------------------------------------------------
    def colour_check(self):
        try:
            from PIL import Image
        except ImportError:
            self.check("Nextcloud blue on screens", None, "Pillow not installed, not checked")
            return
        for s in self.screens:
            with Image.open(self.out / s.png) as im:
                small = im.convert("RGB").resize((max(1, im.width // 4), max(1, im.height // 4)))
            raw = small.tobytes()
            total = len(raw) // 3
            blue = 0
            for k in range(0, len(raw), 3):
                r, g, b = raw[k], raw[k + 1], raw[k + 2]
                if b >= 0x90 and r <= BLUE_TOLERANCE and any(abs(r - c[0]) <= BLUE_TOLERANCE and abs(g - c[1]) <= BLUE_TOLERANCE
                                    and abs(b - c[2]) <= BLUE_TOLERANCE for c in NEXTCLOUD_BLUES):
                    blue += 1
            s.blue = blue / max(1, total)
        bad = [f"{s.png} {s.blue:.2%}" for s in self.screens if not s.browser and s.blue and s.blue > BLUE_LIMIT]
        worst = max((s.blue or 0 for s in self.screens if not s.browser), default=0)
        self.check("Nextcloud blue on screens", not bad, ", ".join(bad) if bad else
                   f"{len(self.screens)} screens, highest share of #0082C9/#00679E/#006AA3 pixels {worst:.3%}")

    def text_check(self):
        app = [f"{s.png}: {h}" for s in self.screens if not s.browser for h in s.text_hits]
        web = [f"{s.png}: {h}" for s in self.screens if s.browser for h in s.text_hits]
        dumped = sum(1 for s in self.screens if s.xml)
        self.check("no screen text names Nextcloud", not app, "; ".join(app[:20]) if app else
                   f"{dumped}/{len(self.screens)} screens dumped, 0 hits")
        self.check("browser pages naming Nextcloud (server side, listed only)", None, "; ".join(web[:10]) or "none")

    def contact_sheets(self):
        try:
            from PIL import Image, ImageDraw
        except ImportError:
            return
        sheets = self.out / "sheets"
        sheets.mkdir(exist_ok=True)
        tile_w, tile_h, label, cols, rows = 360, 800, 28, 3, 2
        per = cols * rows
        for i in range(0, len(self.screens), per):
            group = self.screens[i:i + per]
            sheet = Image.new("RGB", (cols * tile_w, rows * (tile_h + label)), (128, 128, 128))
            draw = ImageDraw.Draw(sheet)
            for j, s in enumerate(group):
                x, y = (j % cols) * tile_w, (j // cols) * (tile_h + label)
                with Image.open(self.out / s.png) as im:
                    thumb = im.convert("RGB")
                    thumb.thumbnail((tile_w - 4, tile_h - 4))
                sheet.paste(thumb, (x + 2, y + label))
                draw.text((x + 4, y + 6), s.png[:-4][:44], fill=(255, 255, 255))
            sheet.save(sheets / f"sheet-{i // per + 1:02d}.png", optimize=True)

    def report(self) -> int:
        fails = [c for c in self.checks if c["result"] == "FAIL"]
        lines = ["# NoDrive screen sweep\n", f"Screens: {len(self.screens)}. Looks: "
                 + ", ".join(lk.name for lk in LOOKS) + ".\n", "## Checks\n", "| Check | Result | Evidence |",
                 "|---|---|---|"]
        lines += [f"| {c['check']} | {c['result']} | {c['evidence'].replace('|', '/')} |" for c in self.checks]
        lines += ["\n## Screens\n", "| # | Look | Screen | File | Window | Text naming Nextcloud | NC blue |",
                  "|---|---|---|---|---|---|---|"]
        for s in self.screens:
            blue = "" if s.blue is None else f"{s.blue:.2%}"
            lines.append(f"| {s.number} | {s.look} | {s.name} | {s.png} | {s.focus} | "
                         f"{'; '.join(s.text_hits).replace('|', '/') or 'none'} | {blue} |")
        lines.append(f"\n## Result\n\n{'FAIL: ' + str(len(fails)) + ' check(s) failed' if fails else 'PASS'}\n")
        (self.out / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        (self.out / "checks.json").write_text(json.dumps(
            {"checks": self.checks, "screens": [s.__dict__ for s in self.screens]}, indent=1, ensure_ascii=False),
            encoding="utf-8")
        print(f"sweep: {len(self.screens)} screens, {len(fails)} failed check(s)")
        return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--test-apk", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--adb", default="adb")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    sweep = Sweep(Device(args.adb, args.out), args.out)
    try:
        sweep.prepare()
        if sweep.install(args.apk, args.test_apk):
            for look in LOOKS:
                sweep.set_look(look)
                sweep.phase_a(look, system_screens=not look.night, launcher_screens=look == LOOKS[0])
            if sweep.stand_in_account():
                for look in reversed(LOOKS):
                    sweep.set_look(look)
                    sweep.phase_b(look, system_accounts=not look.night and look.locale != "de-DE")
    except Exception as e:  # report what we have; the error itself is a failed check
        sweep.check("sweep ran to the end", False, f"{type(e).__name__}: {e}")
    sweep.text_check()
    sweep.colour_check()
    sweep.contact_sheets()
    return sweep.report()


if __name__ == "__main__":
    sys.exit(main())
