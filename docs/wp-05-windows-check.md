# WP-05 Windows check (procedure for Claude Code)

This procedure is for Claude Code on the Windows PC. It does the last part of the WP-05 completion criterion (`docs/development-plan.md`, WP-05): the runs on the pilot application `p2.product`. Then it writes the test report and posts the summary on the pull request.

The branch `wp-05-suite-mode` holds `run --suite`, the three-stage stop of the application and the agent configuration file. The pilot runs are open.

## What the check proves

| Requirement | Check | Where |
|---|---|---|
| R-36 | 20 copies of `pilot.json` run in one application start | step 5 |
| R-39 | The `--out` path `C:\smoke\out\wp05 suite;1` has `;` and a space. The run works | step 5 |
| R-38 | 20 runs with `--scenarios` give 20 stops. No stop uses `System.exit` or `halt` | step 6 |
| R-37 | The Windows CI job runs `SelfTestIT.simulatorRunsBeforeResetAndAfter` with the demo simulator | step 7 |

The check does not test a product with the hardware simulator. That item stays open.

## Rules

- Do the steps in the given order.
- Use the desktop session of the owner. Do not run the steps as a service.
- Do not touch the keyboard or the mouse while a run is in progress. The application windows take the focus.
- The Bash tool of Claude Code is Git Bash. Run each PowerShell command with `powershell.exe -NoProfile -Command "<command>"`. Run the script of steps 5 and 6 with `powershell.exe -File`.
- Do not change files in `D:\dev\p.osgi.ds`. Do not change `C:\smoke\pilot.json` and `C:\smoke\pilot.golden.json`.
- Commit with the noreply address `6750412+taetrus@users.noreply.github.com`. Check it with `git config user.email` before the first commit.
- If a result is different from the expected result, do not change code. Record the result in the report. Keep the `--out` folder of that run.

## Procedure

1. Go to the s.smoker clone (for example `/d/dev/s.smoker`), and get the branch:
   ```bash
   git fetch origin
   git checkout wp-05-suite-mode
   git pull
   ```
2. Build the jars, and copy them to `C:\tools`:
   ```bash
   mvn -B package -DskipTests
   cp smoker-agent/target/smoker-agent.jar smoker-cli/target/smoker-cli.jar /c/tools/
   ```
   Expected: `BUILD SUCCESS`.
3. Check that the files of the WP-04 check are present:
   ```bash
   ls /c/smoke/pilot.json /c/smoke/pilot.golden.json "/c/Program Files/Java/jdk1.8.0_341/bin/java.exe"
   ```
   If a file is missing, stop and tell the owner. The product folder is `D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64`.
4. Record the reset scenario. The reset scenario brings the application back to the start state of `pilot.json`.
   1. Ask the owner to record. Recording needs a person at the mouse.
   2. Run this command in PowerShell:
      ```powershell
      cd D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64
      New-Item -ItemType Directory -Force -Path C:\smoke\suite | Out-Null
      java -jar C:\tools\smoker-cli.jar record --agent C:\tools\smoker-agent.jar --scenario C:\smoke\suite\reset.json `
        --app '\"C:\Program Files\Java\jdk1.8.0_341\bin\java.exe\" {agent} -jar plugins\org.eclipse.osgi_3.23.0.v20250228-0640.jar -configuration configuration -consoleLog'
      ```
   3. The owner clicks `Record`, then `Clear` in the chat window, then `Save` in the toolbar.
   4. The owner stops the `java` process. Save does not stop the application.
   5. Test the reset scenario with 2 copies of `pilot.json`:
      ```bash
      powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-05-windows-check.ps1 -Mode Probe
      ```
      The script writes `C:\smoke\out\wp05-suite-probe.txt` and `C:\smoke\out\wp05-probe-summary.txt`. The `--out` folder is `C:\smoke\out\wp05 probe;1`.
   6. Read `wp05-probe-summary.txt`. Expected: `suite applicationStarts=1`, and `pilot01` and `pilot02` are `PASS` with `attempts=1` and an empty note.
   7. If `pilot02` has the note `failed in a shared JVM, passed in a new JVM`, the reset scenario is not complete. `applicationStarts` is then 2.
      Tell the owner. Ask the owner to record a better reset scenario. Then do sub-steps 2 to 6 again.
5. R-36 and R-39: run the suite with 20 copies:
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-05-windows-check.ps1 -Mode Full
   ```
   The script takes approximately 5 minutes. It does the suite run, then the `--scenarios` run of step 6.
   It makes the copies `pilot01` to `pilot20` in `C:\smoke\suite\scenarios`. It writes `C:\smoke\suite\suite.json`.
   It writes the console output of the suite run to `C:\smoke\out\wp05-suite-full.txt`.
   The `--out` folder of the suite run is `C:\smoke\out\wp05 suite;1`. The name has `;` and a space. This is the R-39 check.

   Expected for the suite run, in `C:\smoke\out\wp05-summary.txt`:
   - `suite exit=0`.
   - `suite applicationStarts=1`.
   - 20 lines `suite pilotNN PASS attempts=1`, with an empty note.
   - The console output has the line `application starts: 1`.
   - The folder `wp05 suite;1` has `suite-result.json`, `junit.xml` and `report.html`.
