#!/usr/bin/env bash
# End-to-end self-test of s.smoker against the demo app.
# Linux/macOS: ./selftest.sh      (Linux without a display: run under `xvfb-run -a ./selftest.sh`)
# Expected: run1 NO_GOLDEN (exit 0) → approve → run2 PASS → run3 FAIL after the app is "broken" → failing scenarios FAIL/HANG.
set -u
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
OUT=${OUT:-/tmp/smoker-selftest}
rm -rf "$OUT"; mkdir -p "$OUT"
CLI="java -jar smoker-cli/target/smoker-cli.jar"
AGENT=smoker-agent/target/smoker-agent.jar
APP="java -Ddemo.log=$OUT/demo.log -jar smoker-demo/target/smoker-demo.jar"
SC=smoker-demo/scenarios/greet.json
cp "$SC" "$OUT/greet.orig.json"

echo "##### run1 — no goldens yet"
$CLI run --agent $AGENT --app "$APP" --scenarios "$SC" --out "$OUT/run1" --log "$OUT/demo.log" | grep -v '^\s*\$'
echo "exit=${PIPESTATUS[0]} (expected 0, NO_GOLDEN)"

echo "##### approve"
$CLI approve --run "$OUT/run1/greet" --scenario "$SC"

echo "##### run2 — against goldens"
$CLI run --agent $AGENT --app "$APP" --scenarios "$SC" --out "$OUT/run2" --log "$OUT/demo.log" | grep -v '^\s*\$'
echo "exit=${PIPESTATUS[0]} (expected 0, PASS)"

echo "##### run3 — app 'changed': greeting language differs (scenario value edited to simulate a regression)"
sed 's/"value": "tr"/"value": "de"/' "$SC" > "$OUT/greet-regressed.json"
$CLI run --agent $AGENT --app "$APP" --scenarios "$OUT/greet-regressed.json" --out "$OUT/run3" --log "$OUT/demo.log" | grep -v '^\s*\$'
echo "exit=${PIPESTATUS[0]} (expected 1, FAIL with CHANGED #demo.lang / #demo.result diffs)"

echo "##### failing scenarios — log hit, uncaught EDT exception, EDT hang"
$CLI run --agent $AGENT --app "$APP" --scenarios smoker-demo/scenarios-failing --out "$OUT/run4" --log "$OUT/demo.log" | grep -v '^\s*\$'
echo "exit=${PIPESTATUS[0]} (expected 1: empty-name FAIL on log hits, hang HANG with thread dump)"

cp "$OUT/greet.orig.json" "$SC"   # leave the repo scenario without goldens
echo; echo "reports: $OUT/run*/report.html  junit: $OUT/run*/junit.xml"
