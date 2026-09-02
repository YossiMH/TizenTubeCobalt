#!/data/data/com.termux/files/usr/bin/python
import fcntl
import json
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

from pyluach import dates

BASE = Path.home() / "morning-sesame"
STATE = BASE / "state.json"
LOG = BASE / "run.log"
LOCK = BASE / "run.lock"
CHANNEL = "https://www.youtube.com/@SesameStreetClassics/videos"
TIZENSUB_PACKAGE = "io.gh.yossim.tizentube.cobalt"
WAKE_HELPER_COMPONENT = "dev.yossi.morningsesame/.WakeAndPlayActivity"
MIN_SECONDS = 30 * 60

def log(message):
    BASE.mkdir(parents=True, exist_ok=True)
    line = f"{datetime.now().isoformat(timespec='seconds')} {message}\n"
    with LOG.open("a", encoding="utf-8") as handle:
        handle.write(line)
    print(line, end="")

def is_yomtov(day):
    hebrew = dates.GregorianDate(day.year, day.month, day.day).to_heb()
    return (hebrew.month, hebrew.day) in {
        (7, 1), (7, 2),       # Rosh Hashanah
        (7, 10),              # Yom Kippur
        (7, 15), (7, 16),      # Sukkot I-II
        (7, 22), (7, 23),      # Shemini Atzeret / Simchat Torah
        (1, 15), (1, 16),      # Pesach I-II
        (1, 21), (1, 22),      # Pesach VII-VIII
        (3, 6), (3, 7),        # Shavuot I-II
    }

def load_state():
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except Exception:
        return {"played_ids": [], "last_run_date": None}

def save_state(state):
    STATE.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

def candidates():
    command = [
        "yt-dlp",
        "--flat-playlist",
        "--playlist-end", "40",
        "--dump-single-json",
        CHANNEL,
    ]
    data = json.loads(subprocess.check_output(command, text=True, timeout=60))
    result = []
    for entry in data.get("entries", []):
        duration = entry.get("duration") or 0
        if duration > MIN_SECONDS:
            result.append({
                "id": entry["id"],
                "title": entry.get("title", ""),
                "duration": duration,
            })
    return result

def launch(video_id):
    url = f"https://www.youtube.com/watch?v={video_id}"
    # Only the dedicated local wake helper is invoked. It wakes the Onn and then
    # launches this URL explicitly in TizenSub+. There is intentionally no browser fallback.
    command = [
        "am", "start", "-W",
        "-n", WAKE_HELPER_COMPONENT,
        "-a", "android.intent.action.VIEW",
        "-d", url,
        "--es", "target_package", TIZENSUB_PACKAGE,
    ]
    proc = subprocess.run(command, capture_output=True, text=True, timeout=20)
    log(f"wake-helper rc={proc.returncode} stdout={proc.stdout.strip()!r} stderr={proc.stderr.strip()!r}")
    if proc.returncode != 0:
        return False

    # Give the helper time to wake the display and hand the URL to TizenSub+.
    time.sleep(4)
    proc = subprocess.run(
        ["pidof", TIZENSUB_PACKAGE],
        capture_output=True, text=True, timeout=5,
    )
    running = proc.returncode == 0 and bool(proc.stdout.strip())
    log(f"tizensub running={running} pid={proc.stdout.strip()!r}")
    return running

def should_run_now(now):
    day = now.date()
    if day.weekday() == 5:  # Saturday
        log("skip Saturday")
        return False
    if is_yomtov(day):
        log("skip Yom Tov")
        return False
    # Cron is exact at 07:00. Keep a short window so a slightly delayed wake still works,
    # while stray/manual invocations outside the morning window are harmless.
    if now.hour != 7 or now.minute > 9:
        return False
    return True

def main(force=False):
    BASE.mkdir(parents=True, exist_ok=True)
    with LOCK.open("a+") as lock_handle:
        fcntl.flock(lock_handle, fcntl.LOCK_EX)
        now = datetime.now()
        state = load_state()

        if not force:
            if not should_run_now(now):
                return
            if state.get("last_run_date") == now.date().isoformat():
                return

        choices = candidates()
        if not choices:
            log("no qualifying >30m videos found")
            return

        played = set(state.get("played_ids", []))
        # Signed-in YouTube watch-progress is not exposed to the Termux process.
        # The fully local fallback is therefore the newest qualifying video this
        # routine has not already played.
        chosen = next((item for item in choices if item["id"] not in played), choices[0])
        log(f"selected {chosen['id']} {chosen['duration']}s {chosen['title']}")

        if launch(chosen["id"]):
            state["last_run_date"] = now.date().isoformat()
            state["played_ids"] = (state.get("played_ids", []) + [chosen["id"]])[-200:]
            state["last_video"] = chosen
            save_state(state)

if __name__ == "__main__":
    main("--force" in sys.argv)
