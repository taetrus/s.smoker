# WP-09 follow-up Windows test

Date: 2026-11-23

Procedure: `docs/wp-09-windows-check.md`. Script: `docs/wp-09-windows-check.ps1`.

All six checks passed. The lint of the record session has 14 components, and all 14 have a proposal (100 %).
The lint table is the same as the macOS table of 2026-11-15.
The record session ran four times. The fourth session gave the results (section "Record sessions").

## Environment

- OS: Windows 11 Pro 10.0.26300.
- Shell: PowerShell 5.1.26100.10068.
- Build: Maven 3.9.6, JDK 25.0.1 (Eclipse Adoptium). `mvn -B package -DskipTests`: BUILD SUCCESS.
- JRE 8: `C:\Program Files\Java\jdk1.8.0_341\bin\java.exe`, 1.8.0_341.
- Commit: `5daf8df` (`master`, with PR #17, PR #18 and PR #19).
- Application: p2.product of `p.osgi.ds`, commit `b9dd341`
  (`D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64`).
- The owner used the desktop session through Chrome Remote Desktop.
- The environment variable `J2D_D3D=false` was set for record sessions 3 and 4 and for the checks (section "Record sessions").
  The script and the `--app` command did not change.

## Results

| Check | Item | Expected result | Actual result | Result |
|---|---|---|---|---|
| N-1 | 12 | `record` creates the folder `C:\smoke\names`. No stack trace. `record exit=0`. | `folder=True recordLog=True exceptionLines=0 recordExit=0`. | Pass |
| N-2 | 8 | 2 saves. The first save has `(0 steps, 0 snapshots)` and 8 components. `walk.lint.json` exists. `record` prints the lint file and the summary line. | `saves=2`. `firstSaveZeroSteps=True firstSaveComponents=8 lintFile=True recordPrintsLintFile=1`. The record summary has the same numbers as the N-3 line. | Pass |
| N-3 | 8, 10, 11 | `lint --run C:\smoke\names` gives exit code 0 and the table. At least 80 % of the components have a proposal. Expected: 14 components, 14 proposals, windows `[OSGi Chatbot]` and `[Settings]`. | `lint exit=0 components=14 proposals=14 percent=100 fields=11 text=3 labels=0 automatic=11`. Windows: `[OSGi Chatbot], [Settings]`. | Pass |
| N-4 | 8 | The patch has 11 `setName` lines, 3 "has no field" comments, 0 superclass comments. | `patch setNameLines=11 noFieldComments=3 superclassComments=0`. | Pass |
| N-5 | 8 | `run --scenarios C:\smoke\names` runs 1 scenario. `walk.lint.json` is not read as a scenario. | `run exit=0 line='1 scenario(s), 0 failed' walkStatus=PASS`. | Pass |
| N-6 | 8 | The lint of the run has the same proposals as the lint of the record session. | `recordEntries=14 runEntries=14 differences=0`. | Pass |

Other lines of `wp09-summary.txt`:

- `walk.json: steps=2`: `s001 CLICK [OSGi Chatbot] JButton('Settings')`, `s002 CLICK [Settings] JButton('Cancel')`.
- `recordLog smokerLinesNonAscii=0`: the `[smoker]` lines of the record log are ASCII (PR #8).

Files:

- Record session: `C:\smoke\names`. Console output: `C:\smoke\out\wp09-record.txt`.
- Lint: `C:\smoke\out\wp09-lint-record.txt`. Patch: `C:\smoke\out\wp09-names.patch`.
- Run: `C:\smoke\out\wp09-run`. Console output: `C:\smoke\out\wp09-run.txt`.
- Summary: `C:\smoke\out\wp09-summary.txt`.

## Lint table

```
window         | class          | field               | type           | text            | label | automatic name                 | proposed name          | source
[OSGi Chatbot] | ChatFrame      | refreshModelsButton | JButton        | Refresh Models  | -     | @ChatFrame.refreshModelsButton | chat.btn.refreshModels | field
[OSGi Chatbot] | ChatFrame      | setDefaultButton    | JButton        | Set Default     | -     | @ChatFrame.setDefaultButton    | chat.btn.setDefault    | field
[OSGi Chatbot] | ChatFrame      | -                   | JButton        | Settings        | -     | -                              | chat.btn.settings      | text
[OSGi Chatbot] | ChatFrame      | modelCombo          | JComboBox      | -               | -     | @ChatFrame.modelCombo          | chat.cmb.model         | field
[OSGi Chatbot] | ChatPanel      | textPane            | JTextPane      | -               | -     | @ChatPanel.textPane            | chat.txt.text          | field
[OSGi Chatbot] | InputPanel     | clearButton         | JButton        | Clear           | -     | @InputPanel.clearButton        | input.btn.clear        | field
[OSGi Chatbot] | InputPanel     | sendButton          | JButton        | Send            | -     | @InputPanel.sendButton         | input.btn.send         | field
[OSGi Chatbot] | InputPanel     | textArea            | JTextArea      | -               | -     | @InputPanel.textArea           | input.txt.text         | field
[Settings]     | SettingsDialog | -                   | JButton        | Cancel          | -     | -                              | settings.btn.cancel    | text
[Settings]     | SettingsDialog | -                   | JButton        | Save            | -     | -                              | settings.btn.save      | text
[Settings]     | SettingsDialog | testButton          | JButton        | Test Connection | -     | @SettingsDialog.testButton     | settings.btn.test      | field
[Settings]     | SettingsDialog | apiKeyField         | JPasswordField | -               | -     | @SettingsDialog.apiKeyField    | settings.pwd.apiKey    | field
[Settings]     | SettingsDialog | baseUrlField        | JTextField     | -               | -     | @SettingsDialog.baseUrlField   | settings.txt.baseUrl   | field
[Settings]     | SettingsDialog | modelField          | JTextField     | -               | -     | @SettingsDialog.modelField     | settings.txt.model     | field
```

Summary line:

```
14 unnamed interactive component(s), 14 with a proposed name (100 %: 11 from fields, 3 from text, 0 from labels), 11 with an automatic name
```

Patch (`C:\smoke\out\wp09-names.patch`):

```
// com.kk.pde.ds.chatbot.ChatFrame
modelCombo.setName("chat.cmb.model");
refreshModelsButton.setName("chat.btn.refreshModels");
setDefaultButton.setName("chat.btn.setDefault");
// JButton "Settings" has no field: setName("chat.btn.settings") where it is created

// com.kk.pde.ds.chatbot.ChatPanel
textPane.setName("chat.txt.text");

// com.kk.pde.ds.chatbot.InputPanel
clearButton.setName("input.btn.clear");
sendButton.setName("input.btn.send");
textArea.setName("input.txt.text");

// com.kk.pde.ds.chatbot.SettingsDialog
apiKeyField.setName("settings.pwd.apiKey");
baseUrlField.setName("settings.txt.baseUrl");
modelField.setName("settings.txt.model");
testButton.setName("settings.btn.test");
// JButton "Cancel" has no field: setName("settings.btn.cancel") where it is created
// JButton "Save" has no field: setName("settings.btn.save") where it is created
```

## Comparison with the macOS table

Reference: the lint table in `docs/wp-09-pilot-report.md` (macOS, 2026-11-15). Both measurements used p.osgi.ds commit `b9dd341`.

| Window | Component | Proposed name | Source | Same as macOS |
|---|---|---|---|---|
| `[OSGi Chatbot]` | `ChatFrame.refreshModelsButton` | `chat.btn.refreshModels` | field | Yes |
| `[OSGi Chatbot]` | `ChatFrame.setDefaultButton` | `chat.btn.setDefault` | field | Yes |
| `[OSGi Chatbot]` | `ChatFrame`, JButton `Settings` | `chat.btn.settings` | text | Yes |
| `[OSGi Chatbot]` | `ChatFrame.modelCombo` | `chat.cmb.model` | field | Yes |
| `[OSGi Chatbot]` | `ChatPanel.textPane` | `chat.txt.text` | field | Yes |
| `[OSGi Chatbot]` | `InputPanel.clearButton` | `input.btn.clear` | field | Yes |
| `[OSGi Chatbot]` | `InputPanel.sendButton` | `input.btn.send` | field | Yes |
| `[OSGi Chatbot]` | `InputPanel.textArea` | `input.txt.text` | field | Yes |
| `[Settings]` | `SettingsDialog`, JButton `Cancel` | `settings.btn.cancel` | text | Yes |
| `[Settings]` | `SettingsDialog`, JButton `Save` | `settings.btn.save` | text | Yes |
| `[Settings]` | `SettingsDialog.testButton` | `settings.btn.test` | field | Yes |
| `[Settings]` | `SettingsDialog.apiKeyField` | `settings.pwd.apiKey` | field | Yes |
| `[Settings]` | `SettingsDialog.baseUrlField` | `settings.txt.baseUrl` | field | Yes |
| `[Settings]` | `SettingsDialog.modelField` | `settings.txt.model` | field | Yes |

All 14 rows have the same proposed name and the same source. The patch is the same as the macOS patch.

The only differences are in the format of PR #18: the table has a `label` column, and the summary line counts `from labels`.
The `label` column is `-` in each row, and the summary line has `0 from labels`.
The rule of PR #18 for `ChatPanel.textPane` and `InputPanel.textArea` gave no change: the proposals stay `chat.txt.text`
and `input.txt.text` with the source `field`.

## Save lines of the record log

```
[smoker] saved C:\smoke\names\walk.json (0 steps, 0 snapshots); 8 component(s) without a real name in C:\smoke\names\walk.lint.json
[smoker] saved C:\smoke\names\walk.json (2 steps, 2 snapshots); 14 component(s) without a real name in C:\smoke\names\walk.lint.json
```

All `[smoker]` lines of the record log (`C:\smoke\names\walk.record.log`):

```
[smoker] agent started, mode=RECORD, scenario=C:\smoke\names\walk.json
[smoker] saved C:\smoke\names\walk.json (0 steps, 0 snapshots); 8 component(s) without a real name in C:\smoke\names\walk.lint.json
[smoker] recording
[smoker] step list: s001  CLICK [OSGi Chatbot] JButton('Settings')  (snapshot pending...)
[smoker] password field: typing is not recorded. Add a SET_TEXT step by hand, or set the value outside the scenario.
[smoker] step list: s001  CLICK [OSGi Chatbot] JButton('Settings')
[smoker] step list: s002  CLICK [Settings] JButton('Cancel')  (snapshot pending...)
[smoker] step list: s002  CLICK [Settings] JButton('Cancel')
[smoker] saved C:\smoke\names\walk.json (2 steps, 2 snapshots); 14 component(s) without a real name in C:\smoke\names\walk.lint.json
```

The line `password field: typing is not recorded` came after the click on `Settings`. `walk.json` has 2 steps and no typing step.
The procedure has no check for this line.

The end of the console output of `record` (`C:\smoke\out\wp09-record.txt`):

```
application exited with -1; scenario file: C:\smoke\names\walk.json
lint file: C:\smoke\names\walk.lint.json
14 unnamed interactive component(s), 14 with a proposed name (100 %: 11 from fields, 3 from text, 0 from labels), 11 with an automatic name
table and patch: lint --run C:\smoke\names --emit-patch <file>
```

`-1` is the exit code of the application process that the script stopped (step 6 of the procedure).

## Record sessions

The record session (step 4) ran four times. Each new session removed `C:\smoke\names`.

| Session | `J2D_D3D=false` | Result |
|---|---|---|
| 1 | No | The owner saw empty application windows through Chrome Remote Desktop. No clicks. The session was stopped. |
| 2 | No | The same as session 1. |
| 3 | Yes | The owner did the clicks. The record log has 2 save lines with `(0 steps, 0 snapshots)` and 8 components, and no `recording` line. The recorder did not start, so the clicks on `Settings` and `Cancel` were not recorded. |
| 4 | Yes | The owner did the clicks again, and made sure that the `Record` button changed to `Pause`. The record log has the `recording` line and 2 steps. The results of this report come from this session. |

- Empty windows: after sessions 1 and 2, the sessions started with the environment variable `J2D_D3D=false`. It turns off the
  Direct3D pipeline of Java 2D. The owner then did the clicks. There was no separate test to find the cause of the empty windows.
- Session 3: the cause of the missing `recording` line is not known. The click on `Record` did not arrive at the toolbar,
  or the owner did not do it. The files of session 3 are in `C:\smoke\out\wp09-names-attempt3` and
  `C:\smoke\out\wp09-record-attempt3.txt`.
- Session 3 shows one more result for N-2: two saves without a step each wrote `walk.lint.json` with 8 components.

## Not tested

- Item 9 (`JSpinner`, `JSlider`): p2.product has no spinner and no slider.
- Windows other than `OSGi Chatbot` and `Settings`.
- The source `label` (item 11): no component of the 2 windows got a proposal from a label.
- The record session without `J2D_D3D=false`, with a person at the local screen.
