#!/data/data/com.termux/files/usr/bin/sh
# Start (or restart) the Vinz Wilds server in the background.
# Stops every node process whose cwd is THIS folder and that runs server.js
# (including a foreground "npm start"), but never touches other node processes
# such as your MCP server in ~/termux-mcp.
cd "$(dirname "$0")" || exit 1
DIR="$(pwd)"
for d in /proc/[0-9]*; do
  pid="${d#/proc/}"
  [ "$pid" = "$$" ] && continue
  if [ "$(readlink "$d/cwd" 2>/dev/null)" = "$DIR" ] && tr '\0' ' ' < "$d/cmdline" 2>/dev/null | grep -q 'server.js'; then
    echo "stopping old server (pid $pid)"
    kill "$pid" 2>/dev/null
  fi
done
sleep 1
nohup node server.js >> server.log 2>&1 &
echo $! > server.pid
sleep 1.5
echo "server started, pid $(cat server.pid)"
tail -n 3 server.log
