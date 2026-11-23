# WP-09 follow-up Windows check (procedure for Claude Code)

This procedure is for Claude Code on the Windows PC. It checks the WP-09 follow-up (PR #17 and PR #18) on the pilot application `p2.product`: the naming assistant without a run. Then it writes the test report.

The follow-up items are in `docs/development-plan.md`, WP-09, items 8 to 12. The Windows CI passed for both pull requests. The CI uses the demo application. No record session ran on a pilot product.

## What the check proves

| Check | Item | What | Where |
|---|---|---|---|
| N-1 | 12 | `record` with a scenario folder that does not exist creates the folder. It gives no stack trace | steps 4, 7 |
| N-2 | 8 | `Save` before the first step writes `walk.lint.json`. `record` prints the lint file and the summary line | steps 5, 7 |
| N-3 | 8, 10, 11 | `lint --run C:\smoke\names` gives the table of the record session. At least 80 % of the components have a proposal (the WP-09 criterion, measured again on Windows) | step 7 |
| N-4 | 8 | `--emit-patch` writes the `setName` lines and the comments | step 7 |
| N-5 | 8 | `run --scenarios C:\smoke\names` runs 1 scenario. The file `walk.lint.json` in the folder is not read as a scenario | step 7 |
| N-6 | 8 | The lint of the run has the same proposals as the lint of the record session | step 7 |

The check does not test item 9 (`JSpinner`, `JSlider`): p2.product has no spinner and no slider.

## Expected lint

The macOS measurement of 2026-11-15 (`docs/wp-09-pilot-report.md`) found 14 components in the windows `OSGi Chatbot` and `Settings`. All 14 had a proposal: 11 from fields, 3 from text. The record session of this check opens the same 2 windows.

The rules of PR #18 can change a proposal in one case only. A component without a field, or with a generated field name, and without a text, now gets the text of its label (`JLabel.setLabelFor`). In the macOS table, `ChatPanel.textPane` and `InputPanel.textArea` have such field names. If p2.product gives them a label, their proposal changes from `chat.txt.text` or `input.txt.text` to a name from the label, with the source `label`. All other proposals must be the same as in the macOS table.

## Rules

- Do the steps in the given order.
- Use the desktop session of the owner. Do not run the steps as a service.
- Recording needs a person at the mouse. Ask the owner before step 4.
- Do not touch the keyboard or the mouse while a run is in progress (step 7). The application windows take the focus.
- The Bash tool of Claude Code is Git Bash. Run the script with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File`.
- Do not change files in `D:\dev\p.osgi.ds`. Do not change the files in `C:\smoke` other than `C:\smoke\names` and `C:\smoke\out\wp09-*`.
- Commit with the noreply address `6750412+taetrus@users.noreply.github.com`. Check it with `git config user.email` before the first commit.
- If a result is different from the expected result, do not change code. Record the result in the report. Keep `C:\smoke\names` and `C:\smoke\out\wp09-run`.

## Procedure

1. Go to the s.smoker clone (for example `/d/dev/s.smoker`), and get `master`:
   ```bash
   git fetch origin
   git checkout master
   git pull
   git log --oneline -3
   ```
   Expected: `master` has the commits of PR #17 and PR #18.
2. Build the jars, and copy them to `C:\tools`:
   ```bash
   mvn -B package -DskipTests
   cp smoker-agent/target/smoker-agent.jar smoker-cli/target/smoker-cli.jar /c/tools/
   ```
   Expected: `BUILD SUCCESS`.
3. Check that the JRE 8 and the product are present:
   ```bash
   ls "/c/Program Files/Java/jdk1.8.0_341/bin/java.exe" "/d/dev/p.osgi.ds/distribution/target/products/com.kk.pde.ds.product/win32/win32/x86_64/plugins/org.eclipse.osgi_3.23.0.v20250228-0640.jar"
   ```
   If a file is missing, stop and tell the owner.
4. Start the record session. The script removes `C:\smoke\names`, so the folder does not exist when `record` starts (N-1).
   The command ends only when the application stops. Run it in the background (Bash tool: `run_in_background`):
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-09-windows-check.ps1 -Mode Record
   ```
   Wait until the chat window `OSGi Chatbot` and the toolbar of the recorder show. The console output goes to `C:\smoke\out\wp09-record.txt`.
   If you do step 4 again, copy `C:\smoke\names` first when you need its files: the script removes the folder.
5. Ask the owner to do these clicks, in this order:
   1. Click `Save` in the toolbar. Do not click `Record` first. This save has no step (N-2).
   2. Click `Record` in the toolbar.
   3. Click `Settings` in the chat window. Wait until the `Settings` dialog shows.
   4. Click `Cancel` in the dialog.
   5. Click `Save` in the toolbar.
   6. Tell Claude Code that the clicks are done.

   The toolbar can be blocked while the modal `Settings` dialog shows. Do not click `Save` while the dialog shows. The snapshot of step 3 holds the dialog, so the lint has the dialog.
6. Stop the application. p2.product does not stop when its windows close.
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-09-windows-check.ps1 -Mode Stop
   ```
   The script stops only the JVM with `-javaagent:...smoker-agent.jar=config=...`. Then the record command of step 4 ends. Wait for it. Its last lines in `C:\smoke\out\wp09-record.txt` are `application exited with <code>` (the code of the stopped process), `lint file: C:\smoke\names\walk.lint.json`, and the summary line. The script writes `record exit=0` to `C:\smoke\out\wp09-record-exit.txt`.
7. Run the checks:
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-09-windows-check.ps1 -Mode Check
   ```
   The script takes approximately 1 minute. It runs `lint` and `run`. It writes `C:\smoke\out\wp09-summary.txt`.

   Expected in `wp09-summary.txt`:
   - `N-1: folder=True recordLog=True exceptionLines=0 recordExit=0`.
   - `N-2: saves=2`. The first save line has `(0 steps, 0 snapshots)`.
   - `N-2: firstSaveZeroSteps=True firstSaveComponents=8 lintFile=True recordPrintsLintFile=1`. The macOS table has 8 components in the chat window.
   - `N-2: record summary:` with the same numbers as the `N-3` line.
   - `walk.json: steps=2`: `s001` is the click on `Settings`, `s002` is the click on `Cancel`.
   - `recordLog smokerLinesNonAscii=0`: the `[smoker]` lines of the record log are ASCII (PR #8).
   - `N-3: lint exit=0 components=14 proposals=14 percent=100`. `fields`, `text` and `labels` together are 14. `windows` has `[OSGi Chatbot]` and `[Settings]`.
   - `N-4: patch setNameLines=11 noFieldComments=3 superclassComments=0`.
   - `N-5: run exit=0 line='1 scenario(s), 0 failed' walkStatus=PASS`. N-5 passes when the line starts with `1 scenario(s)`. If `walkStatus` is `FAIL`, the golden of the record session differs from the run: write the differences of `C:\smoke\out\wp09-run\report.html` in the report.
   - `N-6: recordEntries=14 runEntries=14 differences=0`.

   The criterion of N-3 is 80 %, not 100 %. If `components` is not 14, or a proposal is different from the macOS table, the check does not fail. Write the difference in the report.
8. Compare the table in `C:\smoke\out\wp09-lint-record.txt` with the lint table in `docs/wp-09-pilot-report.md`. For each row, write in the report whether the proposed name and the source are the same. Explain each difference with the rules in the section "Expected lint".
9. Write `docs/wp-09-windows-report.md`. Use the structure of `docs/wp-05-windows-report.md`:
   - Date, environment (OS, PowerShell, Maven, the JDK of the build, the JRE 8 path and version), and the commit (`git rev-parse --short HEAD`).
   - A table with the checks N-1 to N-6: the expected result, the actual result, and "Pass" or "Fail".
   - The lint table of `wp09-lint-record.txt`, the summary line, and the patch `C:\smoke\out\wp09-names.patch`.
   - The comparison with the macOS table (step 8).
   - The 2 save lines of the record log.
   - For each check that failed: the first lines of the relevant output, and the paths of `C:\smoke\names` and `C:\smoke\out\wp09-run`.
   - "Not tested": item 9 (p2.product has no spinner and no slider), and windows other than `OSGi Chatbot` and `Settings`.
   - Write in Simplified Technical English: short sentences, active voice, one term for one thing.
10. In `CLAUDE.md`, section "State", change the line of the WP-09 follow-up: replace "no record session on a pilot product yet" with the result and the link to the report.
11. Commit the report on a new branch, push it, and open a pull request:
    ```bash
    git checkout -b wp-09-windows-check
    git add docs/wp-09-windows-report.md CLAUDE.md
    git commit -m "WP-09: Windows check of the follow-up on the pilot application"
    git push -u origin wp-09-windows-check
    gh pr create --base master --title "WP-09: Windows check of the follow-up on the pilot application" --body "N-1 to N-6: <Pass or Fail for each>. Lint: <summary line>. Report: docs/wp-09-windows-report.md."
    ```
12. Give the owner the table of step 9 with the actual results, and the link to the pull request.
