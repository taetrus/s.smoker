# WP-03 Windows test

Date: 2026-11-18 and 2026-11-19

## Environment

- OS: Windows 11 Pro.
- Shell: PowerShell 5.1.
- Build: Maven 3.9.6, JDK 25.
- Test JVM for build 3: JRE 8u341 (`C:\Program Files\Java\jdk1.8.0_341`).
- Application: the demo application (`smoker-demo`). No product with the hardware simulator was available.

## Builds

| Commit | Build | Result |
|---|---|---|
| `bb407cf` (WP-03 merged) | `mvn -B verify` | core 64, agent 152, cli 22, IT 5, BUILD SUCCESS |
| `bb407cf` | `mvn -B verify -Plocale-tr` | same counts, BUILD SUCCESS |
| `bb407cf` | `mvn -B verify -Plocale-tr "-Djvm=C:\Program Files\Java\jdk1.8.0_341\bin\java.exe"` | same counts, BUILD SUCCESS. The failsafe report gives `java.version` 1.8.0_341 and tr / TR. |
| `03bca7a` (PR #6) | the same three builds | core 64, agent 153, cli 22, IT 5, BUILD SUCCESS |
| `228fa4b` (PR #7) | the same three builds | core 64, agent 154, cli 22, IT 5, BUILD SUCCESS |

`SelfTestIT` includes the WP-03 completion criterion (R-23a): `wait.json` passes, and `wait-timeout.json` gives FAIL with the last value `waiting…`.

## Walkthrough of the recorder button `Wait for…`

Procedure: record the demo application, click Query, wait for `ready 42`, click `Wait for…`, click the unnamed button,
click the answer label, change the regex to `ready \d+`, click OK, click Save. Then replay the scenario.

The first walkthrough could not prove the toolbar checks: the record log had only `paused` and `recording`.
PR #6 makes the toolbar write what it shows to the agent log. The second walkthrough used it.

| Check | Result |
|---|---|
| The Record button is disabled in select mode | Pass. Log: `Record button disabled`, then `Record button enabled`. |
| A click on an unnamed component is refused | Pass. Log: `status: needs a name: [Smoker Demo] JButton('I have no name') (give it a name with setName)`. The button did not react. |
| The dialog gives the current value as the regex | Pass. Log: `Wait for: dialog for #demo.answer, attribute text, regex ready 42`. |
| The step before the `WAIT_FOR` has no snapshot | Pass. Log: `step list: s001  CLICK #demo.query  (no snapshot: WAIT_FOR follows)`. |
| The `WAIT_FOR` step gets its snapshot | Pass. The golden file has only s002. |
| Replay without `approve` | PASS: startup 791 ms, s001 520 ms, s002 3048 ms. |

## Problems found

| Problem | Fix |
|---|---|
| The toolbar did not write its state to the log. A walkthrough could not be checked afterwards. | PR #6 (`03bca7a`). |
| The picker threw `IllegalArgumentException: JViewport.setBorder() not supported` over a scroll pane. There was no highlight, and the picker text kept the previous component. | PR #7 (`228fa4b`). On Windows the picker now shows `⚠ unnamed  [Smoker Demo] JViewport   [JViewport]` with no border and no exception. |
| The record log is in Cp1252, not UTF-8. The `…` of `(snapshot pending…)` was the byte 0x85. | The record log is the console output of the application, in its console charset. The `[smoker]` texts of the agent are now ASCII, and `README.md` describes the charset (PR #8). |

## Not tested

- F-02 on a product with the hardware simulator.
- JRE 11 and JRE 17 at run time on a target application.
