# s.smoker — Swing smoke tester

Record a short scenario on a Swing app, replay it on every build, and fail when the UI state, the
application log, or the EDT misbehaves. Attaches with `-javaagent`, no app code changes; JRE 8+.

```
smoker-core   model (Scenario/Step/Action/Locator/Snapshot), diff, JSON, JUnit-XML + HTML reports — no Swing
smoker-agent  -javaagent: recorder toolbar, semantic player, snapshot taker, log tailer, EDT watchdog   → smoker-agent.jar (shaded)
smoker-cli    launches the app with the agent, approves goldens, writes reports                           → smoker-cli.jar   (shaded)
smoker-demo   tiny Swing app + scenarios for the self-test
smoker-it     integration test: CLI + agent + demo app, full record-free cycle
```

## Build

```bash
mvn -B package                 # JDK 8–21; on JDK 9+ compiles with --release 8; runs the unit tests
mvn -B verify                  # also runs the integration test in smoker-it (needs a display)
mvn -B verify -Plocale-tr      # all tests in the Turkish locale (tr_TR)
xvfb-run -a mvn -B verify      # Linux without a display
```

The integration test runs `run`, `approve`, `run`, `run` on `smoker-demo/scenarios` and `run` on
`smoker-demo/scenarios-failing`. It expects NO_GOLDEN, PASS, FAIL with `CHANGED …#demo.lang`, FAIL on a log hit and HANG.
It copies the scenarios to `smoker-it/target/it-work` and does not change the files in the repository.
The CLI and the demo application use the JVM and the locale of the test.
Without a display the integration test fails, and `LocatorsWindowTest` is skipped.

Windows (PowerShell): `mvn -B verify` then run the commands below; no Xvfb needed on a logged-in desktop.

## Use

```bash
# 1. record (floating toolbar appears over the app: Record / Pick / Rename / Delete / Save)
java -jar smoker-cli.jar record --agent smoker-agent.jar --app "java -jar myapp.jar" --scenario scenarios/orders-create.json

# 2. run — first run has no goldens → NO_GOLDEN (exit 0 unless --strict)
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "java -jar myapp.jar" --scenarios scenarios/ --out out/ --log logs/app.log

# 3. approve the observed state as golden (per scenario, or --steps s003,s004)
java -jar smoker-cli.jar approve --run out/orders-create --scenario scenarios/orders-create.json

# 4. run again → PASS / FAIL; out/junit.xml + out/report.html
```

`--app` is the full command line. The agent option is inserted after the first word, or where you put the
token `{agent}`. Equinox product: `--app "myapp.exe -vmargs {agent}"` — or add
`-javaagent:C:\tools\smoker-agent.jar=mode=play;scenario=...;out=...;log=...` to `eclipse.ini` under `-vmargs`.

Windows: if a path in `--app` contains spaces, put the path in double quotes and write each inner quote as `\"`.
If you do not, Windows splits the `--app` value into many arguments.

```powershell
# Windows PowerShell 5.1: enclose the --app value in single quotes
java -jar smoker-cli.jar run --agent smoker-agent.jar --app '\"C:\Program Files\Java\bin\java.exe\" -jar myapp.jar' --scenarios scenarios --out out
```

```bat
rem cmd.exe: enclose the --app value in double quotes
java -jar smoker-cli.jar run --agent smoker-agent.jar --app "\"C:\Program Files\Java\bin\java.exe\" -jar myapp.jar" --scenarios scenarios --out out
```

A path without spaces needs no quotes. This is the easier procedure.

### Agent arguments (`-javaagent:smoker-agent.jar=k=v;k=v`)

| key | values | default |
|---|---|---|
| `mode` | `play` / `record` | play |
| `scenario` | scenario JSON (play: input, record: output) | required |
| `out` | run directory (run-result.json, screenshots) | smoker-out |
| `log` | comma-separated log files to tail (Logback/Log4j output) | — |
| `failRegex` | regex; a matching log line fails the step | `\bERROR\b|\bSEVERE\b|Exception|\bError\b` |
| `allowRegex` | regex; matching lines are ignored | — |
| `screenshots` | `evidence` / `off` | evidence |
| `edtHangMs` | EDT blocked longer than this → HANG + thread dump | 5000 |
| `exitWhenDone` | play: `System.exit` after writing results | true |

### Scenario file

```json
{
  "name": "orders-create",
  "startupTimeoutMs": 60000, "quietMs": 400, "stepTimeoutMs": 15000,
  "ignore": { "paths": ["#statusbar\\.clock"], "attributes": [], "valuePatterns": ["\\d{2}:\\d{2}:\\d{2}"] },
  "steps": [
    { "id": "s001", "name": "open orders", "action": { "type": "CLICK",    "locator": { "name": "nav.orders" } } },
    { "id": "s002", "name": "customer",    "action": { "type": "SET_TEXT", "locator": { "name": "orders.customer" }, "value": "ACME" } },
    { "id": "s003", "name": "priority",    "action": { "type": "SELECT",   "locator": { "name": "orders.priority" }, "value": "High" } },
    { "id": "s004", "name": "save",        "action": { "type": "KEY",      "value": "ENTER", "modifiers": 128 } },
    { "id": "s005", "name": "close",       "action": { "type": "CLOSE_WINDOW", "locator": { "windowTitle": "New order" } } }
  ]
}
```

Actions: `CLICK`, `SET_TEXT`, `SELECT` (combo/list/tab by text or index, table by row), `KEY` (VK name, `modifiers` =
`InputEvent.*_DOWN_MASK`), `WAIT` (ms), `CLOSE_WINDOW`. `expected` is filled by `approve` (or by the recorder).

Password fields (`JPasswordField`): the snapshot gives the text `***` when the field has a value, and `""` when it is empty.
The recorder does not record typing in a password field. If a scenario must fill one, add the `SET_TEXT` step by hand.
Do not put a real password in a scenario file.

### Locators

`{"name": "orders.save"}` — from `component.setName("orders.save")`, the only stable key. Fallback
`{"windowTitle": "Orders", "className": "JButton", "text": "Save", "index": 0}`. Unnamed interactive components are
listed in the report ("locator lint"). Convention: `<view>.<widget>`.

### Statuses

`PASS` · `FAIL` (snapshot diff or log hit) · `HANG` (EDT blocked / action timed out, thread dump attached) ·
`ERROR` (component not found, exception thrown by the action) · `NO_GOLDEN` (nothing to compare yet).
A scenario stops at its first non-PASS step; the next scenario starts a fresh JVM.

## CI

See `.gitlab-ci.yml`. Linux runners: `xvfb-run -a`. Windows runners: the GitLab runner must run as a
logged-in-user process (not a service in Session 0) for screenshots; semantic replay itself works without focus.
