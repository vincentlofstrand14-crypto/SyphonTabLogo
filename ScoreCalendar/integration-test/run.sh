#!/usr/bin/env bash
# Runs ScoreCalendar on a real Paper 1.21.11 server and lets a bot play through it.
# Usage: bash run.sh   (expects ../target/ScoreCalendar.jar, java 21, node 18+, curl, jq)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
VERSION="1.21.11"
UA="ScoreCalendar-CI (github actions)"
rm -rf "$WORK"; mkdir -p "$WORK/plugins"
cd "$WORK"

echo "== Downloading Paper $VERSION"
URL=$(curl -fsSL -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$VERSION/builds/latest" | jq -r '.downloads."server:default".url' || true)
if [ -z "$URL" ] || [ "$URL" = "null" ]; then
  B=$(curl -fsSL -A "$UA" "https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds" | jq -r '.builds[-1].build')
  URL="https://api.papermc.io/v2/projects/paper/versions/$VERSION/builds/$B/downloads/paper-$VERSION-$B.jar"
fi
echo "   $URL"
curl -fsSL -A "$UA" -o paper.jar "$URL"
cp "$HERE/../target/ScoreCalendar.jar" plugins/
if curl -fsSL -o plugins/PlaceholderAPI.jar https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.11.6/PlaceholderAPI-2.11.6.jar; then
  echo "   PlaceholderAPI downloaded"
else
  rm -f plugins/PlaceholderAPI.jar; echo "   PlaceholderAPI not available, PAPI checks are skipped"
fi

echo "eula=true" > eula.txt
cat > server.properties <<PROPS
online-mode=false
enforce-secure-profile=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
spawn-monsters=false
spawn-animals=false
difficulty=peaceful
gamemode=survival
motd=ScoreCalendar CI
PROPS
OPUUID=$(python3 -c "
import hashlib,uuid
h=bytearray(hashlib.md5(b'OfflinePlayer:ScoreBot').digest()); h[6]=h[6]&0x0f|0x30; h[8]=h[8]&0x3f|0x80
print(uuid.UUID(bytes=bytes(h)))")
echo "[{\"uuid\":\"$OPUUID\",\"name\":\"ScoreBot\",\"level\":4,\"bypassesPlayerLimit\":false}]" > ops.json

(cd "$HERE" && npm install --silent --no-audit --no-fund)

start_server() {
  local log="$1"
  rm -f console.in; mkfifo console.in
  java -Xms1G -Xmx2G -jar paper.jar --nogui < console.in > "$log" 2>&1 &
  SERVER_PID=$!
  exec 3>console.in
  for i in $(seq 1 300); do
    if grep -q "Done (" "$log"; then echo "   server ready after ${i}s"; return 0; fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then echo "server died"; tail -100 "$log"; exit 1; fi
    sleep 1
  done
  echo "server did not start"; tail -100 "$log"; exit 1
}
send() { echo "$1" >&3; sleep 1; }
stop_server() {
  send "stop"
  for i in $(seq 1 60); do kill -0 $SERVER_PID 2>/dev/null || break; sleep 1; done
  exec 3>&-
}

FAIL=0
check_log() { # pattern description
  if grep -qE "$1" "$3"; then echo "PASS  $2"; else echo "FAIL  $2"; FAIL=1; fi
}

echo "== Phase 1: first start"
start_server server1.log
check_log "\[ScoreCalendar\] Loaded 5 calendar\(s\).*\(0 error\(s\), 0 warning\(s\)\)" "5 example calendars load without errors or warnings" server1.log
send "calendar admin reload"
send "calendar info christmas"
send "calendar admin create consoletest 14 WEEKLY"
send "calendar admin delete consoletest"
send "calendar admin delete consoletest confirm"
check_log "Configurations successfully reloaded" "console reload" server1.log
check_log "Created calendar consoletest" "console create" server1.log
check_log "Deleted calendar consoletest" "console delete with confirmation" server1.log
node "$HERE/bot.js" phase1 || FAIL=1
stop_server

echo "== Phase 2: restart (persistence) + broken config"
cp "$HERE/broken.yml" plugins/ScoreCalendar/calendars/broken.yml
start_server server2.log
node "$HERE/bot.js" phase2 || FAIL=1
send "calendar admin problems"
stop_server
check_log "calendars/broken.yml\] unlock.timezone: 'Mars/Olympus' is not a valid timezone" "invalid timezone reported" server2.log
check_log "calendars/broken.yml\] gui.size: size 50 is invalid" "invalid GUI size reported" server2.log
check_log "calendars/broken.yml\].*NOT_A_MATERIAL' is not a valid Minecraft material" "invalid material reported" server2.log
check_log "calendars/broken.yml\].*unknown action type 'FLY_TO_THE_MOON'" "invalid action reported" server2.log
check_log "calendars/broken.yml\].*OPEN_MENU:does-not-exist refers to a menu that does not exist" "missing menu reported" server2.log
check_log "calendars/broken.yml\] rewards.day-9: 'day-9' is not a day between 1 and 5" "reward for a non-existent day reported" server2.log
check_log "calendars/broken.yml\].*unknown sound 'NOT_A_SOUND'" "invalid sound reported" server2.log
check_log "calendars/broken.yml\].*unknown particle 'NOT_A_PARTICLE'" "invalid particle reported" server2.log
check_log "\[ScoreCalendar\] Loaded 6 calendar\(s\)" "broken calendar still loads with safe defaults" server2.log

echo "== Plugin errors in server logs"
if grep -nE "(Exception|Error)" server1.log server2.log | grep -iE "scorecalendar|net\.scoresmp" | grep -v "SEVERE\]: \[ScoreCalendar\] \[calendars/broken.yml\]" ; then
  echo "FAIL  exceptions from ScoreCalendar found above"; FAIL=1
else
  echo "PASS  no exceptions from ScoreCalendar"
fi
echo "== ScoreCalendar log lines"
grep -h "ScoreCalendar" server1.log server2.log | head -150 || true
exit $FAIL
