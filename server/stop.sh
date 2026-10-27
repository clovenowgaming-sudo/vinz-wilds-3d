#!/data/data/com.termux/files/usr/bin/sh
# Stop the Vinz Wilds server (only processes running server.js from this folder).
cd "$(dirname "$0")" || exit 1
DIR="$(pwd)"
FOUND=0
for d in /proc/[0-9]*; do
  pid="${d#/proc/}"
  [ "$pid" = "$$" ] && continue
  if [ "$(readlink "$d/cwd" 2>/dev/null)" = "$DIR" ] && tr '\0' ' ' < "$d/cmdline" 2>/dev/null | grep -q 'server.js'; then
    kill "$pid" 2>/dev/null && echo "stopped pid $pid" && FOUND=1
  fi
done
rm -f server.pid
[ "$FOUND" = "0" ] && echo "no running server found for this folder"
