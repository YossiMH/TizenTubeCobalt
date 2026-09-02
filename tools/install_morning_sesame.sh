#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

BASE="$HOME/morning-sesame"
SCRIPT_SOURCE="${1:-$PWD/tools/morning_sesame.py}"
mkdir -p "$BASE"

if ! command -v crond >/dev/null 2>&1; then
  pkg install -y cronie
fi
python -m pip install --upgrade yt-dlp pyluach

cp "$SCRIPT_SOURCE" "$BASE/run.py"
chmod 700 "$BASE/run.py"

cat > "$BASE/job.sh" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
exec /data/data/com.termux/files/usr/bin/python /data/data/com.termux/files/home/morning-sesame/run.py
SH
chmod 700 "$BASE/job.sh"

current="$(crontab -l 2>/dev/null || true)"
filtered="$(printf '%s\n' "$current" | grep -v '/morning-sesame/job.sh' || true)"
{
  printf '%s\n' "$filtered"
  printf '%s\n' '0 7 * * 0-5 /data/data/com.termux/files/home/morning-sesame/job.sh'
} | sed '/^[[:space:]]*$/d' | crontab -

mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/00-morning-sesame-crond" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
pgrep -x crond >/dev/null 2>&1 || crond
SH
chmod 700 "$HOME/.termux/boot/00-morning-sesame-crond"

pgrep -x crond >/dev/null 2>&1 || crond

echo "Installed morning Sesame routine:"
crontab -l
pgrep -a crond || true