6. R-38: read the rest of `C:\smoke\out\wp05-summary.txt`. The script ran `run --scenarios` on the 20 copies. Each scenario had its own JVM and its own stop.
   Files:
   - `C:\smoke\out\wp05-scenarios.txt`: the console output.
   - `C:\smoke\out\wp05-stops.txt`: the `[smoker] stop:` lines of each run.
   - `C:\smoke\out\wp05-configuration-before.txt` and `wp05-configuration-after.txt`: the files in the `configuration\` folder of the product, before and after the 20 runs.

   Expected in the line that starts with `R-38:`:
   - `runs=20`.
   - `missingAppOutput=0`.
   - `closingTheWindows=20`: each `app-output.log` has `[smoker] stop: closing the windows`.
   - `systemExit=0` and `halt=0`: no run has `[smoker] stop: System.exit(` or `[smoker] stop: halt(`.
   - `startupNotPass=0`: no run has a startup FAIL.

   The file lists of `configuration\` are for the report only. Write in the report whether the lists differ. Write the names of the new files. Do not fail the check for a difference.

   If a run has the line `stop: stopping the OSGi framework` or `stop: System.exit(`, the stop did not end at stage 1. Write the run name and the stop lines in the report.
7. R-37: the Windows CI job runs the demo test `SelfTestIT.simulatorRunsBeforeResetAndAfter`. Find the CI run of the pull request, and copy its link:
   ```bash
   gh pr checks
   ```
   If the job `windows` did not pass, write that in the report.
8. Write `docs/wp-05-windows-report.md`. Use the structure of `docs/wp-04-windows-report.md`:
   - Date, environment (OS, PowerShell, Maven, the JDK of the build, the JRE 8 path and version), and the commit (`git rev-parse --short HEAD`).
   - A table with the checks R-36, R-39, R-38 and R-37: the expected result, the actual result, and "Pass" or "Fail".
   - The result of the reset scenario test (step 4): the number of application starts, and the notes of the 2 copies.
   - For each check that failed: the first lines of the relevant output, and the path of its `--out` folder.
   - The difference of the file lists in `configuration\`.
   - The link to the CI run (step 7).
   - "Not tested": a product with the hardware simulator. R-37 ran with the demo simulator only.
   - Write in Simplified Technical English: short sentences, active voice, one term for one thing.
9. Commit and push the report:
   ```bash
   git add docs/wp-05-windows-report.md
   git commit -m "WP-05: Windows check on the pilot application"
   git push
   ```
10. Post the summary as a comment on the pull request of the branch:
    ```bash
    gh pr comment --body "WP-05 Windows check. R-36: <Pass or Fail>. R-39: <Pass or Fail>. R-38: <Pass or Fail>. R-37 CI run: <link>. Report: docs/wp-05-windows-report.md."
    ```
11. Give the owner the table of step 8 with the actual results, and the link to the comment.
