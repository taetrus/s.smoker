# WP-07 CI matrix and report (code tasks) — design

Date: 2026-11-25. Finding: F-18. Requirements: R-34, R-40a, R-41, R-42.
Source: `docs/development-plan.md`, section "WP-07", tasks 1 to 3 and 5 to 7. Decision 8 moves task 4 (the GitLab
matrix jobs, R-02a) to the field phase.

## 1. Intent

The teams run the smoke tests in a GitLab pipeline with two jobs: Windows with JRE 8 and Windows with JRE 21
(decision 9). Today each job gives its own report. Nothing joins them, nothing remembers the results of earlier
pipelines, a run depends on the locale and the time zone of the runner, a product can ship with the agent option
in `eclipse.ini`, and the lint results cannot fail a pipeline.

WP-07 has four independent parts:

1. Matrix report: one page shows the step results of all jobs, with the results of the last 20 runs (R-41, R-42).
2. Stable conditions: the agent sets the locale, the time zone, the look and feel and the window size from the
   configuration (R-34).
3. Release guard: a command fails when a product folder contains the agent option (R-40a).
4. Lint baseline: the pipeline fails only for a lint result that is not in a baseline file (decision "lint results
   fail the pipeline from version 1.1").

The users are the developers and the team leads of the target applications. They read the report in the pipeline.

## 2. Goals, non-goals, anti-goals

Goals:

1. `run --label <text>` writes the label into each `run-result.json` and into a job summary file.
2. `report --merge` writes one HTML page with one column for each job.
3. `report --merge --history <file>` keeps the step results of the last 20 runs and shows them.
4. The same scenario passes when the locale of the operating system is `tr_TR` and when it is `en_US`, with the
   locale set in the configuration (the criterion of R-34).
5. `check-release` fails for a product folder that has `-javaagent:` with smoker in an `.ini` file (R-40a).
6. `lint --baseline` gives exit code 1 only for a result that is not in the baseline file.

Non-goals:

- The GitLab jobs and the removal of `smoke:linux` (task 4, field phase). `.gitlab-ci.yml` does not change.
- The place where the pipeline keeps the history file between pipelines (GitLab cache, artifact or share). The
  tool gets a path. The field phase decides the storage with task 4.
- A joined `junit.xml`. GitLab reads the `junit.xml` of each job.
- The details of a step (differences, screenshots, log hits) in the merged page. The page links to the report of
  the job.
- DPI as a matrix dimension, and a forced look and feel for an application that sets its own.
- A Maven Enforcer rule. It needs a new module and a new dependency behind Nexus.

Anti-goals (the work fails if one of these occurs):

- A guard or a baseline check that passes because it checked nothing (no `.ini` file found, baseline file missing).
- A damaged or missing history file that stops the report, or that the report replaces without a message.
- A change to the layout of the current `report.html` that a team sees, other than link targets and the warnings
  of section 6 (a result without warnings gives the same page as today).
- A configured value that the agent ignores without a message (unknown locale, unknown time zone, look and feel
  that the application replaced).

## 3. Constraints

The hard constraints of `CLAUDE.md` apply: Java 8 source level, no new dependency, `Locale.ROOT` for case
conversion, every Swing access on the EDT, ASCII `[smoker]` texts, Simplified Technical English in documents.
Old `run-result.json` files and old agent configuration files stay valid: each new field and key is optional.

## 4. Approaches for the merged report

1. Overview page with links (chosen by the owner). The merged page has one row for each step and one column for
   each job. A cell links to the step in the `report.html` of the job. Small; the details stay in one place. One
   more click to see a difference; the job folders must stay beside the merged page.
2. One self-contained page with the details of all jobs. No click. A large page and a second copy of the detail
   layout.
3. One report writer for one job and for N jobs. One code path. It changes the report that each team sees today
   and that `SelfTestIT` checks.

The other parts have one sensible way each; sections 6 to 8 give the reason where an alternative exists.

## 5. Part 1: matrix report

### 5.1 Label

- `run` gets the option `--label <text>`. It is permitted with `--suite` too (the label belongs to the job, not to
  the suite file). Permitted characters: letters, digits, `.`, `_`, `-`; 1 to 40 characters. A wrong label gives
  exit code 2 before the application starts.
- The CLI writes the key `label` into the agent configuration file. The agent writes it into the new field
  `label` of `run-result.json`. A result that the CLI makes itself (no result file, HANG) gets the label too.
- Without `--label` the field is absent.

### 5.2 Job summary file

`run` (both forms) writes `<out>/job-result.json` when it writes `report.html`. The merge reads this file, not the
`run-result.json` files: the summary holds the verdicts that the report of the job shows (a result made by the CLI,
the second attempt of suite mode), and a scenario without a result file is not lost.

```json
{
  "version": 1,
  "label": "jre8",
  "startedAt": 1764050000000,
  "finishedAt": 1764050090000,
  "javaVersion": "1.8.0_341",
  "os": "Windows 11 10.0",
  "locale": "en-US",
  "timeZone": "Europe/Istanbul",
  "lookAndFeel": "com.sun.java.swing.plaf.windows.WindowsLookAndFeel",
  "scenarios": [
    { "key": "orders", "name": "Orders", "status": "FAIL", "note": null, "error": null, "warnings": [],
      "steps": [ { "id": "s001", "name": "open dialog", "group": "Open", "status": "PASS" } ] }
  ]
}
```

`javaVersion`, `os`, `locale`, `timeZone` and `lookAndFeel` come from the first scenario result that has them.
`label` is absent without `--label`. The model class is in `smoker-core`.

Identity of a scenario. `key` is the file name of the scenario without the extension. It is the name of the
result folder, it is unique in a job, and a result that the CLI makes itself has it too. `name` is the
`scenarioName` of the result, as `report.html` shows it; it can differ from the key. The merge and the history
use `key` to join. The page shows `name` and builds the link target from `name`, as `report.html` does.

### 5.3 Link targets in `report.html`

Each step row of `report.html` gets an `id`: `<scenario id>-<step id>` (the scenario id is the one that the page
uses today for the `h2`). Nothing else changes in this page.

### 5.4 `report --merge`

```
java -jar smoker-cli.jar report --merge <dir> [<dir> ...] --out <dir> [--history <file>] [--run-id <text>] [--ref <text>]
```

- Each `<dir>` is the `--out` folder of one job. The values after `--merge` up to the next option are the folders.
- The column name of a job is its label. A job without a label gets the name of its folder. Two jobs with the
  same column name give exit code 2.
- A folder without a readable `job-result.json` gives exit code 2 and names the folder.
- The command writes `<out>/report.html`. Exit code 0 when the page is written: the jobs give the verdict of the
  pipeline, the merge does not repeat it. Exit code 2 for a wrong argument or a file that cannot be written.

Layout of the page (self-contained HTML, inline CSS, same colors as `report.html`):

```
s.smoker matrix report                      run 4711, master, 2026-11-25 14:02

            jre8                    jre21
Java        1.8.0_341               21.0.5
OS          Windows 11 10.0         Windows 11 10.0
Locale      en-US, Europe/Istanbul  en-US, Europe/Istanbul
Scenarios   2 of 2 PASS             1 of 2 PASS

Scenario / step          jre8    jre21   Last 20 runs: jre8        Last 20 runs: jre21
orders                   PASS    FAIL
  [Open]
  s001 open dialog       PASS    PASS    PPPPPPPPPPPPPPPPPPPP      PPPPPPPPPPPPPPPPPPPP
  s002 save              PASS    FAIL    PPPPPPPPPPPPPPPPPPPP      PPPFPPPPFPPPPPPPPPPF  unstable
login                    PASS    PASS
  s001 sign in           PASS    PASS    ...
```

- Rows: the scenarios in the order of the first job, then scenarios that only later jobs have. In a scenario, the
  steps in the order of the first job that has the scenario, then steps that only other jobs have. A group row
  comes before the first step of a group, as in `report.html`.
- A cell shows the status with the status color and links to
  `<relative path to the job folder>/report.html#<scenario id>-<step id>`; a scenario cell links to the scenario.
  When no relative path exists (another drive), the link is a `file:` URI.
- A cell is `-` when the job did not run the scenario or the step.
- A note or an error of a scenario shows below the scenario row, with the column name before it.

### 5.5 History

- `--history <file>` reads the file, adds the current run and writes the file back (temporary file, then move).
  Without the option the page has no history columns and no file is read or written.
- `--run-id` and `--ref` are free texts (for example `$CI_PIPELINE_ID` and `$CI_COMMIT_REF_NAME`). They are stored
  and shown in the page header.
- A run with a `--run-id` that the file already has replaces that run (a job that the pipeline runs again). A run
  without `--run-id` is always added.
- The file keeps the last 20 runs. Older runs are removed.
- The keys below a job are scenario keys (section 5.2), and the keys below `steps` are step ids.

```json
{
  "version": 1,
  "runs": [
    { "time": 1764050090000, "id": "4711", "ref": "master",
      "jobs": { "jre8": { "orders": { "status": "PASS", "steps": { "s001": "PASS", "s002": "PASS" } } } } }
  ]
}
```

- The history column of a job shows one letter for each stored run, the oldest first, the current run last:
  `P` PASS, `F` FAIL, `H` HANG, `E` ERROR, `N` NO_GOLDEN, `-` not run. Each letter has the status color and a
  title with the time, the id and the ref of the run.
- A step row gets the text `unstable` when the stored runs of one job have a PASS and also a FAIL, HANG or ERROR.
- A history file that does not exist starts a new history. The page shows `history: new file`.
- A history file that cannot be read as version 1 is moved to `<file>.damaged` (an existing one is replaced), a
  new history starts, the page shows `history: the file was damaged, a new history starts`, and the command
  prints the same line on `System.err`. The exit code stays 0.
- When the file holds runs with another `ref`, the history shows them too. The title of each letter names the ref.

## 6. Part 2: stable conditions (R-34)

New optional keys of the agent configuration file. `run` and `record` accept them as options with the same names
(`--locale`, `--timeZone`, `--lookAndFeel`, `--windowSize`); the suite file accepts them in `agentConfig`. They
apply in play mode and in record mode, so that a recording and its replays have the same conditions.

| Key | Value | The agent does |
|---|---|---|
| `locale` | a language tag, `en-US` or `en_US` | `Locale.setDefault`, and the system properties `user.language`, `user.country` (and `user.variant` when given) |
| `timeZone` | a time zone ID, `Europe/Istanbul` or `UTC` | `TimeZone.setDefault`, and the system property `user.timezone` |
| `lookAndFeel` | a class name, or `system`, or `crossplatform` | sets the system property `swing.defaultlaf` |
| `windowSize` | `<width>x<height>` in pixels, `1280x800` | sets the size of the main window |

- The agent applies `locale`, `timeZone` and `lookAndFeel` in `premain`, on the calling thread, before it starts
  its own thread. The application `main` runs after `premain`, so the application sees the values from its first
  line. `lookAndFeel` uses the system property, not `UIManager.setLookAndFeel`: the agent must not load Swing
  classes in `premain`. `system` and `crossplatform` become the class names that `UIManager` gives for them; the
  agent resolves them without a call that installs a look and feel.
- Injection of `-D` options into the application command line is not used: a product starts through a launcher
  and `eclipse.ini`, and the CLI does not own that command line.
- Wrong values. The agent checks the four values at start: a language tag that gives an empty language, a time
  zone ID that `TimeZone.getAvailableIDs()` does not have, a `lookAndFeel` that is empty, a `windowSize` that is
  not two positive whole numbers. A wrong value gives one line `[smoker] fatal: ...` and the application exits
  with code 2, as for each wrong configuration value today. `run` then gives ERROR for the scenario and exit
  code 1, and `record` gives its usual exit code, as today for an agent that cannot start.
  The CLI (options and suite file) also checks `locale` and `windowSize` before the application starts, and gives
  exit code 2. It does not check `timeZone` and `lookAndFeel`: the JRE of the CLI and the JRE of the application
  can know different time zones and classes.
- `windowSize`: when the main window shows (the same moment at which the player starts the first step, and at
  which the recorder starts), the agent, on the EDT, sets the first main window (`Player.isMainWindow`) to the
  normal state if it is maximized, and sets its size. The location does not change. The agent then waits for the
  UI to settle. It does this one time for each application start.
- An application that sets its own look and feel wins over `swing.defaultlaf`. The agent does not force a look
  and feel.

Result fields. `run-result.json` gets four optional fields with the actual values at the start of the scenario:
`locale` (language tag), `timeZone` (ID), `lookAndFeel` (class name of `UIManager.getLookAndFeel()`, read on the
EDT) and `windowSize` (size of the first main window).

Warnings. An application can replace a value after `premain` (Equinox sets the locale from `-nl`; an application
sets its own look and feel; a window has a minimum size). For each configured key, the agent compares the
configured value with the actual value at the start of the scenario. A difference adds one text to the new
optional field `warnings` (a list) of `run-result.json`, for example
`look and feel is javax.swing.plaf.metal.MetalLookAndFeel, not the configured ...`. The status does not change.
`warnings` is a separate field, not the `note`: suite mode writes its own text into `note`.
`report.html` shows each warning as a paragraph below the heading of the scenario, where the note shows today;
`run` prints them with the verdict. The summary table of `report.html` does not change. The merged page shows
locale and time zone (section 5.4) and the warnings below the scenario row.

Limit: JRE 8 and JRE 21 format some dates and numbers differently for the same locale (from Java 9 the JRE uses
CLDR locale data by default). The `locale` key does not remove this difference. `README.md` states it, with the
two remedies: an ignore rule for the component, or `-Djava.locale.providers=COMPAT` in the test start of the
product. The agent does not set this property (it cannot make sure that the JRE has not read it yet).

Demo and test. The demo gets a panel behind `-Ddemo.localePanel=true` with one label that shows a fixed instant
and a fixed number in the default locale and the default time zone. The formats must give the same text on JRE 8
and on JDK 27 for `en-US` (for example an explicit pattern with the month name and the hour, and a grouped
number), because `SelfTestIT` runs on both. A scenario with `locale` `en-US` and
`timeZone` `UTC` and one golden passes in the default build and in `-Plocale-tr`. Without the two keys the same
scenario fails in `-Plocale-tr`. This is the check of the R-34 criterion.

## 7. Part 3: release guard (R-40a)

```
java -jar smoker-cli.jar check-release <path> [<path> ...]
```

- A path is a folder, an `.ini` file or a `.zip` file. The command reads each `.ini` file below a folder, and
  each `.ini` entry of each `.zip` file below a folder (a product archive of the build). It does not read an
  archive in an archive.
- A hit is a line that, without leading blanks, starts with `-javaagent:` and contains `smoker` after it, in any
  case (`Locale.ROOT`). The command prints `<file>:<line number>: <line>` for each hit (`<zip>!<entry>` for an
  archive).
- Exit code 1 when there is a hit. Exit code 0 with `check-release: <n> file(s) checked, no agent option` when
  there is none. Exit code 2 when a path does not exist, a file cannot be read, or one of the paths has no
  `.ini` file: a guard that checked nothing for a path must not pass.
- The `.ini` files are read as ISO-8859-1 (each byte is one character; the search text is ASCII).

The command runs in the build of the product, after the product is assembled. `README.md` gets an example for a
Maven or Tycho build (a call of `java -jar smoker-cli.jar check-release target/products`) and for a pipeline job.

## 8. Part 4: lint baseline

```
java -jar smoker-cli.jar lint --run <dir> --write-baseline <file>
java -jar smoker-cli.jar lint --run <dir> --baseline <file>
```

- Two options, not one that writes when the file is missing: a pipeline with a lost baseline file must fail, not
  write a new baseline and pass.
- The identity of a result in the baseline is a baseline key. It is narrower than `Lint.joinKey`, which contains
  the proposed name and the window title: a change of the proposal rules or of a title must not make old results
  new. The baseline key is `field|<runtime class>|<field>` for a result with a field,
  `text|<runtime class>|<type>|<text>` for a result with a text and no field, and `none|<runtime class>|<type>`
  for the others. Results with the same baseline key are one entry. A change of the text of a component without
  a field gives a new result; `README.md` states it.
- `--write-baseline` writes all current results, sorted by key, and gives exit code 0. The team commits the file.

```json
{
  "version": 1,
  "entries": [
    { "key": "field|com.example.OrderPanel|saveButton", "window": "Orders", "class": "com.example.OrderPanel",
      "field": "saveButton", "type": "JButton", "text": "Save", "proposedName": "orders.save" }
  ]
}
```

  Only `key` is read back. The other fields help a person who reads the file or a diff.
- `--baseline` prints the table and the summary for the results that are not in the file, and one line
  `lint: <n> result(s), <m> in the baseline, <k> new, <f> baseline entries not found`. Exit code 1 when `k` is
  more than 0, else 0. A baseline entry that is not found does not fail the run (the component got a name, or the
  run did not show that window). A baseline file that does not exist or cannot be read gives exit code 2.
- `--baseline` and `--write-baseline` together give exit code 2. `--emit-patch` operates as today, on all results.
- Without the two options `lint` operates as today (exit code 0).

## 9. Tests and completion

- Unit tests in `smoker-cli` and `smoker-core`: label check; `job-result.json` from results; merge of two and of
  three jobs with missing scenarios and steps; link targets; history add, replace by id, limit of 20, new file,
  damaged file; `unstable`; `check-release` (folder, file, archive, no `.ini` file, upper case, a line with
  another agent, two paths of which one has no `.ini` file); baseline write, compare, missing file, both options,
  a changed proposed name; a scenario whose name differs from its file name in the merge and in the history.
- Unit tests in `smoker-agent`: the four keys (good and wrong values), the system properties, the result fields,
  the warning for each key that the application replaced, the window size on a maximized frame.
- `SelfTestIT`: two runs of the demo with the labels `a` and `b`, then `report --merge` three times with
  `--history`: the page has the two columns and three letters for a step (R-41, R-42); the locale scenario of
  section 6 (R-34); `check-release` on a folder with an `eclipse.ini` that has the agent option gives exit code 1
  (R-40a); `lint --baseline` gives exit code 1 for a new result and 0 after `--write-baseline`.
- `mvn -B verify` and `mvn -B verify -Plocale-tr` pass, and the three Windows CI builds pass.
- WP-07 is "code complete" after this work, not complete: R-02a and task 4 come in the field phase.

## 10. Documents

`README.md`: the usage block, a section "Matrix report" (label, `job-result.json`, `report --merge`, history, an
example of the two pipeline jobs and the merge job as text, not as a change of `.gitlab-ci.yml`), the four keys in
"Agent configuration file" and "Suite file", a section "Release guard", the baseline in "Lint", the exit codes.
`docs/architecture.md`: the new classes and files. `docs/development-plan.md`: tasks 1 to 3 and 5 to 7 DONE, state
line. `CLAUDE.md`: state, the `SelfTestIT` paragraph, test counts.

## 11. Left to the builder

The class names and the split into classes; the parsing of the value list of `--merge`; the CSS of the merged
page; the exact texts that this document does not give; the content of the demo locale panel; the order of the
work inside each part. The four parts are independent and can be built and reviewed in the order 1, 2, 3, 4.
